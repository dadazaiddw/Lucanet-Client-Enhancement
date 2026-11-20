package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.text.JTextComponent;

/** Adapter to the native class/ImportPart model. Mutations use method handles so native
 * permission, dirty-state, notification and reflection-stack checks remain in place. */
final class JavaEditorContext {
    static final String PACKAGE="com.lucanet.common.dom.script.java.";
    final Object editor, code, context, part, sourceEditor;
    JavaEditorContext(JTextComponent text) throws Throwable {
        Object root=null,source=null;
        for(Component c=text;c!=null;c=c.getParent()) {
            if(c.getClass().getName().equals(PACKAGE+"JavaSourceCodeEditor"))source=c;
            if(c.getClass().getName().equals(PACKAGE+"JavaCodeEditor")){root=c;break;}
        }
        editor=root;sourceEditor=source;
        code=root==null?null:field(root,PACKAGE+"JavaCode");
        context=root==null?null:call(root,"getUiContext");
        part=root==null?null:field(root,PACKAGE+"Part");
    }
    Map<String,Object> request(JTextComponent text,String operation) throws Throwable {
        Map<String,Object> result=new HashMap<>();
        result.put("operation",operation);result.put("text",text.getText());result.put("caret",text.getCaretPosition());
        result.put("source",code==null?"":call(code,"getSourceCode"));
        result.put("part",part==null?"":call(part,"getName"));
        if(part!=null)try{result.put("partBody",call(part,"getBody"));}catch(NoSuchMethodException notMethod){}
        if(part!=null&&part.getClass().getName().equals(PACKAGE+"LocalMethodPart")) {
            String signature="f";try{if(part.getClass().getDeclaredMethod("e").getReturnType()==String.class)signature="e";}catch(NoSuchMethodException legacy){}
            result.put("partSignature",declared(part,signature));
        }
        result.put("sources",relatedSources());
        if(operation.equals("organize"))result.putAll(importState());
        result.put("classpath",classpath(text.getClass()));
        return result;
    }
    Map<String,List<String>> importState()throws Throwable {
        List<String> names=new ArrayList<>(),editable=new ArrayList<>();Object folder=code==null?null:importFolder();
        if(folder!=null){List<Object> entries=new ArrayList<>();collectImports(folder,entries);for(Object entry:entries){
            String name=String.valueOf(call(entry,"getName"));names.add(name);if(Boolean.TRUE.equals(call(entry,"isDeletable")))editable.add(name);
        }}
        Map<String,List<String>> result=new LinkedHashMap<>();result.put("importNames",names);result.put("editableImports",editable);return result;
    }
    /** Snapshot the same Script's Class tree on the EDT; never retain business model objects in javac. */
    private Map<String,String> relatedSources() throws Throwable {
        Map<String,String> sources=new LinkedHashMap<>();if(code==null)return sources;
        Object owner=field(code,"com.lucanet.common.dom.script.elements.ScriptElement");
        if(owner==null)return sources;
        Object script=call(owner,"getScript");if(script==null)return sources;
        collectSources(call(script,"getClassRoot"),sources,Collections.newSetFromMap(new IdentityHashMap<>()));
        return sources;
    }
    private static void collectSources(Object node,Map<String,String> result,Set<Object> visited)throws Throwable {
        if(node==null||!visited.add(node))return;
        if(node.getClass().getName().equals("com.lucanet.common.dom.script.elements.classes.ClassElement")) {
            String name=String.valueOf(call(node,"getJavaClassName"));Object source=call(node,"getSourceCode");
            if(source instanceof String)result.put(name,(String)source);
        }
        for(Object child:(List<?>)call(node,"getChildren"))collectSources(child,result,visited);
    }
    /** Returns the short name if imported, otherwise a fully qualified name that is always usable. */
    String importType(String qualified) throws Throwable {
        String simple=qualified.substring(qualified.lastIndexOf('.')+1);
        boolean implicit=qualified.startsWith("java.lang.") && qualified.substring(10).indexOf('.')<0;
        if(code==null || context==null || editor==null || !Boolean.TRUE.equals(call(editor,"isUsable")))return implicit?simple:qualified;
        Object folder=importFolder();if(folder==null)return qualified;
        List<Object> imports=new ArrayList<>();collectImports(folder,imports);
        for(Object entry:imports) {
            String name=String.valueOf(call(entry,"getName"));
            if(name.equals(qualified))return simple;
            if(name.endsWith("."+simple) && !name.equals(qualified))return qualified;
        }
        String source=String.valueOf(call(code,"getSourceCode"));
        java.util.regex.Matcher pkg=java.util.regex.Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(source);
        String currentPackage=pkg.find()?pkg.group(1):"";
        if(qualified.equals(currentPackage+"."+simple))return simple;
        for(String custom:relatedSources().values()) {
            String lexical=JavaEditing.mask(custom);
            if(java.util.regex.Pattern.compile("\\b(?:class|interface|enum|record)\\s+"+java.util.regex.Pattern.quote(simple)+"\\b").matcher(lexical).find()&&
                    java.util.regex.Pattern.compile("\\bpackage\\s+"+java.util.regex.Pattern.quote(currentPackage)+"\\s*;").matcher(lexical).find())return qualified;
        }
        if(implicit) {
            for(Object entry:imports){String name=String.valueOf(call(entry,"getName"));if(name.endsWith(".*")&&!name.equals("java.lang.*")&&loadable(name.substring(0,name.length()-1)+simple))return qualified;}
            return simple;
        }
        String packageName=qualified.substring(0,qualified.lastIndexOf('.'));
        for(Object entry:imports)if(String.valueOf(call(entry,"getName")).equals(packageName+".*")) {
            boolean conflict=false;
            if(loadable("java.lang."+simple))conflict=true;
            for(Object other:imports){String name=String.valueOf(call(other,"getName"));if(name.endsWith(".*")&&!name.equals(packageName+".*")) {
                if(loadable(name.substring(0,name.length()-1)+simple))conflict=true;
            }}
            if(!conflict)return simple;
        }
        // Flush through the normal source listener before a model event refreshes the current editor.
        if(sourceEditor!=null)call(sourceEditor,"commitChanges");
        ClassLoader loader=code.getClass().getClassLoader();
        Class<?> editability=Class.forName(PACKAGE+"Part$Editability",false,loader);
        Object editable=MethodHandles.lookup().unreflectGetter(editability.getField("EDITABLE")).invoke();
        Class<?> type=Class.forName(PACKAGE+"ImportPart",false,loader);
        Object added=MethodHandles.lookup().unreflectConstructor(type.getConstructor(editability,String.class)).invoke(editable,qualified);
        try{call(folder,"addChild",added,context);}catch(NoSuchMethodException legacy){call(folder,"addPart",added,context);}
        List<Object> after=new ArrayList<>();collectImports(folder,after);
        return after.stream().anyMatch(entry->entry==added)?simple:qualified;
    }
    private boolean loadable(String name){try{Class.forName(name,false,code.getClass().getClassLoader());return true;}catch(ClassNotFoundException absent){return false;}catch(LinkageError unresolved){return true;}}
    void sortImports() throws Throwable {
        if(code==null || context==null || !Boolean.TRUE.equals(call(editor,"isUsable")))return;
        if(sourceEditor!=null)call(sourceEditor,"commitChanges");
        Object folder=importFolder();if(folder!=null)sortFolder(folder);
    }
    void applyImports(List<String> expected,List<String> requested)throws Throwable {
        if(code==null||context==null||!Boolean.TRUE.equals(call(editor,"isUsable")))return;
        Object folder=importFolder();if(folder==null)return;
        List<Object> current=new ArrayList<>();collectImports(folder,current);List<String> names=new ArrayList<>();
        for(Object item:current)names.add(String.valueOf(call(item,"getName")));
        if(!names.equals(expected))return; // A user/model edit during analysis supersedes this plan.
        if(sourceEditor!=null)call(sourceEditor,"commitChanges");
        for(String name:requested)if(!names.contains(name)) {
            ClassLoader loader=code.getClass().getClassLoader();Class<?> editability=Class.forName(PACKAGE+"Part$Editability",false,loader);
            Object editable=MethodHandles.lookup().unreflectGetter(editability.getField("EDITABLE")).invoke();
            Object added=MethodHandles.lookup().unreflectConstructor(Class.forName(PACKAGE+"ImportPart",false,loader).getConstructor(editability,String.class)).invoke(editable,name);
            try{call(folder,"addChild",added,context);}catch(NoSuchMethodException legacy){call(folder,"addPart",added,context);}
        }
        // Verify additions before removing redundant nodes. Preserve hidden/native imports.
        List<Object> after=new ArrayList<>();collectImports(folder,after);Set<String> added=new HashSet<>();for(Object item:after)added.add(String.valueOf(call(item,"getName")));
        if(!added.containsAll(requested))return;
        removeRedundant(folder,new HashSet<>(requested));sortFolder(folder);
    }
    private void removeRedundant(Object folder,Set<String> requested)throws Throwable {
        for(Object item:new ArrayList<>(children(folder))) {
            if(isFolder(item.getClass())){removeRedundant(item,requested);continue;}
            if(!item.getClass().getName().equals(PACKAGE+"ImportPart")||requested.contains(String.valueOf(call(item,"getName")))||!Boolean.TRUE.equals(call(item,"isDeletable")))continue;
            try{call(folder,"removeChild",context,item);}catch(NoSuchMethodException legacy){declared(folder,"a",context,item);}
        }
    }
    private void sortFolder(Object folder) throws Throwable {
        List<?> existing=children(folder);
        List<Object> sorted=new ArrayList<>(existing);
        Map<Object,String> names=new IdentityHashMap<>();
        for(Object entry:sorted) {
            if(isFolder(entry.getClass())) { sortFolder(entry);names.put(entry,"\u0000"+entry); }
            else names.put(entry,String.valueOf(call(entry,"getName")));
        }
        sorted.sort(Comparator.comparing(names::get));
        if(!sorted.equals(existing))try{call(folder,"sortChildren",sorted,context);}catch(NoSuchMethodException legacy){declared(folder,"a",sorted,context);}
    }
    private Object importFolder() throws Throwable {
        for(Field f:code.getClass().getDeclaredFields()) {
            if(!isFolder(f.getType()))continue;
            f.setAccessible(true);Object candidate=MethodHandles.lookup().unreflectGetter(f).invoke(code);
            if(candidate==null)continue;
            try{if(String.valueOf(call(candidate,"getSupportedType")).equals("IMPORT"))return candidate;}
            catch(NoSuchMethodException legacy){
                // 2311 PartFolder is typed by Class<ImportPart>, before PartType was introduced.
                for(Class<?> folderType=candidate.getClass();folderType!=null;folderType=folderType.getSuperclass())for(Field type:folderType.getDeclaredFields())if(type.getType()==Class.class){
                    type.setAccessible(true);Object value=MethodHandles.lookup().unreflectGetter(type).invoke(candidate);
                    if(value instanceof Class && ((Class<?>)value).getName().equals(PACKAGE+"ImportPart"))return candidate;
                }
            }
        }
        return null;
    }
    private static boolean isFolder(Class<?> type){for(Class<?> c=type;c!=null;c=c.getSuperclass())if(c.getName().equals(PACKAGE+"PartFolder"))return true;return false;}
    private void collectImports(Object folder,List<Object> result) throws Throwable {
        for(Object child:children(folder)) {
            if(child.getClass().getName().equals(PACKAGE+"ImportPart"))result.add(child);
            else if(isFolder(child.getClass()))collectImports(child,result);
        }
    }
    private List<?> children(Object folder)throws Throwable {
        try{return (List<?>)call(folder,"getChildren");}catch(NoSuchMethodException legacy){return (List<?>)declared(folder,"a");}
    }
    private static Object declared(Object target,String name,Object...args)throws Throwable {
        for(Class<?> owner=target.getClass();owner!=null;owner=owner.getSuperclass())for(Method method:owner.getDeclaredMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!method.getParameterTypes()[i].isInstance(args[i]))match=false;
            if(match){method.setAccessible(true);return MethodHandles.lookup().unreflect(method).bindTo(target).invokeWithArguments(args);}
        }
        throw new NoSuchMethodException(name);
    }
    private static Object field(Object target,String type) throws Throwable {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields()) {
            if(f.getType().getName().equals(type)){f.setAccessible(true);return MethodHandles.lookup().unreflectGetter(f).invoke(target);}
        }
        return null;
    }
    static Object call(Object target,String name,Object...args) throws Throwable {
        for(Method method:target.getClass().getMethods()) {
            if(!method.getName().equals(name)||method.getParameterCount()!=args.length)continue;
            Class<?>[] types=method.getParameterTypes();boolean match=true;
            for(int i=0;i<types.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i])&&!types[i].isPrimitive())match=false;
            if(match)return MethodHandles.lookup().unreflect(method).bindTo(target).invokeWithArguments(args);
        }
        throw new NoSuchMethodException(target.getClass().getName()+"."+name);
    }
    static String classpath(Class<?> editorType) {
        LinkedHashSet<String> paths=new LinkedHashSet<>();
        // Loaded module locations and URL order select the client's actual versions.
        ClassLoader loader=editorType.getClassLoader();
        for(ClassLoader cursor=loader;cursor!=null;cursor=cursor.getParent())if(cursor instanceof URLClassLoader)
            for(URL url:((URLClassLoader)cursor).getURLs())addUrl(paths,url);
        ModuleLayer layer=editorType.getModule().getLayer();
        if(layer!=null)layer.configuration().modules().forEach(module->module.reference().location().ifPresent(uri->{
            try{addUrl(paths,uri.toURL());}catch(Exception ignored){}
        }));
        try{if(editorType.getProtectionDomain().getCodeSource()!=null)addUrl(paths,editorType.getProtectionDomain().getCodeSource().getLocation());}catch(SecurityException ignored){}
        for(String path:System.getProperty("java.class.path","").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            if(!path.isEmpty())paths.add(path);
        return String.join(java.io.File.pathSeparator,paths);
    }
    private static void addUrl(Set<String> paths,URL url) {
        if(!url.getProtocol().equals("file"))return;
        try{paths.add(Path.of(url.toURI()).toString());}catch(Exception ignored){}
    }
}
