package cn.lucanet.enhancement.exit;

import java.util.*;
import javax.swing.*;

public final class CustomJavaSourceTest {
    public static void main(String[] args)throws Exception {
        CustomJavaSource parser=new CustomJavaSource();
        for(String declaration:List.of("@Deprecated public abstract class Example<T> extends java.util.ArrayList<T> implements Runnable { public abstract void run(); }","public interface Example<T> { T value(); }","public @interface Example { String value() default \"中文\"; }","public enum Example { A, B }","public record Example<T>(T value) {}")) {
            Map<String,String> result=parser.apply("package changed.pkg;\n"+declaration,new HashMap<>());
            check(result.get("qualified").equals("changed.pkg.Example"),"package/type preserved");
        }
        check(parser.apply("class Helper {} public class Primary {} ",new HashMap<>()).get("name").equals("Primary"),"public primary chosen after helper");
        for(String source:List.of("public class Missing {", "class {}", "public class Test(){}", "public class A {} public class B {}", "// class Comment {}"))try{parser.apply(source,new HashMap<>());throw new AssertionError("accepted invalid source");}catch(IllegalArgumentException expected){}
        String scriptPackage="package "+CustomJavaSource.SCRIPT_PACKAGE+";\n";
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        parser.apply(scriptPackage+"public class Test {}",parts);
        check(parts.size()==3&&parts.values().stream().allMatch(List::isEmpty),"plain empty class has a native representation");
        for(String declaration:List.of("public class Test extends Exception {}","public interface Test {}",
                "public class Test { Test(){this(0);} Test(int i){} }","public class Test { static {} }")) {
            parts.clear();parser.apply(scriptPackage+declaration,parts);
            check(parts.isEmpty(),"unrepresentable source remains client-only: "+declaration);
        }
        verifyNativeFormatting();
        check(CustomJavaTypes.validName("中文工具")&&!CustomJavaTypes.validName("record")&&!CustomJavaTypes.validName("a.b")&&!CustomJavaTypes.validName("1Bad"),"type names and keyword validation");
        SwingUtilities.invokeAndWait(()->{
            JTextArea text=new JTextArea("package custom;\n// keep comment\npublic class Example { File f; }");text.setCaretPosition(text.getText().indexOf("File")+4);
            int before=text.getCaretPosition();JavaUnitImports.add(text,"java.io.File");
            check(text.getText().contains("import java.io.File;")&&text.getText().substring(0,text.getCaretPosition()).endsWith("File"),"auto import keeps editing caret");
            check(text.getCaretPosition()>before,"caret adjusts for import insertion");
            String snapshot=text.getText();JavaUnitImports.add(text,"java.io.File");check(snapshot.equals(text.getText()),"no duplicate import");
            check(JavaUnitImports.reference("import java.sql.Date; class C{}","java.util.Date",getLoader()).equals("java.util.Date"),"ambiguous imports stay qualified");
            text.setText("package custom;\nimport java.io.File; // file note\nimport java.io.IOException;\nclass C{}\n");
            List<String> imports=JavaUnitImports.names(text.getText());JavaUnitImports.apply(text,imports,List.of("java.io.*"));
            check(text.getText().contains("// file note")&&JavaUnitImports.names(text.getText()).equals(List.of("java.io.*")),"organize preserves comments");
        });
        JavaIntelligence engine=new JavaIntelligence();
        for(String header:List.of("@Deprecated public abstract class Example<T> {","public interface Example { default void run(){","public @interface Example {")) {
            String source="package custom; "+header+"\n"+(header.contains("abstract")?"void run(){":"")+"String value=\"a\"; value.sub";
            if(header.contains("@interface"))source="package custom; public @interface Example { int value() default Integer.MAX_";
            Map<String,Object> input=new HashMap<>();input.put("text",source);input.put("caret",source.length());input.put("compilationUnit",true);
            List<Map<String,String>> values=engine.apply(input);check(values.stream().anyMatch(row->row.get("name").equals(header.contains("@interface")?"MAX_VALUE":"substring")),"full unit completions for "+header);
        }
        engine.close();System.out.println("PASS: custom unit syntax, types, editable packages/imports, comment preservation and semantic completion");
    }

