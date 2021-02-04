package com.google.j2cl.transpiler.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Field;
import com.google.j2cl.transpiler.ast.FieldAccess;
import com.google.j2cl.transpiler.ast.FieldDescriptor;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.MethodCall;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.NewInstance;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.common.SourceUtils;
import com.google.j2cl.transpiler.J2clTranspilerOptions;

import static com.google.j2cl.transpiler.incremental.TypeGraphStore.ignoreType;

/**
 *
 * TODO
 * -transitive (via interface) method and property renames.
 */

public class TypeGraphManager {
    private TypeGraphStore store;
    private Path           outputPath;
    private List<String>   transpiled =  new ArrayList<>();

    public static TypeGraphManager writeGraph(J2clTranspilerOptions options, List<CompilationUnit> j2clUnits) {
        TypeGraphManager typeGraphManager;
        try {
            typeGraphManager = new TypeGraphManager(options.getOutput(),
                                                    options.getSources()
                                                           .stream()
                                                           .map(SourceUtils.FileInfo::sourcePath)
                                                           .collect(Collectors.toList()));

            typeGraphManager.processJ2CLUnits(j2clUnits);
        } catch (Exception e) {
            throw new IllegalStateException("Using TypeGraphManager mode, unable to process j2clUnits and write the graph to disk.", e);
        }

        return typeGraphManager;
    }

    public TypeGraphManager(Path outputPath, List<String> files) {
        this.outputPath = outputPath;
    }

    public void processJ2CLUnits(List<CompilationUnit> j2clUnits) throws IOException {
        store = new TypeGraphStore();
        store.passthroughChangeSet(outputPath);

        visitor = new TypeGraphVisitor(store.getMemberInfos().values());

        for (CompilationUnit unit : j2clUnits) {
            // The uniqueId to path was not known when the initial ChangeSet was built, so add additional paths now.
            for (Type type : unit.getTypes()) {
                if ( type.getDeclaration().getEnclosingTypeDeclaration() == null ) {
                    String uniqueId = "?" + type.getDeclaration().getUniqueId();
                    //record the outer type, for testing
                    transpiled.add(uniqueId);

                    // prefix the ?, so it has matching uniqueId to the DeclaredTypeDescriptor
                    String pkgPath = unit.getPackageName().replace(".", File.separator);
                    String relativePath = unit.getFilePath().substring(unit.getFilePath().lastIndexOf(pkgPath));
                    store.getPathToUniqueId().put(relativePath, uniqueId);

                    TypeInfo typeInfo = store.get(uniqueId);
                    if (typeInfo != null) {
                        store.prepareForUpdateType(typeInfo);
                    }
                }
            }
        }
        for (CompilationUnit unit : j2clUnits) {
            unit.accept(visitor);
        }

        store.write();
    }

    public List<String> getTranspiled() {
        return transpiled;
    }

    public TypeGraphStore getStore() {
        return store;
    }

    Deque<TypeInfo> typeStack = new ArrayDeque<>();

    public AbstractVisitor visitor;

    public class TypeGraphVisitor extends AbstractVisitor {

        private Map<MemberInfo, MemberInfo> holdingMemberInfo = new HashMap<>();

        public TypeGraphVisitor(Collection<MemberInfo> existingMmberInfos) {
            holdingMemberInfo = new HashMap<>();
            for (MemberInfo memberInfo : existingMmberInfos) {
                holdingMemberInfo.put(memberInfo, memberInfo);
            }
        }

        @Override public boolean enterCompilationUnit(CompilationUnit node) {
            return super.enterCompilationUnit(node);
        }

