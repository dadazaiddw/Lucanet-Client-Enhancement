import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;

/** Real native syntax widget / document / undo, isolated from business sessions. */
public final class JavaEditorRealProbe {
    static final String PKG="com.lucanet.common.dom.script.java.";
    static Object controller,binding,syntax;static JTextComponent text;static JPanel root;static JFrame frame;
    static Object unsafe;static Method allocate;static InputMap original;
    static Object nativeAdapter,nativeImports;static Method nativeAdd;
    static Class<?> nativeImport,nativeEditability;
    public static void main(String[] args)throws Exception {
        try {
            Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");Field singleton=unsafeClass.getDeclaredField("theUnsafe");singleton.setAccessible(true);unsafe=singleton.get(null);allocate=unsafeClass.getMethod("allocateInstance",Class.class);
            Class.forName(PKG+"JavaSourceCodeEditor",false,ClassLoader.getSystemClassLoader());edt(()->null);
            controller=UIManager.get("cn.lucanet.enhancement.javaEditor.v1");check(controller!=null,"agent detects real editor class");
            edt(()->{build();return null;});
            edt(()->{invoke(controller,"scan",Component.class,root);original=text.getInputMap().getParent();return null;});
            binding=edt(()->{Map<?,?> bindings=(Map<?,?>)field(controller,"bindings");check(bindings.size()==1,"only source editor text is enhanced");return bindings.values().iterator().next();});
            if(Boolean.getBoolean("lucanet.probe.completionLatency")) {
                verifyCompletionLatency(Path.of(args[0]));
                return;
            }
            edt(()->{text.setText("String text = \"hello\";\ntext.sub");text.setCaretPosition(text.getDocument().getLength());invoke(binding,"request",new Class<?>[]{String.class,boolean.class},"complete",true);return null;});
            await(()->hasSubstring());
            edt(()->{ListModel<?> m=model();for(int i=0;i<m.getSize();i++)if(String.valueOf(m.getElementAt(i)).contains("substring(int")){((JList<?>)field(binding,"list")).setSelectedIndex(i);break;}invoke(binding,"accept");check(text.getText().contains("text.substring()"),"completion inserts native document");return null;});
            edt(()->{key("control Z");check(text.getText().endsWith("text.sub"),"one native undo reverts full completion");key("control shift Z");check(text.getText().endsWith("text.substring()"),"IDEA redo delegates native history");return null;});
            edt(()->{text.setText("String value = \"hello\"; value.substring(");text.setCaretPosition(text.getDocument().getLength());invoke(binding,"request",new Class<?>[]{String.class,boolean.class},"parameters",true);return null;});
            await(()->hasSubstring() && Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.setText("new java.io.File()");text.setCaretPosition(text.getText().length()-1);key("control P");return null;});
            await(()->has("kind=constructor")&&model().size()==4);
            Path evidence=Path.of(args[0]);capture(evidence.resolveSibling("parameters-"+evidence.getFileName()));
            Path features=Path.of(System.getProperty("lucanet.enhancement.features"));
            Files.writeString(features,"javaEditorAssist=true\njavaCompletionFontSize=24\n");
            await(()->((JList<?>)field(binding,"list")).getFont().getSize()==24);
            edt(()->{check(((JPopupMenu)field(binding,"popup")).isVisible()&&model().size()==4,"font changes on already open parameter popup");return null;});
            Files.writeString(features,"javaEditorAssist=true\njavaCompletionFontSize=18\n");
            await(()->((JList<?>)field(binding,"list")).getFont().getSize()==18);
            edt(()->{text.setText("java.io.File.new");text.setCaretPosition(text.getText().length());key("TAB");return null;});
            await(()->text.getText().equals("new java.io.File()"));
            edt(()->{check(text.getCaretPosition()==text.getText().length()-1,"postfix constructor caret");key("control Z");check(text.getText().equals("java.io.File.new"),"postfix expansion is one native undo");return null;});
            edt(()->{text.setText("String value=\"used\"; \"a|b\".var;");text.setCaretPosition(text.getText().length()-1);key("TAB");return null;});
            await(()->text.getText().equals("String value=\"used\"; String text = \"a|b\";"));
            edt(()->{check("text".equals(text.getSelectedText()),"var selects an inferred name and keeps the expression");key("control Z");check(text.getText().equals("String value=\"used\"; \"a|b\".var;"),"var is a single native undo including the existing semicolon");return null;});
            verifySmartInput();
            verifyCallCompletion();
            // Test isolated compiler loader under an actual bootstrap agent, including SDK classpath.
            Map<String,Object> request=new HashMap<>();String completionText="String value=\"abc\"; value.sub";request.put("text",completionText);request.put("caret",completionText.length());request.put("classpath",System.getProperty("java.class.path"));
            @SuppressWarnings("unchecked") List<Map<String,String>> result=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,request);
            check(result.stream().anyMatch(v->v.get("name").equals("substring")),"jdk.compiler visible behind bootstrap hot module");
            edt(()->{verifyNativeModels();return null;});
            captureRows(evidence.resolveSibling("custom-class-"+evidence.getFileName()));
            String beforeImports=edt(()->text.getText());
            edt(()->{addNativeImport("java.net.URI");addNativeImport("java.net.URL");return null;});
            await(()->importNames().contains("java.net.*"));
            edt(()->{check(!importNames().contains("java.net.URI")&&text.getText().equals(beforeImports),"manual import tree changes auto consolidate without editing document");return null;});
            String saved=edt(()->text.getText());
            Files.writeString(features,"javaEditorAssist=false\n");await(()->text.getInputMap()==original);
            check(edt(()->text.getText().equals(saved)),"live disable preserves native document");
            edt(()->{addNativeImport("java.nio.file.Path");addNativeImport("java.nio.file.Files");return null;});
            Thread.sleep(1400);
            check(edt(()->!importNames().contains("java.nio.file.*")),"disabled feature leaves manually entered imports unchanged");
            Files.writeString(features,"javaEditorAssist=true\n");await(()->text.getInputMap()!=original);
            await(()->importNames().contains("java.nio.file.*"));
            edt(()->{frame.setContentPane(root);return null;});
            edt(()->{text.setText("java.util.List<String> names = new java.util.ArrayList<>();\nnames.get(0).sub");text.setCaretPosition(text.getDocument().getLength());invoke(binding,"request",new Class<?>[]{String.class,boolean.class},"complete",true);return null;});
            await(()->hasSubstring());
            capture(evidence);
            edt(()->{text.setText("File");text.setCaretPosition(4);key("control SPACE");return null;});
            await(()->has("java.io.File")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
            capture(evidence.resolveSibling("classes-"+evidence.getFileName()));
            edt(()->{text.setText("java.io.File.");text.setCaretPosition(text.getText().length());key("control SPACE");return null;});
            await(()->has("pathSeparator")&&has("createTempFile"));
            capture(evidence.resolveSibling("members-"+evidence.getFileName()));
            edt(()->{text.setText("List");text.setCaretPosition(4);key("control SPACE");return null;});
            await(()->has("typeParameters=<E>")&&has("java.util.List"));
            capture(evidence.resolveSibling("generics-"+evidence.getFileName()));
            edt(()->{text.setText("java.util.List.of");text.setCaretPosition(text.getText().length());key("control SPACE");return null;});
            await(()->has("name=of")&&has("detail=List<E>")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
            capture(evidence.resolveSibling("long-signature-"+evidence.getFileName()));
            verifySelectedSignature(evidence);
            verifyAutomaticArguments(evidence);
            verifyLineEndings();
            System.out.println("PASS: actual Lucanet widget, automatic argument completion after parameter hints, Ctrl+P, generics/alignment, native imports, source/Class snapshots, postfix undo and live settings");
        }finally{edt(()->{if(controller instanceof AutoCloseable)((AutoCloseable)controller).close();Object types=UIManager.get("cn.lucanet.enhancement.customJavaTypes.v1");if(types instanceof AutoCloseable)((AutoCloseable)types).close();if(frame!=null)frame.dispose();return null;});}
    }
    static void verifyLineEndings()throws Exception {
        edt(()->{
            invoke(binding,"dismiss");String original="int first = 1;\r\n    int last = 2;";
            text.setText(original);text.setCaretPosition(0);key("control shift J");
            check(text.getText().equals("int first = 1; int last = 2;"),"native CRLF join removes both separator characters");
            key("control Z");check(text.getText().equals(original),"native undo restores CRLF source exactly");
            text.setCaretPosition(text.getDocument().getLength());key("alt shift UP");
            check(text.getText().equals("    int last = 2;\r\nint first = 1;"),"native move keeps CRLF for final line");
            key("control Z");check(text.getText().equals(original),"native move undo restores original source");
            return null;
        });
        System.out.println("PASS: native CRLF join/move and exact single-step undo");
    }
    static void verifySmartInput()throws Exception {
        edt(()->{
            invoke(binding,"dismiss");
            for(String pair:List.of("()","[]","{}","\"\"","''")){
                text.setText("");key("typed "+pair.charAt(0));check(text.getText().equals(pair)&&text.getCaretPosition()==1,"native pair and caret "+pair);
                key("control Z");check(text.getText().isEmpty(),"pair inserted as one undo "+pair);key("control shift Z");
                text.setCaretPosition(1);key("typed "+pair.charAt(1));check(text.getText().equals(pair)&&text.getCaretPosition()==2,"native closer skip "+pair);
                key("control Z");check(text.getText().isEmpty(),"skipping closer adds no undo step "+pair);
                key("typed "+pair.charAt(0));key("BACK_SPACE");check(text.getText().isEmpty(),"native paired backspace "+pair);
                key("control Z");check(text.getText().equals(pair),"pair deletion is one undo "+pair);
                text.setText("selected");text.selectAll();key("typed "+pair.charAt(0));check(text.getText().equals(pair.charAt(0)+"selected"+pair.charAt(1))&&"selected".equals(text.getSelectedText()),"native selection wrap "+pair);
                key("control Z");check(text.getText().equals("selected"),"selection wrap undo "+pair);
            }
            text.setText("{}");text.setCaretPosition(1);key("ENTER");String expanded=text.getText();
            check(expanded.startsWith("{\n")&&expanded.endsWith("\n}")&&text.getCaretPosition()==expanded.lastIndexOf('\n'),"native brace Enter caret/indent");
            key("control Z");check(text.getText().equals("{}"),"brace Enter undo");
            text.setText("// comment ");text.setCaretPosition(text.getText().length());key("typed (");check(text.getText().equals("// comment ("),"native comments are not paired");
            text.setText("\"\"\"\nblock ");text.setCaretPosition(text.getText().length());key("typed \"");check(text.getText().equals("\"\"\"\nblock \""),"native text block quotes remain literal");
            text.setText("paste");text.selectAll();text.replaceSelection("({\"[");check(text.getText().equals("({\"["),"paste is not transformed");
            return null;
        });
        System.out.println("PASS: native paired typing, wrapping, closer skipping, pair deletion, Enter, paste and atomic undo");
    }
    static void verifyCallCompletion()throws Exception {
        String[][] cases={
            {"String s=\"x\"; s.sub#(0);","substring","String s=\"x\"; s.substring(0);"},
            {"String s=\"x\"; s.toS#;","toString","String s=\"x\"; s.toString();"},
            {"new ArrayL#","ArrayList","new java.util.ArrayList()"},
            {"new java.util.ArrayL#<String>()","ArrayList","new java.util.ArrayList<String>()"}
        };
        for(String[] sample:cases){
            String original=sample[0].replace("#","");int caret=sample[0].indexOf('#');
            edt(()->{text.setText(original);text.setCaretPosition(caret);key("control SPACE");return null;});
            await(()->original.equals(field(binding,"snapshot"))&&has("name="+sample[1])&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{for(int i=0;i<model().size();i++)if(sample[1].equals(((Map<?,?>)model().get(i)).get("name"))){((JList<?>)field(binding,"list")).setSelectedIndex(i);break;}
                invoke(binding,"accept");check(text.getText().equals(sample[2]),"call completion preserves arguments and generics: "+text.getText());
                check(text.getText().charAt(text.getCaretPosition()-1)=='(',"call caret inside existing/new parentheses");
                key("control Z");check(text.getText().equals(original),"call completion one undo");return null;});
        }
        System.out.println("PASS: native method/constructor completion, existing arguments, generic arguments and caret/undo");
    }
    static void verifyCompletionLatency(Path evidence)throws Exception {
        KeyboardFocusManager previous=KeyboardFocusManager.getCurrentKeyboardFocusManager();
        edt(()->{KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager(){@Override public Component getFocusOwner(){return text;}});return null;});
        try {
            long[] samples=new long[20];long cold=0;
            for(int i=-5;i<samples.length;i++) {
                long start=edt(()->{
                    text.setText("String name = \"hello\"; name");text.setCaretPosition(text.getDocument().getLength());
                    JPopupMenu popup=(JPopupMenu)field(binding,"popup");popup.setVisible(false);
                    long at=System.nanoTime();text.replaceSelection(".sub");return at;
                });
                long elapsed=0;
                while(elapsed==0&&System.nanoTime()-start<TimeUnit.SECONDS.toNanos(20)) {
                    elapsed=edt(()->((JPopupMenu)field(binding,"popup")).isVisible()&&hasSubstring()?System.nanoTime()-start:0L);
                    if(elapsed==0)Thread.sleep(5);
                }
                check(elapsed>0,"typing opens completion popup");
                if(i==-5)cold=elapsed;
                if(i>=0)samples[i]=elapsed;
            }
            Arrays.sort(samples);
            String report=String.format(Locale.ROOT,"Typing to member popup: cold %.1f ms; warm median %.1f ms, p95 %.1f ms (n=%d)%n",cold/1e6,(samples[9]+samples[10])/2e6,samples[18]/1e6,samples.length);
            Files.writeString(evidence.resolveSibling("typing-"+evidence.getFileName()+".txt"),report);System.out.print(report);
        }finally{edt(()->{invoke(binding,"dismiss");KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);return null;});}
    }
    static void verifySelectedSignature(Path evidence)throws Exception {
        Path features=Path.of(System.getProperty("lucanet.enhancement.features"));Files.writeString(features,"javaEditorAssist=true\njavaCompletionFontSize=30\n");
        await(()->((JList<?>)field(binding,"list")).getFont().getSize()==30);
        edt(()->{text.setText("java.util.Map<String,String> map = new java.util.HashMap<>();\nmap.com");text.setCaretPosition(text.getText().length());key("control SPACE");return null;});
        await(()->has("name=computeIfAbsent")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
        edt(()->{JList<?> list=(JList<?>)field(binding,"list");for(int i=0;i<model().size();i++)if("computeIfAbsent".equals(((Map<?,?>)model().get(i)).get("name")))list.setSelectedIndex(i);return null;});
        await(()->expandedSignature("computeIfAbsent"));
        captureExpansion(evidence.resolveSibling("selected-signature-"+evidence.getFileName()));
        verifyNavigationPerformance(evidence);
        Dimension before=edt(()->((JPopupMenu)field(binding,"popup")).getSize());
        edt(()->{JList<?> list=(JList<?>)field(binding,"list");for(int i=0;i<model().size();i++)if("computeIfPresent".equals(((Map<?,?>)model().get(i)).get("name")))list.setSelectedIndex(i);return null;});
        await(()->expandedSignature("computeIfPresent"));
        edt(()->{
            JPopupMenu popup=(JPopupMenu)field(binding,"popup");check(popup.getSize().equals(before),"selection expansion leaves base lookup size fixed");
            JComponent expanded=(JComponent)field(popup,"expandedView");JLabel main=findLabel(expanded,"lookupMain");
            main.dispatchEvent(new MouseEvent(main,MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,2,2,1,false,MouseEvent.BUTTON1));
            check(popup.isVisible(),"clicking expanded row does not dismiss original popup");
            main.dispatchEvent(new MouseEvent(main,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,2,2,2,false,MouseEvent.BUTTON1));
            check(text.getText().endsWith("map.computeIfPresent()"),"double-click expanded signature inserts original candidate");
            check(field(popup,"expandedView")==null&&field(popup,"expansionWindow")==null,"accepted candidate removes expanded surface");
            return null;
        });
        edt(()->{text.setText("java.util.Map<String,String> map = new java.util.HashMap<>();\nmap.com");text.setCaretPosition(text.getText().length());key("control SPACE");return null;});
        await(()->expandedSignature("compute"));
        Files.writeString(features,"javaEditorAssist=true\njavaCompletionFontSize=24\n");
        await(()->{Object view=field(field(binding,"popup"),"expandedView");return view instanceof JComponent&&findLabel((JComponent)view,"lookupMain").getFont().getSize()==24;});
        Files.writeString(features,"javaEditorAssist=true\njavaCompletionFontSize=18\n");
        await(()->((JList<?>)field(binding,"list")).getFont().getSize()==18);
        edt(()->{invoke(binding,"dismiss");check(field(field(binding,"popup"),"expandedView")==null,"dismiss cancels expanded signature");return null;});
        Thread.sleep(300);
        edt(()->{check(field(field(binding,"popup"),"expandedView")==null,"delayed preview does not reopen after dismiss");return null;});
    }
    static void verifyNavigationPerformance(Path evidence)throws Exception {
        long[] samples=new long[120];Object[] window={null};int[] hides={0};
        java.util.Set<Object> views=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int i=0;i<samples.length+12;i++) {
            final int direction=i%4<2?KeyEvent.VK_DOWN:KeyEvent.VK_UP;
            long start=edt(()->{
                long at=System.nanoTime();KeyEvent event=new KeyEvent(text,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,direction,KeyEvent.CHAR_UNDEFINED);
                check(((KeyEventDispatcher)field(binding,"popupKeys")).dispatchKeyEvent(event)&&event.isConsumed(),"production arrow dispatcher handles selection");return at;
            });
            // An event-queue barrier, without sleeping: the selected signature must update in
            // the next EDT turn instead of waiting for the old 120 ms timer.
            long elapsed=edt(()->{
                JPopupMenu popup=(JPopupMenu)field(binding,"popup");Map<?,?> selected=(Map<?,?>)((JList<?>)field(binding,"list")).getSelectedValue();
                check(expandedSignature(String.valueOf(selected.get("name"))),"full signature follows arrow selection on next EDT turn");
                JComponent view=(JComponent)field(popup,"expandedView");views.add(view);
                // Exercise the actual native window reuse outside all monitors. This invokes
                // the production positioning/update method; no business window is touched.
                invoke(popup,"displayExpansion",new Class<?>[]{JComponent.class,Rectangle.class,Point.class},view,new Rectangle(-19000,-19000,1600,850),new Point(-18900,-18800));
                JWindow host=(JWindow)field(popup,"expansionWindow");
                if(window[0]==null){window[0]=host;host.addComponentListener(new ComponentAdapter(){@Override public void componentHidden(ComponentEvent e){hides[0]++;}});}
                check(window[0]==host&&host.isVisible()&&!host.getFocusableWindowState()&&!host.isFocused(),"selection reuses one non-focusable native expansion window");
                return System.nanoTime()-start;
            });
            if(i>=12)samples[i-12]=elapsed;
        }
        edt(()->{check(hides[0]==0,"continuous long selections never hide/recreate their window");check(views.size()<=3,"prepared signatures reused across arrow navigation");return null;});
        Arrays.sort(samples);String report=String.format(Locale.ROOT,"120 native arrow selections: median %.3f ms; p95 %.3f ms; max %.3f ms; views=%d; windowHides=%d%n",samples[60]/1e6,samples[114]/1e6,samples[119]/1e6,views.size(),hides[0]);
        Files.writeString(evidence.resolveSibling("navigation-performance-"+evidence.getFileName()+".txt"),report);System.out.print(report);
        // The test window was shown offscreen; normal popup teardown must release its peer.
        edt(()->{((JWindow)window[0]).setVisible(false);return null;});
    }
    static boolean expandedSignature(String name)throws Exception {
        JPopupMenu popup=(JPopupMenu)field(binding,"popup");JComponent expanded=(JComponent)field(popup,"expandedView");if(expanded==null)return false;
        @SuppressWarnings("unchecked") Map<String,String> selected=(Map<String,String>)((JList<?>)field(binding,"list")).getSelectedValue();
        JLabel label=findLabel(expanded,"lookupMain");if(label==null||selected==null||!name.equals(selected.get("name")))return false;
        expanded.setSize(expanded.getPreferredSize());layout(expanded);
        check(label.getPreferredSize().width<=label.getWidth()&&label.getPreferredSize().height<=label.getHeight(),"native expanded label has enough paint space: preferred="+label.getPreferredSize()+" actual="+label.getSize());
        String plain=label.getText().replaceAll("<[^>]*>","").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");
        return plain.equals(name+"("+selected.get("parameters")+")");
    }
    static JLabel findLabel(Component component,String name){if(name.equals(component.getName()))return (JLabel)component;if(component instanceof Container)for(Component child:((Container)component).getComponents()){JLabel found=findLabel(child,name);if(found!=null)return found;}return null;}
    static void captureExpansion(Path path)throws Exception {
        edt(()->{
            JPopupMenu popup=(JPopupMenu)field(binding,"popup");JComponent expanded=(JComponent)field(popup,"expandedView");expanded.setSize(expanded.getPreferredSize());layout(expanded);layout(popup);
            JList<?> list=(JList<?>)field(binding,"list");Rectangle cell=list.getCellBounds(list.getSelectedIndex(),list.getSelectedIndex());Point at=SwingUtilities.convertPoint(list,cell.getLocation(),popup);
            // Native font metrics use the monitor's transform. Render at that same DPI; painting
            // into an unscaled 1x bitmap otherwise introduces false clipping at 175% Windows DPI.
            java.awt.geom.AffineTransform scale=text.getGraphicsConfiguration().getDefaultTransform();
            int width=Math.max(popup.getWidth(),expanded.getWidth()+at.x)+80,height=Math.max(420,popup.getHeight()+170);
            BufferedImage image=new BufferedImage((int)Math.ceil(width*scale.getScaleX()),(int)Math.ceil(height*scale.getScaleY()),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.transform(scale);g.setColor(Color.WHITE);g.fillRect(0,0,width,height);
            root.setSize(width,height);layout(root);root.printAll(g);g.translate(40,150);popup.printAll(g);g.translate(at.x,at.y);expanded.printAll(g);g.dispose();ImageIO.write(image,"png",path.toFile());return null;
        });
    }
    static void verifyAutomaticArguments(Path evidence)throws Exception {
        KeyboardFocusManager previous=KeyboardFocusManager.getCurrentKeyboardFocusManager();
        // Route isFocusOwner in this isolated test JVM without focusing an offscreen window or
        // stealing keyboard input from the user's desktop. Document/caret listeners and timers
        // remain the production listeners; no manual completion request is used below.
        edt(()->{KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager(){@Override public Component getFocusOwner(){return text;}});return null;});
        try {
            edt(()->{text.setText("java.util.List.of(\"1\", \"2\", \"3\").forEach()");text.setCaretPosition(text.getText().length()-1);return null;});
            await(()->has("name=forEach")&&Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.replaceSelection("System.");return null;});
            await(()->has("name=out")&&has("name=err")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
            capture(evidence.resolveSibling("argument-members-"+evidence.getFileName()));
            edt(()->{key("control P");return null;});
            await(()->has("name=forEach")&&Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.replaceSelection("out.pr");return null;});
            await(()->has("name=println")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.setText("String message=\"\"; System.out.println()");text.setCaretPosition(text.getText().length()-1);return null;});
            await(()->has("name=println")&&Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.replaceSelection("mess");return null;});
            await(()->has("name=message")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.setText("Math.max(1, )");text.setCaretPosition(text.getText().length()-1);return null;});
            await(()->has("name=max")&&Boolean.TRUE.equals(field(binding,"parameterPopup")));
            edt(()->{text.replaceSelection("Math.");return null;});
            await(()->has("name=abs")&&!Boolean.TRUE.equals(field(binding,"parameterPopup")));
        } finally {edt(()->{invoke(binding,"dismiss");KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);return null;});}
    }
    static void build()throws Exception {
        Class<?> syntaxClass=Class.forName("com.lucanet.lisa.widgets.textarea.AbstractSyntaxTextArea$MyRSyntaxTextArea");
        Constructor<?> constructor=syntaxClass.getDeclaredConstructor(Class.forName("com.lucanet.lisa.widgets.Commitable"));constructor.setAccessible(true);
        text=(JTextComponent)constructor.newInstance(new Object[]{null});syntaxClass.getMethod("setSyntaxEditingStyle",String.class).invoke(text,"text/java");text.setEditable(true);text.setEnabled(true);
        syntax=new JScrollPane(text);
        // Native JavaSourceCodeEditor constructor requires a logged-in DOM context. Allocate just its
        // identity and transplant initialized JPanel state; the child is the real constructed widget.
        root=panel(Class.forName(PKG+"JavaSourceCodeEditor"));
        for(Field field:root.getClass().getDeclaredFields())if(field.getType()==javax.swing.Timer.class){field.setAccessible(true);field.set(root,new javax.swing.Timer(500,null));}
        root.add((Component)syntax,BorderLayout.CENTER);frame=new JFrame("Java editor verification");frame.setContentPane(root);frame.setBounds(-18000,-18000,900,420);frame.setVisible(true);
    }
    static JPanel panel(Class<?> type)throws Exception {
        JPanel result=(JPanel)allocate.invoke(unsafe,type);
        JPanel template=new JPanel(new BorderLayout());
        Method offset=unsafe.getClass().getMethod("objectFieldOffset",Field.class),get=unsafe.getClass().getMethod("getObject",Object.class,long.class),put=unsafe.getClass().getMethod("putObject",Object.class,long.class,Object.class);
        for(Class<?> c=JPanel.class;c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields()) {
            if(Modifier.isStatic(f.getModifiers()))continue;long at=(Long)offset.invoke(unsafe,f);
            if(!f.getType().isPrimitive())put.invoke(unsafe,result,at,get.invoke(unsafe,template,at));
            else {String name=f.getType().getName();name=Character.toUpperCase(name.charAt(0))+name.substring(1);
                Object value=unsafe.getClass().getMethod("get"+name,Object.class,long.class).invoke(unsafe,template,at);
                unsafe.getClass().getMethod("put"+name,Object.class,long.class,f.getType()).invoke(unsafe,result,at,value);}
        }
        return result;
    }
    static void verifyNativeModels()throws Exception {
        ClassLoader loader=ClassLoader.getSystemClassLoader();
        Class<?> javaCode=Class.forName(PKG+"JavaCode",false,loader),folder=Class.forName(PKG+"PartFolder",false,loader),edit=Class.forName(PKG+"Part$Editability",false,loader);
        Class<?> part=Class.forName(PKG+"ImportPart",false,loader),context=Class.forName("com.lucanet.common.dom.base.DOMExecutionContext",false,loader);
        part.getConstructor(edit,String.class);
        javaCode.getMethod("getSourceCode");javaCode.getMethod("contentChanged",context);Class.forName(PKG+"JavaCodeEditor").getMethod("getUiContext");
        Object code=allocate.invoke(unsafe,javaCode);Object imports;Method add,children;
        try {
            Class<?> partType=Class.forName(PKG+"PartType");
            imports=folder.getMethod("createRootFolder",javaCode,partType).invoke(null,code,partType.getField("IMPORT").get(null));
            add=folder.getMethod("addChild",Class.forName(PKG+"PartFolderElement"),context);children=folder.getMethod("getChildren");folder.getMethod("sortChildren",List.class,context);
        }catch(ClassNotFoundException legacy){
            imports=Class.forName(PKG+"ImportPartFolder").getConstructor(javaCode).newInstance(code);
            add=folder.getMethod("addPart",Class.forName(PKG+"Part"),context);children=folder.getDeclaredMethod("a");children.setAccessible(true);
        }
        Object imported=part.getConstructor(edit,String.class).newInstance(edit.getField("EDITABLE").get(null),"java.util.ArrayList");
        add.invoke(imports,imported,null);
        check(((List<?>)children.invoke(imports)).contains(imported),"native import folder accepts ImportPart");
        check(part.getMethod("getName").invoke(imported).equals("java.util.ArrayList"),"native import name retained");
        // Exercise the shipped adapter against both actual import model shapes.
        // All source folders are initialized so getSourceCode regenerates after native import events.
        for(Field f:javaCode.getDeclaredFields())if(folder.isAssignableFrom(f.getType())) {
            f.setAccessible(true);
            if(f.getName().equals("b")||f.getType()==folder&&imports.getClass()==folder)f.set(code,imports);
            else if(f.getType()==folder){Constructor<?> factory=folder.getDeclaredConstructor(javaCode,Class.class);factory.setAccessible(true);f.set(code,factory.newInstance(code,Class.forName(PKG+"MethodPart")));}
            else f.set(code,f.getType().getConstructor(javaCode).newInstance(code));
        }
        set(code,"g","com.lucanet.common.dom.script.rt");set(code,"h","Task__Probe");
        JPanel codeEditor=panel(Class.forName(PKG+"JavaCodeEditor"));
        for(Field f:codeEditor.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            if(f.getType()==javaCode)f.set(codeEditor,code);
            if(f.getType().getName().equals("com.lucanet.common.dom.base.DOMUIContext"))f.set(codeEditor,allocate.invoke(unsafe,f.getType()));
        }
        // k is the verified usable-state field in 2311/2505; no live business object is involved.
        Field usable=codeEditor.getClass().getDeclaredField("k");usable.setAccessible(true);usable.setBoolean(codeEditor,true);
        codeEditor.add(root,BorderLayout.CENTER);frame.setContentPane(codeEditor);
        Class<?> adapter=Class.forName("cn.lucanet.enhancement.exit.JavaEditorContext",true,controller.getClass().getClassLoader());
        Constructor<?> ctor=adapter.getDeclaredConstructor(JTextComponent.class);ctor.setAccessible(true);Object contextAdapter=ctor.newInstance(text);
        nativeImports=imports;nativeAdd=add;nativeImport=part;nativeEditability=edit;
        check(invoke(contextAdapter,"importType",String.class,"java.util.HashMap").equals("HashMap"),"adapter creates native import");
        int count=((List<?>)children.invoke(imports)).size();
        check(invoke(contextAdapter,"importType",String.class,"java.util.HashMap").equals("HashMap")&&((List<?>)children.invoke(imports)).size()==count,"adapter avoids duplicate import");
        invoke(contextAdapter,"importType",String.class,"java.sql.Date");
        check(invoke(contextAdapter,"importType",String.class,"java.util.Date").equals("java.util.Date"),"conflicting simple name stays qualified");
        invoke(contextAdapter,"sortImports");
        Object hidden=part.getConstructor(edit,String.class).newInstance(edit.getField("HIDDEN").get(null),"java.lang.Math");add.invoke(imports,hidden,null);
        for(String name:new String[]{"java.io.File","java.io.InputStream","java.io.OutputStream","java.io.Reader","java.io.IOException"})invoke(contextAdapter,"importType",String.class,name);
        @SuppressWarnings("unchecked") Map<String,Object> organize=(Map<String,Object>)invoke(contextAdapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"organize");
        @SuppressWarnings("unchecked") List<Map<String,String>> plan=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,organize);
        List<String> requested=new ArrayList<>();for(Map<String,String> row:plan)requested.add(row.get("import"));
        invoke(contextAdapter,"applyImports",new Class<?>[]{List.class,List.class},organize.get("importNames"),requested);
        @SuppressWarnings("unchecked") Map<String,Object> organized=(Map<String,Object>)invoke(contextAdapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"organize");
        check(((List<?>)organized.get("importNames")).contains("java.io.*")&&!((List<?>)organized.get("importNames")).contains("java.io.File"),"native import nodes collapse to wildcard");
        check(((List<?>)children.invoke(imports)).contains(hidden),"hidden native import retained");
        invoke(contextAdapter,"applyImports",new Class<?>[]{List.class,List.class},Collections.emptyList(),Collections.singletonList("java.net.*"));
        @SuppressWarnings("unchecked") Map<String,Object> stale=(Map<String,Object>)invoke(contextAdapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"organize");
        check(!((List<?>)stale.get("importNames")).contains("java.net.*"),"stale import plan cannot overwrite newer native model");
        check(invoke(contextAdapter,"importType",String.class,"java.io.File").equals("File"),"wildcard avoids duplicate native import");
        verifyClassSources(contextAdapter,code,codeEditor,javaCode,edit);
    }
    static void verifyClassSources(Object adapter,Object code,JPanel codeEditor,Class<?> javaCode,Class<?> edit)throws Exception {
        Class<?> node=Class.forName("com.lucanet.common.dom.base.node.AbstractDOMNode");
        Object script=allocate.invoke(unsafe,Class.forName("com.lucanet.common.dom.script.elements.Script"));
        Object classRoot=allocate.invoke(unsafe,Class.forName("com.lucanet.common.dom.script.elements.classes.ClassFolderRootElement"));
        Object custom=Class.forName("com.lucanet.common.dom.script.elements.classes.ClassElement").getConstructor().newInstance();
        Object customCode=allocate.invoke(unsafe,javaCode);String source="package com.lucanet.common.dom.script.rt; public class StringUtil {private StringUtil(){} public static boolean isEmpty(String text){return false;}}";
        set(customCode,"h","StringUtil");set(customCode,"k",source);
        for(Field f:custom.getClass().getDeclaredFields())if(f.getType()==javaCode){f.setAccessible(true);f.set(custom,customCode);}
        Field children=node.getDeclaredField("c"),cachedRoot=node.getDeclaredField("i");children.setAccessible(true);cachedRoot.setAccessible(true);
        children.set(script,new ArrayList<>(Collections.singletonList(classRoot)));children.set(classRoot,new ArrayList<>(Collections.singletonList(custom)));children.set(custom,new ArrayList<>());
        cachedRoot.set(custom,script);set(code,"f",custom);
        Class<?> visibility=Class.forName(PKG+"Part$Visibility");
        Object method=Class.forName(PKG+"LocalMethodPart").getConstructor(edit,visibility,String.class,String.class,String.class,String.class,String.class)
            .newInstance(edit.getField("EDITABLE").get(null),visibility.getField("PUBLIC").get(null),"void","doExecute",null,null,null);
        for(Field f:codeEditor.getClass().getDeclaredFields())if(f.getType().getName().equals(PKG+"Part")){f.setAccessible(true);f.set(codeEditor,method);}
        set(code,"k","package com.lucanet.common.dom.script.rt; import java.io.File; public class Task__Probe { public void doExecute() {} }");
        // Build a fresh adapter so its selected member is the actual native LocalMethodPart.
        Constructor<?> ctor=adapter.getClass().getDeclaredConstructor(JTextComponent.class);ctor.setAccessible(true);adapter=ctor.newInstance(text);
        nativeAdapter=adapter;
        text.setText("StringUtil.isE");text.setCaretPosition(text.getText().length());
        @SuppressWarnings("unchecked") Map<String,Object> input=(Map<String,Object>)invoke(adapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"complete");
        check(((Map<?,?>)input.get("sources")).containsValue(source),"reads same-script native Class tree");
        check(String.valueOf(input.get("partSignature")).contains("doExecute()"),"reads native method signature for both releases");
        @SuppressWarnings("unchecked") List<Map<String,String>> rows=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,input);
        check(rows.stream().anyMatch(v->v.get("name").equals("isEmpty")&&v.get("parameters").equals("String text")),"native source and custom class work together");
        input.put("text","StringUtil.ne");input.put("caret",13);
        @SuppressWarnings("unchecked") List<Map<String,String>> blocked=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,input);
        check(blocked.stream().noneMatch(v->v.get("kind").equals("postfix")),"native custom private constructor never offers new");
        input.put("text","StringU");input.put("caret",7);
        @SuppressWarnings("unchecked") List<Map<String,String>> customRows=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,input);
        text.setText("StringU");text.setCaretPosition(7);set(binding,"parameterPopup",false);invoke(binding,"show",List.class,customRows);
        check(invoke(adapter,"importType",String.class,"com.lucanet.common.dom.script.rt.StringUtil").equals("StringUtil"),"same package class needs no import");
        children.set(classRoot,new ArrayList<>());
        @SuppressWarnings("unchecked") Map<String,Object> removed=(Map<String,Object>)invoke(adapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"complete");
        check(((Map<?,?>)removed.get("sources")).isEmpty(),"deleted Class disappears on next snapshot");
        verifyLibrary(adapter,script);
    }
    @SuppressWarnings("unchecked") static void verifyLibrary(Object adapter,Object script)throws Exception {
        Path work=Path.of(System.getProperty("lucanet.enhancement.features")).getParent();
        JavaLibraryProbe library=JavaLibraryProbe.attach(script,work);
        if(library==null)return;
        for(String fragment:List.of("EasyExc","com.alibaba.excel.EasyExcel.fixtureR")){
            text.setText(fragment);text.setCaretPosition(fragment.length());
            Map<String,Object> request=(Map<String,Object>)invoke(adapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"complete");
            List<Map<String,String>> candidates=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,request);
            String expected=fragment.contains(".")?"fixtureRead":"EasyExcel";
            check(candidates.stream().anyMatch(row->expected.equals(row.get("name"))),"script completion sees additional library symbol "+expected);
        }
        library.detachLibrary();
        text.setText("EasyExc");text.setCaretPosition(7);
        Map<String,Object> request=(Map<String,Object>)invoke(adapter,"request",new Class<?>[]{JTextComponent.class,String.class},text,"complete");
        List<Map<String,String>> candidates=(List<Map<String,String>>)invoke(controller,"analyze",Map.class,request);
        check(candidates.stream().noneMatch(row->"EasyExcel".equals(row.get("name"))),"removed Script library does not remain in completion index");
        System.out.println("PASS: native script adapter indexes only the active Script's extra JAR and refreshes after removal");
    }
    static void addNativeImport(String name)throws Exception {
        Object part=nativeImport.getConstructor(nativeEditability,String.class).newInstance(nativeEditability.getField("EDITABLE").get(null),name);nativeAdd.invoke(nativeImports,part,null);
    }
    static List<?> importNames()throws Exception {return (List<?>)((Map<?,?>)invoke(nativeAdapter,"importState")).get("importNames");}
    static void capture(Path path)throws Exception {edt(()->{JPopupMenu popup=(JPopupMenu)field(binding,"popup");BufferedImage image=new BufferedImage(Math.max(900,popup.getWidth()+80),Math.max(420,popup.getHeight()+170),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());root.setSize(image.getWidth(),image.getHeight());layout(root);root.printAll(g);layout(popup);g.translate(40,150);popup.printAll(g);g.dispose();ImageIO.write(image,"png",path.toFile());return null;});}
    static void layout(Container container){container.doLayout();for(Component child:container.getComponents())if(child instanceof Container)layout((Container)child);}
    @SuppressWarnings("unchecked") static void captureRows(Path path)throws Exception {
        edt(()->{
            JList<Map<String,String>> list=(JList<Map<String,String>>)field(binding,"list");int count=Math.min(10,list.getModel().getSize()),total=40,width=list.getFixedCellWidth();
            for(int i=0;i<count;i++)total+=list.getCellRenderer().getListCellRendererComponent(list,list.getModel().getElementAt(i),i,false,false).getPreferredSize().height;
            BufferedImage image=new BufferedImage(width+40,total,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());int y=20;
            for(int i=0;i<count;i++) {
                Map<String,String> item=list.getModel().getElementAt(i);Component row=list.getCellRenderer().getListCellRendererComponent(list,item,i,i==0,false);
                int height=row.getPreferredSize().height;row.setBounds(0,0,width,height);layout((Container)row);Graphics2D cell=(Graphics2D)g.create();cell.translate(20,y);row.printAll(cell);cell.dispose();y+=height;
                for(Component child:((Container)row).getComponents())if(child instanceof JLabel){JLabel label=(JLabel)child;if(label.getText()!=null&&label.getText().contains("当前脚本"))check(label.getFont().canDisplayUpTo("当前脚本")<0,"native lookup Chinese origin font");}
            }
            g.dispose();ImageIO.write(image,"png",path.toFile());return null;
        });
    }
    static boolean has(String value)throws Exception {for(int i=0;i<model().size();i++)if(String.valueOf(model().get(i)).contains(value))return true;return false;}
    static DefaultListModel<?> model()throws Exception{return (DefaultListModel<?>)field(binding,"model");}
    static void key(String value){KeyStroke stroke=KeyStroke.getKeyStroke(value);Object id=text.getInputMap().get(stroke);Action action=text.getActionMap().get(id);check(action!=null,"key "+value);action.actionPerformed(new ActionEvent(text,0,""));}
    static boolean hasSubstring()throws Exception{DefaultListModel<?> m=model();for(int i=0;i<m.size();i++)if(String.valueOf(m.get(i)).contains("substring"))return true;return false;}
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static Object invoke(Object target,String name)throws Exception{return invoke(target,name,new Class<?>[0]);}
    static Object invoke(Object target,String name,Class<?> type,Object arg)throws Exception{return invoke(target,name,new Class<?>[]{type},arg);}
    static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(target,args);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}}
    static <T>T edt(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);EventQueue.invokeAndWait(task);return task.get();}
    static void await(Callable<Boolean> condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<until){if(edt(condition))return;Thread.sleep(30);}throw new AssertionError("Asynchronous editor result timed out: "+edt(()->{Object popup=field(binding,"popup"),view=field(popup,"expandedView");return "text="+text.getText()+", visible="+((JPopupMenu)popup).isVisible()+", selected="+((JList<?>)field(binding,"list")).getSelectedValue()+", expanded="+(view instanceof JComponent?findLabel((JComponent)view,"lookupMain").getText():view);}));}
    static void check(boolean value,String description){if(!value)throw new AssertionError(description);}
}
