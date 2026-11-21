package cn.lucanet.enhancement.exit;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static cn.lucanet.enhancement.exit.CustomJavaSource.FORMATTED_BODY;
import static cn.lucanet.enhancement.exit.CustomJavaModel.call;
import static cn.lucanet.enhancement.exit.CustomJavaModel.create;
import static cn.lucanet.enhancement.exit.JavaEditorContext.*;

/** Persists the JavaCode parts that Lucanet reads when compiling without the enhancement. */
final class CustomJavaParts {
    private static final List<String> GROUPS = List.of("imports","fields","methods","other");
    private final List<FolderEdit> edits = new ArrayList<>();
    final String issue;

    private CustomJavaParts(String issue) { this.issue = issue; }

    static CustomJavaParts prepare(Object code, Map<String,List<Map<String,String>>> parts, String issue,
            boolean wasSynced, Snapshot current, String references) throws Throwable {
        if (current.hasOtherParts()) return new CustomJavaParts("包含无法在完整源码窗口编辑的原生部件");
        if (issue == null) issue = current.importConflict(parts,references);
        if (issue != null && !wasSynced) return new CustomJavaParts(issue);
        Object imports = Objects.requireNonNull(partFolder(code,"IMPORT","ImportPart"), "ImportParts");
        Set<String> required = new HashSet<>(current.lockedImports);
        CustomJavaParts update = new CustomJavaParts(issue);
        FolderEdit importEdit = update.folder(imports);
        FolderEdit memberEdit = update.folder(Objects.requireNonNull(partFolder(code,"MEMBER","MemberPart"), "MemberParts"));
        FolderEdit methodEdit = update.folder(call(code,"getMethodPartFolder"));
        if (issue != null) return update;

        ClassLoader loader = code.getClass().getClassLoader();
        Object editable = constant(loader,"Part$Editability","EDITABLE");
        for (Map<String,String> part : parts.get("imports")) {
            String name = part.get("name");
            if (required.add(name)) importEdit.added.add(create(loader,PACKAGE+"ImportPart",editable,name));
        }
        for (Map<String,String> part : parts.get("fields")) {
            Object visibility = constant(loader,"Part$Visibility",part.get("visibility"));
            memberEdit.added.add(create(loader,PACKAGE+"LocalMemberPart",editable,visibility,
                    part.get("type"),part.get("name"),part.get("value")));
        }
        for (Map<String,String> part : parts.get("methods")) {
            Object visibility = constant(loader,"Part$Visibility",part.get("visibility"));
            String body = CustomJavaFormatting.nativeBody(part.getOrDefault(FORMATTED_BODY,part.get("body")));
            methodEdit.added.add(create(loader,PACKAGE+"LocalMethodPart",editable,visibility,part.get("type"),
                    part.get("name"),part.get("parameters"),part.get("throws"),body));
        }
        return update;
    }

    private static Object constant(ClassLoader loader, String type, String name) throws Throwable {
        Class<?> enumType = Class.forName(PACKAGE+type,false,loader);
        return MethodHandles.lookup().unreflectGetter(enumType.getField(name)).invoke();
    }

    private FolderEdit folder(Object folder) throws Throwable {
        FolderEdit edit = new FolderEdit(folder);
        edits.add(edit);
        for (Object part : edit.before) {
            if (isFolder(part.getClass())) folder(part);
            else if (Boolean.TRUE.equals(call(part,"isDeletable"))) continue;
            edit.retained.add(part);
        }
        return edit;
    }

    void apply() throws Throwable {
        // Notify the owning JavaCode once after the source and all three folders agree.
        for (FolderEdit edit : edits) {
            boolean removesParts = edit.retained.size()!=edit.before.size();
            if (!removesParts && edit.added.isEmpty()) continue;
            edit.changed = true;
            if (removesParts) setChildren(edit.folder,edit.retained);
            for (Object part : edit.added) {
                try { call(edit.folder,"addChild",part,null); }
                catch (NoSuchMethodException legacy) { call(edit.folder,"addPart",part,null); }
            }
            Set<Object> current = Collections.newSetFromMap(new IdentityHashMap<>());
            current.addAll(children(edit.folder));
            if (!current.containsAll(edit.added)) {
                throw new IllegalStateException("Lucanet 未接受自定义类的原生部件。");
            }
        }
    }