        @Override
        public boolean enterType(Type node) {
            //System.out.println("enterType " + node.getTypeDescriptor().getUniqueId());
            DeclaredTypeDescriptor type = node.getTypeDescriptor();
            if(ignoreType(type.getUniqueId())) {
                // ignore built in types
                return false;
            }

            TypeInfo typeInfo = store.get(type);
            typeStack.push(typeInfo);
            if (typeInfo.getType()==null) {
                // If the TypeInfo existed previously and was updated, it's type field is nulled, so reconnect to current TypeDescriptor
                typeInfo.setType(type);
            }

            DeclaredTypeDescriptor parent = type.getEnclosingTypeDescriptor();
            if ( parent != null ) {
                // inner types need to be recorded
                TypeInfo parentTypeInfo = store.get(parent);
                parentTypeInfo.getInnerTypes().add(typeInfo);
                typeInfo.setEnclosingTypeInfo(parentTypeInfo);
            }

            // Is the class renamed?
            boolean        impacting      = !type.getQualifiedJsName().equals(type.getQualifiedSourceName());
            ImpactingState impactingState = determineImpactingState(typeInfo, impacting, Role.CLASS, "", "");

            MemberInfo memberInfo = new MemberInfo(Role.CLASS, type.getUniqueId().substring(1),
                                                   "", "", "", impactingState);
            updateMembers(typeInfo, memberInfo);

            List<DeclaredTypeDescriptor> extendsAndInterfaces = new ArrayList<>();
            DeclaredTypeDescriptor superDeclr = type.getSuperTypeDescriptor();

            while (superDeclr != null && !superDeclr.getUniqueId().substring(1).equals("java.lang.Object")) {
                extendsAndInterfaces.add(superDeclr);
                superDeclr = superDeclr.getSuperTypeDescriptor();
            }

            extendsAndInterfaces.addAll(type.getInterfaceTypeDescriptors());

            for(DeclaredTypeDescriptor descr : extendsAndInterfaces) {
                visitClassReference(typeInfo, descr, Role.SUPER);
            }

            return true;
        }

        @Override
        public void exitType(Type node) {
            //System.out.println("exitType " + node.getTypeDescriptor().getUniqueId());
            if(ignoreType(node.getDeclaration().getUniqueId())) {
                // ignore built in types
                return;
            }

            typeStack.pop();
        }


        @Override
        public boolean enterField(Field node) {
            //System.out.println("enterField " + node);

            FieldDescriptor fieldDescr = node.getDescriptor();
            String enclosingType = fieldDescr.getEnclosingTypeDescriptor().getUniqueId();
            if(ignoreType(enclosingType)) {
                // ignore built in types
                return false;
            }
            TypeInfo enclosingInfo = store.get(enclosingType);

            if (skipSameFileReference(enclosingInfo, store.get(fieldDescr.getTypeDescriptor().getUniqueId()))) {
                return false;
            }

            // Is the field renamed?
            boolean        impacting      = fieldDescr.getJsInfo().getJsName() != null &&  !fieldDescr.getJsInfo().getJsName().equals(fieldDescr.getName());
            ImpactingState impactingState = determineImpactingState(enclosingInfo, impacting, Role.FIELD, fieldDescr.getName(), "");

            MemberInfo memberInfo = new MemberInfo(Role.FIELD, enclosingType.substring(1),
                                                   fieldDescr.getName(), "",
                                                   fieldDescr.getTypeDescriptor().getUniqueId(),
                                                   impactingState);
            updateMembers(enclosingInfo, memberInfo);

            visitClassReference(enclosingInfo, fieldDescr.getTypeDescriptor() );

            return super.enterField(node);
        }

        @Override
        public boolean enterMethod(Method node) {
            //System.out.println("enterMethod " + node);

            MethodDescriptor methodDescr = node.getDescriptor();
            String enclosingType = methodDescr.getEnclosingTypeDescriptor().getUniqueId();
            if(ignoreType(enclosingType)) {
                // ignore built in types
                return false;
            }
            TypeInfo enclosingInfo = store.get(enclosingType);

            // Is the method renamed?
            boolean        impacting      = methodDescr.getJsInfo().getJsName() != null &&  !methodDescr.getJsInfo().getJsName().equals(methodDescr.getName());
            ImpactingState impactingState = determineImpactingState(enclosingInfo, impacting, Role.METHOD, methodDescr.getName(), methodDescr.getSignature());

            MemberInfo memberInfo = new MemberInfo(Role.METHOD, enclosingType.substring(1),
                                                   methodDescr.getName(), methodDescr.getSignature(),
                                                   methodDescr.getReturnTypeDescriptor().getUniqueId(),
                                                   impactingState);


            updateMembers(enclosingInfo, memberInfo);

            visitClassReference(enclosingInfo, methodDescr.getReturnTypeDescriptor());

            return super.enterMethod(node);
        }

