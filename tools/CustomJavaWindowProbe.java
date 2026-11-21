import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Production chooser/editor + real Lucanet model/widget. Only the surrounding script shell is a fixture. */
public final class CustomJavaWindowProbe {
    static Object controller,assist,editor,binding,script,parent,execution;static JTextComponent text;static FixtureSection section;static JFrame owner;
    static Path evidence,features;static Class<?> model;
    public interface EditMode { boolean isEditMode(); }
    public static final class FixtureSection extends JPanel {
        Object selected,root,context;boolean editable=true;int refreshed,structureChanges;
        List<?> visibleClasses=List.of();
        public boolean isUsable(){return editable;}
        public Object getUIContext(){return context;}
        public Object getSelectedDOMNode(){return selected;}
        public Object getCurrentDOMRoot(){return root;}
        public void refreshDetailPanel(){refreshed++;}
        public void structureChanged(boolean markChanged)throws Throwable{
            check(markChanged,"native create publishes a dirty structure change");
            structureChanges++;
            visibleClasses=List.copyOf((List<?>)call(parent,"getChildren"));
        }
    }
    public static final class InteractionController {
        protected Object getDOMSection(){return section;}
    }
    public static final class InteractionEvent {
        final int type;InteractionEvent(int type){this.type=type;}
        public int getType(){return type;}
        public Object getTreeTableContext(){return new InteractionCell();}
    }
    public static final class InteractionCell {
        public Object getInteractedCell(){return this;}
        public Object getNode(){return section.selected;}
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        evidence=Path.of(args[0]);features=Path.of(System.getProperty("lucanet.enhancement.features"));
        try {
            Class.forName("com.lucanet.common.dom.script.java.JavaCode");
            Class.forName("com.lucanet.common.dom.base.section.AbstractDOMUserInteractionController");edt(()->null);
            controller=UIManager.get("cn.lucanet.enhancement.customJavaTypes.v1");assist=UIManager.get("cn.lucanet.enhancement.javaEditor.v1");
            check(controller!=null&&assist!=null,"controllers installed");
            model=Class.forName("cn.lucanet.enhancement.exit.CustomJavaModel",true,controller.getClass().getClassLoader());
            edt(()->{
                script=create("com.lucanet.common.dom.script.elements.Script");parent=call(script,"getClassRoot");
                Object handler=staticCall(Class.forName("com.lucanet.common.dom.base.node.DOMImportExportHandler"),"createHandlerForDOMExecution");execution=call(handler,"getDOMExecutionContext");
                section=new FixtureSection();section.root=script;section.selected=parent;
                Class<?> contextType=Class.forName("com.lucanet.common.dom.base.DOMExecutionContext");
                section.context=Proxy.newProxyInstance(getLoader(),new Class<?>[]{contextType,EditMode.class},(proxy,method,values)->{
                    if(method.getName().equals("isEditMode"))return section.editable;
                    return call(execution,method.getName(),values==null?new Object[0]:values);
                });
                owner=new JFrame("Lucanet isolated editor test");owner.setContentPane(section);owner.setSize(640,460);owner.setVisible(true);
                check(!(Boolean)call(controller,"dispatchKeyEvent",new KeyEvent(new JTextField(),KeyEvent.KEY_PRESSED,0,InputEvent.ALT_DOWN_MASK,KeyEvent.VK_INSERT,KeyEvent.CHAR_UNDEFINED)),"text inputs do not intercept Alt+Insert");
                check(!(Boolean)call(controller,"createFromSelection",section),"custom Java types default off with no settings file");
                Files.writeString(features,"customJavaTypes=true\n");
                check((Boolean)call(controller,"createFromSelection",section),"explicit enable opens chooser");return null;
            });
            await(()->((JTextField)find((Window)field(controller,"chooser"),"customJavaTypeName")).getFont().getSize()==20,"default chooser font");
            Files.writeString(features,"customJavaTypes=true\njavaCompletionFontSize=26\n");
            await(()->find((Window)field(controller,"chooser"),"customJavaTypeKind").getFont().getSize()==26,"live chooser font");
            edt(()->{JDialog chooser=(JDialog)field(controller,"chooser");capture(chooser,"chooser-");
                JTextField name=(JTextField)find(chooser,"customJavaTypeName");JList<?> kinds=(JList<?>)find(chooser,"customJavaTypeKind");
                check(kinds.getModel().getSize()==7,"all type templates available");name.setText("WindowType");kinds.setSelectedIndex(1);name.postActionEvent();return null;});
            await(()->editors().size()==1,"editor opened from chooser");
            edt(()->{editor=editors().values().iterator().next();text=(JTextComponent)field(editor,"text");return null;});
            String initial=edt(()->text.getText());check(initial.contains("public interface WindowType")&&!initial.contains("End of Class"),"selected interface template has no native section comments");
            await(()->((Map<?,?>)field(assist,"bindings")).containsKey(text),"real native editor enhanced");
            edt(()->{binding=((Map<?,?>)field(assist,"bindings")).get(text);return null;});
            edt(()->{JFrame frame=(JFrame)field(editor,"frame");
                GraphicsConfiguration screen=owner.getGraphicsConfiguration();Rectangle area=new Rectangle(screen.getBounds());Insets reserved=Toolkit.getDefaultToolkit().getScreenInsets(screen);
                area.x+=reserved.left;area.y+=reserved.top;area.width-=reserved.left+reserved.right;area.height-=reserved.top+reserved.bottom;
                Rectangle actual=frame.getBounds();
                check(area.contains(actual)&&Math.abs(actual.getCenterX()-area.getCenterX())<=1&&Math.abs(actual.getCenterY()-area.getCenterY())<=1,"editor centered within owning monitor work area");
                check(actual.width==Math.min(area.width,Math.max(640,(int)Math.round(area.width*0.75)))&&actual.height==Math.min(area.height,Math.max(420,(int)Math.round(area.height*0.70))),"editor uses about half screen area");
                check(frame.getIconImages().size()==2&&frame.getIconImages().get(0).getWidth(null)==16,"editor has packaged Lucanet window icon");
                check(text.getFont().getSize()==26&&find(frame,"customJavaDiagnostics").getFont().getSize()==26,"editor opens with configured font");
                return null;});
            Files.writeString(features,"customJavaTypes=true\njavaCompletionFontSize=20\n");
            await(()->text.getFont().getSize()==20&&((JList<?>)field(binding,"list")).getFont().getSize()==20,"live code and completion font");
            Thread.sleep(1700);check(edt(()->initial.equals(text.getText())),"native template imports unchanged after attachment");
            verifyLibraryCompletion();
            verifyAnnotationCompletion();
            String incomplete="package edited.pkg;\n// import comment\npublic class WindowType {\n    Fil value;\n}\n";
            edt(()->{text.setText(incomplete);text.setCaretPosition(incomplete.indexOf("Fil")+3);key("control SPACE");return null;});
            await(()->has("import=java.io.File"),"full unit class completion");
            edt(()->{select("import=java.io.File");call(binding,"accept");check(text.getText().contains("import java.io.File;")&&text.getText().contains("File value"),"completion imports inside document");return null;});
            Thread.sleep(1100);
            edt(()->{key("control Z");check(incomplete.equals(text.getText()),"one native undo reverts completion and import");return null;});
            String members="package edited.pkg;\npublic class WindowType { void run(){\n    String value=\"hello\"; value.sub\n}}";
            edt(()->{text.setText(members);text.setCaretPosition(members.indexOf("value.sub")+9);key("control SPACE");return null;});
            await(()->has("name=substring"),"full source method completion");
            edt(()->{capture((Window)field(editor,"frame"),"completion-");return null;});
            String params="package edited.pkg; public class WindowType { void run(){ new java.io.File(); }}";
            edt(()->{text.setText(params);text.setCaretPosition(params.indexOf("File(")+5);key("control P");return null;});
            await(()->Boolean.TRUE.equals(field(binding,"parameterPopup"))&&has("kind=constructor"),"full unit parameter hints");
            verifyDiagnosticsAndOverrides();
            verifyGeneration();
            String saved="package edited.pkg;\nimport java.util.List;\n@Deprecated public class WindowType<T> extends java.util.ArrayList<T> implements Runnable {\n    public void run() {}\n    public T first() { return get(0); }\n}\n";
            edt(()->{text.setText(saved);JFrame frame=(JFrame)field(editor,"frame");check(frame.isResizable(),"native maximizable frame");frame.dispatchEvent(new WindowEvent(frame,WindowEvent.WINDOW_CLOSING));return null;});
            await(()->editors().isEmpty(),"close saves and disposes editor");
            Object node=edt(()->((List<?>)call(parent,"getChildren")).get(0));check(saved.equals(call(node,"getSourceCode")),"source saved in native Class node");
            check(section.structureChanges==1&&section.visibleClasses.equals(List.of(node)),"new Class published through the outline structure refresh");
            edt(()->{verifyDetails(node);return null;});
            edt(()->{section.selected=node;check(!((BiPredicate<Object,Object>)controller).test(new InteractionController(),new InteractionEvent(1)),"single click remains native");check(((BiPredicate<Object,Object>)controller).test(new InteractionController(),new InteractionEvent(4)),"double click opens source editor");return null;});
            await(()->editors().size()==1,"reopened");
            edt(()->{editor=editors().values().iterator().next();text=(JTextComponent)field(editor,"text");check(saved.equals(text.getText()),"reopen retains package/imports/body");
                JTextComponent status=(JTextComponent)field(editor,"status");
                check(status.getToolTipText().contains("额外应用程序库")&&red(status),"reopened client-only class shows a red server compatibility warning");
                JFrame frame=(JFrame)field(editor,"frame");
                Dimension originalSize=frame.getSize();
                frame.setSize(frame.getMinimumSize().width,originalSize.height);
                frame.validate();
                Rectangle first=status.modelToView2D(0).getBounds();
                Rectangle last=status.modelToView2D(status.getDocument().getLength()-1).getBounds();
                check(last.y>first.y&&last.y+last.height<=status.getHeight(),"the full warning wraps at minimum window width");
                frame.setSize(originalSize);
                frame.validate();
                capture(frame,"compatibility-");
                ((BiPredicate<Object,Object>)controller).test(new InteractionController(),new InteractionEvent(4));check(editors().size()==1,"double click reuses existing editor");return null;});
            String implementsDraft="package com.lucanet.common.dom.script.rt;\n"
                    +"public class WindowType implements Runnable {\n    public void run() {}\n}\n";
            edt(()->{text.setText(implementsDraft);key("control S");return null;});
            await(()->implementsDraft.equals(savedSource(node)),"Ctrl+S saves the class using implements");
            edt(()->{
                JTextComponent status=(JTextComponent)field(editor,"status");
                check(red(status)&&status.getText().equals("原生客户端和服务器无法还原此类。使用了 implements。请使用额外应用程序库部署此类。")
                                &&!status.getToolTipText().contains("保存脚本"),
                        "Ctrl+S keeps the red, specific implements warning without a script-save prompt");
                section.root=new Object();text.setText(implementsDraft+"// pending edit\n");call(editor,"save",true);return null;
            });
            await(()->((JTextComponent)field(editor,"status")).getText().startsWith("保存失败："),"script switch blocks closing save");
            edt(()->{for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isVisible())window.dispose();
                check(editors().size()==1&&text.getText().endsWith("// pending edit\n")&&implementsDraft.equals(savedSource(node)),"failed save retains draft without overwriting model");section.root=script;return null;});
            Files.writeString(features,"customJavaTypes=false\njavaEditorAssist=true\n");
            await(()->((JTextComponent)field(editor,"status")).getText().contains("入口已关闭"),"live feature disable");
            edt(()->{check(!((BiPredicate<Object,Object>)controller).test(new InteractionController(),new InteractionEvent(4)),"disabled double click passes through");section.selected=parent;check(!(Boolean)call(controller,"createFromSelection",section),"disabled Alt+Insert passes through");text.setText(implementsDraft+"// retained draft\n");((JFrame)field(editor,"frame")).dispatchEvent(new WindowEvent((JFrame)field(editor,"frame"),WindowEvent.WINDOW_CLOSING));return null;});
            await(()->editors().isEmpty(),"disabled draft still saves");check(((String)call(node,"getSourceCode")).endsWith("// retained draft\n"),"disabled saving persists");
            Files.writeString(features,"customJavaTypes=true\njavaEditorAssist=true\n");Thread.sleep(500);
            edt(()->{section.editable=false;check(!(Boolean)call(controller,"createFromSelection",section),"read-only script disallows creation");section.editable=true;return null;});
            check(section.refreshed>=2,"native details refreshed after save");
            verifyLegacySave();
            verifyNativeChange();
            Files.writeString(evidence.resolveSibling("window-"+evidence.getFileName()+".txt"),"PASS production chooser, native widget completion/import/undo/parameters, close-save/reopen, single/double click, live toggle, read-only scope\n");
            System.out.println("PASS: custom Java chooser/editor, native completion and atomic undo, close-save/reopen, click scope and live switch");
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}finally{edt(()->{if(controller instanceof AutoCloseable)((AutoCloseable)controller).close();if(assist instanceof AutoCloseable)((AutoCloseable)assist).close();for(Window window:Window.getWindows())window.dispose();return null;});}
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    static void verifyLegacySave()throws Exception {
        String key="cn.lucanet.enhancement.javaSource.v1";
        String head="package com.lucanet.common.dom.script.rt;\npublic class Test {\n";
        String source=head+"  public int value() {\n    return 40 + 2;\n  }\n}\n";
        String previous=head+"  // ==============================  Methods  ==============================\n"
                +"  public int value() {\n"
                +"    try {\n      CallStack.incAndCheckStackLevel(\"Test.value\");\n\n          return 40+2;\n\n    }\n"
                +"    finally {\n      CallStack.decStackLevel();\n    }\n  }\n"
                +"  // ===========================  End of Class  ===========================\n}\n";
        Object legacy=edt(()->{
            Object node=create("com.lucanet.common.dom.script.elements.classes.ClassElement");
            Object code=staticCall(model,"javaCode",node);
            call(code,"setJavaClassName","Test",null,false);
            call(node,"setIdentifier",null,"Test");
            Map<String,String> labels=new LinkedHashMap<>();
            for(Object language:(List<?>)call(script,"getAvailableLanguages")) {
                labels.put(String.valueOf(call(language,"getID")),"Test");
            }
            call(node,"setAttributeMap",null,Map.of("label",labels,key,Map.of("name","Test","source",previous)));
            call(parent,"addChild",execution,node);
            section.selected=node;
            check(((BiPredicate<Object,Object>)controller).test(new InteractionController(),new InteractionEvent(4)),"legacy class opens");
            editor=editors().get(node);text=(JTextComponent)field(editor,"text");
            check(source.equals(text.getText()),"legacy source hides the native markers and guard and shows formatted statements");
            check(previous.equals(savedSource(node)),"opening the editor leaves the stored legacy source untouched");
            JTextComponent status=(JTextComponent)field(editor,"status");
            check(red(status)&&status.getToolTipText().contains("Ctrl+S"),"unsynchronized legacy source is flagged in red");
            key("control S");
            return node;
        });
        await(()->Boolean.TRUE.equals(staticCall(model,"hasNativeParts",legacy)),"Ctrl+S syncs unchanged legacy class");
        edt(()->{
            Object code=staticCall(model,"javaCode",legacy);
            check("return 40 + 2;".equals(call(call(code,"getMethodPart","value"),"getBody"))&&source.equals(text.getText()),
                    "migration stores the unwrapped, formatted body and preserves the draft");
            String generated=savedSource(legacy);
            check(generated.contains("  Methods  ")&&generated.contains("  End of Class  ")
                            &&generated.contains("CallStack.incAndCheckStackLevel(\"Test.value\");")&&generated.contains("CallStack.decStackLevel();"),
                    "Ctrl+S immediately exposes generated sections and stack guards through the native source getter:\n"+generated);
            JTextComponent status=(JTextComponent)field(editor,"status");
            check(!red(status)&&status.getText().equals("已同步到原生 Class。")&&!status.getToolTipText().contains("保存脚本"),
                    "Ctrl+S shows a normal sync status without a script-save prompt");
            ((JFrame)field(editor,"frame")).dispose();
            return null;
        });
        await(()->editors().isEmpty(),"legacy editor closes");
    }

