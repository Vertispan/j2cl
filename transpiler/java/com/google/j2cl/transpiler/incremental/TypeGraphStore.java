package com.google.j2cl.transpiler.incremental;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.common.base.Splitter;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;

public class TypeGraphStore {
    private Map<String, Set<String>>        sourceDirs         = new HashMap<>();

    private Map<String, ChangeSet>          changeSets         = new HashMap<>();

    private Map<String, TypeInfo>           typeInfoLookup     = new HashMap<>();

    private Map<String, String>             uniqueIdToPath     = new HashMap<>();

    private Map<String, String>             pathToUniqueId     = new HashMap<>();

    private Map<String, List<String>>       innerTypesChanged  = new HashMap<>(); // removed or updated

    private Map<MemberInfo, MemberInfo>     memberInfos        = new HashMap<>();

    private Map<String, TypeInfo>           impactingAncestors = new HashMap<>();

    private Map<MemberInfo, ImpactingState> oldMemberStates    = new HashMap<>();

    private Path                            outputPath;

    public TypeGraphStore() {
        this.impactingAncestors = new HashMap<>();
    }

    public static boolean ignoreType(String uniqueId) {
        String type = uniqueId.substring(1);
        return type.startsWith("java.") || type.startsWith("javax.") ||
               type.startsWith("jdk.") || type.startsWith("sun.") ||
               type.endsWith("$$LambdaAdaptor") || type.endsWith("$$JsFunction") ||
               type.startsWith("$synthetic.") || type.endsWith("nativebootstrap.Util");
    }

    public Map<String, String> getUniqueIdToPath() {
        return uniqueIdToPath;
    }

    public Map<String, String> getPathToUniqueId() {
        return pathToUniqueId;
    }

    public Map<String, List<String>> getInnerTypesChanged() {
        return innerTypesChanged;
    }

    public Map<MemberInfo, MemberInfo> getMemberInfos() {
        return memberInfos;
    }

    public Map<MemberInfo, ImpactingState> getOldMemberStates() {
        return oldMemberStates;
    }

    public List<TypeInfo> getImpactingTypeInfos() {
        Map<String, TypeInfo> cachedTypeInfos = new HashMap<>();
        List<TypeInfo>        cloned          = new ArrayList<>();
        for (TypeInfo typeInfo : getTypeInfoLookup().values()) {
            if (hasImpactingState(typeInfo, ImpactingState.IS_IMPACTING, ImpactingState.PREVIOUSLY_IMPACTING) ) {
                cloned.add(typeInfo.clone(cachedTypeInfos));
            }
        }
        return cloned;
    }

    public static boolean hasImpactingState(TypeInfo typeInfo, ImpactingState... states) {
        for (MemberInfo memberInfo : typeInfo.getMembers().values()) {
            for (ImpactingState state : states) {
                if (memberInfo.getImpactingState() == state) {
                    return true;
                }
            }
        }
        return false;
    }

