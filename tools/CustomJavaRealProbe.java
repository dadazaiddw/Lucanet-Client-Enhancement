import java.awt.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Actual native ClassElement, native XML export/import and source hook; no business session. */
public final class CustomJavaRealProbe {
    private static final String SCRIPT="com.lucanet.common.dom.script.";
    private static final String JAVA=SCRIPT+"java.";
    private static final String DOM="com.lucanet.common.dom.base.";
    private static final String SRC="cn.lucanet.enhancement.javaSource.v1";
    private static final String RT=SCRIPT+"rt";
    private static final String PKG="package "+RT+";\n";
    private static final String COMPILER="com.lucanet.lisa.util.compiler.DynamicCompiler";
    private static final String RESULT="fixture.custom-java.result";
    private static final String LIBRARY_TEST="cn.lucanet.enhancement.examples.Test";
    static Class<?> model;static ClassLoader module;static Object script,parent,context;
    public static void main(String[] args)throws Exception {
        try {
            if(Boolean.getBoolean("fixture.server")) {
                Path path=Path.of(args[0]);
                check(UIManager.get(SRC+".reader")==null,"runner has no enhancement agent");
                initializeCompiler(path.resolveSibling(path.getFileName()+"-runner"));
                runSavedTask(path,"Test","server-x\t");
                runSavedTask(revisedPath(path),"RevisedTest","revised");
                verifyComparatorTasks(path,Path.of(args[1]));
                editNativeTasks(path);
                return;
            }
            Class.forName("com.lucanet.common.dom.script.java.JavaCode");EventQueue.invokeAndWait(()->{});
            Object reader=UIManager.get("cn.lucanet.enhancement.javaSource.v1.reader");check(reader!=null,"source hook registered");module=reader.getClass().getClassLoader();
            model=Class.forName("cn.lucanet.enhancement.exit.CustomJavaModel",true,module);
            if(Boolean.getBoolean("fixture.checkNativeEdits")) {
                Path path=Path.of(args[0]);
                initializeCompiler(path.resolveSibling(path.getFileName()+"-reloaded"));
                verifyNativeEdits(path);
                return;
            }
            script=create("com.lucanet.common.dom.script.elements.Script");parent=call(script,"getClassRoot");
            Object handler=staticCall(Class.forName("com.lucanet.common.dom.base.node.DOMImportExportHandler"),"createHandlerForDOMExecution");context=call(handler,"getDOMExecutionContext");
            String base=(String)staticCall(model,"template",parent.getClass().getClassLoader(),"Baseline","Class");
            check(base.startsWith("package com.lucanet.common.dom.script.rt;"),"native package retained");check(base.contains("import "),"native imports retained");
            check(!base.contains("End of Class"),"enhanced template hides native section markers");
            String header=base.substring(0,base.indexOf("public class Baseline"));
            String[] declarations={"public interface Contract<T> { T value(); }","public abstract class Base<T> implements Contract<T> { public abstract T value(); }","@Deprecated public class Child extends Base<String> { @Override public String value(){return \"ok\";} }","public enum Choice { A, B }","public @interface Marker { String value() default \"中文\"; }","public record Pair<T>(T value) {}","public class CustomFailure extends Exception { public CustomFailure(String message){super(message);} }"};
            List<String> sources=new ArrayList<>();
            for(String declaration:declarations){
                String source=header+declaration+"\n";String name=declaration.replaceFirst("(?s).*?(?:class|interface|enum|record)\\s+([A-Za-z]+).*","$1");
                Object node=save(null,parent,source);check(source.equals(call(node,"getSourceCode")),"native getter uses complete "+name+" source");
                check(((List<?>)call(parent,"getChildren")).contains(node),"saved into native Class folder");
                Object xml=call(handler,"getXMLElement",node);check(String.valueOf(xml).contains("ClassElement")||String.valueOf(xml).contains("Element"),"native XML created");
                Object reloaded=roundtrip(handler,script);
                List<?> restored=(List<?>)call(call(reloaded,"getClassRoot"),"getChildren");Object loaded=restored.get(restored.size()-1);check(source.equals(call(loaded,"getSourceCode")),"native XML roundtrip preserves "+name);
                check(name.equals(call(loaded,"getJavaClassName")),"native type name retained");sources.add(source);
                Object helper=call(loaded,"createHelperClassPart");check(name.equals(call(helper,"getJavaClassName")),"native compilation unit named correctly");
                String revised=source+"// edit after reopen\n";Object again=save(node,parent,revised);check(again==node&&revised.equals(call(node,"getSourceCode")),"editing preserves native node identity");
                try{save(null,parent,source);throw new AssertionError("duplicate accepted");}catch(IllegalArgumentException expected){}
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
            verifyNativeParts(handler);
            if(args.length>1) {
                Path path=Path.of(args[1]);
                writeTask(path);
                writeComparatorTasks(path,Path.of(args[2]));
                writeSyncTasks(path);
            }
            Files.writeString(Path.of(System.getProperty("lucanet.enhancement.features")),"customJavaTypes=false\n");
            Object first=((List<?>)call(parent,"getChildren")).get(0);check(((String)call(first,"getSourceCode")).contains("public interface"),"disable preserves compilation semantics");
            Object imported=roundtrip(handler,script);check(((List<?>)call(call(imported,"getClassRoot"),"getChildren")).size()==declarations.length,"whole script native XML roundtrip");
            Path evidence=Path.of(args[0]).resolveSibling("custom-java-model-"+Path.of(args[0]).getFileName()+".txt");Files.writeString(evidence,"PASS native Class/XML roundtrip, seven templates, compiler bytecode and native ClassLoader execution, identity, duplicate rejection, disabled source access\n"+String.join("\n",sources));
            System.out.println("PASS: native custom types / XML / compile and execute through native ClassLoader / live switch");
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}finally{System.exit(0);}
    }
    @SuppressWarnings("unchecked")
    private static Object save(Object node,Object folder,String source)throws Throwable {
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        Map<String,String> descriptor=(Map<String,String>)staticCall(model,"parse",source,parts);
        return staticCall(model,"save",node,folder,context,source,descriptor,parts);
    }

    private static void verifyNativeParts(Object handler)throws Throwable {
        Object fixture=create(SCRIPT+"elements.Script");
        Object folder=call(fixture,"getClassRoot");
        Object node=save(null,folder,PKG+"public class SyncTest {}");
        check(Boolean.TRUE.equals(staticCall(model,"hasNativeParts",node)),"empty plain class is synced");
        check(((String)call(node,"getSourceCode")).contains("  End of Class  "),"the empty class already has native source");
        check(save(node,folder,SYNC_SOURCE)==node,"plain class edit preserves node identity");
        String generated=verifySyncRendering(classCode(node));
        check(save(node,folder,SYNC_FORMATTED_SOURCE)==node,"repeated synchronization preserves node identity");
        check(generated.equals(call(node,"getSourceCode")),"saving again does not add a second stack guard");
        Object loaded=((List<?>)call(call(roundtrip(handler,fixture),"getClassRoot"),"getChildren")).get(0);
        check(Boolean.TRUE.equals(staticCall(model,"hasNativeParts",loaded)),"sync status survives XML roundtrip");
        String reloadedSource=verifySyncRendering(classCode(loaded));
        String declaration="public class SyncTest {";
        check(reloadedSource.contains("import java.io.File;")
                        &&reloadedSource.substring(reloadedSource.indexOf(declaration)).equals(generated.substring(generated.indexOf(declaration))),
                "the class body and custom import survive XML roundtrip even when Lucanet reorders imports");
        Object version=staticCall(model,"version",loaded);
        check(SYNC_FORMATTED_SOURCE.equals(callDeclared(version,"source")),
                "the enhanced editor retains its comments and handwritten try/finally without generated sections");
        Object code=staticCall(model,"javaCode",loaded);
        check(SYNC_CONSTRUCTOR.equals(call(call(code,"getMethodPart","SyncTest"),"getBody")),
                "the constructor survives native XML without the guard or outer indent in its body");
        Object loadedParent=call(loaded,"getParent");
        String unsupported=PKG+"public class SyncTest extends Exception {}";
        save(loaded,loadedParent,unsupported);
        check(!Boolean.TRUE.equals(staticCall(model,"hasNativeParts",loaded)),"unsupported source stays client-only");
        check(unsupported.equals(call(loaded,"getSourceCode"))&&call(code,"getMethodPart","value")==null,"outdated native method removed when representation is lost");
    }

    private static void writeTask(Path path)throws Throwable {
        initializeCompiler(path.resolveSibling(path.getFileName()+"-writer"));
        Object handler=executionHandler();
        context=call(handler,"getDOMExecutionContext");
        Object script=create(SCRIPT+"elements.Script");
        Object code=addMain(script);
        Object execute=call(code,"getMethodPart","execute");
        call(execute,"setBody","try { System.setProperty(\""+RESULT+"\", new Test(\"server\").value(\"x\")); }"
                +" catch (java.io.IOException failure) { throw new RuntimeException(failure); }",context);
        Object classes=call(script,"getClassRoot");
        String source=PKG+"import java.io.IOException;\nimport static java.util.Objects.requireNonNull;\n"
                +"public class Test {\n"
                +"    private static final String EMPTY = \"\";\n"
                +"    private final char tab = '\\t';\n"
                +"    private final String prefix;\n"
                +"    public Test(String prefix) { this.prefix = requireNonNull(prefix); }\n"
                +"    public String value(String... values) throws IOException {\n"
                +"        return this + \"-\" + String.join(\",\", values) + EMPTY + tab;\n"
                +"    }\n"
                +"    public String value() { return \"unused overload\"; }\n"
                +"    private static <T> T identity(T value) { return value; }\n"
                +"    @Override public String toString() { return identity(prefix); }\n"
                +"}\n";
        Object old=create(SCRIPT+"elements.classes.ClassElement");
        setLabel(old,script,"Test");
        call(old,"setIdentifier",null,"Test");
        call(staticCall(model,"javaCode",old),"setJavaClassName","Test",null,false);
        @SuppressWarnings("unchecked") Map<String,Map<String,String>> attributes=(Map<String,Map<String,String>>)staticCall(model,"attributes",old);
        attributes.put(SRC,Map.of("source",source,"name","Test","kind","CLASS"));
        call(old,"setAttributeMap",null,attributes);
        call(classes,"addChild",context,old);
        check(Boolean.TRUE.equals(callDeclared(staticCall(model,"version",old),"needsResave")),"legacy source requires synchronization");
        check(save(old,classes,source)==old,"legacy class migrates in place");
        check(!Boolean.TRUE.equals(callDeclared(staticCall(model,"version",old),"needsResave")),"migration is recorded");
        exportTask(handler,script,path);

        String revised=PKG+"public class RevisedTest { public String updated(){ return \"revised\"; } }";
        check(save(old,classes,revised)==old,"rename keeps the existing node");
        call(execute,"setBody","System.setProperty(\""+RESULT+"\", new RevisedTest().updated());",context);
        exportTask(handler,script,revisedPath(path));
    }

    private static Object addMain(Object script)throws Throwable {
        Object main=create(SCRIPT+"elements.tasks.JavaCodeTaskElement");
        setLabel(main,script,"Main");
        call(main,"setIdentifier",null,"Main");
        call(call(script,"getTaskRoot"),"addChild",context,main);
        return staticCall(model,"javaCode",main);
    }

    private static final String SYNC_SOURCE=PKG+"import java.io.File;\n"
            +"// Keep the original source until a native edit is saved.\n"
            +"public class SyncTest {\n"
            +"    private String text = \"before\";\n"
            +"    private char suffix = '!';\n"
            +"    private String s;\n"
            +"    private int i;\n"
            +"    public SyncTest(String s, int i) {\n\n"
            +"        this.s = s+\" world\";\n"
            +"        this.i = i+1;\n\n"
            +"    }\n"
            +"    public String constructorValue() { return s + \":\" + i; }\n"
            +"    public String value() {\n\n"
            +"        try {\n"
            +"            return text + suffix;\n"
            +"        } finally {\n"
            +"            Objects.requireNonNull(text);\n"
            +"        }\n\n"
            +"    }\n"
            +"    public int obsolete() { return 7; }\n"
            +"    public String textBlock() {\n"
            +"        return \"\"\"\n"
            +"                one\n"
            +"                  two\\s\n"
            +"                \"\"\";\n"
            +"    }\n"
            +"}\n";
    private static final String SYNC_FORMATTED_SOURCE=SYNC_SOURCE.replace("s+\" world\"","s + \" world\"").replace("i+1","i + 1");
    private static final String SYNC_CONSTRUCTOR="this.s = s + \" world\";\nthis.i = i + 1;";
    private static final String SYNC_LEGACY_CONSTRUCTOR="this.s = s+\" world\";\nthis.i = i+1;";
    private static final String SYNC_NATIVE_EDIT="this.s = s + \" native\";\nthis.i = i + 2;";
    private static final String SYNC_NATIVE_BODY="try {\n    return text + suffix;\n} finally {\n    Objects.requireNonNull(text);\n}";
    private static final String SYNC_ADDED_BODY="StringJoiner result = new StringJoiner(\"\");\n"
            +"if (addedValue > 0) {\n    result.add(\"-\" + addedValue);\n}\n\nreturn result.toString();";
    private static final String TEXT_BLOCK_VALUE="one\n  two \n";

    private static String verifySyncRendering(Object code)throws Throwable {
        String rendered=(String)call(code,"getSourceCode");
        check(rendered.contains("  Variables  ")&&rendered.contains("  Methods  ")&&rendered.contains("  End of Class  "),
                "the source tab shows Lucanet's generated class sections");
        List<String> methods=List.of("SyncTest","constructorValue","value","obsolete","textBlock");
        for(String method:methods) {
            String guard="CallStack.incAndCheckStackLevel(\"SyncTest."+method+"\");";
            check(rendered.indexOf(guard)>=0&&rendered.indexOf(guard)==rendered.lastIndexOf(guard),
                    method+" has exactly one native stack guard");
        }
        check(rendered.lines().filter(line->line.strip().equals("CallStack.decStackLevel();")).count()==methods.size()
                        &&rendered.contains("Objects.requireNonNull(text);"),
                "generated cleanup and the handwritten finally body both survive");
        return rendered;
    }

    @SuppressWarnings("unchecked")
    private static void writeSyncTasks(Path path)throws Throwable {
        Object script=create(SCRIPT+"elements.Script");
        Object execute=call(addMain(script),"getMethodPart","execute");
        call(execute,"setBody","SyncTest item = new SyncTest(\"1\", 2); System.setProperty(\""+RESULT
                +"\", item.constructorValue() + \"|\" + item.value() + item.textBlock());",context);
        Object node=save(null,call(script,"getClassRoot"),SYNC_SOURCE);
        check(Boolean.TRUE.equals(staticCall(model,"hasNativeParts",node)),"native edits start from a synced Class");
        Object handler=executionHandler();
        exportTask(handler,script,comparatorPath(path,"sync-original"));
        Map<String,Map<String,String>> attributes=(Map<String,Map<String,String>>)staticCall(model,"attributes",node);
        attributes.get(SRC).remove("partsRevision");
        attributes.get(SRC).put("source",SYNC_SOURCE);
        Object code=classCode(node);
        call(call(code,"getMethodPart","SyncTest"),"setBody",SYNC_LEGACY_CONSTRUCTOR,context);
        call(node,"setAttributeMap",null,attributes);
        exportTask(handler,script,comparatorPath(path,"sync-legacy"));
    }

    private static void editNativeTasks(Path path)throws Throwable {
        Object handler=executionHandler();
        Object editContext=call(handler,"getDOMExecutionContext");
        Object editable=Class.forName(JAVA+"Part$Editability").getField("EDITABLE").get(null);
        Class<?> visibility=Class.forName(JAVA+"Part$Visibility");
        for(String variant:List.of("sync-original","sync-legacy")) {
            Object script=loadTask(comparatorPath(path,variant));
            Object code=classCode(firstClass(script));
            String rendered=verifySyncRendering(code);
            check(SYNC_NATIVE_BODY.equals(call(call(code,"getMethodPart","value"),"getBody")),
                    "native method details receive a deindented body without boundary blank lines");
            Object constructor=call(code,"getMethodPart","SyncTest");
            boolean legacy=variant.equals("sync-legacy");
            check((legacy?SYNC_LEGACY_CONSTRUCTOR:SYNC_CONSTRUCTOR).equals(call(constructor,"getBody")),
                    "constructor body starts on line one with the expected operator spaces");
            if(!legacy) {
                check(rendered.contains("\n      this.s = s + \" world\";\n      this.i = i + 1;\n    }"),
                        "native source uses its standard method indent with no boundary blank lines");
                Files.writeString(path.resolveSibling("native-SyncTest.java"),rendered);
            }
            runTask(script,compileTask(script,true),variant+" before native edit","1 world:3|before!"+TEXT_BLOCK_VALUE);
            call(constructor,"setBody","\n        this.s=s+\" native\";\n        this.i=i+2;\n\n",editContext);
            call(call(code,"getMethodPart","value"),"setBody","return text+added()+suffix;",editContext);
            Object methods=call(code,"getMethodPartFolder");
            call(methods,"removeChild",editContext,call(code,"getMethodPart","obsolete"));
            call(code,"addMemberPart",create(JAVA+"LocalMemberPart",editable,visibility.getField("PRIVATE").get(null),
                    "int","addedValue","9"),editContext);
            Object members=nativeFolder(code,"MEMBER");
            call(findPart(members,"text"),"setValue","\"native\"",editContext);
            call(code,"addMethodPart",create(JAVA+"LocalMethodPart",editable,visibility.getField("PRIVATE").get(null),
                    "String","added",null,null,SYNC_ADDED_BODY),editContext);
            Object imports=nativeFolder(code,"IMPORT");
            call(imports,"removeChild",editContext,findPart(imports,"java.io.File"));
            call(imports,"addChild",create(JAVA+"ImportPart",editable,"java.util.StringJoiner"),editContext);
            exportTask(handler,script,comparatorPath(path,variant+"-edited"));
            Object reloaded=loadTask(comparatorPath(path,variant+"-edited"));
            runTask(reloaded,compileTask(reloaded,true),variant+" saved without the plugin","1 native:4|native-9!"+TEXT_BLOCK_VALUE);
        }
        Object script=loadTask(comparatorPath(path,"class"));
        Object code=classCode(firstClass(script));
        call(code,"addMethodPart",create(JAVA+"LocalMethodPart",editable,visibility.getField("PUBLIC").get(null),
                "int","nativeOnly",null,null,"return 99;"),editContext);
        exportTask(handler,script,comparatorPath(path,"class-native-edit"));
    }

    private static void verifyNativeEdits(Path path)throws Throwable {
        Object handler=executionHandler();
        context=call(handler,"getDOMExecutionContext");
        for(String variant:List.of("sync-original","sync-legacy")) {
            Object unchanged=loadTask(comparatorPath(path,variant));
            String expected=variant.equals("sync-legacy")?SYNC_SOURCE:SYNC_FORMATTED_SOURCE;
            Object originalNode=firstClass(unchanged);
            verifySyncRendering(classCode(originalNode));
            check(expected.equals(staticCall(model,"storedSource",originalNode)),variant+" retains the complete source");
            Object initial=staticCall(model,"version",originalNode);
            check(SYNC_FORMATTED_SOURCE.equals(callDeclared(initial,"source")),variant+" displays normalized operators without discarding comments");
            runTask(unchanged,compileTask(unchanged,true),variant+" compiled on the enhanced client","1 world:3|before!"+TEXT_BLOCK_VALUE);
            Object script=loadTask(comparatorPath(path,variant+"-edited"));
            Object node=firstClass(script);
            Object version=staticCall(model,"version",node);
            String source=(String)callDeclared(version,"source");
            String outline=(String)callDeclared(version,"outline");
            check(source.contains("return text + added() + suffix;")&&source.contains("int addedValue = 9;")
                    &&source.contains("import java.util.StringJoiner;")&&!source.contains("java.io.File")&&!source.contains("obsolete("),
                    "reload exposes native imports, field and method edits in the complete source");
            check(!source.contains("CallStack")&&!source.contains("  End of Class  ")
                            &&source.contains("        this.s = s + \" native\";\n        this.i = i + 2;\n    }")
                            &&source.contains("        if (addedValue > 0) {\n            result.add(\"-\" + addedValue);\n        }\n\n        return result.toString();"),
                    "reloaded editor hides native scaffolding, restores operator spacing and keeps relative indentation:\n"+source);
            check(outline.contains("added()")&&!outline.contains("obsolete()"),"reload rebuilds the class outline");
            runTask(script,compileTask(script,true),variant+" reloaded with the plugin","1 native:4|native-9!"+TEXT_BLOCK_VALUE);
            Object folder=call(script,"getClassRoot");
            save(node,folder,source.replace("addedValue = 9","addedValue = 12"));
            Object again=roundtrip(handler,script);
            Object updated=classCode(firstClass(again));
            check(SYNC_ADDED_BODY.equals(call(call(updated,"getMethodPart","added"),"getBody")),
                    "round-trip formatting is idempotent for a native method");
            check(SYNC_NATIVE_EDIT.equals(call(call(updated,"getMethodPart","SyncTest"),"getBody")),
                    "saving a native-edited class writes back normalized constructor steps");
            runTask(again,compileTask(again,true),variant+" edited again in the plugin","1 native:4|native-12!"+TEXT_BLOCK_VALUE);
        }
        String generated=Files.readString(path.resolveSibling("native-SyncTest.java"));
        Object restored=loadTask(comparatorPath(path,"sync-original"));
        Object restoredNode=firstClass(restored);
        save(restoredNode,call(restored,"getClassRoot"),generated);
        Object restoredVersion=staticCall(model,"version",restoredNode);
        String cleaned=(String)callDeclared(restoredVersion,"source");
        check(!cleaned.contains("CallStack")&&!cleaned.contains("  Methods  ")&&cleaned.contains("Objects.requireNonNull(text);"),
                "pasting the actual generated source removes only Lucanet's outer wrapper and sections");
        check(SYNC_CONSTRUCTOR.equals(call(call(classCode(restoredNode),"getMethodPart","SyncTest"),"getBody")),
                "the copied native source stores the constructor body without a nested guard");
        verifySyncRendering(classCode(restoredNode));
        runTask(restored,compileTask(restored,true),"generated source restored by the plugin","1 world:3|before!"+TEXT_BLOCK_VALUE);
        Object incompatible=loadTask(comparatorPath(path,"class-native-edit"));
        Object node=firstClass(incompatible);
        String source=(String)call(node,"getSourceCode");
        check(source.contains("implements Comparator")&&!source.contains("nativeOnly"),"native placeholder edits preserve the independent complete definition");
        Object status=callDeclared(staticCall(model,"version",node),"status");
        Field message=status.getClass().getDeclaredField("message");
        message.setAccessible(true);
        check(String.valueOf(message.get(status)).contains("原生端修改需手动合并"),"enhanced type reports the external native edit");
        runTask(incompatible,compileTask(incompatible,true),"Comparator after native edit to its placeholder","1:2");
    }

    private static Object firstClass(Object script)throws Throwable {
        return ((List<?>)call(call(script,"getClassRoot"),"getChildren")).get(0);
    }

    private static Object classCode(Object node)throws Throwable {
        for(Field field:node.getClass().getDeclaredFields()) {
            if(!field.getType().getName().equals(JAVA+"JavaCode"))continue;
            field.setAccessible(true);
            return field.get(node);
        }
        throw new NoSuchFieldException("JavaCode");
    }

    private static Object nativeFolder(Object code,String kind)throws Throwable {
        for(Field field:code.getClass().getDeclaredFields()) {
            if(!field.getType().getName().equals(JAVA+"PartFolder"))continue;
            field.setAccessible(true);
            Object folder=field.get(code);
            if(kind.equals(String.valueOf(call(folder,"getSupportedType"))))return folder;
        }
        throw new NoSuchFieldException(kind);
    }

    private static Object findPart(Object folder,String name)throws Throwable {
        for(Object part:(List<?>)call(folder,"getChildren"))if(name.equals(call(part,"getName")))return part;
        throw new IllegalStateException(name);
    }

    private static Object callDeclared(Object target,String name)throws Throwable {
        Method method=target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method).invoke(target);
    }

