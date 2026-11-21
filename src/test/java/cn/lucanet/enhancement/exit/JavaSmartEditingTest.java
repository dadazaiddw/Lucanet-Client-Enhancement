package cn.lucanet.enhancement.exit;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import javax.tools.ToolProvider;

public final class JavaSmartEditingTest {
    private static JavaIntelligence engine;private static Path root;
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]);Files.createDirectories(root);
        try(JavaIntelligence service=new JavaIntelligence()){
            engine=service;variables();completions();
        }
        SwingUtilities.invokeAndWait(()->{pairs();calls();});
        System.out.println("PASS: inferred variable declarations compile; semantic calls, pair wrapping/skipping/deletion, lexical boundaries and newline caret");
    }
    private static void variables()throws Exception{
        variable("", "\"a|b\"", "String text", "");
        variable("int number=0;", "(1 | 2)", "int number1", "int number2=0;");
        variable("", "new java.util.ArrayList<String>()", "ArrayList<String> strings", "");
        variable("", "java.util.List.of(\"x\")", "List<String> strings", "");
        variable("", "new String[0]", "String[] strings", "");
        variable("String customer=\"\";", "getCustomer()", "String customer1", "");
        variable("", "getURL()", "java.net.URL url", "");
        variable("", "isReady()", "boolean ready", "");
        variable("java.util.List<? extends Number> numbers=null;", "numbers.get(0)", "Number number", "");
        variable("java.util.List<? super String> items=null;", "items.get(0)", "Object object", "");
        variable("", "new Runnable(){public void run(){}}", "Runnable runnable", "");
        variable("", "java.util.List.of(1,\"x\")", null, "");
        variable("class Local {}", "new Local()", "Local local", "");
        variable("", "new Object()", "Object object", "");
        String generic="class Demo<T>{void run(T input){input.var;}}";check(expand(generic).contains("T input1 = input;"),"named type parameter retained");
        expand("class Demo<T>{class Inner<U>{} void run(){new Demo<String>().new Inner<Integer>().var;}}");
        String conflict="class Demo{static class List{} void run(){java.util.List.of(\"x\").var;}}";
        check(expand(conflict).contains("java.util.List<String> strings"),"conflicting simple type remains qualified");
        String hidden="class Factory {private static class Hidden extends java.util.ArrayList<String>{} static Hidden create(){return null;} } class Demo{void run(){Factory.create().var;}}";
        expand(hidden);
        String nested="class Factory {private static class Hidden {} static java.util.List<java.util.List<Hidden>> create(){return null;} } class Demo{void run(){Factory.create().var;}}";
        expand(nested);
        expand("class Factory {private static class Hidden {} static java.util.List<? super Hidden> create(){return null;} } class Demo{void run(){Factory.create().var;}}");
        Map<String,Object> part=new HashMap<>();String body="java.util.List.of(\"x\").var;";part.put("text",body);part.put("caret",body.indexOf(".var")+4);
        Map<String,String> row=named(engine.apply(part),"var");check(row.get("insert").startsWith("java.util.List<String> strings")&&!row.containsKey("imports"),"part editor uses qualified types without method-body imports");
        part.put("source","import java.util.*; class Demo {void run(){}} ");part.put("part","run");
        check(named(engine.apply(part),"var").get("insert").startsWith("List<String> strings"),"part editor respects existing wildcard imports");
        String illegal="class Demo {void run(){super.var;}}";check(request(illegal,illegal.indexOf(".var")+4).stream().noneMatch(item->"var".equals(item.get("name"))),"super cannot be used as a variable initializer");
    }
    private static void variable(String before,String expression,String declaration,String after)throws Exception{
        String source="class Demo {String getCustomer(){return null;} java.net.URL getURL(){return null;} boolean isReady(){return true;} void run(){"+before+expression+".var;"+after+"}}";
        Map<String,String> row=named(request(source,source.indexOf(".var")+4),"var");
        if(declaration!=null){String expected=declaration.replace("java.net.URL","URL");check(row.get("insert").startsWith(expected+" = "),"inferred declaration "+expected+": "+row);}
        String name=row.get("insert").substring(Integer.parseInt(row.get("selectionStart")),Integer.parseInt(row.get("caret")));
        check(!name.isEmpty()&&row.get("insert").contains(" "+name+" = "),"selection exactly covers generated variable");expand(source);
    }
    private static String expand(String source)throws Exception{
        Map<String,String> row=named(request(source,source.indexOf(".var")+4),"var");
        int start=Integer.parseInt(row.get("replaceStart")),end=Integer.parseInt(row.get("replaceEnd"));if(source.charAt(end)==';')end++;
        JTextArea area=new JTextArea(source.substring(0,start)+row.get("insert")+source.substring(end));
        for(String name:row.getOrDefault("imports","").split("\n"))if(!name.isBlank())JavaUnitImports.add(area,name);
        Path file=root.resolve("Demo.java");Files.writeString(file,area.getText());
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",root.toString(),file.toString())==0,"generated declaration compiles:\n"+area.getText());return area.getText();
    }
    private static void completions(){
        String source="class Demo{void run(){new ArrayL;}}";Map<String,String> row=named(request(source,source.indexOf("ArrayL")+6),"ArrayList");
        check("constructor".equals(row.get("call"))&&row.get("insert").equals("ArrayList()"),"new completion marks constructor context");
        source="class Demo{ArrayL value;}";row=named(request(source,source.indexOf("ArrayL")+6),"ArrayList");
        check(!row.containsKey("call")&&row.get("insert").equals("ArrayList"),"type declarations do not gain parentheses");
        source="class Demo{void run(){String s=\"\";s.toS;}}";row=named(request(source,source.indexOf(".toS")+4),"toString");
        check("method".equals(row.get("call"))&&row.get("caret").equals("9"),"zero-argument completion caret inside parentheses");
        source="class Demo{java.util.function.Supplier<String> f=\"\"::toS;}";row=named(request(source,source.indexOf("::toS")+5),"toString");
        check(!row.containsKey("call")&&row.get("insert").equals("toString"),"method references do not gain call parentheses");
        source="class Demo{java.util.function.Function<Integer,String> f=\"\"::valueO;}";check(request(source,source.indexOf("::valueO")+8).stream().noneMatch(item->"valueOf".equals(item.get("name"))),"static methods are not offered on bound instance references");
        source="class Demo{void run(){new java.util.ArrayL;}}";row=named(request(source,source.indexOf("ArrayL")+6),"ArrayList");
        check("constructor".equals(row.get("call"))&&row.get("import").isEmpty(),"qualified constructor keeps existing package without duplicate import");
    }
    private static List<Map<String,String>> request(String source,int caret){return engine.apply(Map.of("text",source,"caret",caret,"compilationUnit",true));}
    private static Map<String,String> named(List<Map<String,String>> rows,String name){return rows.stream().filter(r->name.equals(r.get("name"))).findFirst().orElseThrow(()->new AssertionError("missing "+name+": "+rows));}
    private static void pairs(){
        for(String pair:List.of("()","[]","{}","\"\"","''")){
            JTextArea area=new JTextArea();JavaEditing.typePair(area,pair.charAt(0));check(area.getText().equals(pair)&&area.getCaretPosition()==1,"paired input "+pair);
            JavaEditing.typePair(area,pair.charAt(1));check(area.getText().equals(pair)&&area.getCaretPosition()==2,"skip closer "+pair);
            area.setCaretPosition(1);check(JavaEditing.backspacePair(area)&&area.getText().isEmpty(),"paired backspace "+pair);
            area.setText("selected");area.selectAll();JavaEditing.typePair(area,pair.charAt(0));check(area.getText().equals(pair.charAt(0)+"selected"+pair.charAt(1))&&"selected".equals(area.getSelectedText()),"selection wrapping "+pair);
        }
        for(String prefix:List.of("// comment ","/* comment ","\"string ","'", "\"\"\"\ntext ")){
            JTextArea area=new JTextArea(prefix);area.setCaretPosition(prefix.length());JavaEditing.typePair(area,'(');check(area.getText().equals(prefix+"("),"no pairing inside literals/comments");
        }
        JTextArea area=new JTextArea("String s=\"a\\\"b\";");area.setCaretPosition(area.getText().indexOf('b'));JavaEditing.typePair(area,'(');check(area.getText().contains("a\\\"(b"),"escaped quote keeps string context");
        area.setText("\"\"");area.setCaretPosition(2);JavaEditing.typePair(area,'"');check(area.getText().equals("\"\"\""),"third quote starts text block");
        area.setText(")");area.setCaretPosition(0);JavaEditing.typePair(area,')');check(area.getText().equals("))"),"unmatched closer is not swallowed");
        area.setText("a\"b\n");area.selectAll();JavaEditing.typePair(area,'"');check(area.getText().equals("\"a\\\"b\\n\""),"quote wrapping escapes content");
        for(String newline:List.of("\n","\r\n")){
            area.setTabSize(4);
            area.setText("class Demo {"+newline+"    void run(){ }");int at=area.getText().lastIndexOf('{')+1;area.setCaretPosition(at);
            check(JavaEditing.enterPair(area),"brace Enter handled");check(area.getText().endsWith("void run(){"+newline+"        "+newline+"    }")&&area.getCaretPosition()==at+newline.length()+8,"brace Enter indentation/caret/line endings");
        }
    }
    private static void calls(){
        call("sub",3,"substring",false,"substring()",10);
        call("sub(1,2)",3,"substring",false,"substring(1,2)",10);
        call("sub /*keep*/ (1)",3,"substring",false,"substring /*keep*/ (1)",20);
        call("sub(",3,"substring",false,"substring()",10);
        call("File<>(\"x\")",4,"File",true,"File<>(\"x\")",7);
        call("ArrayL<String>",6,"ArrayList",true,"ArrayList<String>()",18);
        call("String[3]",6,"String",true,"String[3]",6);
        call("ArrayL<",6,"ArrayList",true,"ArrayList<",9);
    }
    private static void call(String source,int end,String name,boolean constructor,String expected,int caret){
        JavaEditing.CallInsertion plan=JavaEditing.callInsertion(source,end,name,constructor);
        check((plan.text+source.substring(plan.end)).equals(expected)&&plan.caret==caret,"call insertion "+source+": "+plan.text+source.substring(plan.end)+" caret="+plan.caret);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
