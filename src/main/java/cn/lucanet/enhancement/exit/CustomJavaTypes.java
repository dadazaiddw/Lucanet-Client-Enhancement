package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** IDEA-style type chooser and a full-source editor, scoped to the native Script Class tree. */
final class CustomJavaTypes implements KeyEventDispatcher,BiPredicate<Object,Object>,AutoCloseable {
    static final String HANDLER="cn.lucanet.enhancement.customJavaTypes.v1";
    private static final String SECTION="com.lucanet.common.dom.base.section.DOMExplorerDetailSection";
    private static final String[] KINDS={"Class","Interface","Record","Enum","Annotation","Exception","Abstract Class"};
    private final BooleanSupplier current;
    private final Map<Object,Editor> editors=new IdentityHashMap<>();
    private final ExecutorService validation=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"client-enhancement-type-validation");thread.setDaemon(true);thread.setContextClassLoader(null);return thread;});
    private URLClassLoader compilerLoader;
    private Function<String,Map<String,String>> parser;
    private final javax.swing.Timer lifetime;
    private boolean closed;
    private JDialog chooser;
    private IntConsumer chooserFont;
    private int fontSize=FeatureSettings.javaCompletionFontSize();
    private static final List<Image> WINDOW_ICONS=loadIcons();

    static void ensureInstalled(BooleanSupplier current) {
        EventQueue.invokeLater(()->{
            if(!current.getAsBoolean())return;
            Object old=UIManager.get(HANDLER);
            if(old instanceof CustomJavaTypes&&!((CustomJavaTypes)old).closed)return;
            if(old instanceof AutoCloseable)try{((AutoCloseable)old).close();}catch(Exception failure){EnhancementLog.error("CUSTOM_JAVA_CLEANUP_FAILED",failure);}
            UIManager.put(HANDLER,new CustomJavaTypes(current));
        });
    }
    CustomJavaTypes(BooleanSupplier current) {
        this.current=current;KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        lifetime=new javax.swing.Timer(250,e->{
            if(!current.getAsBoolean()){close();return;}
            boolean enabled=FeatureSettings.customJavaTypes();
            if(!enabled&&chooser!=null)chooser.dispose();
            int nextFont=FeatureSettings.javaCompletionFontSize();
            if(nextFont!=fontSize){
                fontSize=nextFont;
                if(chooserFont!=null)chooserFont.accept(fontSize);
                for(Editor editor:editors.values())editor.updateFont();
            }
            for(Editor editor:editors.values())editor.updateAvailability(enabled);
        });lifetime.start();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if(closed||event.getID()!=KeyEvent.KEY_PRESSED||event.getKeyCode()!=KeyEvent.VK_INSERT||event.getModifiersEx()!=InputEvent.ALT_DOWN_MASK||!FeatureSettings.customJavaTypes())return false;
        Component focus=event.getComponent();if(focus instanceof JTextComponent)return false;
        Object section=findSection(focus);if(section==null)return false;
        try {
            if(!createFromSelection(section))return false;event.consume();return true;
        }catch(Throwable failure){error(focus,failure);return true;}
    }
    boolean createFromSelection(Object section)throws Throwable {
        if(closed||!FeatureSettings.customJavaTypes()||!usable(section))return false;
        Object node=CustomJavaModel.call(section,"getSelectedDOMNode");
        if(!CustomJavaModel.isFolder(node))return false;choose(section,node);return true;
    }
    private static Object findSection(Component component){
        for(Component c=component;c!=null;c=c.getParent())for(Class<?> type=c.getClass();type!=null;type=type.getSuperclass())if(type.getName().equals(SECTION))return c;
        return null;
    }
    @Override public boolean test(Object controller,Object event) {
        if(closed||!FeatureSettings.customJavaTypes()||!EventQueue.isDispatchThread())return false;
        try {
            if(!Integer.valueOf(4).equals(CustomJavaModel.call(event,"getType")))return false;
            Object section=CustomJavaModel.declared(controller,"getDOMSection");
            if(!usable(section))return false;
            Object table=CustomJavaModel.call(event,"getTreeTableContext"),cell=CustomJavaModel.call(table,"getInteractedCell");
            if(cell==null)return false;Object node=CustomJavaModel.call(cell,"getNode");
            if(!CustomJavaModel.isClass(node)||node!=CustomJavaModel.call(section,"getSelectedDOMNode"))return false;
            open(section,CustomJavaModel.call(node,"getParent"),node,CustomJavaModel.source(node));return true;
        }catch(Throwable failure){EnhancementLog.error("CUSTOM_JAVA_OPEN_FAILED",failure);return false;}
    }
    static boolean usable(Object section)throws Throwable {
        return Boolean.TRUE.equals(CustomJavaModel.call(section,"isUsable"))&&Boolean.TRUE.equals(CustomJavaModel.call(CustomJavaModel.call(section,"getUIContext"),"isEditMode"));
    }
    private void choose(Object section,Object parent) {
        if(chooser!=null&&chooser.isDisplayable()){chooser.toFront();return;}
        Window owner=SwingUtilities.getWindowAncestor((Component)section);
        final Object root;try{root=CustomJavaModel.call(section,"getCurrentDOMRoot");}catch(Throwable failure){error((Component)section,failure);return;}
        JDialog dialog=new JDialog(owner,"New Java Class",Dialog.ModalityType.MODELESS);chooser=dialog;
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setResizable(false);dialog.setIconImages(WINDOW_ICONS);
        JPanel body=new JPanel(new BorderLayout(0,12));body.setBackground(Color.WHITE);body.setBorder(BorderFactory.createEmptyBorder(18,18,16,18));
        JTextField name=new JTextField(22);name.setName("customJavaTypeName");
        name.putClientProperty("JTextField.placeholderText","Name");name.getAccessibleContext().setAccessibleName("类型名称");
        JPanel heading=new JPanel(new BorderLayout(10,0));heading.setOpaque(false);heading.add(new JLabel("Name"),BorderLayout.WEST);heading.add(name);body.add(heading,BorderLayout.NORTH);
        JList<String> list=new JList<>(KINDS);list.setName("customJavaTypeKind");list.setSelectedIndex(0);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> view,Object value,int index,boolean selected,boolean focus){
            JLabel label=(JLabel)super.getListCellRendererComponent(view,value,index,selected,focus);String kind=String.valueOf(value);
            String icon=kind.equals("Abstract Class")?"classAbstract":kind.toLowerCase(Locale.ROOT);
            label.setIcon(JavaCompletionIcons.forItem(Collections.singletonMap("icon",icon),22));label.setIconTextGap(12);label.setBorder(BorderFactory.createEmptyBorder(4,10,4,10));return label;
        }});body.add(list);
        JLabel message=new JLabel("Enter 创建 · ↑↓ 选择类型 · Esc 取消");message.setForeground(new Color(100,107,121));body.add(message,BorderLayout.SOUTH);
        Runnable create=()->{
            try {
                if(!FeatureSettings.customJavaTypes()||!usable(section)){dialog.dispose();return;}
                if(root!=CustomJavaModel.call(section,"getCurrentDOMRoot")||root!=CustomJavaModel.call(parent,"getDOMRootNode"))throw new IllegalStateException("目标脚本或 Class 文件夹已变更，请重新打开创建窗口。");
                String value=name.getText().trim();if(!validName(value))throw new IllegalArgumentException("请输入有效的 Java 类型名称，不含包名或 .java 后缀。");
                CustomJavaModel.checkName(parent,null,value);
                String source=CustomJavaModel.template(parent.getClass().getClassLoader(),value,list.getSelectedValue());
                open(section,parent,null,source);dialog.dispose();
            }catch(Throwable failure){message.setForeground(new Color(180,45,45));message.setText(message(failure));}
        };
        name.addActionListener(e->create.run());list.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)create.run();}});
        for(JComponent target:List.of(name,list)) {
            bind(target,"ENTER",create);bind(target,"ESCAPE",dialog::dispose);
            bind(target,"UP",()->list.setSelectedIndex(Math.max(0,list.getSelectedIndex()-1)));
            bind(target,"DOWN",()->list.setSelectedIndex(Math.min(KINDS.length-1,list.getSelectedIndex()+1)));
        }
        chooserFont=size->{
            setFonts(body,size);
            list.setFixedCellHeight(Math.max(35, list.getFontMetrics(list.getFont()).getHeight()+10));
            dialog.pack();
            dialog.setSize(Math.max(420,dialog.getWidth()),dialog.getHeight());
        };
        dialog.addWindowListener(new WindowAdapter(){@Override public void windowClosed(WindowEvent event){
            if(chooser==dialog){chooser=null;chooserFont=null;}
        }});
        dialog.setContentPane(body);chooserFont.accept(fontSize);dialog.setLocationRelativeTo(owner);dialog.setVisible(true);name.requestFocusInWindow();
    }
    static boolean validName(String name){
        if(name.isEmpty()||!Character.isJavaIdentifierStart(name.codePointAt(0)))return false;
        for(int i=Character.charCount(name.codePointAt(0));i<name.length();){int ch=name.codePointAt(i);if(!Character.isJavaIdentifierPart(ch))return false;i+=Character.charCount(ch);}
        return !Set.of("abstract","assert","boolean","break","byte","case","catch","char","class","const","continue","default","do","double","else","enum","extends","final","finally","float","for","goto","if","implements","import","instanceof","int","interface","long","native","new","package","private","protected","public","return","short","static","strictfp","super","switch","synchronized","this","throw","throws","transient","try","void","volatile","while","true","false","null","_","var","yield","record","sealed","permits").contains(name);
    }
    void open(Object section,Object parent,Object node,String source)throws Throwable {
        Editor existing=node==null?null:editors.get(node);
        if(existing!=null){existing.frame.setExtendedState(existing.frame.getExtendedState()&~Frame.ICONIFIED);existing.frame.toFront();existing.text.requestFocusInWindow();return;}
        Editor editor=new Editor(section,parent,node,source);editors.put(editor.key,editor);editor.frame.setVisible(true);editor.text.requestFocusInWindow();
    }
    @SuppressWarnings("unchecked") private Map<String,String> parse(String source)throws Exception {
        if(parser==null){
            compilerLoader=new URLClassLoader(new URL[]{CustomJavaTypes.class.getProtectionDomain().getCodeSource().getLocation()},ClassLoader.getPlatformClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                    if(name.equals("cn.lucanet.enhancement.exit.CustomJavaSource")||name.startsWith("cn.lucanet.enhancement.exit.CustomJavaSource$")) {
                        Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                    }
                    return super.loadClass(name,resolve);
                }
            };
            parser=(Function<String,Map<String,String>>)Class.forName("cn.lucanet.enhancement.exit.CustomJavaSource",true,compilerLoader).getConstructor().newInstance();
        }
        return parser.apply(source);
    }
    static void bind(JComponent component,String key,Runnable action){String id=HANDLER+"."+key;component.getInputMap().put(KeyStroke.getKeyStroke(key),id);component.getActionMap().put(id,new AbstractAction(){public void actionPerformed(ActionEvent event){action.run();}});}
    private static String message(Throwable error){while(error instanceof java.lang.reflect.InvocationTargetException&&error.getCause()!=null)error=error.getCause();String message=error.getMessage();return message==null?error.getClass().getSimpleName():message;}
    private static void error(Component owner,Throwable failure){EnhancementLog.error("CUSTOM_JAVA_FAILED",failure);JOptionPane.showMessageDialog(owner,message(failure),"自定义 Java 类型",JOptionPane.ERROR_MESSAGE);}
    @Override public void close(){
        if(closed)return;closed=true;lifetime.stop();KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);if(chooser!=null)chooser.dispose();
        // Existing editors keep their source and save handlers during a module swap. Never discard drafts.
        for(Editor editor:editors.values())editor.status.setText("增强模块已更新；当前窗口仍可保存，重新打开后使用新版本。");
        if(editors.isEmpty())release();if(UIManager.get(HANDLER)==this)UIManager.put(HANDLER,null);
    }
    private void release(){validation.shutdown();try{if(compilerLoader!=null)compilerLoader.close();}catch(Exception ignored){}}

    final class Editor {
        final Object section,parent,context,originalRoot;Object node,key;
        final JFrame frame;final JTextComponent text;final JComponent gutter;
        final JLabel status=new JLabel(),tab=new JLabel();final JButton saveButton=new JButton("保存  Ctrl+S");
        String saved;boolean saving,disposed;Boolean entrancesEnabled;
        Editor(Object section,Object parent,Object node,String source)throws Throwable {
            this.section=section;this.parent=parent;this.node=node;key=node==null?new Object():node;context=CustomJavaModel.call(section,"getUIContext");originalRoot=CustomJavaModel.call(section,"getCurrentDOMRoot");saved=source;
            Window owner=SwingUtilities.getWindowAncestor((Component)section);
            GraphicsConfiguration screen=owner==null?GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration():owner.getGraphicsConfiguration();
            frame=new JFrame(typeName(source),screen);frame.setName("customJavaEditor");frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);frame.setIconImages(WINDOW_ICONS);
            ClassLoader loader=parent.getClass().getClassLoader();
            text=(JTextComponent)CustomJavaModel.create(loader,"com.lucanet.lisa.widgets.textarea.AbstractSyntaxTextArea$MyRSyntaxTextArea",new Object[]{null});
            CustomJavaModel.call(text,"setSyntaxEditingStyle","text/java");
            try{CustomJavaModel.call(text,"setCodeFoldingEnabled",true);}catch(NoSuchMethodException ignored){}
            text.setName("customJavaSource");text.setFont(new Font(Font.MONOSPACED,Font.PLAIN,fontSize));text.setText(source);text.setCaretPosition(0);
            configureColors(text);
            try{CustomJavaModel.call(text,"discardAllEdits");}catch(NoSuchMethodException ignored){}
            Map<String,Object> document=new HashMap<>();document.put("owner",node);document.put("parent",parent);document.put("context",context);document.put("section",section);
            text.putClientProperty(CustomJavaModel.DOCUMENT,document);
            JPanel body=new JPanel(new BorderLayout());body.setBackground(new Color(247,248,250));
            tab.setText("  "+typeName(source)+".java");tab.setBorder(BorderFactory.createEmptyBorder(9,8,9,8));tab.setFont(new Font(Font.DIALOG,Font.PLAIN,14));body.add(tab,BorderLayout.NORTH);
            JScrollPane scroll=(JScrollPane)CustomJavaModel.create(loader,"org.fife.ui.rtextarea.RTextScrollPane",text);
            CustomJavaModel.call(scroll,"setFoldIndicatorEnabled",true);CustomJavaModel.call(scroll,"setLineNumbersEnabled",true);
            gutter=(JComponent)CustomJavaModel.call(scroll,"getGutter");gutter.setBackground(new Color(247,248,250));
            CustomJavaModel.call(gutter,"setLineNumberColor",new Color(130,137,151));
            scroll.setBorder(BorderFactory.createMatteBorder(1,0,1,0,new Color(217,220,227)));
            JavaSourceDiagnostics diagnostics=new JavaSourceDiagnostics(text);
            text.putClientProperty(JavaSourceDiagnostics.DISPLAY,diagnostics);
            body.add(scroll);JPanel footer=new JPanel(new BorderLayout(12,0));footer.setBorder(BorderFactory.createEmptyBorder(8,12,8,12));footer.add(status);
            JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));JButton discard=new JButton("放弃修改");
            discard.addActionListener(e->{if(!saving&&(text.getText().equals(saved)||JOptionPane.showConfirmDialog(frame,"放弃当前窗口中的未保存修改？","放弃修改",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION))frame.dispose();});
            actions.add(discard);actions.add(saveButton);footer.add(actions,BorderLayout.EAST);
            JPanel bottom=new JPanel(new BorderLayout());
            bottom.add(diagnostics.component(),BorderLayout.NORTH);
            bottom.add(footer,BorderLayout.SOUTH);
            body.add(bottom,BorderLayout.SOUTH);
            text.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){}private void changed(){boolean dirty=!text.getText().equals(saved);frame.setTitle(typeName(text.getText())+(dirty?" *":""));tab.setText("  "+typeName(text.getText())+".java"+(dirty?" *":""));}});
            bind(text,"control S",()->save(false));saveButton.addActionListener(e->save(false));
            frame.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent event){save(true);}@Override public void windowClosed(WindowEvent event){disposed=true;text.putClientProperty(CustomJavaModel.DOCUMENT,null);editors.remove(key);if(closed&&editors.isEmpty())release();}});
            frame.setContentPane(body);
            Rectangle available=new Rectangle(screen.getBounds());Insets reserved=Toolkit.getDefaultToolkit().getScreenInsets(screen);
            available.x+=reserved.left;available.y+=reserved.top;available.width-=reserved.left+reserved.right;available.height-=reserved.top+reserved.bottom;
            // 75% width x 70% height is about half the work area, in the monitor's own DPI coordinates.
            int width=Math.min(available.width,Math.max(640,(int)Math.round(available.width*0.75)));
            int height=Math.min(available.height,Math.max(420,(int)Math.round(available.height*0.70)));
            frame.setMinimumSize(new Dimension(Math.min(640,available.width),Math.min(420,available.height)));
            frame.setBounds(available.x+(available.width-width)/2,available.y+(available.height-height)/2,width,height);
            updateFont();updateAvailability(FeatureSettings.customJavaTypes());
        }
        private void updateFont(){
            setFonts(frame.getContentPane(),fontSize);
            try{CustomJavaModel.call(gutter,"setLineNumberFont",text.getFont());}
            catch(Throwable failure){EnhancementLog.error("CUSTOM_JAVA_GUTTER_FONT_FAILED",failure);}
            frame.revalidate();
            frame.repaint();
        }
        void updateAvailability(boolean enabled){
            // Disabling the entry points must never erase or prevent saving an already-open draft.
            if(!saving&&!Boolean.valueOf(enabled).equals(entrancesEnabled)) {
                entrancesEnabled=enabled;status.setText(enabled?"关闭窗口自动保存到 Class；脚本按原方式保存。":"自定义类型入口已关闭；当前草稿仍可保存。");
            }
        }
        void save(boolean closeAfter) {
            if(saving||disposed)return;if(node!=null&&text.getText().equals(saved)){if(closeAfter)frame.dispose();return;}
            final String source=text.getText();saving=true;saveButton.setEnabled(false);text.setEditable(false);status.setText("正在检查源码…");
            validation.submit(()->{
                Map<String,String> descriptor;try{descriptor=parse(source);}catch(Throwable failure){EventQueue.invokeLater(()->failed(failure));return;}
                EventQueue.invokeLater(()->{
                    try {
                        if(disposed)return;
                        if(!usable(section)||originalRoot!=CustomJavaModel.call(section,"getCurrentDOMRoot"))throw new IllegalStateException("脚本已关闭、切换或不可编辑。源码已保留，请返回原脚本后再保存。");
                        if(originalRoot!=CustomJavaModel.call(parent,"getDOMRootNode"))throw new IllegalStateException("目标 Class 文件夹已删除或移到其他脚本，当前源码仍保留。");
                        if(node!=null&&CustomJavaModel.call(node,"getParent")!=parent)throw new IllegalStateException("Class 节点已删除或移动，当前源码仍保留在窗口中。");
                        if(node!=null&&!CustomJavaModel.source(node).equals(saved))throw new IllegalStateException("Class 已在其他位置修改。为避免覆盖，请先复制当前源码后重新打开。");
                        boolean inserted=node==null;
                        Object result=CustomJavaModel.save(node,parent,context,source,descriptor);editors.remove(key);node=result;key=result;editors.put(key,this);
                        @SuppressWarnings("unchecked") Map<String,Object> document=(Map<String,Object>)text.getClientProperty(CustomJavaModel.DOCUMENT);document.put("owner",node);
                        saved=source;frame.setTitle(descriptor.get("name"));tab.setText("  "+descriptor.get("name")+".java");saving=false;text.setEditable(true);saveButton.setEnabled(true);status.setText("已保存到 Class；请按原方式保存脚本。");
                        try {
                            if(inserted)CustomJavaModel.call(section,"structureChanged",true);
                            CustomJavaModel.call(section,"refreshDetailPanel");
                        }catch(Throwable refreshFailure){EnhancementLog.error("CUSTOM_JAVA_REFRESH_FAILED",refreshFailure);}
                        if(closeAfter)frame.dispose();
                    }catch(Throwable failure){failed(failure);}
                });
            });
        }
        private void failed(Throwable failure){saving=false;text.setEditable(true);saveButton.setEnabled(true);status.setText("保存失败："+message(failure));EnhancementLog.error("CUSTOM_JAVA_SAVE_FAILED",failure);error(frame,failure);}
    }
    private static List<Image> loadIcons(){
        List<Image> icons=new ArrayList<>();
        for(int size:List.of(16,32)){
            URL resource=CustomJavaTypes.class.getResource("lucanet-"+size+".png");
            if(resource!=null)icons.add(new ImageIcon(resource).getImage());
        }
        return List.copyOf(icons);
    }
    private static void setFonts(Component component,int size){
        Font font=component.getFont();
        if(font!=null&&font.getSize()!=size)component.setFont(font.deriveFont((float)size));
        if(component instanceof Container)for(Component child:((Container)component).getComponents())setFonts(child,size);
    }
    private static void configureColors(JTextComponent text)throws Throwable {
        text.setForeground(new Color(40,43,48));text.setBackground(Color.WHITE);text.setSelectionColor(new Color(185,208,250));
        CustomJavaModel.call(text,"setCurrentLineHighlightColor",new Color(247,248,250));
        CustomJavaModel.call(text,"setAntiAliasingEnabled",true);CustomJavaModel.call(text,"setTabSize",4);
        Object scheme=CustomJavaModel.call(text,"getSyntaxScheme");Object[] styles=(Object[])CustomJavaModel.call(scheme,"getStyles");
        for(int token=0;token<styles.length;token++) {
            Object style=styles[token];if(style==null)continue;Color color=text.getForeground();
            if(token>=1&&token<=5)color=new Color(140,140,140);
            else if(token==6||token==7||token==9||token==16)color=new Color(0,51,179);
            else if(token>=10&&token<=12)color=new Color(23,80,235);
            else if(token>=13&&token<=15)color=new Color(6,125,23);
            else if(token==19)color=new Color(158,136,13);
            else if(token>=35)color=new Color(190,45,45);
            style.getClass().getField("foreground").set(style,color);style.getClass().getField("font").set(style,text.getFont());
        }
        CustomJavaModel.call(text,"setSyntaxScheme",scheme);
    }
    private static String typeName(String source){String lexical=JavaEditing.mask(source);
        for(String prefix:List.of("\\bpublic\\s+(?:(?:abstract|final|sealed|non-sealed|static)\\s+)*@?","\\b@?")) {
            java.util.regex.Matcher name=java.util.regex.Pattern.compile(prefix+"(?:class|interface|enum|record)\\s+([\\p{javaJavaIdentifierPart}]+)").matcher(lexical);if(name.find())return name.group(1);
        }return "Java";
    }
}