    void restore(Throwable failure) {
        for (FolderEdit edit : edits) {
            if (!edit.changed) continue;
            try {
                setChildren(edit.folder,edit.before);
            } catch (Throwable rollback) {
                failure.addSuppressed(rollback);
            }
        }
    }

    private static void setChildren(Object folder, List<?> children) throws Throwable {
        try { call(folder,"sortChildren",children,null); }
        catch (NoSuchMethodException legacy) { declared(folder,"a",children,null); }
    }

    static Snapshot snapshot(Object code) throws Throwable {
        return new Snapshot(code);
    }

    static final class Snapshot {
        final String name, revision;
        private final Set<String> lockedImports = new LinkedHashSet<>();
        private final List<String> allImports = new ArrayList<>();
        private final Map<String,List<Map<String,String>>> parts = new LinkedHashMap<>();
        private final ClassLoader loader;

        Snapshot(Object code) throws Throwable {
            loader=code.getClass().getClassLoader();
            // Read the persisted values through Lucanet's XML codec. getSourceCode adds a runtime
            // stack guard to each method, so that generated source cannot be saved as method bodies.
            Object xml = call(code,"getXMLElement","JavaCode");
            Class<?> codec = Class.forName("ln.xml.XMLUtil",false,xml.getClass().getClassLoader());
            MethodHandle read = MethodHandles.lookup().unreflect(codec.getMethod("getStringOrNull",xml.getClass(),String.class));
            name = (String)read.invoke(xml,"javaClassName");
            for (String group : GROUPS) parts.put(group,new ArrayList<>());
            readFolder(read,call(xml,"getChild","ImportParts"),"imports");
            readFolder(read,call(xml,"getChild","MemberParts"),"fields");
            readFolder(read,call(xml,"getChild","MethodParts"),"methods");
            revision = revision(name,parts);
        }

        @SuppressWarnings("unchecked")
        private void readFolder(MethodHandle read, Object folder, String group) throws Throwable {
            Deque<Object> entries = new ArrayDeque<>((List<?>)call(folder,"getChildren"));
            while (!entries.isEmpty()) {
                Object entry = entries.removeFirst();
                if ("PartFolder".equals(call(entry,"getName"))) {
                    entries.addAll((List<?>)call(entry,"getChildren"));
                    continue;
                }
                Map<String,String> raw = (Map<String,String>)call(entry,"getAttributes");
                String partName = (String)read.invoke(entry,"name");
                if ("imports".equals(group)) {
                    allImports.add(partName);
                    if ("EDITABLE".equals(raw.get("editability"))) parts.get(group).add(Map.of("name",partName));
                    else lockedImports.add(partName);
                    continue;
                }
                boolean field = "fields".equals(group);
                if (!raw.containsKey(field?"type":"body")) {
                    parts.get("other").add(raw);
                    continue;
                }
                Map<String,String> part = new HashMap<>();
                part.put("name",partName);
                part.put("visibility",Objects.toString(read.invoke(entry,"visibility"),"PACKAGE"));
                if (field) {
                    copy(read,entry,"type",part,"type");
                    copy(read,entry,"value",part,"value");
                } else {
                    copy(read,entry,"returnType",part,"type");
                    copy(read,entry,"arguments",part,"parameters");
                    copy(read,entry,"exceptions",part,"throws");
                    copy(read,entry,"body",part,"body");
                }
                parts.get(group).add(part);
            }
        }

        boolean hasOtherParts() { return !parts.get("other").isEmpty(); }

        boolean hasPaddedMethods() {
            for (Map<String,String> method : parts.get("methods")) {
                String body = method.get("body");
                if (body != null && CustomJavaFormatting.hasBodyPadding(body)) return true;
            }
            return false;
        }

