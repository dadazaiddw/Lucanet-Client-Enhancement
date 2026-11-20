package cn.lucanet.enhancement.exit;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** A discoverable processor must not execute merely because the user edits code. */
public final class AnnotationIsolationTest {
    private static final String MARKER="fixture.annotation.processor";
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        Path processor=root.resolve("Tracker.java"),annotation=root.resolve("FixtureTag.java");
        Files.writeString(processor,"package fixture; public class Tracker extends javax.annotation.processing.AbstractProcessor {"
            +"public Tracker(){System.setProperty(\""+MARKER+"\",\"constructed\");}"
            +"public java.util.Set<String> getSupportedAnnotationTypes(){return java.util.Set.of(\"*\");}"
            +"public javax.lang.model.SourceVersion getSupportedSourceVersion(){return javax.lang.model.SourceVersion.latestSupported();}"
            +"public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> a,javax.annotation.processing.RoundEnvironment r){System.setProperty(\""+MARKER+"\",\"processed\");return false;}} ");
        Files.writeString(annotation,"package fixture; public @interface FixtureTag {} ");
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","--release","11","-d",root.toString(),processor.toString(),annotation.toString())==0,"fixture compilation");
        Path library=root.resolve("processor-fixture.jar");
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(library))){
            for(String name:List.of("fixture/Tracker.class","fixture/FixtureTag.class")){
                jar.putNextEntry(new JarEntry(name));Files.copy(root.resolve(name),jar);jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("META-INF/services/javax.annotation.processing.Processor"));jar.write("fixture.Tracker\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));jar.closeEntry();
        }
        Path control=root.resolve("Control.java");Files.writeString(control,"@fixture.FixtureTag class Control {} ");
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:only","-classpath",library.toString(),control.toString())==0,"positive control compilation");
        check("processed".equals(System.getProperty(MARKER)),"positive control discovers and executes processor");System.clearProperty(MARKER);
        try(JavaIntelligence engine=new JavaIntelligence()){
            Map<String,Object> request=new HashMap<>();request.put("classpath",library.toString());request.put("compilationUnit",true);
            request.put("text","@Fix class Demo {}");request.put("caret",4);
            List<Map<String,String>> rows=engine.apply(request);
            check(rows.stream().anyMatch(row->"fixture.FixtureTag".equals(row.get("import"))),"external annotation offered without execution");
            check(rows.stream().allMatch(row->"annotation".equals(row.get("icon"))),"only annotations offered");
            request.put("text","@fixture.FixtureTag class Demo { int value; }");request.put("caret",36);request.put("operation","diagnostics");
            check(engine.apply(request).isEmpty(),"valid annotated source analyzed");
            request.put("operation","override");check(!engine.apply(request).isEmpty(),"method analysis available");
            check(System.getProperty(MARKER)==null,"editor must neither instantiate nor invoke a processor");
            request.put("text","@Fix class Demo {}");request.put("caret",4);request.put("operation","complete");request.put("classpath","");
            check(engine.apply(request).stream().noneMatch(row->"fixture.FixtureTag".equals(row.get("import"))),"removed annotation library leaves the index");
        }
        check(!Files.exists(root.resolve("Demo.class")),"editor writes no user class files");
        System.out.println("PASS: service-discovered processor executes in native javac control, never during editor completion/diagnostics/override; annotation library removal refreshes index");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