    @SuppressWarnings("unchecked")
    static void verifyNativeChange()throws Exception {
        edt(()->{
            Object node=section.selected;
            Object code=staticCall(model,"javaCode",node);
            Object method=call(code,"getMethodPart","value");
            BiPredicate<Object,Object> open=(BiPredicate<Object,Object>)controller;
            InteractionController interaction=new InteractionController();
            check(open.test(interaction,new InteractionEvent(4)),"synced class opens");
            editor=editors().get(node);text=(JTextComponent)field(editor,"text");
            check(!red((JTextComponent)field(editor,"status")),"synced status uses the default text color");
            call(method,"setBody","\n        int i=50+2;\n        try {\n            return i;\n        } finally {\n            ++i;\n        }\n\n",execution);
            check(open.test(interaction,new InteractionEvent(4))&&text.getText().contains(" {\n        int i = 50 + 2;\n        try {\n            return i;\n        } finally {\n            ++i;\n        }\n    }"),
                    "clean editor restores formatting after a native save and preserves the user's try/finally");
            check(!text.getText().contains("CallStack"),"editable method body excludes the native compiler wrapper");
            capture((JFrame)field(editor,"frame"),"native-method-edited-");

            String draft=text.getText()+"// unsaved draft\n";
            text.setText(draft);
            call(method,"setBody","return 62;",execution);
            check(open.test(interaction,new InteractionEvent(4))&&draft.equals(text.getText()),"refresh retains a conflicting draft");
            check(red((JTextComponent)field(editor,"status")),"native update conflicting with draft is red");
            key("control S");
            return null;
        });
        await(()->((JTextComponent)field(editor,"status")).getText().contains("保存失败："),"draft save detects the native revision conflict");
        edt(()->{
            for(Window window:Window.getWindows())if(window instanceof JDialog&&window.isVisible())window.dispose();
            check(text.getText().endsWith("// unsaved draft\n")&&savedSource(section.selected).contains("return 62;"),"conflict preserves both edits");
            ((Window)field(editor,"frame")).dispose();
            return null;
        });
        await(()->editors().isEmpty(),"conflict editor closes");
    }