        String importConflict(Map<String,List<Map<String,String>>> projected,String references) {
            Set<String> imported=new HashSet<>();
            for (Map<String,String> part : projected.getOrDefault("imports",List.of())) {
                String name = part.get("name");
                imported.add(name);
                if (name.startsWith("static ") || name.endsWith(".*")) continue;
                String simple = name.substring(name.lastIndexOf('.'));
                for (String locked : lockedImports) {
                    if (locked.endsWith(simple) && !locked.equals(name)) {
                        return "导入 "+name+" 与原生内置导入 "+locked+" 冲突";
                    }
                }
            }
            Set<String> simpleNames=new HashSet<>(Arrays.asList(Objects.toString(references,"").split("\n")));
            for(String locked:lockedImports){
                if(locked.endsWith(".*")||locked.startsWith("static ")||imported.contains(locked))continue;
                String simple=locked.substring(locked.lastIndexOf('.')+1);
                if(simpleNames.contains(simple))return "源码中的 "+simple+" 未显式使用原生内置导入 "+locked+"，写入原生 Class 可能改变类型含义；请改用完整类型名";
            }
            for(String locked:lockedImports)if(locked.endsWith(".*")&&!locked.startsWith("static ")&&!imported.contains(locked)){
                for(String simple:simpleNames){
                    if(imported.stream().anyMatch(name->!name.startsWith("static ")&&name.endsWith("."+simple)))continue;
                    String candidate=locked.substring(0,locked.length()-1)+simple;
                    if(!publicType(candidate))continue;
                    Set<String> originalWildcards=new HashSet<>(imported);originalWildcards.add("java.lang.*");
                    boolean conflict=false;
                    for(String original:originalWildcards)if(original.endsWith(".*")){
                        String other=original.substring(original.startsWith("static ")?7:0,original.length()-1)+simple;
                        if(!candidate.equals(other)&&publicType(other)){conflict=true;break;}
                    }
                    // Existing scripts may intentionally rely on the native default imports.
                    // Only reject a wildcard when it competes with a type already visible in source.
                    if(!conflict)continue;
                    return "原生内置导入 "+locked+" 会引入源码中未显式导入的 "+simple+"，可能改变类型含义；请改用完整类型名或显式导入";
                }
            }
            return null;
        }

        private boolean publicType(String qualified) {
            String binary=qualified;
            while(true)try{return java.lang.reflect.Modifier.isPublic(Class.forName(binary,false,loader).getModifiers());}
            catch(ClassNotFoundException absent){int dot=binary.lastIndexOf('.');if(dot<0)return false;binary=binary.substring(0,dot)+'$'+binary.substring(dot+1);}
            catch(LinkageError unavailable){return true;}
        }

        boolean matchesProjection(String storedName, Map<String,List<Map<String,String>>> projected) {
            Map<String,List<Map<String,String>>> expected = new HashMap<>(projected);
            Set<String> imports = new HashSet<>(lockedImports);
            List<Map<String,String>> editableImports = new ArrayList<>();
            for (Map<String,String> part : projected.get("imports")) {
                if (imports.add(part.get("name"))) editableImports.add(part);
            }
            expected.put("imports",editableImports);
            List<Map<String,String>> projectedMethods = projected.get("methods"), currentMethods = parts.get("methods");
            if (projectedMethods.size() != currentMethods.size()) return false;
            List<Map<String,String>> expectedMethods = new ArrayList<>();
            for (int index = 0; index < projectedMethods.size(); index++) {
                Map<String,String> method = new HashMap<>(projectedMethods.get(index));
                String raw = method.get("body"), formatted = method.remove(FORMATTED_BODY);
                String actual = normalizedLines(currentMethods.get(index).getOrDefault("body",""));
                // Old clients stored the raw block or only removed its outer indentation.
                if (!actual.equals(normalizedLines(raw)) && !actual.equals(CustomJavaFormatting.nativeBody(raw))
                        && (formatted == null || !actual.equals(CustomJavaFormatting.nativeBody(formatted)))) return false;
                method.put("body",actual);
                expectedMethods.add(method);
            }
            expected.put("methods",expectedMethods);
            return revision.equals(revision(storedName,expected));
        }

        boolean differsFromEmptyClass(String storedName) {
            return !revision.equals(revision(storedName,Map.of()));
        }