    private static void writeComparatorTasks(Path path,Path librarySource)throws Throwable {
        Object handler=executionHandler();
        Object script=create(SCRIPT+"elements.Script");
        Object code=addMain(script);
        Object execute=call(code,"getMethodPart","execute");
        String readTest="System.setProperty(\""+RESULT+"\", test.getS()+\":\"+test.getI());";
        call(execute,"setBody","Test test = new Test(\"1\", 2); "+readTest,context);
        String source=Files.readString(librarySource).replace("\r\n","\n").replace("package cn.lucanet.enhancement.examples;\n",PKG);
        check(source.startsWith(PKG),"example source uses the script package");
        Object folder=call(script,"getClassRoot");
        Object node=save(null,folder,source);
        check(source.equals(call(node,"getSourceCode")),"enhanced client sees the full Comparator declaration");
        check(!Boolean.TRUE.equals(staticCall(model,"hasNativeParts",node)),"interface declaration cannot be represented by native parts");
        exportTask(handler,script,comparatorPath(path,"class"));
        runTask(script,compileTask(script,true),"Test implements Comparator (enhanced)","1:2");

        String plain=source.replace(" implements Comparator","").replace("import java.util.Comparator;\n","")
                .replace("    @Override\n    public int compare","    public int compare");
        check(save(node,folder,plain)==node&&Boolean.TRUE.equals(staticCall(model,"hasNativeParts",node)),
                "editing the existing client-only Class synchronizes its native parts");
        exportTask(handler,script,comparatorPath(path,"plain"));
        Files.writeString(path.resolveSibling("Test.java"),plain);

        Object libraryScript=create(SCRIPT+"elements.Script");
        Object libraryMain=addMain(libraryScript);
        Class<?> editorContext=Class.forName("cn.lucanet.enhancement.exit.JavaEditorContext",true,module);
        Object imports=staticCall(editorContext,"partFolder",libraryMain,"IMPORT","ImportPart");
        Object editable=Class.forName(SCRIPT+"java.Part$Editability").getField("EDITABLE").get(null);
        call(imports,"addChild",create(SCRIPT+"java.ImportPart",editable,LIBRARY_TEST),context);
        Object libraryExecute=call(libraryMain,"getMethodPart","execute");
        call(libraryExecute,"setBody","Test test = new Test(\"1\", 2); java.util.Comparator comparator = test; "
                +"try { comparator.compare(test, test); throw new AssertionError(\"compare should throw\"); } "
                +"catch (UnsupportedOperationException expected) { "
                +"if (!\"TODO\".equals(expected.getMessage())) throw expected; "+readTest+" }",context);
        exportTask(handler,libraryScript,comparatorPath(path,"library"));
    }

