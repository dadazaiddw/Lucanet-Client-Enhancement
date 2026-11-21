package cn.lucanet.enhancement.exit;
import java.nio.file.*;
import java.util.*;
import javax.swing.JTextArea;
import javax.tools.*;

/** Saves generated source and compiles it independently of the editor diagnostics. */
public class AuditGenerationProbe {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);
        try(JavaIntelligence engine=new JavaIntelligence()) {
            check(engine,root,"EnumWithoutSeparator","enum Demo { A, B | }","toString");
            check(engine,root,"EnumBeforeConstant","enum Demo { | A, B; }","toString");
            check(engine,root,"EnumWithSeparator","enum Demo { A, B; | }","toString");
            check(engine,root,"GenericOverrideShadow","class Base<X> { public <T> X convert(T x) {return null;} } class Demo<T> extends Base<T> { | }","convert");
            check(engine,root,"CovariantOverride","interface A { Number n(); } interface B { Integer n(); } class Demo implements A, B { | }","n");
            postfix(engine,root,"BooleanOrPostfix","class Demo { boolean f(boolean a,boolean b){ return (a || b).not#; } }","not");
            postfix(engine,root,"StringPipePostfix","class Demo { void f(){ \"a|b\".sout#; } }","sout");
            String helper="class Helper {} public class Primary {}";
            Map<String,Object> helperInput=new HashMap<>();helperInput.put("text",helper);helperInput.put("caret",helper.length()-1);helperInput.put("compilationUnit",true);helperInput.put("operation","diagnostics");
            System.out.println("PublicAfterHelper independentCompiler="+compile(root.resolve("PublicAfterHelper"),"Primary",helper)+" diagnosticCodes="+codes(engine.apply(helperInput)));
            organize(engine,root);
        }
    }
    static List<String> codes(List<Map<String,String>> rows){List<String> result=new ArrayList<>();for(Map<String,String> row:rows)result.add(row.get("code"));return result;}
    static boolean compile(Path output,String name,String source)throws Exception {
        Files.createDirectories(output);Path file=output.resolve(name+".java");Files.writeString(file,source);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)){
            boolean ok=compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-d",output.toString()),null,manager.getJavaFileObjects(file)).call();
            Files.writeString(output.resolve("compiler.log"),diagnostics.getDiagnostics().toString());return ok;
        }
    }
    static void organize(JavaIntelligence engine,Path root)throws Exception {
        String source="import static java.awt.Desktop.*;\nimport javax.swing.Action;\nimport javax.swing.JButton;\nclass Demo { Action action; JButton button; }";
        Map<String,Object> input=new HashMap<>();input.put("text",source);input.put("caret",source.length()-1);input.put("compilationUnit",true);input.put("operation","organize");input.put("importNames",JavaUnitImports.names(source));
        List<Map<String,String>> rows=engine.apply(input);List<String> requested=new ArrayList<>();for(Map<String,String> row:rows)requested.add(row.get("import"));
        JTextArea text=new JTextArea(source);JavaUnitImports.apply(text,JavaUnitImports.names(source),requested);
        Path out=root.resolve("StaticImportConflict");Files.createDirectories(out);Files.writeString(out.resolve("before.java"),source);Files.writeString(out.resolve("after.java"),text.getText());
        input.put("operation","diagnostics");input.put("text",source);input.put("caret",source.length()-1);List<Map<String,String>> before=engine.apply(input);
        input.put("text",text.getText());input.put("caret",text.getText().length()-1);List<Map<String,String>> after=engine.apply(input);
        System.out.println("StaticImportConflict compiledBefore="+compile(out.resolve("before"),"Demo",source)+" compiledAfter="+compile(out.resolve("after"),"Demo",text.getText())+" imports="+requested+" diagnosticsBefore="+codes(before)+" diagnosticsAfter="+codes(after));
    }
    static void postfix(JavaIntelligence engine,Path root,String id,String marked,String action)throws Exception {
        int caret=marked.indexOf('#');String source=marked.replace("#", "");
        Map<String,Object> input=new HashMap<>();input.put("text",source);input.put("caret",caret);input.put("compilationUnit",true);input.put("operation","postfix");
        List<Map<String,String>> rows=engine.apply(input);
        Map<String,String> row=rows.stream().filter(r->action.equals(r.get("name"))&&"postfix".equals(r.get("kind"))).findFirst().orElse(null);
        if(row==null){System.out.println(id+" no candidate="+rows);return;}
        Path out=root.resolve(id);Files.createDirectories(out);Files.writeString(out.resolve("candidate.txt"),row.toString());
        System.out.println(id+" replacement="+row.get("insert")+" caret="+row.get("caret"));
    }
    static void check(JavaIntelligence engine,Path root,String id,String marked,String method)throws Exception {
        int caret=marked.indexOf('|');String source=marked.replace("|", "");
        boolean original=compile(root.resolve(id).resolve("original"),"Demo",source);
        Map<String,Object> input=new HashMap<>();input.put("text",source);input.put("caret",caret);input.put("compilationUnit",true);input.put("operation","override");
        List<Map<String,String>> rows=engine.apply(input);
        Map<String,String> row=rows.stream().filter(r->method.equals(r.get("name"))).findFirst().orElse(null);
        if(row==null){System.out.println(id+" no candidate: "+rows);return;}
        JTextArea text=new JTextArea(source);JavaUnitImports.generated(text,List.of(row));
        Path out=root.resolve(id);Files.createDirectories(out);Path file=out.resolve("Demo.java");Files.writeString(file,text.getText());
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)){
            boolean ok=compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-d",out.toString()),null,manager.getJavaFileObjects(file)).call();
            Files.writeString(out.resolve("diagnostics.txt"),diagnostics.getDiagnostics().toString());
            List<String> codes=new ArrayList<>();for(Diagnostic<?> item:diagnostics.getDiagnostics())codes.add(item.getCode());
            System.out.println(id+" compiledBefore="+original+" generated="+row.get("insert").replace('\n',' ')+" compiled="+ok+(ok?"":" errorCodes="+codes));
        }
    }
}
