package cn.lucanet.enhancement.exit;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.*;
import javax.swing.*;

/** Full source is part of the native node's exported attribute map, never a sidecar file. */
final class CustomJavaModel {
    static final String CLASS="com.lucanet.common.dom.script.elements.classes.ClassElement";
    static final String CODE="com.lucanet.common.dom.script.java.JavaCode";
    static final String ATTRIBUTE="cn.lucanet.enhancement.javaSource.v1";
    static final String SOURCE_HANDLER=ATTRIBUTE+".reader";
    static final String DOCUMENT=ATTRIBUTE+".document";
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
    static String sourceForCode(Object code) {
        Object owner=null;
        try {owner=field(code,"com.lucanet.common.dom.script.elements.ScriptElement");return storedSource(owner);}
        catch(Throwable failure){EnhancementLog.error("CUSTOM_JAVA_SOURCE_UNAVAILABLE",failure);
            if(isClass(owner))throw new IllegalStateException("无法读取 Class 源码，已停止生成旧结构代码。",failure);return null;
        }
    }
    static String source(Object node)throws Throwable {return String.valueOf(call(node,"getSourceCode"));}
    static Object javaCode(Object node)throws Throwable{return field(node,CODE);}
    static String template(ClassLoader loader,String name,String kind)throws Throwable {
        Object node=create(loader,CLASS);Object code=javaCode(node);
        call(code,"setJavaClassName",name,null,false);
        String source=String.valueOf(call(code,"getSourceCode"));
        int declaration=source.indexOf("public class "+name);
        if(declaration<0)throw new IllegalStateException("当前 Lucanet Class 模板不受支持");
        String header=source.substring(0,declaration);
        String tail="\n    // ===========================  End of Class  ===========================\n}\n";
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
    static Object save(Object node,Object parent,Object context,String source,Map<String,String> descriptor)throws Throwable {
        String name=descriptor.get("name");checkName(parent,node,name);
        boolean fresh=node==null;
        if(fresh)node=create(parent.getClass().getClassLoader(),CLASS);
        Object code=javaCode(node);Map<String,Map<String,String>> before=attributes(node),after=attributes(node);
        Map<String,String> data=new LinkedHashMap<>(descriptor);data.put("source",source);
        after.put(ATTRIBUTE,data);
        String oldName=String.valueOf(call(code,"getJavaClassName")),oldIdentifier=String.valueOf(call(node,"getIdentifier"));
        if(fresh||!oldName.equals(name)) {
            Object root=call(parent,"getDOMRootNode");Map<String,String> labels=after.computeIfAbsent("label",ignored->new LinkedHashMap<>());
            for(Object language:(List<?>)call(root,"getAvailableLanguages"))labels.put(String.valueOf(call(language,"getID")),name);
            Object selected=call(root,"getSelectedLanguage");if(selected!=null)labels.put(String.valueOf(call(selected,"getID")),name);
        }
        try {
            // Detached preparation avoids publishing a half-created node or compiling stale source.
            call(node,"setAttributeMap",null,after);
            call(code,"setJavaClassName",name,null,false);
            if(fresh)call(node,"setIdentifier",null,name);
            if(fresh)call(parent,"addChild",context,node);
            else call(code,"contentChanged",context);
            call(node,"nodeChanged",context);
            return node;
        }catch(Throwable failure) {
            try {
                if(fresh&&call(node,"getParent")==parent)call(parent,"removeChild",context,node);
                call(node,"setAttributeMap",null,before);call(code,"setJavaClassName",oldName,null,false);call(node,"setIdentifier",null,oldIdentifier);
                if(!fresh)call(code,"contentChanged",context);
            }catch(Throwable rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
}