    private static Path comparatorPath(Path path,String variant) {
        return path.resolveSibling("comparator-"+variant+"-"+path.getFileName());
    }

    private static void verifyComparatorTasks(Path path,Path jar)throws Throwable {
        Object unsupported=loadTask(comparatorPath(path,"class"));
        Object empty=((List<?>)call(call(unsupported,"getClassRoot"),"getChildren")).get(0);
        String source=(String)call(empty,"getSourceCode");
        check(source.contains("public class Test {")&&!source.contains("getI(")&&!source.contains("implements Comparator"),
                "native renderer reproduces the reported empty class");
        compileTask(unsupported,false);
        System.out.println("PASS: native XML reload reproduces the missing Test(String, int) error");

        Object plain=loadTask(comparatorPath(path,"plain"));
        runTask(plain,compileTask(plain,true),"plain Test","1:2");
        ClassLoader loader=(ClassLoader)call(call(plain,"getClasses"),"getClassLoader");
        check(!Comparator.class.isAssignableFrom(loader.loadClass(RT+".Test")),
                "the plain Class does not implement Comparator");

        Path libraryScript=comparatorPath(path,"library");
        compileTask(loadTask(libraryScript),false);
        Object withLibrary=loadTask(libraryScript);
        Object libraries=JavaLibraryProbe.attachForExecution(withLibrary,jar);
        call(call(withLibrary,"getClasses"),"resetClassLoader");
        runTask(withLibrary,compileTask(withLibrary,true),"modular Comparator library","1:2",libraries);
        ClassLoader libraryLoader=(ClassLoader)call(call(withLibrary,"getClasses"),"getClassLoader");
        Class<?> external=libraryLoader.loadClass(LIBRARY_TEST);
        check(Comparator.class.isAssignableFrom(external)
                &&Files.isSameFile(jar,Path.of(external.getProtectionDomain().getCodeSource().getLocation().toURI())),
                "runtime resolves the Comparator type from the additional library JAR");
    }

