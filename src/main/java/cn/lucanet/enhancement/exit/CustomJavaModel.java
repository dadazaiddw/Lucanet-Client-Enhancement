package cn.lucanet.enhancement.exit;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.function.BiFunction;
import javax.swing.*;
import javax.swing.text.View;

/** Full source is part of the native node's exported attribute map, never a sidecar file. */
final class CustomJavaModel {
    static final String CLASS="com.lucanet.common.dom.script.elements.classes.ClassElement";
    static final String CODE="com.lucanet.common.dom.script.java.JavaCode";
    static final String ATTRIBUTE="cn.lucanet.enhancement.javaSource.v1";
    static final String SOURCE_HANDLER=ATTRIBUTE+".reader";
    static final String DOCUMENT=ATTRIBUTE+".document";
    static final String SCRIPT_PACKAGE=CustomJavaSource.SCRIPT_PACKAGE;
    private static final String PARTS_STATUS="partsStatus";
    private static final String PARTS_REVISION="partsRevision";
    private static final String NATIVE_ISSUE="nativeIssue";
    private static final String SYNCED="synced";
    private static final String PARSER_PACKAGE="cn.lucanet.enhancement.exit.";
    private static final String PARSER="cn.lucanet.enhancement.exit.CustomJavaSource";
    private static final Map<String,Definition> legacyDefinitions=Collections.synchronizedMap(new WeakHashMap<>());
    private CustomJavaModel(){}
    static boolean isClass(Object node){return node!=null&&node.getClass().getName().equals(CLASS);}
    static boolean isFolder(Object node){if(node==null)return false;String name=node.getClass().getName();return name.equals(CLASS.replace("ClassElement","ClassFolderRootElement"))||name.equals(CLASS.replace("ClassElement","ClassFolderElement"));}
    static Object call(Object target,String name,Object...args)throws Throwable{return JavaEditorContext.call(target,name,args);}
    static Object field(Object target,String type)throws Throwable {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())for(Field field:c.getDeclaredFields())if(field.getType().getName().equals(type)) {
            field.setAccessible(true);return MethodHandles.lookup().unreflectGetter(field).invoke(target);
        }
        throw new NoSuchFieldException(type);
    }
    static Object declared(Object target,String name)throws Throwable {
        for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())try {
            Method method=c.getDeclaredMethod(name);method.setAccessible(true);return MethodHandles.lookup().unreflect(method).invoke(target);
        }catch(NoSuchMethodException absent){}
        throw new NoSuchMethodException(name);
    }
    static Object create(ClassLoader loader,String name,Object...args)throws Throwable {
        Class<?> type=Class.forName(name,true,loader);
        for(Constructor<?> ctor:type.getDeclaredConstructors()) {
            if(ctor.getParameterCount()!=args.length)continue;
            boolean match=true;Class<?>[] types=ctor.getParameterTypes();
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i])&&!types[i].isPrimitive())match=false;
            if(match){ctor.setAccessible(true);return MethodHandles.lookup().unreflectConstructor(ctor).invokeWithArguments(args);}
        }
        throw new NoSuchMethodException(name+" constructor");
    }
    @SuppressWarnings("unchecked") static Map<String,Map<String,String>> attributes(Object node)throws Throwable {
        Map<String,Map<String,String>> result=new LinkedHashMap<>();
        Map<String,Map<String,String>> original=(Map<String,Map<String,String>>)call(node,"getAttributeMap");
        if(original!=null)original.forEach((key,value)->result.put(key,new LinkedHashMap<>(value)));
        return result;
    }
    static String storedSource(Object node)throws Throwable {
        if(!isClass(node))return null;
        Map<String,String> data=attributes(node).get(ATTRIBUTE);return data==null?null:data.get("source");
    }
    static boolean hasNativeParts(Object node)throws Throwable {
        return SYNCED.equals(attributes(node).getOrDefault(ATTRIBUTE,Map.of()).get(PARTS_STATUS));
    }

    @SuppressWarnings("unchecked")
    static Map<String,String> parse(String source,Map<String,List<Map<String,String>>> parts)throws Exception {
        try(URLClassLoader loader=new URLClassLoader(new URL[]{CustomJavaModel.class.getProtectionDomain().getCodeSource().getLocation()},
                ClassLoader.getPlatformClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(!name.startsWith(PARSER_PACKAGE))return super.loadClass(name,resolve);
                Class<?> type=findLoadedClass(name);
                if(type==null)type=findClass(name);
                if(resolve)resolveClass(type);
                return type;
            }
        }) {
            // The agent's isolated loader cannot resolve jdk.compiler on all client versions.
            BiFunction<String,Map<String,List<Map<String,String>>>,Map<String,String>> parser=
                    (BiFunction<String,Map<String,List<Map<String,String>>>,Map<String,String>>)loader
                        .loadClass(PARSER).getConstructor().newInstance();
            return parser.apply(source,parts);
        }
    }

    private static Definition legacyDefinition(String source)throws Exception {
        Definition known=legacyDefinitions.get(source);
        if(known!=null)return known;
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        Definition parsed=new Definition(parse(source,parts),parts);
        legacyDefinitions.put(source,parsed);
        return parsed;
    }

    private static String sourceForEditor(String source) {
        try {
            return legacyDefinition(source).descriptor.getOrDefault(CustomJavaSource.FORMATTED_SOURCE,source);
        } catch (IllegalArgumentException invalidSource) {
            // Native users can save an incomplete draft. Open it so they can fix the syntax.
            return CustomJavaFormatting.withoutSectionComments(source);
        } catch (Exception | LinkageError failure) {
            EnhancementLog.error("CUSTOM_JAVA_FORMAT_FAILED",failure);
            return CustomJavaFormatting.withoutSectionComments(source);
        }
    }

    private static boolean changed(Map<String,String> data,CustomJavaParts.Snapshot current)throws Exception {
        String baseline=data.get(PARTS_REVISION);
        if(baseline!=null)return !baseline.equals(current.revision);
        String name=data.getOrDefault("name",current.name);
        if(!SYNCED.equals(data.get(PARTS_STATUS)))return current.differsFromEmptyClass(name);
        Definition former=legacyDefinition(data.get("source"));
        return former.parts.isEmpty()||!current.matchesProjection(name,former.parts);
    }

    static String sourceForCode(Object code) {
        Object owner=null;
        try {
            owner=field(code,"com.lucanet.common.dom.script.elements.ScriptElement");
            if(!isClass(owner))return null;
            Map<String,String> data=attributes(owner).getOrDefault(ATTRIBUTE,Map.of());
            // The source tab and compiler both read this getter. Synced classes use Lucanet's
            // generator for section comments and stack guards, just as they do on the server.
            if(SYNCED.equals(data.get(PARTS_STATUS)))return null;
            return data.get("source");
        }
        catch(Throwable failure){EnhancementLog.error("CUSTOM_JAVA_SOURCE_UNAVAILABLE",failure);
            if(isClass(owner))throw new IllegalStateException("无法读取 Class 源码，已停止生成旧结构代码。",failure);return null;
        }
    }
    static Object javaCode(Object node)throws Throwable{return field(node,CODE);}

    static Version version(Object node)throws Throwable {
        return version(javaCode(node),attributes(node).getOrDefault(ATTRIBUTE,Map.of()));
    }

    static Version version(Object code,Map<String,String> data)throws Throwable {
        CustomJavaParts.Snapshot current=CustomJavaParts.snapshot(code);
        return new Version(data,current,changedIfStored(data,current));
    }

    private static boolean changedIfStored(Map<String,String> data,CustomJavaParts.Snapshot current)throws Exception {
        return data.containsKey("source")&&changed(data,current);
    }

    private static final class Definition {
        final Map<String,String> descriptor;
        final Map<String,List<Map<String,String>>> parts;

        Definition(Map<String,String> descriptor,Map<String,List<Map<String,String>>> parts) {
            this.descriptor=descriptor;
            this.parts=parts;
        }
    }

    static final class Version {
        private final Map<String,String> data;
        private final CustomJavaParts.Snapshot current;
        private final boolean nativeChanged;

        Version(Map<String,String> data,CustomJavaParts.Snapshot current,boolean nativeChanged) {
            this.data=data;
            this.current=current;
            this.nativeChanged=nativeChanged;
        }

        boolean hasStoredSource() { return data.containsKey("source"); }

        private boolean usesNativeSource() {
            return !hasStoredSource()||nativeChanged&&SYNCED.equals(data.get(PARTS_STATUS));
        }

        String source() {
            return sourceForEditor(unformattedSource());
        }

        boolean needsResave() {
            if(hasStoredSource()&&!data.containsKey(PARTS_STATUS))return true;
            if((usesNativeSource()||SYNCED.equals(data.get(PARTS_STATUS)))&&current.hasPaddedMethods())return true;
            String raw=unformattedSource();
            return !raw.equals(sourceForEditor(raw));
        }

        private String unformattedSource() { return usesNativeSource()?current.source():data.get("source"); }

        String qualifiedName() {
            return usesNativeSource()?SCRIPT_PACKAGE+"."+current.name:data.getOrDefault("qualified",current.name);
        }

        String outline() {
            return usesNativeSource()?current.outline():data.getOrDefault("outline","");
        }

        boolean editableAsSource() { return !usesNativeSource()||!current.hasOtherParts(); }

        boolean sameAs(Version other) {
            return other!=null&&data.equals(other.data)&&current.revision.equals(other.current.revision);
        }

        Status status()throws Exception {
            if(!hasStoredSource())return new Status("关闭窗口自动保存到 Class。",false);
            if(SYNCED.equals(data.get(PARTS_STATUS))) {
                if(!editableAsSource())return new Status("已读取原生端修改。额外原生部件请在原生编辑器中查看。",true);
                return new Status(nativeChanged?"已读取原生端修改。":"已同步到原生 Class。",false);
            }
            String reason=data.get(NATIVE_ISSUE);
            if(reason==null) {
                Definition definition=legacyDefinition(data.get("source"));
                reason=definition.descriptor.get(NATIVE_ISSUE);
                if(reason==null)reason=current.importConflict(definition.parts);
            }
            String message="原生客户端和服务器无法还原此类。";
            if(reason!=null)message+=reason+"。请使用额外应用程序库部署此类。";
            else if(!data.containsKey(PARTS_STATUS))message+="请按 Ctrl+S 写入原生 Class。";
            else message+="请使用额外应用程序库部署此类。";
            if(nativeChanged)message+="已保留增强源码，原生端修改需手动合并。";
            return new Status(message,true);
        }
    }

    static final class Status {
        private static final Color RED=new Color(183,46,49);
        final String message;
        final boolean warning;

        Status(String message,boolean warning) {
            this.message=message;
            this.warning=warning;
        }

        Status append(String text) { return new Status(message+text,warning); }

        void show(StatusText label) {
            label.setForeground(warning?RED:UIManager.getColor("Label.foreground"));
            label.setText(message);
            String escaped=message.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
            label.setToolTipText("<html><body style='width: 380px'>"+escaped+"</body></html>");
        }
    }

    static final class StatusText extends JTextArea {
        StatusText() {
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setBorder(null);
            setFont(UIManager.getFont("Label.font"));
        }

        @Override public Dimension getPreferredSize() {
            Container parent=getParent();
            if(parent==null||parent.getWidth()==0)return super.getPreferredSize();
            Insets outer=parent.getInsets(), padding=getInsets();
            int width=Math.max(1,parent.getWidth()-outer.left-outer.right);
            int textWidth=Math.max(1,width-padding.left-padding.right);
            // BorderLayout measures the footer before it assigns the text area its new bounds.
            View view=getUI().getRootView(this);
            view.setSize(textWidth,Integer.MAX_VALUE);
            int height=(int)Math.ceil(view.getPreferredSpan(View.Y_AXIS))+padding.top+padding.bottom;
            return new Dimension(width,height);
        }
    }

    static String template(ClassLoader loader,String name,String kind)throws Throwable {
        Object node=create(loader,CLASS);Object code=javaCode(node);
        call(code,"setJavaClassName",name,null,false);
        String source=CustomJavaParts.snapshot(code).source();
        int declaration=source.indexOf("public class "+name);
        if(declaration<0)throw new IllegalStateException("当前 Lucanet Class 模板不受支持");
        String header=source.substring(0,declaration);
        String tail="\n}\n";
        switch(kind) {
            case "Interface": return header+"public interface "+name+" {"+tail;
            case "Record": return header+"public record "+name+"() {"+tail;
            case "Enum": return header+"public enum "+name+" {\n    ;"+tail;
            case "Annotation": return header+"public @interface "+name+" {"+tail;
            case "Exception": return header+"public class "+name+" extends Exception {\n    public "+name+"(String message) {\n        super(message);\n    }"+tail;
            case "Abstract Class": return header+"public abstract class "+name+" {"+tail;
            default:return source;
        }
    }
    static void checkName(Object parent,Object current,String name)throws Throwable {
        Object root=call(parent,"getDOMRootNode");
        if(root==null)root=parent;
        checkNameTree(root,current,name,Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    private static void checkNameTree(Object node,Object current,String name,Set<Object> visited)throws Throwable {
        if(node==null||!visited.add(node))return;
        if(node!=current&&isClass(node)&&name.equals(call(node,"getJavaClassName")))throw new IllegalArgumentException("Class 中已存在类型："+name);
        for(Object child:(List<?>)call(node,"getChildren"))checkNameTree(child,current,name,visited);
    }
    /** All edits use native setters/notifications, preserving node identity and the normal save flow. */
    static Object save(Object node,Object parent,Object context,String source,Map<String,String> descriptor,
            Map<String,List<Map<String,String>>> parts)throws Throwable {
        String name=descriptor.get("name");checkName(parent,node,name);
        boolean fresh=node==null;
        if(fresh)node=create(parent.getClass().getClassLoader(),CLASS);
        Object code=javaCode(node);Map<String,Map<String,String>> before=attributes(node),after=attributes(node);
        Map<String,String> previous=before.getOrDefault(ATTRIBUTE,Map.of());
        boolean wasSynced=SYNCED.equals(previous.get(PARTS_STATUS));
        CustomJavaParts.Snapshot current=CustomJavaParts.snapshot(code);
        CustomJavaParts nativeParts=CustomJavaParts.prepare(code,parts,descriptor.get(NATIVE_ISSUE),wasSynced,current);
        Map<String,String> data=new LinkedHashMap<>(descriptor);
        String formatted=data.remove(CustomJavaSource.FORMATTED_SOURCE);
        data.put("source",formatted==null?source:formatted);
        data.put(PARTS_STATUS,nativeParts.issue==null?SYNCED:"client");
        if(nativeParts.issue!=null)data.put(NATIVE_ISSUE,nativeParts.issue);
        after.put(ATTRIBUTE,data);
        String oldName=(String)call(code,"getJavaClassName"),oldIdentifier=(String)call(node,"getIdentifier");
        if(fresh||!oldName.equals(name)) {
            Object root=call(parent,"getDOMRootNode");Map<String,String> labels=after.computeIfAbsent("label",ignored->new LinkedHashMap<>());
            for(Object language:(List<?>)call(root,"getAvailableLanguages"))labels.put(String.valueOf(call(language,"getID")),name);
            Object selected=call(root,"getSelectedLanguage");if(selected!=null)labels.put(String.valueOf(call(selected,"getID")),name);
        }
        try {
            call(code,"setJavaClassName",name,null,false);
            nativeParts.apply();
            CustomJavaParts.Snapshot updated=CustomJavaParts.snapshot(code);
            // For client-only types retain the old baseline if native edits are still pending.
            if(nativeParts.issue!=null&&!wasSynced&&changedIfStored(previous,current)) {
                String baseline=previous.get(PARTS_REVISION);
                if(baseline!=null)data.put(PARTS_REVISION,baseline);
            }else data.put(PARTS_REVISION,updated.revision);
            call(node,"setAttributeMap",null,after);
            if(fresh)call(node,"setIdentifier",null,name);
            if(fresh)call(parent,"addChild",context,node);
            else call(code,"contentChanged",context);
            call(node,"nodeChanged",context);
            return node;
        }catch(Throwable failure) {
            nativeParts.restore(failure);
            try {
                if(fresh&&call(node,"getParent")==parent)call(parent,"removeChild",context,node);
                call(node,"setAttributeMap",null,before);call(code,"setJavaClassName",oldName,null,false);call(node,"setIdentifier",null,oldIdentifier);
                if(!fresh)call(code,"contentChanged",context);
            }catch(Throwable rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
}