        @Override public boolean enterMethodCall(MethodCall node) {
            //System.out.println("enterMethodCall " + node);

            MethodDescriptor methodDescr = node.getTarget();
            DeclaredTypeDescriptor   calleeType = methodDescr.getEnclosingTypeDescriptor();
            if(ignoreType(calleeType.getUniqueId())) {
                // ignore built in types
                return false;
            }

            TypeInfo callerInfo = typeStack.peek();

            TypeInfo calleeInfo = store.get(calleeType);

            if (calleeInfo == callerInfo) {
                // cannot depend on itself
                return true;
            }

            String         name       = methodDescr.getName();
            String         signature  = methodDescr.getSignature();
            TypeDescriptor returnType = methodDescr.getReturnTypeDescriptor();

            if (signature.startsWith("$ctor__") ||
                signature.equals("$clinit()") ||
                signature.equals("<init>()")) {
                return true;
            }

            MemberInfo lookupInfo = new MemberInfo(Role.METHOD, calleeType.getUniqueId().substring(1),
                                                   name, signature, returnType.getUniqueId());
            MemberInfo memberInfo = getMemberInfo(calleeInfo, lookupInfo);

            Dependency dep = new Dependency(callerInfo, calleeInfo, memberInfo);

            return true;
        }

        @Override
        public boolean enterFieldAccess(FieldAccess node) {
            FieldDescriptor fieldDescr = node.getTarget();

            DeclaredTypeDescriptor calleeType =  fieldDescr.getEnclosingTypeDescriptor();
            if(ignoreType(calleeType.getUniqueId())) {
                // ignore built in types
                return false;
            }

            TypeInfo callerInfo = typeStack.peek();

            TypeInfo calleeInfo = store.get(calleeType);

            if (calleeInfo == callerInfo) {
                // cannot depend on itself
                return true;
            }

            String         name       = fieldDescr.getName();
            TypeDescriptor returnType =  fieldDescr.getTypeDescriptor();

            MemberInfo lookupInfo = new MemberInfo(Role.FIELD, calleeType.getUniqueId().substring(1),
                                                   name, "", returnType.getUniqueId());
            MemberInfo memberInfo = getMemberInfo(calleeInfo, lookupInfo);

            Dependency dep = new Dependency(callerInfo, calleeInfo, memberInfo);

            return true;
        }

        @Override public boolean enterVariable(Variable node) {
            //System.out.println("enterVariable " + node);

            TypeDescriptor   calleeType = node.getTypeDescriptor();

            if(!(calleeType instanceof DeclaredTypeDescriptor) ||
               ignoreType(calleeType.getUniqueId())) {
                // ignore built in types
                return false;
            }

            TypeInfo callerInfo = typeStack.peek();

            visitClassReference(callerInfo, calleeType);
            return super.enterVariable(node);
        }

        @Override
        public boolean enterNewInstance(NewInstance node)
        {
            //System.out.println("enterNewInstance " + node);
            // JsType's have their "new" used directly, non JsType have "create$" used directly, and the "new" is internal.
            MethodDescriptor methodDescr = node.getTarget();

            DeclaredTypeDescriptor   callee = methodDescr.getEnclosingTypeDescriptor();
            if(ignoreType(callee.getUniqueId())) {
                // ignore built in types
                return false;
            }

            TypeInfo callerInfo = typeStack.peek();

            TypeInfo calleeInfo = store.get(callee);

            if (skipSameFileReference(callerInfo, calleeInfo)) {
                return false;
            }

            String         name       = methodDescr.getName();
            String         signature  = methodDescr.getSignature();
            TypeDescriptor returnType = methodDescr.getReturnTypeDescriptor();

            MemberInfo lookupInfo = new MemberInfo(Role.METHOD, callee.getUniqueId().substring(1),
                                                   name, signature, returnType.getUniqueId());
            MemberInfo memberInfo = getMemberInfo(calleeInfo, lookupInfo);

            Dependency dep = new Dependency(callerInfo, calleeInfo, memberInfo);

            return true;
        }

