package cn.lucanet.enhancement.exit;

import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.tools.ToolProvider;

/** Compile and execute generated value methods; never use user/business sources. */
public final class JavaMemberGenerationTest {
    static JavaIntelligence engine;
    public static void main(String[] args)throws Exception{
        try(JavaIntelligence service=new JavaIntelligence()){
            engine=service;verifyNames();verifyCompletionImports();verifyMembers();verifyConstructors();verifyIdeaTemplates();verifyInsertion();verifyValueSemantics(Path.of(args[0]));
        }
        System.out.println("PASS: generated imports/shadowing, constructors, boolean accessors, duplicate guards, generic classes, arrays/NaN/null equality and hash contract");
    }
    static List<Map<String,String>> request(String marked,String operation,Map<String,Object> options){
        Map<String,Object> input=new HashMap<>(options);input.put("text",withoutCaret(marked));input.put("caret",marked.lastIndexOf('|'));input.put("compilationUnit",true);input.put("operation",operation);return engine.apply(input);
    }
    static List<Map<String,String>> prepare(String source,String kind){return request(source,"generate:"+kind,Map.of());}
    static Map<String,String> generate(String source,String kind,String...fields){return request(source,"generate:"+kind,Map.of("selectedFields",Arrays.asList(fields))).get(0);}
    static String apply(String marked,Map<String,String> row){
        check(!"info".equals(row.get("kind")),"generation offered: "+row);
        JTextArea text=new JTextArea(withoutCaret(marked));JavaUnitImports.generated(text,List.of(row));return text.getText().substring(0,text.getCaretPosition())+"|"+text.getText().substring(text.getCaretPosition());
    }
    static void compiles(String source){List<Map<String,String>> errors=request(source,"diagnostics",Map.of());check(errors.isEmpty(),"generated source compiles: "+errors+"\n"+source);}
    static void verifyNames(){
        String source="import java.util.Comparator;\nclass Demo implements Comparator<String> { | }";
        Map<String,String> row=request(source,"quickFix",Map.of()).get(0);
        check(row.get("insert").contains("@Override")&&!row.get("insert").contains("java.lang."),"java.lang implicit short names: "+row);
        compiles(apply(source,row));
        source="interface Api { java.util.List<java.nio.file.Path> read() throws java.io.IOException; } class Demo implements Api { | }";
        row=request(source,"quickFix",Map.of()).get(0);String edited=apply(source,row);
        check(edited.contains("import java.util.List;")&&edited.contains("import java.nio.file.Path;")&&edited.contains("List<Path> read()"),"external generic types imported");compiles(edited);
        source="import java.awt.List; interface Api { java.util.List<java.lang.String> read(); } class Demo implements Api { List existing; | }";
        edited=apply(source,request(source,"quickFix",Map.of()).get(0));check(edited.contains("java.util.List<String> read()")&&!edited.contains("import java.util.List;"),"explicit import collision retained");compiles(edited);
        source="class Override {} class Object {} class String {} class Demo implements java.util.Comparator<java.lang.String> { | }";
        edited=apply(source,request(source,"quickFix",Map.of()).get(0));check(edited.contains("@java.lang.Override")&&edited.contains("compare(java.lang.String"),"custom java.lang names retained");compiles(edited);
        source="class Demo<Objects> { String name; | }";
        edited=apply(source,generate(source,"equalsHashCode","name"));check(edited.contains("java.util.Objects.equals")&&!edited.contains("import java.util.Objects;"),"type parameter shadow guard");compiles(edited);
        source="class Demo { Object Objects; | }";
        edited=apply(source,generate(source,"equalsHashCode","Objects"));check(edited.contains("java.util.Objects.equals(Objects"),"field shadow guard");compiles(edited);
        source="interface Api { <List> List transform(List input); } class Demo implements Api { | }";
        edited=apply(source,request(source,"quickFix",Map.of()).get(0));compiles(edited);
        source="interface A { java.util.Date first(); } interface B { java.sql.Date second(); } class Demo implements A,B { | }";
        edited=apply(source,request(source,"quickFix",Map.of()).get(0));compiles(edited);
        check(!(edited.contains("import java.util.Date;")&&edited.contains("import java.sql.Date;")),"batch method imports never conflict");
    }
    static void verifyMembers(){
        String source="class Demo<T> { private T value; private boolean isReady; private Boolean enabled; private final int fixed=1; static String ignored; | }";
        String edited=apply(source,generate(source,"accessors","value","isReady","enabled","fixed","ignored"));
        check(edited.contains("T getValue()")&&edited.contains("isReady()")&&edited.contains("setReady(boolean isReady)")&&edited.contains("Boolean getEnabled()"),"generic and JavaBeans boolean accessors");
        check(!edited.contains("setFixed")&&!edited.contains("getIgnored"),"final setter and static fields excluded");compiles(edited);
        check(prepare(edited,"accessors").get(0).get("kind").equals("info"),"repeat generation does not duplicate accessors");
        source="class Parent { public final String getName(){return null;} } class Demo extends Parent { String name; | }";
        check(prepare(source,"getter").get(0).get("kind").equals("info"),"inherited final method guarded");
        source="class Demo { String a; public boolean equals(Object o){return true;} | }";
        check(generate(source,"equalsHashCode","a").get("kind").equals("info"),"equals hashCode pair consistency");
        source="class Outer { String outer; class Inner { int id; | } }";
        edited=apply(source,generate(source,"accessors","id"));check(edited.contains("getId()")&&!edited.contains("getOuter"),"innermost owner only");compiles(edited);
        source="package p; class Outer { private class Value {} class Inner { Value value; | } }";
        edited=apply(source,generate(source,"getter","value"));check(!edited.contains("import p.Outer.Value;"),"visible private nested type needs no import");compiles(edited);
        source="class Parent { void setItems(java.util.List<String> items){} } class Demo extends Parent { java.util.List<Integer> items; | }";
        check(prepare(source,"setter").get(0).get("kind").equals("info"),"same-erasure incompatible inherited setter excluded");
        for(String type:List.of("interface Demo { | }","enum Demo { ; | }","record Demo(int id) { | }"))check(prepare(type,"constructor").get(0).get("kind").equals("info"),"unsupported declaration guarded");
        source="class Demo { int value; // End of data\n// ==================== End of Class ================\n|}\n";
        // The caret belongs inside the class body, not after its closing brace.
        edited=apply(source,generate(source,"getter","value"));check(edited.indexOf("getValue")<edited.indexOf("End of Class"),"native footer preserved");compiles(edited);
    }
    static void verifyCompletionImports(){
        for(String source:List.of("class Demo<List> { Lis| value; }","class Demo { class List {} Lis| value; }","import java.awt.List; class Demo { Lis| value; }","class Demo { <List> void run(){ Lis| value; } }")){
            Map<String,String> candidate=request(source,"complete",Map.of()).stream().filter(row->"java.util.List".equals(row.get("import"))).findFirst().orElseThrow();
            check("java.util.List".equals(candidate.get("unitReference"))&&candidate.get("imports").isEmpty(),"completion avoids type shadow: "+source+candidate);
        }
        Map<String,String> candidate=request("class Demo { File| value; }","quickFix",Map.of()).stream().filter(row->"java.io.File".equals(row.get("import"))).findFirst().orElseThrow();
        check("File".equals(candidate.get("unitReference"))&&candidate.get("imports").equals("java.io.File"),"unresolved type is eligible for an import: "+candidate);
        candidate=request("import java.awt.*; class Demo { Lis| value; }","complete",Map.of()).stream().filter(row->"java.util.List".equals(row.get("import"))).findFirst().orElseThrow();
        check("List".equals(candidate.get("unitReference"))&&candidate.get("imports").equals("java.util.List"),"explicit import disambiguates wildcard");
        String samePackage="package example; import java.util.List; import java.util.ArrayList; class Demo { List<String> values = new ArrayList<>(); | }";
        List<Map<String,String>> organized=request(samePackage,"organize",Map.of("importNames",List.of("java.util.List","java.util.ArrayList"),"sources",Map.of("example.List","package example; class List {}")));
        check(organized.stream().anyMatch(row->"java.util.List".equals(row.get("import"))),"organize preserves explicit import against package-private same-package class");
        String shadow="class Demo { Runnable action=new Runnable(){ public void run(){ | } }; }";
        check(prepare(shadow,"getter").get(0).get("kind").equals("info")&&request(shadow,"override",Map.of()).isEmpty(),"anonymous class does not redirect generation into outer type");
    }
    static void verifyConstructors(){
        String source="class Demo { final String id; final int initialized=3; int count; | }";
        String edited=apply(source,generate(source,"constructor","count"));check(edited.contains("Demo(String id, int count)")&&!edited.contains("this.initialized ="),"blank final forced, initialized final excluded");compiles(edited);
        check(generate(edited,"constructor","count").get("kind").equals("info"),"duplicate constructor guard");
        source="class Parent<T> { protected Parent(T arg) throws java.io.IOException {} } class Demo extends Parent<String> { int count; | }";
        edited=apply(source,generate(source,"constructor","count"));check(edited.contains("Demo(String arg, int count) throws IOException")&&edited.contains("super(arg);"),"generic super constructor and checked exceptions");compiles(edited);
        source="class Demo { final String name; { name = \"ready\"; } | }";
        edited=apply(source,generate(source,"constructor"));check(!edited.contains("this.name ="),"instance initializer final assignment excluded");compiles(edited);
        source="class Parent { private Parent(){} } class Demo extends Parent { | }";
        check(prepare(source,"constructor").get(0).get("kind").equals("info"),"inaccessible parent constructor guarded");
        source="class Parent { Parent() {} Parent(String name) {} } class Demo extends Parent { int id; | }";
        Map<String,String> chosen=request(source,"generate:constructor",Map.of("selectedFields",List.of("id"),"superConstructor","1")).get(0);
        edited=apply(source,chosen);check(edited.contains("Demo(String name, int id)")&&edited.contains("super(name)"),"chosen superclass overload");compiles(edited);
        source="class Parent { public boolean equals(Object o){return super.equals(o);} public int hashCode(){return super.hashCode();} } class Demo extends Parent { int id; | }";
        chosen=request(source,"generate:equalsHashCode",Map.of("selectedFields",List.of("id"),"includeSuper",true)).get(0);
        edited=apply(source,chosen);check(edited.contains("!super.equals(o)")&&edited.contains("Objects.hash(super.hashCode(), id)"),"superclass participates in both value methods");compiles(edited);
        source="class Demo { | }";edited=apply(source,generate(source,"constructor"));compiles(edited);check(edited.contains("public Demo()"),"explicit empty constructor");
    }
    static void verifyIdeaTemplates(){
        String source="class Test { private String s; private int i; | }";
        String edited=apply(source,generate(source,"equalsHashCode","s","i"));compiles(edited);
        check(edited.contains("Test test = (Test) o;")&&edited.contains("return i == test.i && Objects.equals(s, test.s);")&&edited.contains("return Objects.hash(s, i);"),"IDEA Objects template for screenshot sample: "+edited);
        edited=apply(source,generate(source,"accessors","s","i"));check(edited.contains("return s;")&&edited.contains("this.s = s;"),"IDEA getter setter template");compiles(edited);
        edited=apply(source,generate(source,"toString","s","i"));check(edited.contains("\"s='\" + s + '\\''")&&edited.contains("\", i=\" + i"),"IDEA quoted string concat template");compiles(edited);
        source="class Class { Object o, class1, Objects; | }";edited=apply(source,generate(source,"equalsHashCode","o","class1","Objects"));compiles(edited);
        check(edited.contains("Object o1")&&edited.contains("Class class2"),"local names avoid fields and Java keywords");
        source="class Demo { String s; int i; String getS(){return s;} int getI(){return i;} | }";
        edited=apply(source,request(source,"generate:equalsHashCode",Map.of("selectedFields",List.of("s","i"),"hashFields",List.of("s"),"nonNullFields",List.of("s"),"useGetters",true,"useInstanceof",true)).get(0));compiles(edited);
        check(edited.contains("o instanceof Demo")&&edited.contains("getS().equals(demo.getS())")&&edited.contains("Objects.hashCode(getS())"),"wizard options produce valid source");
        source="class Demo { String s; String getS() throws Exception{return s;} | }";
        edited=apply(source,request(source,"generate:toString",Map.of("selectedFields",List.of("s"),"useGetters",true,"insertOverride",false)).get(0));check(!edited.contains("@Override"),"toString annotation option");compiles(edited);
        source="class Demo { String[] values; | }";edited=apply(source,generate(source,"equalsHashCode","values"));compiles(edited);
        check(edited.contains("return Arrays.deepHashCode(values);")&&!edited.contains("Objects.hash(values)"),"single reference array never treated as hash varargs");
        source="class Demo { int a,b; | }";edited=apply(source,request(source,"generate:equalsHashCode",Map.of("selectedFields",List.of("a"),"hashFields",List.of("a","b"))).get(0));compiles(edited);check(!edited.contains("hash(a, b)"),"hash selection stays a subset of equals fields");
    }
    static void verifyInsertion(){
        for(String marked:List.of(
            "class Demo {\n    int value;\n    |\n    void later() {}\n}",
            "class Demo {\n    int value;\n    void before(){ | }\n    void later() {}\n}",
            "class Demo { int value=|1, other=2; void later(){} }",
            "class Demo { int value; { value=|1; } void later(){} }",
            "class Demo { int value; // trail |comment\n void later(){} }",
            "class Demo {\n int value;\n /** documentation | text */\n void later(){}\n}",
            "class Demo { int value; /* trailing | block */ void later(){} }",
            "class Demo { int value; void before(){ String s=\"a}b|c\"; } void later(){} }",
            "class Demo { int value;\n |/** docs */\n void later(){} }",
            "class Outer { class Demo { int value;\n |\n void later(){} } }",
            "class Demo {\r\n    int value;\r\n    |\r\n    void later(){}\r\n}\r\n"
        )){
            Map<String,String> row=generate(marked,"getter","value");String generated=apply(marked,row);compiles(generated);
            check(generated.indexOf("getValue()")<generated.indexOf("void later()"),"inserts near caret before later member: "+generated);
            if(marked.contains("/**"))check(generated.indexOf("getValue()")<generated.indexOf("/**"),"Javadoc stays attached to later member");
            if(marked.contains("\r\n"))check(!row.get("insert").replace("\r\n","").contains("\n"),"CRLF preserved");
        }
        String source="class Demo {\n int value;\n |\n // keep this section\n void later(){}\n}";
        Map<String,String> row=generate(source,"getter","value");check(Integer.parseInt(row.get("replaceStart"))==source.indexOf(" |"),"blank caret line does not skip to next line");
        source="class Demo implements Runnable {\n |\n void later(){}\n}";String edited=apply(source,request(source,"quickFix",Map.of()).get(0));compiles(edited);check(edited.indexOf("void run()")<edited.indexOf("void later()"),"quick fix shares caret insertion");
    }
    static void verifyValueSemantics(Path root)throws Exception{
        Files.createDirectories(root);
        String source="public class GeneratedBean<T> { public int id; public float fraction; public double amount; public int[] numbers; public Object[][] values; public T payload; public String result; | }";
        String[] fields={"id","fraction","amount","numbers","values","payload","result"};
        source=apply(source,generate(source,"equalsHashCode",fields));source=apply(source,generate(source,"toString",fields));compiles(source);
        Path file=root.resolve("GeneratedBean.java");Files.writeString(file,withoutCaret(source));
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",root.toString(),file.toString())==0,"runtime fixture compiles");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{root.toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class<?> bean=loader.loadClass("GeneratedBean");Object a=bean.getConstructor().newInstance(),b=bean.getConstructor().newInstance();
            bean.getField("numbers").set(a,new int[]{1,2});bean.getField("numbers").set(b,new int[]{1,2});
            bean.getField("values").set(a,new Object[][]{{new int[]{3,4},null}});bean.getField("values").set(b,new Object[][]{{new int[]{3,4},null}});
            bean.getField("fraction").setFloat(a,Float.NaN);bean.getField("fraction").setFloat(b,Float.NaN);
            bean.getField("amount").setDouble(a,Double.NaN);bean.getField("amount").setDouble(b,Double.NaN);
            check(a.equals(b)&&b.equals(a)&&a.hashCode()==b.hashCode(),"deep arrays null NaN equality/hashCode consistency");
            check(!a.equals(null)&&!a.equals("wrong type"),"null and wrong type equality");
            check(a.toString().contains("numbers=[1, 2]")&&a.toString().contains("values=[[[3, 4], null]]"),"array content toString");
            bean.getField("amount").setDouble(a,0.0);bean.getField("amount").setDouble(b,-0.0);check(!a.equals(b),"signed zero contract");
        }
    }
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static String withoutCaret(String source){int at=source.lastIndexOf('|');return source.substring(0,at)+source.substring(at+1);}
}
