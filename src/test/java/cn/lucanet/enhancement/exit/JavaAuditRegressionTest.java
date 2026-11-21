package cn.lucanet.enhancement.exit;

import java.nio.file.*;
import java.util.*;
import javax.swing.JTextArea;
import javax.tools.ToolProvider;

/** Audit failures are checked with an independent javac invocation as well as editor diagnostics. */
public final class JavaAuditRegressionTest {
    private static JavaIntelligence engine;
    private static Path root;
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]);Files.createDirectories(root);
        try(JavaIntelligence service=new JavaIntelligence()){
            engine=service;postfix();variables();imports();types();enums();primary();
        }
        System.out.println("PASS: audit regressions: literal/operators, static wildcard imports, generic identity, enum insertion, primary source name");
    }
    private static List<Map<String,String>> request(String source,int caret,String operation,Map<String,Object> extra){
        Map<String,Object> input=new HashMap<>(extra);input.put("text",source);input.put("caret",caret);input.put("compilationUnit",true);input.put("operation",operation);return engine.apply(input);
    }
    private static Map<String,String> named(List<Map<String,String>> rows,String name){return rows.stream().filter(row->name.equals(row.get("name"))).findFirst().orElseThrow(()->new AssertionError(name+": "+rows));}
    private static void postfix()throws Exception{
        for(String expression:List.of("\"a|b\"","(a | b)","(a |= b)","(a || b)")){
            boolean bool=expression.contains("||");
            String source="class Demo { void run(){ "+(bool?"boolean a=true,b=false;":"int a=1,b=2;")+expression+".sout; } }";
            Map<String,String> row=named(request(source,source.indexOf(".sout")+5,"complete",Map.of()),"sout");
            check(row.get("insert").equals("System.out.println("+expression+");"),"postfix preserves expression: "+row);
            check(Integer.parseInt(row.get("caret"))==row.get("insert").length(),"postfix caret follows literal pipes");
            compile("Demo",replace(source,row));
        }
        String source="class Demo { boolean run(boolean a,boolean b){return (a || b).not;} }";
        Map<String,String> row=named(request(source,source.indexOf(".not")+4,"complete",Map.of()),"not");
        check(row.get("insert").equals("!((a || b))"),"boolean postfix keeps ||");compile("Demo",replace(source,row));
    }
    private static void imports()throws Exception{
        String source="import static java.awt.Desktop.*;\nimport javax.swing.Action;\nimport javax.swing.JButton;\nclass Demo { Action action; JButton button; }";
        List<String> imports=JavaUnitImports.names(source);
        List<Map<String,String>> result=request(source,source.length(),"organize",Map.of("importNames",imports));
        check(result.stream().anyMatch(row->"javax.swing.Action".equals(row.get("import"))),"static imported nested type retains explicit binding: "+result);
        JTextArea area=new JTextArea(source);List<String> next=new ArrayList<>();for(Map<String,String> row:result)next.add(row.get("import"));JavaUnitImports.apply(area,imports,next);compile("Demo",area.getText());
    }
    private static void variables()throws Exception{
        for(String expression:List.of("\"a|b\"","(1 | 2)","java.util.List.of(\"x\")","new Object()")){
            String source="class Demo { void run(){ int value=0; "+expression+".var; int value1=2; } }";
            Map<String,String> row=named(request(source,source.indexOf(".var")+4,"complete",Map.of()),"var");
            check(!row.get("insert").startsWith("var ")&&row.get("insert").endsWith(" = "+expression+";"),"var infers explicit type and retains initializer: "+row);
            int start=Integer.parseInt(row.get("selectionStart")),end=Integer.parseInt(row.get("caret"));
            check(start>0&&end>start&&!Set.of("value","value1").contains(row.get("insert").substring(start,end)),"var name selected for renaming");
            JTextArea area=new JTextArea(replace(source,row));for(String name:row.getOrDefault("imports","").split("\n"))if(!name.isBlank())JavaUnitImports.add(area,name);compile("Demo",area.getText());
        }
        for(String source:List.of("class Demo {void run(){ System.gc().var; }}","class Demo {void run(){ null.var; }}","class Demo {void run(){ if(true) \"x\".var; }}","class Demo {void run(int i){switch(i){case 1 -> \"x\".var; default -> {}}}}"))
            check(request(source,source.indexOf(".var")+4,"complete",Map.of()).stream().noneMatch(row->"var".equals(row.get("name"))),"var only offered for valid local declarations");
        String parent="class Base { protected String value; }",source="class Demo extends Base {void run(){value.var;} }";
        Map<String,String> row=named(request(source,source.indexOf(".var")+4,"complete",Map.of("sources",Map.of("Base",parent))),"var");
        check(row.get("insert").equals("String value1 = value;"),"var cannot shadow an inherited initializer field: "+row);compile("Demo",parent+replace(source,row));
    }
    private static void types()throws Exception{
        for(String source:List.of(
            "class Base<X> { public <T> X convert(T x){return null;} } class Demo<T> extends Base<T> { # }",
            "class Base<X> { public <T extends Comparable<T>,E extends Exception> java.util.Map<X,? extends T[]> convert(T[] x) throws E{return null;} } class Demo<T,E> extends Base<T> { # }",
            "class Base<X> { public <T,T1> X convert(T x,T1 y){return null;} } class Outer<T> { class Demo extends Base<T> { # } }")){
            int caret=source.indexOf('#');source=source.replace("#","");
            Map<String,String> row=named(request(source,caret,"override",Map.of()),"convert");
            compile("Demo",generated(source,List.of(row)));
            check(row.get("insert").contains("T1")||row.get("insert").contains("T2"),"colliding method variable renamed: "+row);
        }
        String source="class Base<X> { public <T> Base(X x,T t){} } class Demo<T> extends Base<T> { }";
        List<Map<String,String>> rows=request(source,source.lastIndexOf('}'),"generate:constructor",Map.of("selectedFields",List.of()));
        check(!rows.isEmpty()&&!"info".equals(rows.get(0).get("kind")),"generic constructor available: "+rows);
        compile("Demo",generated(source,List.of(rows.get(0))));
    }
    private static void enums()throws Exception{
        for(String marked:List.of("enum Demo { A, B # }","enum Demo { # A, B; }","enum Demo { A, B; # }","enum Demo { # }","enum Demo { A { public String toString(){return \";\";} }, B, # }","enum Demo { A, B // trailing\n # }")){
            int caret=marked.indexOf('#');String source=marked.replace("#","");
            List<Map<String,String>> rows=request(source,caret,"override",Map.of());
            Map<String,String> toString=named(rows,"toString");compile("Demo",generated(source,List.of(toString)));
            Map<String,String> clone=rows.stream().filter(row->"name".equals(row.get("name"))).findFirst().orElse(null);
            check(clone==null,"final enum name excluded");
        }
        String source="interface Api {void first(); void second();} enum Demo implements Api { A, B }";
        List<Map<String,String>> fixes=request(source,source.lastIndexOf('}'),"quickFix",Map.of());
        check(!fixes.isEmpty(),"enum missing methods fix available");compile("Demo",replace(source,fixes.get(0)));
    }
    private static void primary()throws Exception{
        for(String source:List.of("class Helper {} public class Primary {}","class Helper { public class Nested {} } public class Primary {}","class Helper {} public interface Primary {}","class Helper {} @Deprecated public enum Primary {ONE}")){
            compile("Primary",source);
            check(request(source,source.length(),"diagnostics",Map.of()).isEmpty(),"public top-level primary file name");
        }
    }
    private static String replace(String source,Map<String,String> row){return source.substring(0,Integer.parseInt(row.get("replaceStart")))+row.get("insert")+source.substring(Integer.parseInt(row.get("replaceEnd")));}
    private static String generated(String source,List<Map<String,String>> rows){JTextArea area=new JTextArea(source);JavaUnitImports.generated(area,rows);return area.getText();}
    private static void compile(String name,String source)throws Exception{
        Path file=root.resolve(name+".java");Files.writeString(file,source);
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",root.toString(),file.toString())==0,"independent compilation:\n"+source);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