        String source() {
            StringBuilder source = new StringBuilder("package ").append(CustomJavaModel.SCRIPT_PACKAGE).append(";\n\n");
            for (String name : allImports) source.append("import ").append(name).append(";\n");
            source.append("\npublic class ").append(name).append(" {\n");
            for (Map<String,String> field : parts.get("fields")) {
                String type = field.get("type"), value = field.get("value");
                source.append("    ").append(modifiers(field)).append(type).append(' ').append(field.get("name"));
                if (value != null) {
                    source.append(" = ");
                    if ("String".equals(type)) source.append('"').append(value).append('"');
                    else if ("char".equals(type)) source.append('\'').append(value).append('\'');
                    else source.append(value);
                }
                source.append(";\n");
            }
            for (Map<String,String> method : parts.get("methods")) {
                source.append("\n    ").append(signature(method)).append(" {");
                String body = method.get("body");
                if (body == null) body = defaultBody(method.get("type"));
                body = CustomJavaFormatting.editorBody(body);
                if (!body.isEmpty()) source.append('\n').append(body);
                source.append("\n    }\n");
            }
            return source.append("}\n").toString();
        }

        String outline() {
            StringBuilder outline = new StringBuilder();
            for (String name : allImports) outline.append("0\timport ").append(name).append(";\n");
            outline.append("0\tpublic class ").append(name).append('\n');
            for (Map<String,String> field : parts.get("fields")) {
                outline.append("1\t").append(modifiers(field)).append(field.get("type")).append(' ').append(field.get("name")).append('\n');
            }
            for (Map<String,String> method : parts.get("methods")) outline.append("1\t").append(signature(method)).append('\n');
            return outline.toString();
        }

        private static String signature(Map<String,String> method) {
            String type = method.get("type"), exceptions = method.get("throws");
            return modifiers(method)+(type==null?"":type+" ")+method.get("name")+
                    "("+method.getOrDefault("parameters","")+")"+(exceptions==null?"":" throws "+exceptions);
        }

        private static String modifiers(Map<String,String> part) {
            String visibility = part.get("visibility").toLowerCase(Locale.ROOT).replace('_',' ');
            if (visibility.startsWith("package")) visibility = visibility.substring("package".length()).trim();
            return visibility.isEmpty()?"":visibility+" ";
        }

        private static String defaultBody(String type) {
            if (type == null) return "";
            switch (type) {
                case "void": return "";
                case "boolean": return "return false;";
                case "byte": case "short": case "int": case "long": case "float": case "double": case "char":
                    return "return 0;";
                default: return "return null;";
            }
        }
    }

    private static void copy(MethodHandle read, Object entry, String attribute, Map<String,String> part, String key)
            throws Throwable {
        String value = (String)read.invoke(entry,attribute);
        if (value != null) part.put(key,value);
    }

    private static String revision(String name, Map<String,List<Map<String,String>>> parts) {
        StringBuilder encoded = new StringBuilder();
        append(encoded,name);
        for (String group : GROUPS) {
            List<Map<String,String>> entries = parts.getOrDefault(group,List.of());
            encoded.append(entries.size()).append(';');
            for (Map<String,String> part : entries) {
                // XML may round-trip a zero-length body as an absent attribute.
                Map<String,String> sorted = new TreeMap<>(part);
                sorted.values().removeIf(value -> value == null || value.isEmpty());
                encoded.append(sorted.size()).append(';');
                sorted.forEach((key,value) -> { append(encoded,key);append(encoded,value); });
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static void append(StringBuilder target, String value) {
        String normalized = normalizedLines(value);
        target.append(normalized.length()).append(':').append(normalized);
    }

    private static String normalizedLines(String value) { return value.replace("\r\n","\n").replace('\r','\n'); }

    private static final class FolderEdit {
        final Object folder;
        final List<?> before;
        final List<Object> retained = new ArrayList<>();
        final List<Object> added = new ArrayList<>();
        boolean changed;

        FolderEdit(Object folder) throws Throwable {
            this.folder = folder;
            this.before = new ArrayList<>(children(folder));
        }
    }
}
