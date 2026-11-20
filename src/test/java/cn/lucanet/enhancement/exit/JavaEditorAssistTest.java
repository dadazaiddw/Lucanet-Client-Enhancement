package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import javax.swing.*;
import com.lucanet.common.dom.script.java.JavaSourceCodeEditor;

public final class JavaEditorAssistTest {
    private static JavaEditorAssist controller;private static JavaSourceCodeEditor panel;
    private static InputMap original;private static String content;
    public static void main(String[] args)throws Exception {
        Path features=Path.of(System.getProperty("lucanet.enhancement.features"));Files.createDirectories(features.getParent());Files.writeString(features,"javaEditorAssist=true\n");
        EventQueue.invokeAndWait(()->{
            panel=new JavaSourceCodeEditor();original=panel.text.getInputMap();controller=new JavaEditorAssist(()->true);controller.scan(panel);
            check(panel.text.getInputMap()!=original,"enabled editor overlay");
            JPanel unrelated=new JPanel();JTextArea sql=new JTextArea("select *");unrelated.add(sql);InputMap before=sql.getInputMap();controller.scan(unrelated);check(sql.getInputMap()==before,"non-Java isolation");
            panel.text.setText("one\ntwo\nthree");panel.text.setCaretPosition(5);press("control D");check(panel.text.getText().equals("one\ntwo\ntwo\nthree"),"duplicate current line");
            press("control Y");check(panel.text.getText().equals("one\ntwo\nthree"),"delete current line");
            panel.text.setCaretPosition(5);press("alt shift UP");check(panel.text.getText().equals("two\none\nthree"),"move line up");
            press("alt shift DOWN");check(panel.text.getText().equals("one\ntwo\nthree"),"move line down");
            panel.text.selectAll();press("control SLASH");check(panel.text.getText().equals("//one\n//two\n//three"),"line comments");
            press("control SLASH");check(panel.text.getText().equals("one\ntwo\nthree"),"uncomment");
            panel.text.setText("sout");panel.text.setCaretPosition(4);press("TAB");check(panel.text.getText().equals("System.out.println();")&&panel.text.getCaretPosition()==19,"template caret");
            panel.text.setText("if (true) {\nvalue();\n}");panel.text.setCaretPosition(0);press("control alt I");check(panel.text.getText().contains("\n    value();"),"indentation");
            check(!JavaEditing.codeAt("// comment",8)&&!JavaEditing.codeAt("\"hello",4)&&JavaEditing.codeAt("\"hello\";",8),"comment/string suppression");
            String block="String value=\"\"\"\n  { text }\n\"\"\";";check(!JavaEditing.codeAt(block,22),"text block suppression");
            content="String pending = \"unsaved\";";panel.text.setText(content);panel.text.select(7,14);
        });
        Files.writeString(features,"javaEditorAssist=false\n");await(()->panel.text.getInputMap()==original);
        EventQueue.invokeAndWait(()->{check(panel.text.getText().equals(content)&&panel.text.getSelectionStart()==7&&panel.text.getSelectionEnd()==14,"disable preserves document and selection");});
        Files.writeString(features,"javaEditorAssist=true\n");await(()->panel.text.getInputMap()!=original);
        EventQueue.invokeAndWait(()->{panel.text.setEditable(false);check(panel.text.getInputMap()==original,"read-only restores original keys");panel.text.setEditable(true);check(panel.text.getInputMap()!=original,"editable resumes");controller.close();check(panel.text.getInputMap()==original,"hot replacement cleanup");});
        System.out.println("PASS: IDEA editing actions, context isolation, live enable/disable, read-only, selection/document preservation and teardown");
    }
    private static void press(String stroke){Object key=panel.text.getInputMap().get(KeyStroke.getKeyStroke(stroke));Action action=panel.text.getActionMap().get(key);check(action!=null,"binding "+stroke);action.actionPerformed(new ActionEvent(panel.text,0,""));}
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{long until=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<until){boolean[] done={false};EventQueue.invokeAndWait(()->done[0]=condition.getAsBoolean());if(done[0])return;Thread.sleep(25);}throw new AssertionError("Live setting not applied");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