    private static Path revisedPath(Path path) {
        return path.resolveSibling("revised-"+path.getFileName());
    }

    private static void exportTask(Object handler,Object script,Path path)throws Throwable {
        Object stream=create(SCRIPT+"xml.ScriptXMLStreamHandler");
        call(handler,"save",script,path.toFile(),stream,call(script,"getLeastAvailableVersion"));
    }

    private static Object loadTask(Path path)throws Throwable {
        Object stream=create(SCRIPT+"xml.ScriptXMLStreamHandler");
        return call(executionHandler(),"loadWithoutLicenseChecking",path.toFile(),stream);
    }

    private static void runSavedTask(Path path,String name,String result)throws Throwable {
        Object script=loadTask(path);
        Object node=((List<?>)call(call(script,"getClassRoot"),"getChildren")).get(0);
        String source=(String)call(node,"getSourceCode");
        check(name.equals(call(node,"getJavaClassName")),"native source has the saved class name");
        if(name.equals("RevisedTest"))check(!source.contains("IOException")&&!source.contains(" value(")&&!source.contains("EMPTY")&&!source.contains("@Override"),"deleted parts are absent after rename");
        runTask(script,compileTask(script,true),name,result);
    }

    private static Object compileTask(Object script,boolean expected)throws Throwable {
        Object classes=call(script,"getClasses");
        call(classes,"javaContentChanged");
        call(classes,"compileClasses");
        Object logger=create("com.lucanet.lisa.widgets.processlogger.StringBufferProcessLogger");
        boolean valid=Boolean.TRUE.equals(call(script,"validateAllJavaElements",logger));
        check(valid==expected,"unexpected native task compilation result: "+logger);
        return logger;
    }

