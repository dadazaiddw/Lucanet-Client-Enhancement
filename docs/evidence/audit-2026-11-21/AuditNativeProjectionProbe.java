import java.awt.EventQueue;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.UIManager;
import javax.tools.*;

/** Audit-only reproduction; creates isolated in-memory native scripts. */
public final class AuditNativeProjectionProbe {
    static final String PKG="com.lucanet.common.dom.script.rt";
    static final String KEY="cn.lucanet.enhancement.javaSource.v1";
    static Path root;
    static Class<?> model;
    static Object context;
    public static void main(String[] args) throws Exception {
        try {
            root=Path.of(args[0]); Files.createDirectories(root);
            Class.forName("com.lucanet.common.dom.script.java.JavaCode");
            EventQueue.invokeAndWait(()->{});
            Object reader=UIManager.get(KEY+".reader");
            model=Class.forName("cn.lucanet.enhancement.exit.CustomJavaModel",true,reader.getClass().getClassLoader());
            Object handler=CustomJavaRealProbe.staticCall(Class.forName("com.lucanet.common.dom.base.node.DOMImportExportHandler"),"createHandlerForDOMExecution");
            context=CustomJavaRealProbe.call(handler,"getDOMExecutionContext");
            check("WildcardDate","import java.util.*;\npublic class WildcardDate { public String value() { return Date.class.getName(); } }");
            check("AnnotatedCtor","public class AnnotatedCtor { @Deprecated public AnnotatedCtor() {} public String value() { return \"ok\"; } }");
            check("GenericCtor","public class GenericCtor { public <T> GenericCtor() {} public String value() { return \"ok\"; } }");
            check("FieldBlock","public class FieldBlock { private String text = \"\"\"\n        first\n          second\n        \"\"\"; public String value(){ return text; } }");
            check("InlineBlock","public class InlineBlock { public String value() { return \"\"\"\n        first\n          second\n        \"\"\"; } }");
            check("BlankFinal","public class BlankFinal { private final String s; public BlankFinal() { s=\"ok\"; } public String value(){return s;} }");
            check("ArrayInitializer","public class ArrayInitializer { int[] a={1,2}; public String value(){return java.util.Arrays.toString(a);} }");
            check("FieldOrder","public class FieldOrder { private int first=read(); private int second=42; private int read(){return second;} public String value(){return first+\":\"+second;} }");
            check("TrailingComment","public class TrailingComment { public String value(){ return \"ok\"; // keep\n } }");
            check("UnicodeEscape","public class UnicodeEscape { public String value(){ int x \\u003d 1; return \"\"+x; } }");
            check("StringShadow","public class StringShadow { public String value(){return \"ok\";} }");
            check("NoWildcardImports","public class NoWildcardImports { public String value(){ return java.util.Date.class.getName(); } }");
            check("TerminalEscape","public class TerminalEscape { public String value() { return \"\"\"\n        a\n          b\"\"\"; } }");
            commentRoundTrip();
            nestedFields();
            System.out.println("AUDIT_PROBE_COMPLETE");
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
        System.exit(0);
    }
    @SuppressWarnings("unchecked")
    static void commentRoundTrip() throws Throwable {
        String source="package "+PKG+";\n/** business type documentation */\npublic class CommentRoundTrip {\n // Keep this field explanation\n private int n;\n /** business method documentation */\n public String value() {\n // body comment survives\n return \"before\";\n }\n}";
        Object script=CustomJavaRealProbe.create("com.lucanet.common.dom.script.elements.Script");
        Object parent=CustomJavaRealProbe.call(script,"getClassRoot");
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        Map<String,String> descriptor=(Map<String,String>)CustomJavaRealProbe.staticCall(model,"parse",source,parts);
        Object node=CustomJavaRealProbe.staticCall(model,"save",null,parent,context,source,descriptor,parts);
        Object code=CustomJavaRealProbe.staticCall(model,"javaCode",node);
        CustomJavaRealProbe.call(CustomJavaRealProbe.call(code,"getMethodPart","value"),"setBody","// body comment survives\nreturn \"after\";",context);
        Object version=CustomJavaRealProbe.staticCall(model,"version",node);
        String editor=(String)invokePrivate(version,"source");Files.writeString(root.resolve("comment-roundtrip-before.java"),source);Files.writeString(root.resolve("comment-roundtrip-after.java"),editor);
        Object status=invokePrivate(version,"status");Field warning=status.getClass().getDeclaredField("warning");warning.setAccessible(true);
        System.out.println("CommentRoundTrip typeComment="+editor.contains("business type documentation")+" fieldComment="+editor.contains("Keep this field explanation")+" methodComment="+editor.contains("business method documentation")+" bodyComment="+editor.contains("body comment survives")+" warning="+warning.get(status));
        parts.clear();descriptor=(Map<String,String>)CustomJavaRealProbe.staticCall(model,"parse",editor,parts);
        CustomJavaRealProbe.staticCall(model,"save",node,parent,context,editor,descriptor,parts);
        String saved=(String)CustomJavaRealProbe.staticCall(model,"storedSource",node);
        System.out.println("CommentRoundTrip originalCommentsRetainedAfterResave="+saved.contains("business method documentation"));
    }
    @SuppressWarnings("unchecked")
    static void nestedFields() throws Throwable {
        Class<?> folderType=Class.forName("com.lucanet.common.dom.script.java.PartFolder");
        if(Arrays.stream(folderType.getDeclaredMethods()).noneMatch(m->m.getName().equals("createPartFolderForParent"))){System.out.println("NestedFields SKIP: release has no part folders");return;}
        String name="NestedFields", source="package "+PKG+"; public class "+name+" { private int a=1; private int b=this.a+1; public String value(){return \"\"+b;} }";
        Object script=CustomJavaRealProbe.create("com.lucanet.common.dom.script.elements.Script");
        Object parent=CustomJavaRealProbe.call(script,"getClassRoot");
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        Map<String,String> descriptor=(Map<String,String>)CustomJavaRealProbe.staticCall(model,"parse",source,parts);
        Object node=CustomJavaRealProbe.staticCall(model,"save",null,parent,context,source,descriptor,parts);
        Object code=CustomJavaRealProbe.staticCall(model,"javaCode",node);
        Class<?> adapter=Class.forName("cn.lucanet.enhancement.exit.JavaEditorContext",true,model.getClassLoader());
        Object fields=CustomJavaRealProbe.staticCall(adapter,"partFolder",code,"MEMBER","MemberPart");
        List<?> entries=(List<?>)CustomJavaRealProbe.call(fields,"getChildren");
        Object first=entries.get(0), second=entries.get(1);
        Object group=CustomJavaRealProbe.staticCall(folderType,"createPartFolderForParent",fields,"group");
        CustomJavaRealProbe.call(fields,"removeChild",context,first);
        CustomJavaRealProbe.call(group,"addChild",first,context);
        CustomJavaRealProbe.call(fields,"addChild",0,group,context);
        CustomJavaRealProbe.call(code,"contentChanged",context);
        String before=(String)CustomJavaRealProbe.call(node,"getSourceCode");
        Object version=CustomJavaRealProbe.staticCall(model,"version",node);
        String editor=(String)invokePrivate(version,"source");
        String resultBefore=compileAndRun(name,before,"grouped-native-before");
        String resultEditor=compileAndRun(name,editor,"editor-after-grouping");
        parts.clear();descriptor=(Map<String,String>)CustomJavaRealProbe.staticCall(model,"parse",editor,parts);
        CustomJavaRealProbe.staticCall(model,"save",node,parent,context,editor,descriptor,parts);
        String after=(String)CustomJavaRealProbe.call(node,"getSourceCode");
        String resultAfter=compileAndRun(name,after,"native-after-resave");
        System.out.println(name+" nativeBefore="+resultBefore+" editor="+resultEditor+" nativeAfterResave="+resultAfter+" same="+resultBefore.equals(resultAfter));
    }
    @SuppressWarnings("unchecked")
    static void check(String name,String declaration) throws Throwable {
        String source="package "+PKG+";\n"+declaration;
        Object script=CustomJavaRealProbe.create("com.lucanet.common.dom.script.elements.Script");
        Object parent=CustomJavaRealProbe.call(script,"getClassRoot");
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        Map<String,String> descriptor=(Map<String,String>)CustomJavaRealProbe.staticCall(model,"parse",source,parts);
        Object node=CustomJavaRealProbe.staticCall(model,"save",null,parent,context,source,descriptor,parts);
        String nativeSource=(String)CustomJavaRealProbe.call(node,"getSourceCode");
        String original=compileAndRun(name,source,"original"), generated=compileAndRun(name,nativeSource,"native");
        Object synced=CustomJavaRealProbe.staticCall(model,"hasNativeParts",node);
        Object version=CustomJavaRealProbe.staticCall(model,"version",node);
        Object status=invokePrivate(version,"status");
        Field warning=status.getClass().getDeclaredField("warning"); warning.setAccessible(true);
        System.out.println(name+" synced="+synced+" warning="+warning.get(status)+" original="+original+" native="+generated+" same="+original.equals(generated));
    }
    static Object invokePrivate(Object value,String name)throws Throwable {
        Method method=value.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(value);
    }
    static String compileAndRun(String name,String source,String stage)throws Exception {
        Path output=root.resolve(name).resolve(stage); Files.createDirectories(output);
        Path file=output.resolve(name+".java"); Files.writeString(file,source);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)) {
            boolean ok=compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",output.toString()),null,manager.getJavaFileObjects(file)).call();
            if(!ok){Files.writeString(output.resolve("diagnostics.txt"),diagnostics.getDiagnostics().toString()); return "COMPILE_ERROR:"+diagnostics.getDiagnostics().get(0).getMessage(Locale.ROOT).replace('\n',' ');}
        }
        try(URLClassLoader loader=new URLClassLoader(new URL[]{output.toUri().toURL()},AuditNativeProjectionProbe.class.getClassLoader())){
            Class<?> type=loader.loadClass(PKG+"."+name);
            try {return "VALUE:"+String.valueOf(type.getMethod("value").invoke(type.getConstructor().newInstance())).replace("\n","\\n");}
            catch(InvocationTargetException failure){return "THREW:"+failure.getCause();}
        }
    }
}