    private static void verifyNativeFormatting() {
        String methods="// "+"=".repeat(30)+"  Methods  "+"=".repeat(30);
        String end="// "+"=".repeat(27)+"  End of Class  "+"=".repeat(27);
        String custom="// ==== Methods ====";
        String source="public class Example {\r\n  "+methods+"\r\n  "+custom+"\r\n"
                +"  String text = \"\"\"\r\n  "+methods+"\r\n  \"\"\";\r\n"
                +"  /*\r\n  "+end+"\r\n  */\r\n"
                +"  void run() {\r\n    "+end+"\r\n    try { first(); } finally { cleanup(); }\r\n  }\r\n"
                +"  "+end+"\r\n}\r\n";
        String cleaned=CustomJavaFormatting.withoutSectionComments(source);
        check(cleaned.equals(source.replaceFirst("  "+java.util.regex.Pattern.quote(methods)+"\\r\\n", "")
                        .replace("  "+end+"\r\n}\r\n","}\r\n")),
                "strip exact scaffolding only at member level, preserving text blocks and hand-written comments");

        String raw="\r\n\r\n\t\tint i = 1;\r\n\t\tif (i > 0) {\r\n\t\t\t++i;\r\n\t\t}\r\n\r\n\t\treturn i;\r\n  \r\n\t";
        String body="int i = 1;\nif (i > 0) {\n\t++i;\n}\n\nreturn i;";
        check(CustomJavaFormatting.nativeBody(raw).equals(body),"remove method margin and edge blank lines, keep tabs at relative indent");
        String visible=CustomJavaFormatting.editorBody(body);
        check(visible.equals("        int i = 1;\n        if (i > 0) {\n        \t++i;\n        }\n\n        return i;"),"restore the class editor's method indent");
        check(CustomJavaFormatting.nativeBody("\n"+visible+"\n    ").equals(body),"indent round trips without accumulating");
        check(CustomJavaFormatting.nativeBody(" first(); ").equals("first();"),"inline method removes spaces beside the braces");
        check(CustomJavaFormatting.nativeBody(" return format(\n            value);\n    ").equals("return format(\n    value);"),
                "inline statement preserves continuation indentation");
        check(CustomJavaFormatting.nativeBody("\n\t \n  ").isEmpty(),"blank method has no body padding");

        String constructor="    public Example(String s, int i) {\n"
                +"        this.s=s+\" world\";\n"
                +"        this.i=i+1;\n"
                +"        int n=-1;\n"
                +"        n+=i++;\n"
                +"        List<List<String>> values = null;\n"
                +"        // Keep a+b and the whitespace inside the string.\n"
                +"        String label=\"a+b\";\n"
                +"        int other=n /*+*/ +\n            i;\n"
                +"    }\n";
        String spaced=constructor.replace("this.s=s+","this.s = s + ").replace("this.i=i+1","this.i = i + 1")
                .replace("n=-1","n = -1").replace("n+=i++","n += i++").replace("label=","label = ").replace("other=n","other = n");
        String header="package "+CustomJavaSource.SCRIPT_PACKAGE+";\npublic class Example {\n";
        Map<String,List<Map<String,String>>> parts=new HashMap<>();
        CustomJavaSource parser=new CustomJavaSource();
        String normalized=parser.apply(header+constructor+"}\n",parts).get(CustomJavaSource.FORMATTED_SOURCE);
        check((header+spaced+"}\n").equals(normalized),"format operators without changing comments, literals, generic delimiters or line breaks");
        check(parser.apply(normalized,parts).get(CustomJavaSource.FORMATTED_SOURCE)==null,"method formatting is idempotent");
    }

    private static ClassLoader getLoader(){return CustomJavaSourceTest.class.getClassLoader();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