    private static void runTask(Object script,Object logger,String name,String result)throws Throwable {
        runTask(script,logger,name,result,null);
    }

    private static void runTask(Object script,Object logger,String name,String result,Object libraries)throws Throwable {
        Object main=((List<?>)call(call(script,"getTaskRoot"),"getChildren")).get(0);
        Object provider=Proxy.newProxyInstance(CustomJavaRealProbe.class.getClassLoader(),
                new Class<?>[]{Class.forName(SCRIPT+"util.DOMVariableDataProvider")},(proxy,method,args)->{
                    if(method.getReturnType()==boolean.class)return false;
                    if(Map.class.isAssignableFrom(method.getReturnType()))return new HashMap<>();
                    return null;
                });
        Class<?> engineType=Class.forName(SCRIPT+"elements.ScriptEngine");
        Object builder=staticCall(engineType,"configure");
        call(builder,"withExecutionProperties",Map.of());
        call(builder,"reportToHealthSystem",false);
        if(libraries!=null) {
            Object fallback=call(executionHandler(),"getDOMExecutionContext");
            Class<?> type=Class.forName(DOM+"DOMExecutionContext");
            Object execution=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
                if(method.getName().equals("getDeviatingAdditionalLibrariesProvider"))return libraries;
                return MethodHandles.lookup().unreflect(method).bindTo(fallback)
                        .invokeWithArguments(args==null?new Object[0]:args);
            });
            call(builder,"withExecutionContext",execution);
        }
        Object engine=call(builder,"build");
        System.clearProperty(RESULT);
        call(engine,"executeScript",logger,script,main,provider);
        check(result.equals(System.getProperty(RESULT)),"Main did not execute the saved version of "+name+": "+logger);
        System.out.println("PASS: Main compiles and executes with "+name);
    }

    private static Object executionHandler()throws Throwable {
        return staticCall(Class.forName(DOM+"node.DOMImportExportHandler"),"createHandlerForDOMExecution");
    }

    private static void setLabel(Object node,Object script,String name)throws Throwable {
        Map<String,String> labels=new LinkedHashMap<>();
        for(Object language:(List<?>)call(script,"getAvailableLanguages")) {
            labels.put(String.valueOf(call(language,"getID")),name);
        }
        call(node,"setAttributeMap",null,Map.of("label",labels));
    }

    private static void initializeCompiler(Path directory)throws Throwable {
        Class<?> application=Class.forName("ln.application.Application");
        staticCall(application,"setApplicationPaths",create("ScriptProbeApplicationPaths",Files.createDirectories(directory).toFile()));
        Class<?> builder=Class.forName("ln.application.spi.ApplicationComponentBuilder");
        Object builders=Array.newInstance(builder,1);
        Array.set(builders,0,staticCall(Class.forName(COMPILER),"createApplicationComponentBuilder"));
        application.getMethod("runWith",builders.getClass()).invoke(null,builders);
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