    public void provideChangeSet(Path outputPath, List<String> removed, List<String> added, List<String> updated) {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param outputPath
     * @param dirs contains all files
     */
    public void calculateChangeSet(Path outputPath, List<String> dirs) {
        buildAllFiles(dirs);
        ChangeSetBuilder changeSetBuilder = new CalculatedChangeSetBuilder();
        buildAndProcessChangeSet(outputPath, changeSetBuilder);
        //System.out.println("changeSets: " + changeSets);
        //System.out.println("sourcesDirs: " + sourceDirs);
    }

    public void passthroughChangeSet(Path outputPath) {
        ChangeSetBuilder changeSetBuilder = new PassthroughChangeSetBuilder();

        this.outputPath = outputPath;
        Path dataPath = Paths.get(outputPath.toAbsolutePath().toString(), "incremental.dat");
        if (Files.exists(dataPath)) {
            try {
                read(dataPath, changeSetBuilder);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read incremental.dat file", e);
            }
        }
    }

    private void buildAllFiles(List<String> dirs) {
        for (String dir : dirs) {
            Path parentPath = Paths.get(dir);
            Set<String> set = new HashSet<>();
            sourceDirs.put(dir, set);
            changeSets.put(dir, new ChangeSet(dir)); // also initialise a ChangeSet per DIR
            try {
                Files.find(Paths.get(dir), Integer.MAX_VALUE,
                           (path, basicFileAttributes) -> {
                               File file = path.toFile();
                               return !file.isDirectory() && !file.toString().endsWith("native_js");}).forEach( path -> set.add(parentPath.relativize(path).toString()));

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void buildAndProcessChangeSet(Path outputPath, ChangeSetBuilder changeSetBuilder) {
        this.outputPath = outputPath;
        Path dataPath = Paths.get(outputPath.toAbsolutePath().toString(), "incremental.dat");
        if (Files.exists(dataPath)) {
            try {
                read(dataPath, changeSetBuilder);
                processChangeSet();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read incremental.dat file", e);
            }
        } else {
            changeSetBuilder.apply();
        }
    }

    public Map<String, Set<String>> getSourceDirs() {
        return sourceDirs;
    }

    public Map<String, ChangeSet> getChangeSets() {
        return changeSets;
    }

    public TypeInfo get(String uniqueId) {
        String key = uniqueId.substring(1);
        TypeInfo typeInfo = impactingAncestors.get(uniqueId.substring(1));
        if ( typeInfo == null) {
            typeInfo = typeInfoLookup.get(key);
        }
        return typeInfo;
    }

    public void addAllToDelegate(List<TypeInfo> typeInfos,
                                 Map<String, String> uniqueIdToPath) {
        for (TypeInfo typeInfo : typeInfos) {
            this.impactingAncestors.put(typeInfo.getUniqueId().substring(1), typeInfo);

            typeInfo.getMembers().values().stream().forEach(m -> memberInfos.put(m, m));

            String path = uniqueIdToPath.get(typeInfo.getUniqueId());
            this.uniqueIdToPath.put(typeInfo.getUniqueId(), path);
            this.pathToUniqueId.put(path, typeInfo.getUniqueId());
        }

    }

    public TypeInfo get(DeclaredTypeDescriptor type) {
        String uniqueId = type.getUniqueId();
        TypeInfo typeInfo = get(uniqueId);
        if (typeInfo == null) {
            typeInfo = new TypeInfo(type);
            typeInfoLookup.put(type.getUniqueId().substring(1), typeInfo);
        }
        if (typeInfo.getType() == null) {
            typeInfo.setType(type);
        }
        return typeInfo;
    }

    public Map<String, TypeInfo> getTypeInfoLookup() {
        return typeInfoLookup;
    }

    private void processChangeSet() {
        for (ChangeSet changeSet : changeSets.values()) {
            String dir = changeSet.getDir();
            for (String file : changeSet.getRemoved()) {
                if (file.endsWith(".java")) {
                    String   type     = getPathToUniqueId().get(file);
                    TypeInfo typeInfo = get(type);
                    if (typeInfo.getEnclosingTypeInfo() != null) {
                        // Cannot directly remove inner classes.
                        // They should be excluded, before being added to the changeset.
                        throw new IllegalStateException("Cannot directly remove inner classes");
                    }
                    remove(changeSet, typeInfo);
                }
            }

            Set<String> updated = new HashSet<>(changeSet.getUpdated());
            Set<String> added = new HashSet<>(changeSet.getAdded());
            Set<String> removed = new HashSet<>(changeSet.getRemoved());

            int updatedSize = updated.size();
            Stream.concat(changeSet.getAdded().stream(),
                          Stream.concat(changeSet.getUpdated().stream(),
                                        changeSet.getRemoved().stream())).forEach(file -> {
                if (file.endsWith(".native.js")) {
                    // only add .java if it is not already in the ChangeSet
                    String javaName = file.substring(0, file.length()-9) + "java";
                    if (!added.contains(javaName) && !removed.contains(javaName)) {
                        // the assumption is it already exists, so place it in updated.
                        // if it's already there, it doesn't matter as it's a Set.
                        updated.add(javaName);
                    }
                }
            });

            if ( updated.size() > updatedSize) {
                // more files where added to the ChangeSet, so rebuild updated
                changeSet.getUpdated().clear();
                changeSet.getUpdated().addAll(updated);
            }

            // build the list of impacted types, so they can be added to the updated list
            // add those impacted types to the sources list, and also prepare their TypoInfo's for update
            buildImpacted(changeSet);

            Stream.concat(changeSet.getUpdated().stream(),
                    changeSet.getImpacted().stream()).forEach( file -> {
                if (file.endsWith(".java")) {
                    String   type     = getPathToUniqueId().get(file);
                    TypeInfo typeInfo = get(type);
                    prepareForUpdateType(typeInfo);
                }
            });
        }
    }

    private void buildImpacted(ChangeSet changeSet) {
        for (String file : changeSet.getUpdated()) {
            if (file.endsWith(".java")) {
                String type = getPathToUniqueId().get(file);
                buildImpacted(get(type), changeSet);
            }
        }
    }

    private void buildImpacted(TypeInfo typeInfo, ChangeSet changeSet) {
        for (Dependency dep : typeInfo.getIncomingDependencies()) {
            MemberInfo memberInfo = dep.getMemberInfo();

            ImpactingState impactingState = memberInfo.getImpactingState();;

            if (impactingState == ImpactingState.IS_IMPACTING || impactingState == ImpactingState.PREVIOUSLY_IMPACTING) {
                TypeInfo caller = dep.getCaller();

                // if inner, get root parent, as this relates to the path of the source file
                while (caller.getEnclosingTypeInfo() != null) {
                    caller = caller.getEnclosingTypeInfo();
                }

                String callerPath = getUniqueIdToPath().get(caller.getUniqueId());
                if (!changeSet.getUpdated().contains(callerPath)) {
                    changeSet.getImpacted().add(callerPath);
                 }
            }
        }
    }

    public void prepareForUpdateType(TypeInfo typeInfo) {
        recordInnerTypes(typeInfo);

        for (TypeInfo inner : typeInfo.getInnerTypes()) {
            prepareForUpdateType(inner);
        }
        typeInfo.setEnclosingTypeInfo(null);
        typeInfo.getInnerTypes().clear();

        // Remove all method call references (where this class calls other methods).
        // The visitor will re-add all method call references.
        // Incoming does not to be here, as it's assume those callers are part of the changeset and it'll be processed there.
        List<Dependency> depsSet = typeInfo.getOutgoingDependencies();
        Dependency[] deps = depsSet.toArray(new Dependency[depsSet.size()]);
        for(Dependency dep : deps) {
            dep.remove();
        }

        // Note This type may have references to removed references in thee callee list.
        // These will be updated, when the callers are visited, which must happen for
        // the workspace to compile without errors.

        // Visitor will update with the new DeclaredType
        typeInfo.setType(null);
    }

    private void recordInnerTypes(TypeInfo typeInfo) {
        if ( typeInfo.getEnclosingTypeInfo() == null) {
            // only the root parent is recorded
            List<String> innerTypes = new ArrayList<>();
            buildInnerTypesList(innerTypes, typeInfo);
            if (!innerTypes.isEmpty()) {
                getInnerTypesChanged().put(typeInfo.getUniqueId(), innerTypes);
            }
        }
    }

    public void buildInnerTypesList(List<String> types, TypeInfo typeInfo) {
        for (TypeInfo child : typeInfo.getInnerTypes()) {
            types.add(child.getUniqueId());
            if (!child.getInnerTypes().isEmpty()) {
                buildInnerTypesList(types, child);
            }
        }
    }

    public TypeInfo remove(ChangeSet changeSet, TypeInfo typeInfo) {
        typeInfoLookup.remove(typeInfo.getUniqueId().substring(1));

        recordInnerTypes(typeInfo);

        // Iterate and remove all inner, depth first
        if ( !typeInfo.getInnerTypes().isEmpty()) {
            for (TypeInfo inner : typeInfo.getInnerTypes()) {
                remove(changeSet, inner);
            }
        }

        typeInfo.setEnclosingTypeInfo(null);
        typeInfo.getInnerTypes().clear();

        // Remove all method call references (where this class calls other methods).
        List<Dependency> callerDeps = typeInfo.getOutgoingDependencies();
        Dependency[] deps = callerDeps.toArray(new Dependency[callerDeps.size()]);
        for(Dependency dep : deps) {
            dep.remove();
        }

        // Remove all method caller references (where other classes call methods on this one).
        List<Dependency> calleeDeps = typeInfo.getIncomingDependencies();
        deps = calleeDeps.toArray(new Dependency[calleeDeps.size()]);
        for(Dependency dep : deps) {
            dep.remove();
        }

        for (MemberInfo memberInfo : typeInfo.getMembers().values() ) {
            memberInfos.remove(memberInfo);
        }
        typeInfo.getMembers().clear();

        typeInfo.setType(null);

        return typeInfo;
    }

    public void write() throws IOException {
        Path dataPath = Paths.get(outputPath.toAbsolutePath().toString(), "incremental.dat");


        try(Writer writer = Files.newBufferedWriter(dataPath, Charset.forName("UTF-8"))){
            write(writer);
        }
    }

    void write(Writer out) throws IOException {
        writeFileMetaData(out);

        out.append(typeInfoLookup.size() + System.lineSeparator());

        // build id memberinfo id map
        int counter = 0;
        Map<MemberInfo, Integer> memberInfoIds = new HashMap<>();
        for (Map.Entry<String, TypeInfo> entry : typeInfoLookup.entrySet()) {
            for (MemberInfo memberInfo : entry.getValue().getMembers().values()) {
                memberInfoIds.put(memberInfo, counter++);
            }
        }

        for (Map.Entry<String, TypeInfo> entry : typeInfoLookup.entrySet()) {
            StringBuilder typeString = new StringBuilder();

            TypeInfo typeInfo = entry.getValue();

            typeString.append("'" + typeInfo.getUniqueId() + "'");
            typeString.append(',');
            if (typeInfo.getEnclosingTypeInfo() != null) {
                typeString.append("'" + typeInfo.getEnclosingTypeInfo().getUniqueId() + "'");
            }

            typeString.append(",[");
            if ( !typeInfo.getInnerTypes().isEmpty()) {
                boolean afterFirst = false;
                typeString.append(typeInfo.getInnerTypes().size() + ",");
                for (TypeInfo innerType : typeInfo.getInnerTypes()) {
                    if (afterFirst) {
                        typeString.append(',');
                    }
                    typeString.append("'" + innerType.getUniqueId() +"'");
                    afterFirst = true;
                }
            }
            typeString.append("],");

            typeString.append("[");
            if (!typeInfo.getMembers().isEmpty()) {
                typeString.append(typeInfo.getMembers().size() + ",");
                boolean afterFirst = false;
                for (MemberInfo memberInfo : typeInfo.getMembers().values()) {
                    if (afterFirst) {
                        typeString.append(',');
                    }
                    typeString.append(memberInfo.getRole().ordinal());
                    typeString.append(",");
                    typeString.append(memberInfoIds.get(memberInfo));
                    typeString.append(",");
                    typeString.append(memberInfo.getName());
                    typeString.append(",");
                    typeString.append("'" + memberInfo.getSignature() + "'");
                    typeString.append(",");
                    typeString.append("'" + memberInfo.getReturnType() + "'");
                    typeString.append(",");
                    typeString.append(memberInfo.getImpactingState().ordinal());
                    afterFirst = true;
                }
            }
            typeString.append("],");

            StringBuilder outgoing = new StringBuilder();
            StringBuilder incoming = new StringBuilder();
            // Only need to write one way, as it'll reconnect the incoming
            writeDependency(outgoing, typeInfo.getOutgoingDependencies(), memberInfoIds);
            typeString.append(outgoing);
            typeString.append(',');
            typeString.append(incoming);

            out.append(typeString);
            out.append(System.lineSeparator());
        }
    }

    void writeFileMetaData(Writer out) throws IOException {
        out.append(sourceDirs.size() + System.lineSeparator());

        List<String> dirs = new ArrayList(sourceDirs.keySet());
        Collections.sort(dirs);
        for (String dir : dirs) {
            List<String> files = new ArrayList(sourceDirs.get(dir));
            Collections.sort(files);
            Path base = Paths.get(dir);
            out.append(dir + System.lineSeparator());
            out.append(files.size() + System.lineSeparator());

            //sourceDirs
            for (String file : files) {
                Path absFile = base.resolve(file);
                FileTime newTime = Files.getLastModifiedTime(absFile);
                String uniqueId = getPathToUniqueId().get(file);

                if (uniqueId == null) {
                    // the file was added, but it's contents not yet processed,
                    // so we cannot know it's type.
                    uniqueId = "NA";

                }

                out.append(newTime.toMillis() + ",'" + uniqueId + "'," + file + System.lineSeparator());
            }
        }
    }

    void writeDependency(StringBuilder out, List<Dependency> list, Map<MemberInfo, Integer> memberInfoIds) {
        boolean afterFirst = false;
        out.append('[');
        if (list.size() > 0) {
            out.append(list.size() + ",");
        }

        for (Dependency dep : list) {
            if (afterFirst) {
                out.append(",");
            }
            out.append("'" + dep.getCallee().getUniqueId() + "'");
            out.append(",");
            out.append("'" + dep.getCaller().getUniqueId() + "'");
            out.append(",");
            int id = memberInfoIds.get(dep.getMemberInfo());
            out.append(id);
            afterFirst = true;
        }
        out.append(']');
    }
    void read(Path in, ChangeSetBuilder changeSetBuilder) throws IOException {
        List<DelayedSetter> delayed = new ArrayList<>();

        // Due to circular TypeInfo dependencies, this only creates shallow TypeInfos, to ensure all instances exist first
        // It they creates delayed setters, that are applied after to recreate all state.
        File file = in.toFile();
        if ( file.length() == 0 ) {
            throw new IllegalStateException("The contents of the file are empty");
        }
        try (BufferedReader lineScanner = new BufferedReader( new FileReader(in.toFile()))) {
            changeSetBuilder.apply(lineScanner);

            readTypeInfos(delayed, lineScanner);
        }

        // recreate the final state, now all TypeInfos exist.
        applyDelayedSetters(delayed);
    }

    interface ChangeSetBuilder {
        void apply(BufferedReader lineScanner);

        void apply();
    }

    class CalculatedChangeSetBuilder implements ChangeSetBuilder {

        CalculatedChangeSetBuilder() {
        }

        @Override
        public void apply(BufferedReader lineScanner) {
            try {

                String line     = lineScanner.readLine();
                int    dirSize = Integer.valueOf(line);
                for (int j = 0; j < dirSize; j++) {
                    String dir = lineScanner.readLine();
                    Path base = Paths.get(dir);

                    ChangeSet changeSet = changeSets.get(dir);
                    Set<String> set = new HashSet(sourceDirs.get(dir));

                    // get old files
                    line = lineScanner.readLine();
                    int    fileSize = Integer.valueOf(line);

                    for (int i = 0; i < fileSize; i++) {
                        line = lineScanner.readLine();
                        List<String> resultList = Splitter.on(',').trimResults().splitToList(line);
                        Iterator<String> it = resultList.iterator();

                        long   oldTime  = Long.valueOf(it.next());
                        String uniqueId = readType(it);
                        String file     = it.next();
                        changeSet.getAll().add(file);

                        // "NA" happens if a file is added, transpilation fails and then the file is updated again
                        // It is also used for non .java resources
                        if (!uniqueId.equals("NA")) {
                            // this is done twice, as not all the uniqueIds were known when the ChangeSet was built.
                            // pathToUniqueId is later augmented with updated types
                            getUniqueIdToPath().put(uniqueId, file);
                            getPathToUniqueId().put(file, uniqueId);
                        }

                        // remove the keys, anything left over we know is added
                        if (set.remove(file)) {
                            if (!uniqueId.equals("NA") || !file.endsWith(".java")) {
                                FileTime newTime = Files.getLastModifiedTime(base.resolve(file));
                                if (newTime.toMillis() > oldTime) {
                                    // File has new filetime, so it's been updated
                                    changeSet.getUpdated().add(file);
                                }
                            } else {
                                // "NA" happens for a .java, if a file is added, transpilation fails and then the file is updated again
                                changeSet.getAdded().add(file);
                            }
                        } else {
                            // file does not exist, so it was removed
                            changeSet.getRemoved().add(file);
                        }
                    }

                    // Any keys left over are added files, which must also processed
                    for (String file : set) {
                        changeSet.getAdded().add(file);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void apply() {
            // When first created, for each SourceDir just use the files list,
            // All files are "added" state, Which contains all files.
            for(String dir : sourceDirs.keySet()) {
                ChangeSet changeSet = changeSets.get(dir);
                Set<String> files = sourceDirs.get(dir);
                changeSet.getAdded().addAll(files);
                changeSet.getAll().addAll(files);
            }
        }
    }

    class PassthroughChangeSetBuilder implements ChangeSetBuilder {

        PassthroughChangeSetBuilder() {
        }

        @Override
        public void apply(BufferedReader lineScanner) {
            try {
                String line     = lineScanner.readLine();
                int    dirSize = Integer.valueOf(line);
                for (int j = 0; j < dirSize; j++) {
                    String dir = lineScanner.readLine();

                    ChangeSet changeSet = changeSets.get(dir);
                    if (changeSet == null) {
                        changeSet = new ChangeSet(dir);
                        changeSets.put(dir, changeSet);
                    }

                    Set<String> set = sourceDirs.get(dir);
                    if (set == null) {
                        set = new HashSet<>();
                        sourceDirs.put(dir, set);
                    }

                    // get old files
                    line = lineScanner.readLine();
                    int    fileSize = Integer.valueOf(line);

                    for (int i = 0; i < fileSize; i++) {
                        line = lineScanner.readLine();
                        List<String> resultList = Splitter.on(',').trimResults().splitToList(line);
                        Iterator<String> it = resultList.iterator();
                        long   oldTime  = Long.valueOf(it.next());
                        String uniqueId = readType(it);
                        String file     = it.next();

                        // "NA" happens if a file is added, transpilation fails and then the file is updated again
                        // It is also used for non .java resources
                        if (!uniqueId.equals("NA")) {
                            // this is done twice, as not all the uniqueIds were known when the ChangeSet was built.
                            // pathToUniqueId is later augmented with updated types
                            getUniqueIdToPath().put(uniqueId, file);
                            getPathToUniqueId().put(file, uniqueId);
                        }

                        set.add(file);
                        changeSet.getAll().add(file);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void apply() {
            throw new UnsupportedOperationException();
        }
    }

    private void readTypeInfos(List<DelayedSetter> delayed, BufferedReader lineScanner) throws IOException {
        // get the expected number of rows
        String line = lineScanner.readLine();
        int typeInfoSize = Integer.valueOf(line);

        Map<Integer, MemberInfo> memberInfoIds = new HashMap<>();
        for (int i = 0; i < typeInfoSize; i++) {
            line = lineScanner.readLine();

            List<String> resultList = Splitter.on(',').trimResults().splitToList(line);
            Iterator<String> it = resultList.iterator();

            String   uniqueId = readType(it);
            TypeInfo typeInfo = new TypeInfo(uniqueId);
            this.typeInfoLookup.put(uniqueId.substring(1), typeInfo);

            String enclosingUniqueId = readType(it);

            delayed.add(new SetEnclosingType(this, typeInfo, enclosingUniqueId));

            readInner(it, delayed, typeInfo);

            readMembers(it, typeInfo, memberInfoIds);

            // Only need to write one way, as it'll reconnect the incoming
            readDependency(it, delayed, memberInfoIds);
        }
    }

    private void readMembers(Iterator<String> it, TypeInfo typeInfo, Map<Integer, MemberInfo> memberInfoIds) {
        String memberString = it.next();
        if (!memberString.equals("[]")) {
            int        memberSize       = Integer.valueOf(memberString.substring(1)); // strip leading [

            for (int j = 0;j<memberSize;j++) {
                int role           = Integer.valueOf(it.next());
                int id                = Integer.valueOf(it.next());
                String     name       = it.next();
                String     signature  = readType(it);
                String     returnType = readType(it);
                String impactingStr = it.next();
                if ( j == memberSize-1) {
                    impactingStr = impactingStr.substring(0, impactingStr.length()-1); // strip trailing  ]
                }
                ImpactingState impacting  = ImpactingState.values()[Integer.valueOf(impactingStr)];
                MemberInfo memberInfo = new MemberInfo(Role.values()[role], typeInfo.getUniqueId().substring(1), name, signature, returnType, impacting);
                memberInfoIds.put(id, memberInfo);
                typeInfo.getMembers().put(memberInfo, memberInfo);

                oldMemberStates.put(memberInfo, memberInfo.getImpactingState());

            }
        }
    }

    public static String getMemberKey(TypeInfo typeInfo, Role field, String name, String signature) {
        return typeInfo.getUniqueId().substring(1) + ":" + name + ":" + signature;
    }

    private void applyDelayedSetters(List<DelayedSetter> delayed) {
        // Apply the delayed setters
        for (int i = 0; i < delayed.size(); i++) {
            delayed.get(i).apply();
        }
    }

    void readInner(Iterator<String> it, List<DelayedSetter> delayed, TypeInfo typeInfo) {
        String innerString = it.next();
        if (!innerString.equals("[]")) {
            int size = Integer.valueOf(innerString.substring(1)); // strip leading [

            for ( int i = 0; i < size-1; i++) {
                innerString = readType(it);
                delayed.add(new AddInnerType(this, typeInfo, innerString));
            }
            innerString = readType(it, "']");
            delayed.add(new AddInnerType(this, typeInfo, innerString));
        }
    }

    void readDependency(Iterator<String> it, List<DelayedSetter> delayed,
                        Map<Integer, MemberInfo> memberInfoIds) {
        String first = it.next();

        if (!first.equals("[]")) {
            int size = Integer.valueOf(first.substring(1));
            for (int i = 0; i < size; i++) {
                String callee    = readType(it);
                String caller    = readType(it);
                String idStr = it.next();
                if ( i == size-1) {
                    idStr = idStr.substring(0, idStr.length()-1); // strip trailing  ]
                }
                int id = Integer.valueOf(idStr);

                delayed.add(new AddDependency(this,
                                              caller, callee, id, memberInfoIds));
            }
        }
    }


    private String readType(Iterator<String> it) {
        return readType(it, "'");
    }

    private String readType(Iterator<String> it, String end) {
        String str = it.next();
        if (str == null || str.isEmpty()) {
            return "";
        }
        String type = str.substring(1); // strip leading '
        while (!type.endsWith(end)) {
            type += "," + it.next();
        }
        type = type.substring(0, type.length() - end.length());  // strip trailing '
        return type;
    }

    interface DelayedSetter {
        void apply();
    }

    static class SetEnclosingType implements DelayedSetter {

        private TypeGraphStore store;
        private TypeInfo       target;
        private String         enclosingTypeString;

        SetEnclosingType(TypeGraphStore store, TypeInfo target, String enclosingTypeString) {
            this.store = store;
            this.target = target;
            this.enclosingTypeString = enclosingTypeString;
        }

        @Override
        public void apply() {
            if (!enclosingTypeString.equals("")) {
                TypeInfo enclosingType = store.get(enclosingTypeString);
                this.target.setEnclosingTypeInfo(enclosingType);
            }
        }
    }

    static class AddInnerType implements DelayedSetter {

        private TypeGraphStore store;
        private TypeInfo       target;
        private String         innerTypeString;

        AddInnerType(TypeGraphStore store, TypeInfo target, String innerTypeString) {
            this.store = store;
            this.target = target;
            this.innerTypeString = innerTypeString;
        }

        @Override
        public void apply() {
            TypeInfo innerType = store.get(innerTypeString);
            this.target.getInnerTypes().add(innerType);
        }
    }

    static class AddDependency implements DelayedSetter {
        private TypeGraphStore           store;
        private String                   callee;
        private String                   caller;
        private int                      memberId;
        private Map<Integer, MemberInfo> memberInfoIds;

        AddDependency(TypeGraphStore store,
                      String caller, String callee, int memberId, Map<Integer, MemberInfo> memberInfoIds) {
            this.store = store;
            this.caller = caller;
            this.callee = callee;
            this.memberId = memberId;
            this.memberInfoIds = memberInfoIds;
        }

        @Override
        public void apply() {
            MemberInfo memberInfo = memberInfoIds.get(memberId);
            Dependency dep = new Dependency(store.get(caller), store.get(callee), memberInfo);

            TypeInfo ancTypeInfo = store.impactingAncestors.get(callee.substring(1));
            if (ancTypeInfo!=null) {
                MemberInfo ancMemberInfo = ancTypeInfo.getMembers().get(memberInfo);
                if (ancMemberInfo.getImpactingState() == ImpactingState.IS_IMPACTING ||
                    ancMemberInfo.getImpactingState() == ImpactingState.PREVIOUSLY_IMPACTING) {
                    String callerPath = store.uniqueIdToPath.get(caller);
                    for ( Map.Entry<String, ChangeSet> entry : store.changeSets.entrySet()) {
                        ChangeSet changeSet = entry.getValue();
                        if ( !changeSet.getUpdated().contains(callerPath)) {
                            changeSet.getImpacted().add(callerPath);
                            break;
                        }
                        throw new IllegalStateException("The path must match one ChangeSet " + callerPath);
                    }
                }
            }
        }
    }

    public List<String> getSources() {
        List<String> sources = new ArrayList<>();

        for (String dir : sourceDirs.keySet()) {
            ChangeSet changeSet = changeSets.get(dir);
            sources.addAll(changeSet.getSourcesToProcess() );
        }

        return sources;
    }

}
