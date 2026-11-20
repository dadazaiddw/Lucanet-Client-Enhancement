package cn.lucanet.enhancement.exit;

import java.util.*;
import javax.swing.*;

public final class CustomJavaSourceTest {
    public static void main(String[] args)throws Exception {
        CustomJavaSource parser=new CustomJavaSource();
        for(String declaration:List.of("@Deprecated public abstract class Example<T> extends java.util.ArrayList<T> implements Runnable { public abstract void run(); }","public interface Example<T> { T value(); }","public @interface Example { String value() default \"中文\"; }","public enum Example { A, B }","public record Example<T>(T value) {}")) {
            Map<String,String> result=parser.apply("package changed.pkg;\n"+declaration);
            check(result.get("qualified").equals("changed.pkg.Example"),"package/type preserved");
        }
        check(parser.apply("class Helper {} public class Primary {} ").get("name").equals("Primary"),"public primary chosen after helper");
        for(String source:List.of("public class Missing {", "class {}", "public class A {} public class B {}", "// class Comment {}"))try{parser.apply(source);throw new AssertionError("accepted invalid source");}catch(IllegalArgumentException expected){}
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
    private static ClassLoader getLoader(){return CustomJavaSourceTest.class.getClassLoader();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
