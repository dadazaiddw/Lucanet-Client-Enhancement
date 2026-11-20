import java.awt.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;

/** Actual native ClassElement, native XML export/import and source hook; no business session. */
public final class CustomJavaRealProbe {
    static Class<?> model;static ClassLoader module;static Object script,parent,context;
    public static void main(String[] args)throws Exception {
        try {
            Class.forName("com.lucanet.common.dom.script.java.JavaCode");EventQueue.invokeAndWait(()->{});
            Object reader=UIManager.get("cn.lucanet.enhancement.javaSource.v1.reader");check(reader!=null,"source hook registered");module=reader.getClass().getClassLoader();
            model=Class.forName("cn.lucanet.enhancement.exit.CustomJavaModel",true,module);
            script=create("com.lucanet.common.dom.script.elements.Script");parent=call(script,"getClassRoot");
            Object handler=staticCall(Class.forName("com.lucanet.common.dom.base.node.DOMImportExportHandler"),"createHandlerForDOMExecution");context=call(handler,"getDOMExecutionContext");
            String base=(String)staticCall(model,"template",parent.getClass().getClassLoader(),"Baseline","Class");
            check(base.startsWith("package com.lucanet.common.dom.script.rt;"),"native package retained");check(base.contains("import "),"native imports retained");
            String header=base.substring(0,base.indexOf("public class Baseline"));
            String[] declarations={"public interface Contract<T> { T value(); }","public abstract class Base<T> implements Contract<T> { public abstract T value(); }","@Deprecated public class Child extends Base<String> { @Override public String value(){return \"ok\";} }","public enum Choice { A, B }","public @interface Marker { String value() default \"中文\"; }","public record Pair<T>(T value) {}","public class CustomFailure extends Exception { public CustomFailure(String message){super(message);} }"};
            List<String> sources=new ArrayList<>();
            for(String declaration:declarations){
                String source=header+declaration+"\n";String name=declaration.replaceFirst("(?s).*?(?:class|interface|enum|record)\\s+([A-Za-z]+).*","$1");
                Map<String,String> descriptor=Map.of("name",name,"kind","TEST","qualified","com.lucanet.common.dom.script.rt."+name);
                Object node=staticCall(model,"save",null,parent,context,source,descriptor);check(source.equals(call(node,"getSourceCode")),"native getter uses complete "+name+" source");
                check(((List<?>)call(parent,"getChildren")).contains(node),"saved into native Class folder");
                Object xml=call(handler,"getXMLElement",node);check(String.valueOf(xml).contains("ClassElement")||String.valueOf(xml).contains("Element"),"native XML created");
                Object reloaded=roundtrip(handler,script);
                List<?> restored=(List<?>)call(call(reloaded,"getClassRoot"),"getChildren");Object loaded=restored.get(restored.size()-1);check(source.equals(call(loaded,"getSourceCode")),"native XML roundtrip preserves "+name);
                check(name.equals(call(loaded,"getJavaClassName")),"native type name retained");sources.add(source);
                Object helper=call(loaded,"createHelperClassPart");check(name.equals(call(helper,"getJavaClassName")),"native compilation unit named correctly");
                String revised=source+"// edit after reopen\n";Object again=staticCall(model,"save",node,parent,context,revised,descriptor);check(again==node&&revised.equals(call(node,"getSourceCode")),"editing preserves native node identity");
                try{staticCall(model,"save",null,parent,context,source,descriptor);throw new AssertionError("duplicate accepted");}catch(IllegalArgumentException expected){}
            }
            // Ordinary nodes still use all original parts, and disabling editing keeps persisted source readable.
            Object ordinary=create("com.lucanet.common.dom.script.elements.classes.ClassElement");String ordinarySource=(String)call(ordinary,"getSourceCode");check(ordinarySource.contains("public class "),"ordinary source generation unchanged");
            List<Object> units=new ArrayList<>();for(Object node:(List<?>)call(parent,"getChildren"))units.add(create("com.lucanet.lisa.util.compiler.CompilationUnit",call(node,"getJavaClassName"),call(node,"getSourceCode")));
            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics=new javax.tools.DiagnosticCollector<>();
            Object task=staticCall(Class.forName("com.lucanet.lisa.util.compiler.DynamicCompiler"),"createCompilationTask",units,diagnostics);
            boolean compiled=((javax.tools.JavaCompiler.CompilationTask)call(task,"getTask")).call();
            check(compiled,"native compiler accepts all types: "+diagnostics.getDiagnostics());
            Object manager=null;for(Field f:task.getClass().getDeclaredFields())if(javax.tools.JavaFileManager.class.isAssignableFrom(f.getType())){f.setAccessible(true);manager=f.get(task);}
            Object compilation=null;for(Method method:manager.getClass().getDeclaredMethods())if(method.getParameterCount()==0&&method.getReturnType().getName().equals("com.lucanet.lisa.util.compiler.CompilationResult")){method.setAccessible(true);compilation=MethodHandles.lookup().unreflect(method).invoke(manager);}
            check(compilation!=null,"native bytecode result available");Map<String,byte[]> bytecode=new LinkedHashMap<>();
            Iterator<?> entries=(Iterator<?>)call(compilation,"getEntryIterator");while(entries.hasNext()){Map.Entry<?,?> entry=(Map.Entry<?,?>)entries.next();bytecode.put(String.valueOf(entry.getKey()),(byte[])entry.getValue());}
            Object classes=call(script,"getClasses");call(classes,"updateCompilationResult",compilation);
            ClassLoader compiledLoader=(ClassLoader)call(classes,"getClassLoader");
            String pkg="com.lucanet.common.dom.script.rt.";Class<?> child=compiledLoader.loadClass(pkg+"Child");Object instance=child.getConstructor().newInstance();
            check("ok".equals(child.getMethod("value").invoke(instance)),"native compiled inheritance method executes");
            check(compiledLoader.loadClass(pkg+"Contract").isInstance(instance),"generic interface implemented");
            check(compiledLoader.loadClass(pkg+"Choice").isEnum()&&compiledLoader.loadClass(pkg+"Marker").isAnnotation(),"enum and annotation bytecode");
            Class<?> pair=compiledLoader.loadClass(pkg+"Pair");check("record".equals(pair.getMethod("value").invoke(pair.getConstructor(Object.class).newInstance("record"))),"record constructor and accessor execute");
            check("expected".equals(((Exception)compiledLoader.loadClass(pkg+"CustomFailure").getConstructor(String.class).newInstance("expected")).getMessage()),"custom exception executes");call(task,"close");
            Files.writeString(Path.of(System.getProperty("lucanet.enhancement.features")),"customJavaTypes=false\n");
            Object first=((List<?>)call(parent,"getChildren")).get(0);check(((String)call(first,"getSourceCode")).contains("public interface"),"disable preserves compilation semantics");
            Object imported=roundtrip(handler,script);check(((List<?>)call(call(imported,"getClassRoot"),"getChildren")).size()==declarations.length,"whole script native XML roundtrip");
            Path evidence=Path.of(args[0]).resolveSibling("custom-java-model-"+Path.of(args[0]).getFileName()+".txt");Files.writeString(evidence,"PASS native Class/XML roundtrip, seven templates, compiler bytecode and native ClassLoader execution, identity, duplicate rejection, disabled source access\n"+String.join("\n",sources));
            System.out.println("PASS: native custom types / XML / compile and execute through native ClassLoader / live switch");
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}finally{System.exit(0);}
    }
    static Object roundtrip(Object handler,Object script)throws Throwable {
        Object stream=create("com.lucanet.common.dom.script.xml.ScriptXMLStreamHandler");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        call(handler,"save",script,bytes,stream,call(script,"getLeastAvailableVersion"),"custom-java-test");
        Object importer=staticCall(handler.getClass(),"createHandlerForDOMExecution");
        return call(importer,"loadWithoutLicenseChecking",new java.io.ByteArrayInputStream(bytes.toByteArray()),stream,"custom-java-test");
    }
    static Object create(String name,Object...args)throws Throwable {
        Class<?> type=Class.forName(name);for(Constructor<?> ctor:type.getDeclaredConstructors())if(matches(ctor.getParameterTypes(),args)){ctor.setAccessible(true);return MethodHandles.lookup().unreflectConstructor(ctor).invokeWithArguments(args);}throw new NoSuchMethodException(name);
    }
    static Object call(Object target,String name,Object...args)throws Throwable {
        for(Method method:target.getClass().getMethods())if(method.getName().equals(name)&&matches(method.getParameterTypes(),args))return MethodHandles.lookup().unreflect(method).bindTo(target).invokeWithArguments(args);throw new NoSuchMethodException(target.getClass()+"."+name);
    }
    static Object staticCall(Class<?> type,String name,Object...args)throws Throwable {
        for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&matches(method.getParameterTypes(),args)){method.setAccessible(true);return MethodHandles.lookup().unreflect(method).invokeWithArguments(args);}throw new NoSuchMethodException(name);
    }
    static boolean matches(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i])&&!types[i].isPrimitive())return false;return true;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