        private void updateMembers(TypeInfo typeInfo, MemberInfo memberInfo) {
            MemberInfo holding = holdingMemberInfo.remove(memberInfo);
            if (holding != null) {
                holding.update(memberInfo);
            } else {
                typeInfo.getMembers().put(memberInfo, memberInfo);
            }
        }

        private boolean skipSameFileReference(TypeInfo callerInfo, TypeInfo calleeInfo) {
            if ( calleeInfo==null) {
                // The callee hasn't been visited yet, so no way it's in the same file.
                return false;
            }
            TypeInfo parentCaller = callerInfo;
            while (parentCaller.getEnclosingTypeInfo() != null) {
                parentCaller = parentCaller.getEnclosingTypeInfo();
            }

            TypeInfo parentCallee = calleeInfo;
            while (parentCallee.getEnclosingTypeInfo() != null) {
                parentCallee = parentCallee.getEnclosingTypeInfo();
            }

            if (parentCaller == parentCallee) {
                // do not track references within the same file
                return true;
            }
            return false;
        }

        private MemberInfo getMemberInfo(TypeInfo calleeInfo, MemberInfo lookupInfo) {
            MemberInfo memberInfo = calleeInfo.getMembers().get(lookupInfo);
            if (memberInfo == null) {
                memberInfo = lookupInfo;
                // This class has not been visited yet. Add the MemberInfo to a tracking class, so it can be corrected later.
                holdingMemberInfo.put(memberInfo, memberInfo);
                calleeInfo.getMembers().put(memberInfo, memberInfo);
            }
            return memberInfo;
        }

        private void visitClassReference(TypeInfo callerInfo, TypeDescriptor calleeDescriptor) {
            visitClassReference(callerInfo, calleeDescriptor, Role.CLASS);
        }

        private void visitClassReference(TypeInfo callerInfo, TypeDescriptor calleeDescriptor, Role role) {
            // Callee type must be a declared Class
            if (calleeDescriptor instanceof  DeclaredTypeDescriptor) {
                if (skipSameFileReference(callerInfo, store.get(calleeDescriptor.getUniqueId()))) {
                    return;
                }

                String calleeStr = calleeDescriptor.getUniqueId();
                // ignore JRE/JDK/Sun classes
                if (TypeGraphStore.ignoreType(calleeStr)) {
                    return;
                }

                DeclaredTypeDescriptor calleeType = (DeclaredTypeDescriptor) calleeDescriptor;
                TypeInfo        calleeInfo = store.get(calleeType);
                MemberInfo lookupInfo = new MemberInfo(role, calleeInfo.getUniqueId().substring(1),
                                                       "", "", "");
                MemberInfo clsInfo = getMemberInfo(calleeInfo, lookupInfo);
                Dependency dep     = new Dependency(callerInfo, calleeInfo, clsInfo);
            }
        }
    }

    private ImpactingState determineImpactingState(TypeInfo typeInfo, boolean impacting, Role role, String name, String signature) {
        //String         memberKey         = TypeGraphStore.getMemberKey(typeInfo, aClass, s, s2);
        MemberInfo memberKey = new MemberInfo(role, typeInfo.getUniqueId().substring(1), name, signature, "");
        ImpactingState oldImpactingState = store.getOldMemberStates().get(memberKey);
        ImpactingState impactingState;
        if (impacting) {
            impactingState = ImpactingState.IS_IMPACTING;
        } else {
            impactingState = (oldImpactingState != ImpactingState.IS_IMPACTING) ?
                             ImpactingState.NOT_IMPACTING : ImpactingState.PREVIOUSLY_IMPACTING;
        }
        return impactingState;
    }
}
