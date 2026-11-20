package cn.lucanet.enhancement.exit;

import java.util.*;

public final class JavaIntelligenceTest {
    private static JavaIntelligence engine;
    public static void main(String[] args) throws Exception {
        try(JavaIntelligence service=new JavaIntelligence()) {
            engine=service;
            check(complete("String text=\"abc\"; text.sub|").stream().anyMatch(v->v.get("label").contains("substring(int")),"String overload completion");
            check(complete("java.util.List<String> list=new java.util.ArrayList<>(); list.get(0).toU|").stream().anyMatch(v->v.get("name").equals("toUpperCase")),"generic chained return type");
            List<Map<String,String>> generic=complete("java.util.List<String> list=new java.util.ArrayList<>(); list.ad|");
            check(generic.stream().anyMatch(v->v.get("label").contains("add(String")),"generic parameter substitution");
            check(complete("Math.ma|").stream().anyMatch(v->v.get("name").equals("max")),"static methods");
            check(complete("String.class.toS|").stream().anyMatch(v->v.get("name").equals("toString")),"class literal members");
            check(complete("String message=\"ok\"; mess|").stream().anyMatch(v->v.get("name").equals("message")),"local variable");
            check(complete("java.util.ArrayList<String> list=new java.util.ArrayList<>(); list.elementD|").isEmpty(),"private members excluded");
            check(complete("String[] values={}; values.len|").stream().anyMatch(v->v.get("name").equals("length")),"array length");
            check(complete("ArrayL|").stream().anyMatch(v->v.get("import").equals("java.util.ArrayList")),"class import candidates");
            Map<String,Object> constructor=request("new String|(\"hello\");");constructor.put("operation","parameters");
            check(service.apply(constructor).stream().anyMatch(v->v.get("kind").equals("constructor")),"constructor parameters");
            check(complete("ret|").stream().anyMatch(v->v.get("name").equals("return")),"Java keywords");
            Map<String,Object> input=request("input.sub|");input.put("part","execute");input.put("source","import java.util.*; class Demo { String value; void execute(String input) { System.out.println(input); } }");
            check(service.apply(input).stream().anyMatch(v->v.get("name").equals("substring")),"native method signature retained");
            input=request("val|");input.put("part","execute");input.put("source","class Demo { String value; void execute() { } }");
            check(service.apply(input).stream().anyMatch(v->v.get("name").equals("value")),"native class members");
            verifyContext();verifyParameters();verifyArgumentCompletion();verifyGenericPresentation();verifyPostfix();verifyConstructorAccess();verifyIconKinds();verifyImports();verifyTypeActions();verifyAnnotations();
            System.out.println("PASS: Java semantic completion, overloads, generic chains/parameters, scope, access control, imports and native class context");
        }
    }
    static Map<String,Object> request(String marked) {
        Map<String,Object> input=new HashMap<>();input.put("text",marked.replace("|",""));input.put("caret",marked.indexOf('|'));input.put("classpath","");return input;
    }
    static List<Map<String,String>> complete(String marked) {return engine.apply(request(marked));}
    static void verifyAnnotations(){
        List<Map<String,String>> rows=unitAction("@| public class Demo {}","complete");
        check(!rows.isEmpty()&&rows.stream().allMatch(row->row.get("icon").equals("annotation")),"bare @ offers only annotations: "+rows);
        check(rows.stream().anyMatch(row->row.get("name").equals("Deprecated")),"bare @ finds JDK annotations");
        rows=unitAction("class Demo { @Over| public String toString(){return \"\";} }","complete");
        Map<String,String> override=named(rows,"Override");check("Override".equals(override.get("unitReference"))&&override.get("imports").isEmpty(),"java.lang annotation needs no import");
        rows=unitAction("@java.lang.Dep| class Demo {}","complete");
        check(rows.size()==1&&rows.get(0).get("insert").equals("Deprecated")&&rows.get(0).get("import").isEmpty(),"qualified annotation preserves qualifier without redundant import");
        rows=unitAction("@interface Tag {} @Ta| class Demo {}","complete");
        check(rows.stream().anyMatch(row->row.get("name").equals("Tag")),"local annotation declaration");
        Map<String,Object> custom=request("@Cus| class Demo {}");custom.put("compilationUnit",true);custom.put("sources",Map.of("Tag","package fixture; public @interface CustomTag {}"));
        check(engine.apply(custom).stream().anyMatch(row->row.get("import").equals("fixture.CustomTag")),"companion source annotation");
        check(unitAction("@SuppressWarnings(System.getPro|) class Demo {}","complete").stream().anyMatch(row->row.get("name").equals("getProperty")),"annotation argument keeps expression completion");
        check(unitAction("class Demo { void f(){ Strin| value; } }","complete").stream().anyMatch(row->row.get("name").equals("String")),"ordinary type completion unaffected");
    }
    static List<Map<String,String>> unitAction(String source,String operation) {
        Map<String,Object> input=request(source);input.put("compilationUnit",true);input.put("operation",operation);
        return engine.apply(input);
    }
    static String applyAction(String marked,Map<String,String> action) {
        String source=marked.replace("|","");
        int start=Integer.parseInt(action.get("replaceStart")),end=Integer.parseInt(action.get("replaceEnd"));
        javax.swing.JTextArea text=new javax.swing.JTextArea(source.substring(0,start)+action.get("insert")+source.substring(end));
        text.setCaretPosition(start+action.get("insert").length());
        for(String name:action.getOrDefault("imports","").split("\n"))if(!name.isBlank())JavaUnitImports.add(text,name);
        return text.getText().substring(0,text.getCaretPosition())+"|"+text.getText().substring(text.getCaretPosition());
    }
    static void verifyTypeActions() {
        for(String type:List.of("java.util.Comparator","java.util.Comparator<String>")) {
            String source="import java.util.*;\npublic class Demo implements "+type+" {\n    |\n}\n";
            List<Map<String,String>> errors=unitAction(source,"diagnostics");
            check(errors.size()==1&&errors.get(0).get("code").equals("compiler.err.does.not.override.abstract"),"javac reports missing compare before save: "+errors);
            List<Map<String,String>> fixes=unitAction(source,"quickFix");
            check(!fixes.isEmpty()&&fixes.get(0).get("label").equals("实现 compare()"),"Alt+Enter offers Comparator implementation inside class: "+fixes);
            String updated=applyAction(source,fixes.get(0));
            String argument=type.endsWith(">")?"String":"Object";
            check(updated.contains("int compare("+argument)&&unitAction(updated,"diagnostics").isEmpty(),"generated Comparator implementation compiles: "+updated);
            List<Map<String,String>> methods=unitAction(updated,"override");
            check(methods.stream().noneMatch(row->Set.of("compare","wait","comparing").contains(row.get("name"))),"already implemented, final and static methods omitted");
            Map<String,String> reverse=named(methods,"reversed");
            check(reverse.get("insert").contains("Comparator.super.reversed()")
                &&unitAction(applyAction(updated,reverse),"diagnostics").isEmpty(),"generated override preserves the default interface implementation");
        }
        String base="class Base<T> { protected T identity(T value) throws java.io.IOException { return value; } }\n";
        String nested=base+"class Outer { class Inner extends Base<String> {\n    |\n}}\n";
        Map<String,String> method=named(unitAction(nested,"override"),"identity");
        check(method.get("insert").contains("protected String identity(String value)")&&
            method.get("insert").contains("return super.identity(value)"),"nested class overrides parent with the actual generic signature and preserves its behavior");
        check(unitAction(applyAction(nested,method),"diagnostics").isEmpty(),"generated nested override compiles");
        String incomplete="class Demo { void run(){ int x = 1|\n} }";
        List<Map<String,String>> fixes=unitAction(incomplete,"quickFix");
        check(!fixes.isEmpty()&&";".equals(fixes.get(0).get("insert"))&&unitAction(applyAction(incomplete,fixes.get(0)),"diagnostics").isEmpty(),
            "quick fix inserts expected semicolon: "+fixes+"; diagnostics: "+unitAction(incomplete,"diagnostics"));
        check(unitAction("class Demo { File| file; }","quickFix").stream().anyMatch(row->"java.io.File".equals(row.get("import"))),"full source Alt+Enter still offers imports");
    }
    static void verifyArgumentCompletion() {
        List<Map<String,String>> members=complete("java.util.List.of(\"1\",\"2\",\"3\").forEach(System.|)");
        check(members.stream().anyMatch(v->v.get("name").equals("out"))&&members.stream().anyMatch(v->v.get("name").equals("err")),"argument receiver members replace enclosing signature");
        check(complete("java.util.List.of(\"1\",\"2\",\"3\").forEach(System.out.pr|)").stream().anyMatch(v->v.get("name").equals("println")),"nested member prefix inside argument");
        check(complete("String message=\"\"; System.out.println(mess|)").stream().anyMatch(v->v.get("name").equals("message")),"argument local variable");
        check(complete("new java.io.File(System.getPro|)").stream().anyMatch(v->v.get("name").equals("getProperty")),"constructor argument expression completion");
        check(complete("java.util.List.of(\"one\").forEach(value -> value.sub|)").stream().anyMatch(v->v.get("name").equals("substring")),"inferred lambda argument completion");
    }
    static void verifyGenericPresentation() {
        Map<String,String> list=complete("Lis|").stream().filter(v->"java.util.List".equals(v.get("import"))).findFirst().orElseThrow();
        check("<E>".equals(list.get("typeParameters"))&&list.get("insert").equals("List"),"List<E> display without inserting undeclared E: "+list);
        check("<K, V>".equals(named(complete("HashMa|"),"HashMap").get("typeParameters")),"two class type parameters");
        check(!named(complete("Strin|"),"String").containsKey("typeParameters"),"non-generic class has no suffix");
        Map<String,Object> custom=script("Gener|");custom.put("sources",Collections.singletonMap("GenericBox","package com.lucanet.common.dom.script.rt; public class GenericBox<T extends Number, U> {}"));
        check("<T, U>".equals(named(engine.apply(custom),"GenericBox").get("typeParameters")),"custom Class declared type parameters");
        Map<String,Object> local=request("Nes|");local.put("part","run");local.put("source","class Demo {class Nested<T>{} void run(){} }");
        check("<T>".equals(named(engine.apply(local),"Nested").get("typeParameters")),"scope type declaration retains generic suffix");
        check(complete("java.util.List.o|").stream().filter(v->v.get("name").equals("of")).allMatch(v->v.get("detail").equals("List<E>")),"generic method return type retained");
    }
    static Map<String,Object> script(String marked) {
        Map<String,Object> input=request(marked);input.put("part","doExecute");
        input.put("source","package com.lucanet.common.dom.script.rt;\nimport java.io.File;\nimport java.util.*;\npublic class Task__Demo { private String field; public void doExecute() {try {String old=\"before\";} finally {}} }");
        input.put("sources",Collections.singletonMap("StringUtil","package com.lucanet.common.dom.script.rt; public class StringUtil { public static final String SEPARATOR=\",\"; public static boolean isEmpty(String str){return str==null;} public static List<String> values(){return null;} }".replace("List<String>","java.util.List<String>")));
        return input;
    }
    static void verifyContext() {
        check(engine.apply(script("StringUtil.isE|")).stream().anyMatch(v->v.get("label").contains("isEmpty(String str)")),"same-script Class static method and named parameter");
        check(engine.apply(script("StringUtil.values().get(0).toU|")).stream().anyMatch(v->v.get("name").equals("toUpperCase")),"companion generic return type chain");
        check(engine.apply(script("StringUtil.SEP|")).stream().anyMatch(v->v.get("name").equals("SEPARATOR")),"custom class constant");
        List<Map<String,String>> values=engine.apply(script("StringU|"));
        check(!values.isEmpty()&&values.get(0).get("import").equals("com.lucanet.common.dom.script.rt.StringUtil"),"custom class ahead of dependency classes");
        values=engine.apply(script("Fi|"));check(values.get(0).get("name").equals("File"),"explicit import ranked first");
        values=engine.apply(script("String fieldValue=\"\"; fi|"));check(values.get(0).get("name").equals("fieldValue"),"local variable ahead of class field and classes");
        values=engine.apply(script("int valueNumber=1; String valueText=\"\"; String result=val|"));
        check(values.get(0).get("name").equals("valueText"),"assignment-compatible local ranked first: "+values);
        Map<String,Object> input=script("StringUtil.isE|");input.put("sources",Collections.emptyMap());check(engine.apply(input).isEmpty(),"removed/other-script Class never leaks from previous request");
        input=script("StringUtil.ren|");input.put("sources",Collections.singletonMap("StringUtil","package com.lucanet.common.dom.script.rt; public class StringUtil {public static String renamed(int n){return null;}}"));
        check(engine.apply(input).stream().anyMatch(v->v.get("name").equals("renamed")),"changed Class reindexed on next request");
        input=request("input.sub|");input.put("part","run");input.put("partSignature","public void run(String input)");
        input.put("source","class Demo {void run(int input){} public void run(String input){try{}finally{}} }");
        check(engine.apply(input).stream().anyMatch(v->v.get("name").equals("substring")),"correct overloaded native member selected");
        input.put("partSignature","");input.put("partBody","String previous=\"\";");input.put("source","class Demo {void run(int input){} public void run(String input){try{String previous=\"\";}finally{}} }");
        check(engine.apply(input).stream().anyMatch(v->v.get("name").equals("substring")),"body identifies overloaded member when signature unavailable");
        input=request("input.sub|");input.put("part","Demo");input.put("partSignature","public Demo(String input)");input.put("source","class Demo {Demo(int n){} public Demo(String input){} }");
        check(engine.apply(input).stream().anyMatch(v->v.get("name").equals("substring")),"custom constructor source context");
    }
    static List<Map<String,String>> parameters(String text) {Map<String,Object> input=script(text);input.put("operation","parameters");return engine.apply(input);}
    static void verifyParameters() {
        List<Map<String,String>> constructors=parameters("new File(|)");
        check(constructors.size()==4,"incomplete File constructor lists four accessible overloads: "+constructors);
        check(constructors.stream().allMatch(v->v.get("kind").equals("constructor")),"constructor hint kind");
        check(parameters("new File(\"a,b\", |)").stream().allMatch(v->v.get("activeParameter").equals("1")),"comma in string not counted as argument");
        check(parameters("new File(\"te|st\")").size()==4,"hint works inside argument string");
        check(parameters("new File(\"test\"|)").get(0).get("parameters").startsWith("String"),"String constructor ranks first for string argument");
        check(parameters("new File(java.net.URI.create(\"file:/tmp\")|)").get(0).get("parameters").startsWith("URI"),"URI constructor ranks first for typed expression");
        check(parameters("new java.io.File(|)").size()==4,"qualified constructor");
        check(!parameters("new ArrayList<String>(|)").isEmpty(),"generic constructor");
        check(parameters("StringUtil.isEmpty(|)").stream().anyMatch(v->v.get("parameters").equals("String str")),"custom class parameter information");
        check(parameters("String s=\"\"; s.substring(1, |)").stream().allMatch(v->v.get("activeParameter").equals("1")),"method active parameter and overloads");
        check(parameters("String s=\"\"; s.substring(Math.max(1, 2), |)").stream().anyMatch(v->v.get("activeParameter").equals("1")&&v.get("name").equals("substring")),"nested call commas ignored");
        check(parameters("String s=\"\"; s.substring(Math.max(1, |), 2)").stream().anyMatch(v->v.get("name").equals("max")),"innermost invocation hint");
        check(parameters("new File((|))").size()==4,"grouping parentheses do not suppress outer call hint");
        check(parameters("String.format(\"%s\", new Object[]{1, |})").stream().anyMatch(v->v.get("name").equals("format")&&v.get("activeParameter").equals("1")),"array literal commas do not count as method arguments");
        check(parameters("Math.max(1.5, |)").get(0).get("parameters").startsWith("double"),"method overload ranks by provided argument type");
        check(parameters("int a=1,b=2,c=3,d=4; Math.max(a<b ? 1:2, |c>d ? 1:2)").get(0).get("activeParameter").equals("1"),"comparison operators are not generic brackets");
        check(parameters("int x=1; |").isEmpty(),"outside call has no hint");
    }
    static Map<String,String> postfix(String marked,String name) {
        return engine.apply(script(marked)).stream().filter(v->v.get("kind").equals("postfix")&&v.get("name").equals(name)).findFirst().orElseThrow(()->new AssertionError("missing postfix "+marked));
    }
    static void verifyPostfix() {
        Map<String,String> row=postfix("File.ne|","new");check(row.get("insert").equals("new File()")&&row.get("caret").equals("9"),"File.new expansion and caret");
        row=postfix("File file=File.ne|","new");check(row.get("replaceStart").equals("10"),"postfix replaces receiver only");
        row=postfix("String value=\"ok\"; value.so|","sout");check(row.get("insert").equals("System.out.println(value);"),"sout postfix");
        row=postfix("String value=\"ok\"; value.n|","nn");check(row.get("insert").contains("if (value != null)"),"null guard");
        row=postfix("boolean ready=true; ready.i|","if");check(row.get("insert").contains("if (ready)"),"boolean if postfix");
        row=postfix("    String value=\"\";\n    value.nn|","nn");check(row.get("insert").equals("if (value != null) {\n        \n    }"),"postfix preserves surrounding indentation");
        check(engine.apply(script("String value=\"\"; String result=value.so|")).stream().noneMatch(v->v.get("kind").equals("postfix")),"statement postfix suppressed in initializer expression");
        check(complete("File.new|").stream().anyMatch(v->v.get("kind").equals("postfix")&&v.get("import").equals("java.io.File")),"unimported File.new offers auto import");
        check(engine.apply(script("int n=1; n.nn|")).stream().noneMatch(v->v.get("kind").equals("postfix")),"no null guard on primitive");
    }
    static List<String> organized(String source,List<String> names,List<String> editable) {
        Map<String,Object> input=script("|");input.put("source",source+"\nclass Demo {void doExecute(){}} ");input.put("operation","organize");input.put("importNames",names);input.put("editableImports",editable);
        List<String> result=new ArrayList<>();for(Map<String,String> row:engine.apply(input))result.add(row.get("import"));return result;
    }
    static Map<String,Object> constructorContext(String marked,String typeSource,String caller) {
        Map<String,Object> input=request(marked);input.put("source",caller);input.put("part","run");input.put("sources",Collections.singletonMap("Example",typeSource));return input;
    }
    static boolean offersNew(Map<String,Object> input){return engine.apply(input).stream().anyMatch(v->"postfix".equals(v.get("kind"))&&"new".equals(v.get("name")));}
    static void verifyConstructorAccess() {
        String caller="package p; class Demo {void run(){}}";
        for(String visibility:new String[]{"private","public","protected",""}) {
            String source="package p; public class StringUtil {"+visibility+" StringUtil(){} public static boolean isEmpty(String s){return false;} }";
            Map<String,Object> input=constructorContext("StringUtil.ne|",source,caller);
            check(offersNew(input)!=visibility.equals("private"),"same package constructor access: "+visibility);
            input=constructorContext("StringUtil.ne|",source,"package q; import p.StringUtil; class Demo {void run(){}}");
            check(offersNew(input)==visibility.equals("public"),"cross package constructor access: "+visibility);
            input=constructorContext("new StringUtil(|)",source,caller);input.put("operation","parameters");
            check(engine.apply(input).isEmpty()==visibility.equals("private"),"constructor hints use same access policy: "+visibility);
        }
        String privateSource="package p; public class StringUtil {private StringUtil(){} public void run(){} public static boolean isEmpty(String s){return false;} }";
        Map<String,Object> input=constructorContext("StringUtil.ne|",privateSource,privateSource);
        check(offersNew(input),"private constructor remains available within its declaring class");
        input=constructorContext("new StringUtil(|)",privateSource,privateSource);input.put("operation","parameters");
        check(engine.apply(input).size()==1,"private constructor hint within declaring class");
        input=constructorContext("StringUtil.isE|",privateSource,caller);
        check(engine.apply(input).stream().anyMatch(v->v.get("name").equals("isEmpty")),"utility class static members remain available");
        input=constructorContext("StringUtil.ne|","package p; public class StringUtil {private StringUtil(){} public StringUtil(String value){} }",caller);
        check(offersNew(input),"public overload allows new despite private zero-argument constructor");
        input.put("text","new StringUtil()");input.put("caret",15);input.put("operation","parameters");
        List<Map<String,String>> visible=engine.apply(input);check(visible.size()==1&&visible.get(0).get("parameters").equals("String value"),"only accessible constructor overload shown");
        check(!offersNew(constructorContext("Base.ne|","package p; public class Base {protected Base(){}}","package q; class Demo extends p.Base {void run(){}}")),"subclass cannot directly invoke protected constructor across packages");
        check(offersNew(constructorContext("DefaultType.ne|","package p; public class DefaultType {}",caller)),"implicit default constructor remains available");
        check(!offersNew(constructorContext("AbstractType.ne|","package p; public abstract class AbstractType {public AbstractType(){}}",caller)),"abstract class cannot be directly instantiated");
        check(!offersNew(constructorContext("Choice.ne|","package p; public enum Choice {ONE}",caller)),"enum cannot be instantiated");
        check(!offersNew(request("java.util.Collections.ne|")),"JDK utility class private constructor excluded");
        check(!offersNew(request("Collections.ne|")),"unimported utility class does not bypass constructor access");
    }
    static void verifyImports() {
        List<String> io=Arrays.asList("java.io.File","java.io.InputStream","java.io.OutputStream","java.io.IOException","java.io.Reader");
        String source="";for(String name:io)source+="import "+name+";\n";
        check(organized(source,io,io).equals(Collections.singletonList("java.io.*")),"multiple class imports consolidate");
        List<String> hidden=organized(source,io,io.subList(0,4));check(hidden.contains("java.io.Reader")&&hidden.contains("java.io.*"),"hidden import preserved during consolidation");
        List<String> single=io.subList(0,1);check(organized(source,single,single).equals(single),"single class import stays explicit");
        check(organized(source,io.subList(0,2),io.subList(0,2)).equals(Collections.singletonList("java.io.*")),"two imports now consolidate");
        List<String> screenshot=Arrays.asList("java.io.File","java.io.IOError","java.io.IOException","java.io.InvalidObjectException");
        String actual="";for(String name:screenshot)actual+="import "+name+";\n";
        check(organized(actual,screenshot,screenshot).equals(Collections.singletonList("java.io.*")),"user's four java.io imports consolidate");
        List<String> duplicate=Arrays.asList("java.io.*","java.io.File","static java.lang.Math.max");
        check(organized("import java.io.*;\nimport java.io.File;\nimport static java.lang.Math.max;\n",duplicate,duplicate).equals(Arrays.asList("java.io.*","static java.lang.Math.max")),"wildcard redundancy removed, static preserved");
        List<String> dates=Arrays.asList("java.util.Date","java.util.List","java.util.Map","java.util.Set","java.util.ArrayList","java.sql.*","java.awt.*");
        source="";for(String name:dates)source+="import "+name+";\n";
        List<String> result=organized(source,dates,dates);
        check(result.contains("java.util.*")&&result.contains("java.util.Date")&&result.contains("java.util.List"),"Date/List ambiguities retain explicit imports: "+result);
    }
    static Map<String,String> named(List<Map<String,String>> values,String name){return values.stream().filter(v->name.equals(v.get("name"))).findFirst().orElseThrow(()->new AssertionError("missing "+name));}
    static void verifyIconKinds() {
        Map<String,String> field=named(complete("java.io.File.sep|"),"separator");
        check(field.get("kind").equals("field")&&field.get("icon").equals("field")&&field.get("static").equals("true")&&field.get("final").equals("true"),"static final field icon and overlays");
        Map<String,String> method=named(complete("java.io.File.createT|"),"createTempFile");
        check(method.get("icon").equals("method")&&method.get("static").equals("true"),"static method icon");
        check(named(complete("String local=\"\"; loc|"),"local").get("icon").equals("variable"),"local variable icon");
        Map<String,Object> input=request("arg|");input.put("part","run");input.put("source","class Demo {void run(String argument){}}");
        check(named(engine.apply(input),"argument").get("icon").equals("parameter"),"method parameter icon");
        check(named(complete("FileNotFoundExc|"),"FileNotFoundException").get("icon").equals("exception"),"exception lightning icon");
        check(named(complete("Runnab|"),"Runnable").get("icon").equals("interface"),"interface icon");
        check(named(complete("java.util.List<String> values=null; values.ad|"),"add").get("icon").equals("methodAbstract"),"abstract method icon");
        check(named(complete("java.time.DayOfWeek.MO|"),"MONDAY").get("icon").equals("field"),"enum constant field icon");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
