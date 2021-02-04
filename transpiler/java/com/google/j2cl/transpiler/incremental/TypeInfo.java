package com.google.j2cl.transpiler.incremental;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;

public class TypeInfo {
    private DeclaredTypeDescriptor      type;
    private String                      uniqueId;
    private List<Dependency>            outgoingDependencies = new ArrayList<>(); // points of use, where method is called
    private List<Dependency>            incomingDependencies = new ArrayList<>(); // points of declaration, where method is defined

    // This Map will "leak" during updates, as we do not track which members no longer have references. TODO scan at end to removed unused MemberInfos (mdp)
    private Map<MemberInfo, MemberInfo> members              = new HashMap<>();

    //private List<Dependency>       incomingDependencies = new ArrayList<>(); // points of declaration, where method is defined

    private TypeInfo enclosingTypeInfo;
    private List<TypeInfo> innerTypes = new ArrayList<>();

    public TypeInfo(DeclaredTypeDescriptor type) {
        setType(type);
        this.uniqueId = type.getUniqueId();
    }

    public TypeInfo(String uniqueId) {
        setType(null);
        this.uniqueId = uniqueId;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public TypeInfo getEnclosingTypeInfo() {
        return enclosingTypeInfo;
    }

    public void setEnclosingTypeInfo(TypeInfo enclosingTypeInfo) {
        this.enclosingTypeInfo = enclosingTypeInfo;
    }

    public List<TypeInfo> getInnerTypes() {
        return innerTypes;
    }

    public DeclaredTypeDescriptor getType() {
        return type;
    }

    public void setType(DeclaredTypeDescriptor type) {
        this.type = type;
    }

    public List<Dependency> getOutgoingDependencies() {
        return outgoingDependencies;
    }

    public List<Dependency> getIncomingDependencies() {
        return incomingDependencies;
    }

    public boolean addOutgoingDependency(Dependency dependency) {
        return outgoingDependencies.add(dependency);
    }

    public boolean addIncomingDependency(Dependency dependency) {
        return incomingDependencies.add(dependency);
    }

    public Map<MemberInfo, MemberInfo> getMembers() {
        return members;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TypeInfo that = (TypeInfo) o;
        return uniqueId.equals(that.uniqueId);
    }

    public TypeInfo clone(Map<String, TypeInfo> cachedTypeInfos) {
        TypeInfo cloneTypeInfo = type != null ? new TypeInfo(type) : new TypeInfo(uniqueId);
        cachedTypeInfos.put(cloneTypeInfo.getUniqueId().substring(1), cloneTypeInfo);

        for (MemberInfo memberInfo : members.values()) {
            MemberInfo cloneMemberInfo = memberInfo.clone();
            cloneTypeInfo.members.put(cloneMemberInfo, cloneMemberInfo);
        }
        if (enclosingTypeInfo != null) {
            cloneTypeInfo.enclosingTypeInfo = getOrCreate(enclosingTypeInfo, cachedTypeInfos);
        }

        for(TypeInfo innerTypeInfo : innerTypes) {
            cloneTypeInfo.innerTypes.add(getOrCreate(innerTypeInfo, cachedTypeInfos));
        }

        for (Dependency dep : outgoingDependencies) {
            if (dep.getMemberInfo().getRole() == Role.SUPER) {
                Dependency clonedDep = new Dependency(getOrCreate(dep.getCaller(),cachedTypeInfos),
                                                      getOrCreate(dep.getCallee(),cachedTypeInfos),
                                                      dep.getMemberInfo());
            }
        }

        return cloneTypeInfo;
    }

    public static TypeInfo getOrCreate(TypeInfo typeInfo,
                                       Map<String, TypeInfo> cachedTypeInfos) {
        TypeInfo cachedTypeInfo = cachedTypeInfos.get(typeInfo.getUniqueId().substring(1));
        if (cachedTypeInfo==null) {
            cachedTypeInfo = typeInfo.clone(cachedTypeInfos);
            cachedTypeInfos.put(cachedTypeInfo.getUniqueId().substring(1), cachedTypeInfo);
        }

        return cachedTypeInfo;
    }

    @Override
    public int hashCode() {
        return uniqueId.hashCode();
    }

    @Override public String toString() {
        return "TypeInfo{" +
               " uniqueId='" + uniqueId + '\'' +
               '}';
    }
}