    static boolean red(Component label) {
        Color color=label.getForeground();
        return color.getRed()>color.getGreen()*2&&color.getRed()>color.getBlue()*2;
    }
    static void verifyDiagnosticsAndOverrides()throws Exception {
        String missing="import java.util.Comparator;\npublic class WindowType implements Comparator<String> {\n    \n}\n";
        edt(()->{text.setText(missing);text.setCaretPosition(missing.indexOf("    ")+4);return null;});
        await(()->find((Window)field(editor,"frame"),"customJavaDiagnostics").isVisible(),"unsaved compiler error is shown");
        edt(()->{
            check(text.getHighlighter().getHighlights().length>0,"javac error is underlined");
            check(((JButton)find((Window)field(editor,"frame"),"customJavaDiagnostics")).getText().contains("compare"),"error message names missing method");
            capture((Window)field(editor,"frame"),"diagnostics-");
            key("alt ENTER");return null;
        });
        await(()->has("实现 compare()"),"Alt+Enter offers method fix");
        edt(()->{select("实现 compare()");call(binding,"accept");check(text.getText().contains("int compare(String")&&!text.getText().contains("@java.lang.Override"),"quick fix writes short specialized method");return null;});
        await(()->!find((Window)field(editor,"frame"),"customJavaDiagnostics").isVisible(),"fixed source clears error");
        edt(()->{key("control Z");check(missing.equals(text.getText()),"one undo restores source before quick fix");key("control O");return null;});
        await(()->overridePicker()!=null,"Ctrl+O opens method chooser");
        edt(()->{
            JDialog picker=overridePicker();
            JList<?> methods=(JList<?>)find(picker,"customJavaOverrideMethods");
            for(int i=0;i<methods.getModel().getSize();i++)if(String.valueOf(methods.getModel().getElementAt(i)).contains("name=compare"))methods.setSelectedIndex(i);
            capture(picker,"overrides-");
            ((JButton)find(picker,"customJavaGenerateOverride")).doClick();
            check(text.getText().contains("int compare(String"),"chosen override inserted");
            key("control Z");check(missing.equals(text.getText()),"one undo restores source before Ctrl+O");
            return null;
        });
    }
    static void verifyGeneration()throws Exception {
        verifyGenerationSearch();
        String template="package edited.pkg;\npublic class WindowType<T> {\n    private final T value;\n    private boolean isReady;\n    private int[] counts;\n    \n    public void later() {}\n}\n";
        for(String kind:List.of("constructor","getter","setter","accessors","equalsHashCode","toString")){
            String source=kind.equals("constructor")?template:template.replace("private final T value;","private final T value = null;");
            edt(()->{text.setText(source);text.setCaretPosition(source.indexOf("    \n")+4);key("alt INSERT");return null;});
            await(()->field(binding,"generationMenu") instanceof JPopupMenu&&((JPopupMenu)field(binding,"generationMenu")).isVisible(),"Alt+Insert generation menu");
            edt(()->{
                JPopupMenu menu=(JPopupMenu)field(binding,"generationMenu");check(menu.getFont().getSize()==20,"menu uses configured font");
                JMenuItem item=(JMenuItem)find(menu,"customJavaGenerate-"+kind);check(item!=null,"generation entry "+kind);if(kind.equals("constructor"))capture(menu,"generate-menu-");item.doClick();return null;
            });
            await(()->generationPicker()!=null,"field chooser for "+kind);
            if(kind.equals("constructor")){
                Files.writeString(features,"customJavaTypes=true\njavaCompletionFontSize=24\n");
                await(()->find(generationPicker(),"customJavaGenerationFields").getFont().getSize()==24,"field picker live font change");
                Files.writeString(features,"customJavaTypes=true\njavaCompletionFontSize=20\n");
                await(()->find(generationPicker(),"customJavaGenerationFields").getFont().getSize()==20,"field picker font restored");
            }
            if(kind.equals("equalsHashCode")){
                Files.writeString(features,"customJavaTypes=true\njavaCompletionFontSize=30\n");
                await(()->find(generationPicker(),"customJavaGenerationGetClass").getFont().getSize()==30,"wizard supports maximum live font");
                edt(()->{capture(generationPicker(),"generate-equals-font30-");return null;});
                Files.writeString(features,"customJavaTypes=true\njavaCompletionFontSize=20\n");
                await(()->find(generationPicker(),"customJavaGenerationGetClass").getFont().getSize()==20,"wizard font restored");
            }
            edt(()->{
                JDialog picker=generationPicker();
                if(kind.equals("equalsHashCode")){
                    check(find(picker,"customJavaGenerationGetClass")!=null,"equals wizard starts with template and type comparison");
                    capture(picker,"generate-equals-options-");((JButton)find(picker,"customJavaGenerationNext")).doClick();
                }
                JList<?> fields=(JList<?>)find(picker,"customJavaGenerationFields");
                check(fields.getModel().getSize()==(kind.equals("setter")?2:3)&&fields.getFont().getSize()==20,"eligible instance fields displayed");
                check(picker.getIconImages().size()==2,"generation dialog inherits Lucanet icons");
                capture(picker,"generate-"+kind+"-");
                if(kind.equals("accessors")){
                    fields.setSelectionInterval(0,1);
                    JTextField search=(JTextField)find(picker,"customJavaFieldSearch");search.setText("counts");check(fields.getModel().getSize()==1&&fields.isSelectionEmpty(),"field search retains hidden selections");search.setText("");check(fields.getSelectedIndices().length==2,"selection restored when search cleared");
                }
                if(kind.equals("equalsHashCode")){
                    ((JButton)find(picker,"customJavaGenerationNext")).doClick();
                    check(((JList<?>)find(picker,"customJavaHashFields")).getModel().getSize()==3,"hash step uses equals fields");
                    capture(picker,"generate-hash-fields-");
                    ((JButton)find(picker,"customJavaGenerationBack")).doClick();
                    check(((JList<?>)find(picker,"customJavaGenerationFields")).getSelectedIndices().length==3,"Back preserves field selection");
                    ((JButton)find(picker,"customJavaGenerationNext")).doClick();((JButton)find(picker,"customJavaGenerationNext")).doClick();
                    check(((JList<?>)find(picker,"customJavaNonNullFields")).isSelectionEmpty(),"nullable fields remain safe by default");capture(picker,"generate-nonnull-fields-");
                }
                ((JButton)find(picker,"customJavaGenerateMembers")).doClick();return null;
            });
            String token=kind.equals("constructor")?"public WindowType(":kind.equals("accessors")||kind.equals("getter")?"getValue()":kind.equals("setter")?"setReady(":kind.equals("equalsHashCode")?"int hashCode()":"String toString()";
            await(()->text.getText().contains(token),"generated result for "+kind);
            Thread.sleep(1100); // Let the import watcher run before checking a single-step undo.
            edt(()->{
                String generated=text.getText();
                check(generated.indexOf(token)<generated.indexOf("public void later()"),"generated at original caret before existing method");
                if(kind.equals("accessors"))check(!generated.contains("getCounts()")&&!generated.contains("setValue("),"field selection and final setter guard");
                if(kind.equals("equalsHashCode"))check(generated.contains("import java.util.Objects;")&&generated.contains("import java.util.Arrays;")&&!generated.contains("@java.lang.Override"),"helpers imported and implicit names short");
                capture((Window)field(editor,"frame"),"generated-"+kind+"-");
                key("control Z");check(source.equals(text.getText()),"one native undo removes generation and imports for "+kind);return null;
            });
        }
        verifyConstructorWizard();
        System.out.println("PASS: Alt+Insert menu, field selection, constructor/accessors/equals/hashCode/toString, short names/imports and native atomic undo");
    }
    static void verifyConstructorWizard()throws Exception {
        String source="class Parent { Parent(){} Parent(String name){} }\npublic class WindowType extends Parent {\n final String name; int id;\n    \n void later() {}\n}";
        edt(()->{text.setText(source);text.setCaretPosition(source.indexOf("    \n")+4);key("alt INSERT");return null;});
        edt(()->{((JMenuItem)find((JPopupMenu)field(binding,"generationMenu"),"customJavaGenerate-constructor")).doClick();return null;});
        await(()->generationPicker()!=null,"parent constructor chooser");
        edt(()->{JDialog picker=generationPicker();JList<?> parents=(JList<?>)find(picker,"customJavaSuperConstructor");check(parents.getModel().getSize()==2,"multiple superclass overloads get first step");parents.setSelectedIndex(1);capture(picker,"generate-super-constructor-");((JButton)find(picker,"customJavaGenerationNext")).doClick();JList<?> fields=(JList<?>)find(picker,"customJavaGenerationFields");fields.clearSelection();check(fields.getSelectedIndices().length==1,"blank final field cannot be deselected");((JButton)find(picker,"customJavaGenerateMembers")).doClick();return null;});
        await(()->text.getText().contains("super(name_);"),"chosen parent parameter renamed to avoid field collision");
        edt(()->{check(text.getText().contains("WindowType(String name_, String name)"),"selected parent and required field parameters");key("control Z");check(source.equals(text.getText()),"constructor wizard atomic undo");return null;});
    }
    static void verifyGenerationSearch()throws Exception {
        String source="public class WindowType { String s; int i;\n    \n    public void later() {}\n}";
        edt(()->{text.setText(source);text.setCaretPosition(source.indexOf("    \n")+4);key("alt INSERT");return null;});
        await(()->((JPopupMenu)field(binding,"generationMenu")).isVisible(),"search menu opens");
        edt(()->{
            JPopupMenu menu=(JPopupMenu)field(binding,"generationMenu");typeMenu("s");
            check(!find(menu,"customJavaGenerate-getter").isVisible()&&find(menu,"customJavaGenerate-setter").isVisible()&&find(menu,"customJavaGenerate-constructor").isVisible(),"direct substring search includes constructor and setter");
            capture(menu,"generate-search-");pressMenu(KeyEvent.VK_DELETE,0);check(source.equals(text.getText()),"search and Delete never edit the underlying document");
            typeMenu("zzzz");check(!find(menu,"customJavaGenerate-setter").isVisible(),"empty search results");pressMenu(KeyEvent.VK_ENTER,0);check(menu.isVisible(),"Enter with no results is inert");
            pressMenu(KeyEvent.VK_BACK_SPACE,InputEvent.CTRL_DOWN_MASK);check(find(menu,"customJavaGenerate-getter").isVisible(),"Ctrl+Backspace clears search");
            typeMenu("getter");pressMenu(KeyEvent.VK_ESCAPE,0);check(menu.isVisible()&&find(menu,"customJavaGenerate-constructor").isVisible(),"Escape clears active search");
            pressMenu(KeyEvent.VK_ESCAPE,0);check(!menu.isVisible(),"second Escape closes menu");
            key("alt INSERT");return null;
        });
        edt(()->{JPopupMenu menu=(JPopupMenu)field(binding,"generationMenu");typeMenu("setter");check(find(menu,"customJavaGenerate-setter").isVisible()&&!find(menu,"customJavaGenerate-getter").isVisible(),"reopened menu search");pressMenu(KeyEvent.VK_DOWN,0);pressMenu(KeyEvent.VK_UP,0);pressMenu(KeyEvent.VK_ENTER,0);check(!menu.isVisible(),"Enter closes menu");return null;});
        await(()->generationPicker()!=null,"Enter selects matching command");
        edt(()->{JDialog picker=generationPicker();check(picker.getTitle().contains("Setters"),"keyboard selects setter");((JButton)find(picker,"customJavaGenerationCancel")).doClick();check(source.equals(text.getText()),"cancel leaves source unchanged");return null;});
    }
    static void typeMenu(String query){for(char c:query.toCharArray()){pressMenu(KeyEvent.getExtendedKeyCodeForChar(c),0);KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(text,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,c));}}
    static void pressMenu(int key,int modifiers){KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(text,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),modifiers,key,KeyEvent.CHAR_UNDEFINED));}
    static JDialog generationPicker(){for(Window window:Window.getWindows())if("customJavaGeneration".equals(window.getName())&&window.isShowing())return (JDialog)window;return null;}
    static void verifyLibraryCompletion()throws Exception {
        JavaLibraryProbe library=edt(()->JavaLibraryProbe.attach(script,evidence.getParent()));
        if(library==null)return;
        String incomplete="class WindowType { EasyExc value; }";
        edt(()->{text.setText(incomplete);text.setCaretPosition(incomplete.indexOf("EasyExc")+7);key("control SPACE");return null;});
        await(()->has("import=com.alibaba.excel.EasyExcel"),"full editor sees additional library type");
        edt(()->{
            select("import=com.alibaba.excel.EasyExcel");call(binding,"accept");
            check(text.getText().contains("import com.alibaba.excel.EasyExcel;"),"additional library type is imported into the full source");
            library.detachLibrary();return null;
        });
        await(()->find((Window)field(editor,"frame"),"customJavaDiagnostics").isVisible(),"open editor rechecks against removed library");
        System.out.println("PASS: full-source editor imports types from the active Script library provider and notices removal");
    }
    static void verifyAnnotationCompletion()throws Exception{
        // Exercise automatic dispatch on the real native text component, without Ctrl+Space.
        edt(()->{call(binding,"dismiss");text.setText("public class WindowType {\n    \n}\n");text.setCaretPosition(text.getText().indexOf("    ")+4);text.requestFocusInWindow();return null;});
        await(()->text.isFocusOwner(),"annotation input focus");
        edt(()->{text.replaceSelection("@");return null;});
        await(()->has("name=Override")&&((JPopupMenu)field(binding,"popup")).isVisible(),"typing @ triggers annotation suggestions");
        edt(()->{for(int i=0;i<rows().size();i++)check(String.valueOf(rows().get(i)).contains("icon=annotation"),"annotation candidates only");call(binding,"dismiss");return null;});
        String incomplete="package fixture;\n@Doc\npublic @interface WindowType {}\n";
        edt(()->{text.setText(incomplete);text.setCaretPosition(incomplete.indexOf("@Doc")+4);key("control SPACE");return null;});
        await(()->incomplete.equals(field(binding,"snapshot"))&&has("import=java.lang.annotation.Documented"),"annotation completion with import");
        edt(()->{select("import=java.lang.annotation.Documented");call(binding,"accept");check(text.getText().contains("import java.lang.annotation.Documented;")&&text.getText().contains("@Documented\n"),"annotation accepted with auto import: "+text.getText());return null;});
        Thread.sleep(1100);
        edt(()->{key("control Z");check(incomplete.equals(text.getText()),"one undo restores annotation and import");return null;});
        System.out.println("PASS: native @ auto-completion; annotation-only suggestions, auto import and atomic undo");
    }
    static JDialog overridePicker(){
        for(Window window:Window.getWindows())if("customJavaOverrides".equals(window.getName())&&window.isShowing())return (JDialog)window;
        return null;
    }
    static ClassLoader getLoader(){return CustomJavaWindowProbe.class.getClassLoader();}
    static void verifyDetails(Object node)throws Throwable {
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field singleton=unsafeType.getDeclaredField("theUnsafe");singleton.setAccessible(true);Object unsafe=singleton.get(null);
        Class<?> editorType=Class.forName("com.lucanet.common.dom.script.java.JavaCodeEditor");
        JPanel details=(JPanel)unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,editorType),template=new JPanel(new BorderLayout());
        Method offset=unsafeType.getMethod("objectFieldOffset",Field.class);
        for(Class<?> c=JPanel.class;c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())) {
            long at=(Long)offset.invoke(unsafe,f);String suffix=f.getType().isPrimitive()?f.getType().getName():"Object";suffix=Character.toUpperCase(suffix.charAt(0))+suffix.substring(1);
            Object value=unsafeType.getMethod("get"+suffix,Object.class,long.class).invoke(unsafe,template,at);
            unsafeType.getMethod("put"+suffix,Object.class,long.class,f.getType().isPrimitive()?f.getType():Object.class).invoke(unsafe,details,at,value);
        }
        Object code=staticCall(model,"javaCode",node);
        for(Field f:editorType.getDeclaredFields())if(f.getType()==code.getClass()){f.setAccessible(true);f.set(details,code);}
        call(details,"setUsable",true);check(Boolean.FALSE.equals(call(details,"isUsable")),"managed details cannot overwrite complete source");
        JTree tree=(JTree)find(details,"customJavaStructure");check(tree!=null,"native structure tab shows source outline");
        check(red(find(details,"customJavaDetailsStatus")),"native detail tab uses the same red compatibility warning");
        StringBuilder rows=new StringBuilder();for(int row=0;row<tree.getRowCount();row++)rows.append(tree.getPathForRow(row)).append('\n');
        check(rows.toString().contains("first()")&&rows.toString().contains("run()"),"outline reflects saved generic class methods");
        call(details,"commitChanges");call(details,"javaCodeChanged");check(savedSource(node).contains("first()"),"native commit preserves source");
        Object editController=call(node,"getEditController");Object errors=call(editController,"setElementName",null,"WrongName");
        check(errors instanceof List&&!((List<?>)errors).isEmpty()&&"WindowType".equals(call(node,"getJavaClassName")),"native rename cannot desynchronize source");
        Object ordinary=create("com.lucanet.common.dom.script.elements.classes.ClassElement");Object ordinaryCode=staticCall(model,"javaCode",ordinary);
        for(Field f:editorType.getDeclaredFields())if(f.getType()==code.getClass()){f.setAccessible(true);f.set(details,ordinaryCode);}
        Predicate<Object> detector=(Predicate<Object>)UIManager.get("cn.lucanet.enhancement.javaSource.v1.details");check(!detector.test(details),"ordinary details unchanged");
    }
    static String savedSource(Object node)throws Throwable{return (String)call(node,"getSourceCode");}
    static Map<?,?> editors()throws Throwable{return (Map<?,?>)field(controller,"editors");}
    static DefaultListModel<?> rows()throws Throwable{return (DefaultListModel<?>)field(binding,"model");}
    static boolean has(String token)throws Throwable{for(int i=0;i<rows().size();i++)if(String.valueOf(rows().get(i)).contains(token))return true;return false;}
    static void select(String token)throws Throwable{for(int i=0;i<rows().size();i++)if(String.valueOf(rows().get(i)).contains(token)){((JList<?>)field(binding,"list")).setSelectedIndex(i);return;}throw new AssertionError(token);}
    static void key(String key){Object id=text.getInputMap().get(KeyStroke.getKeyStroke(key));Action action=text.getActionMap().get(id);check(action!=null,"key exists: "+key);action.actionPerformed(new ActionEvent(text,0,""));}
    static Component find(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container)for(Component child:((Container)c).getComponents()){Component found=find(child,name);if(found!=null)return found;}return null;}
    static void capture(Component window,String prefix)throws Exception{BufferedImage bitmap=new BufferedImage(window.getWidth(),window.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=bitmap.createGraphics();window.printAll(graphics);graphics.dispose();ImageIO.write(bitmap,"png",evidence.resolveSibling(prefix+evidence.getFileName()).toFile());}
    static Object field(Object target,String name)throws Throwable{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    static Object create(String name,Object...args)throws Throwable{for(Constructor<?> ctor:Class.forName(name).getDeclaredConstructors())if(matches(ctor.getParameterTypes(),args)){ctor.setAccessible(true);return MethodHandles.lookup().unreflectConstructor(ctor).invokeWithArguments(args);}throw new NoSuchMethodException(name);}
    static Object call(Object target,String name,Object...args)throws Throwable{for(Class<?> c=target.getClass();c!=null;c=c.getSuperclass())for(Method method:c.getDeclaredMethods())if(method.getName().equals(name)&&matches(method.getParameterTypes(),args)){method.setAccessible(true);return MethodHandles.lookup().unreflect(method).bindTo(target).invokeWithArguments(args);}throw new NoSuchMethodException(target.getClass()+"."+name);}
    static Object staticCall(Class<?> type,String name,Object...args)throws Throwable{for(Method method:type.getDeclaredMethods())if(method.getName().equals(name)&&matches(method.getParameterTypes(),args)){method.setAccessible(true);return MethodHandles.lookup().unreflect(method).invokeWithArguments(args);}throw new NoSuchMethodException(name);}
    static boolean matches(Class<?>[] types,Object[] args){if(types.length!=args.length)return false;for(int i=0;i<types.length;i++)if(args[i]!=null&&!types[i].isPrimitive()&&!types[i].isInstance(args[i]))return false;return true;}
    interface ActionWithResult<T>{T run()throws Throwable;}
    static <T>T edt(ActionWithResult<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(()->{try{return action.run();}catch(Throwable error){throw new Exception(error);}});EventQueue.invokeAndWait(task);return task.get();}
    static void await(ActionWithResult<Boolean> condition,String label)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);while(System.nanoTime()<deadline){if(edt(condition))return;Thread.sleep(40);}throw new AssertionError("Timeout: "+label);}
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
}
