package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.beans.*;
import java.lang.ref.WeakReference;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

/** Java-only, reversible overlay on Lucanet's existing document and undo manager. */
final class JavaEditorAssist implements AutoCloseable,AWTEventListener {
    static final String SOURCE="com/lucanet/common/dom/script/java/JavaSourceCodeEditor";
    static final String ACTIVE="cn.lucanet.enhancement.javaEditor.v1";
    private final Map<JTextComponent,Binding> bindings=new WeakHashMap<>();
    private final ThreadPoolExecutor analysis;
    private final ScheduledExecutorService settings;
    private final BooleanSupplier current;
    private boolean enabled=true,closed;
    private int completionFontSize=FeatureSettings.javaCompletionFontSize();
    private int settingsTicks;
    private long requestOrder;
    private URLClassLoader compilerLoader;
    private Function<Map<String,Object>,List<Map<String,String>>> engine;

    static boolean isCurrent(java.lang.instrument.ClassFileTransformer transformer) {
        try {
            // Protocol 1 keeps this field package-private in the bootstrap loader. Reading our own
            // stable bridge avoids adding a new bridge method that older installed agents lack.
            java.lang.reflect.Field field=LiveBridge.class.getDeclaredField("current");field.setAccessible(true);
            AgentModule module=(AgentModule)field.get(null);return module!=null&&module.transformer()==transformer;
        }catch(ReflectiveOperationException failure){return false;}
    }

    static void ensureInstalled(BooleanSupplier current) {
        EventQueue.invokeLater(()->{
            if(!current.getAsBoolean())return;
            Object previous=UIManager.get(ACTIVE);
            if(previous instanceof JavaEditorAssist && !((JavaEditorAssist)previous).closed)return;
            if(previous instanceof AutoCloseable)try{((AutoCloseable)previous).close();}catch(Exception failure){EnhancementLog.error("JAVA_EDITOR_CLEANUP_FAILED",failure);}
            JavaEditorAssist controller=new JavaEditorAssist(current);UIManager.put(ACTIVE,controller);
            for(Window window:Window.getWindows())if(window.isDisplayable())controller.scan(window);
        });
    }
    JavaEditorAssist(BooleanSupplier current) {
        this.current=current;
        analysis=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new PriorityBlockingQueue<>(),
            task->daemon(task,"client-enhancement-java-analysis"));
        settings=Executors.newSingleThreadScheduledExecutor(task->daemon(task,"client-enhancement-java-settings"));
        Toolkit.getDefaultToolkit().addAWTEventListener(this,AWTEvent.CONTAINER_EVENT_MASK|AWTEvent.WINDOW_EVENT_MASK|AWTEvent.FOCUS_EVENT_MASK);
        settings.scheduleWithFixedDelay(()->{
            boolean next=FeatureSettings.javaEditorAssist();
            int nextFont=FeatureSettings.javaCompletionFontSize();
            EventQueue.invokeLater(()->{
                if(closed)return;
                if(!current.getAsBoolean()){close();return;}
                if(enabled!=next||completionFontSize!=nextFont){enabled=next;completionFontSize=nextFont;for(Binding b:new ArrayList<>(bindings.values()))b.refresh();}
                if(enabled&&++settingsTicks%3==0)for(Binding b:new ArrayList<>(bindings.values())){
                    b.checkImports();
                    b.checkDiagnostics();
                }
            });
        },0,250,TimeUnit.MILLISECONDS);
    }
    private static Thread daemon(Runnable task,String name) {Thread t=new Thread(task,name);t.setDaemon(true);t.setContextClassLoader(null);return t;}
    @Override public void eventDispatched(AWTEvent event) {
        if(closed)return;
        if(event instanceof ContainerEvent) {
            Component child=((ContainerEvent)event).getChild();
            EventQueue.invokeLater(()->{
                if(closed)return;
                if(event.getID()==ContainerEvent.COMPONENT_ADDED)scan(child);
                else for(Binding binding:new ArrayList<>(bindings.values())) {
                    JTextComponent text=binding.text.get();if(text==null||!isJavaEditor(text)||!text.isDisplayable())binding.close();
                }
            });
        } else if(event instanceof FocusEvent && event.getID()==FocusEvent.FOCUS_GAINED)scan(((FocusEvent)event).getComponent());
        else if(event instanceof WindowEvent && event.getID()==WindowEvent.WINDOW_CLOSED) {
            Window window=((WindowEvent)event).getWindow();
            for(Binding binding:new ArrayList<>(bindings.values())) {
                JTextComponent text=binding.text.get();if(text==null||SwingUtilities.getWindowAncestor(text)==window)binding.close();
            }
        }
    }
    static boolean isJavaEditor(JTextComponent text) {
        if(text.getClientProperty(CustomJavaModel.DOCUMENT) instanceof Map)return true;
        for(Component c=text.getParent();c!=null;c=c.getParent())
            if(c.getClass().getName().equals(SOURCE.replace('/','.')))return true;
        return false;
    }
    /** A displayed signature is not a persistent editing mode: argument expressions take priority. */
    static String automaticOperation(String source,int caret,boolean followingParameters) {
        if(caret<=0||caret>source.length())return null;
        boolean code=JavaEditing.codeAt(source,caret);char last=source.charAt(caret-1);
        if(code&&(Character.isJavaIdentifierPart(last)||last=='.'||last=='@'))return "complete";
        int before=caret;while(before>0&&Character.isWhitespace(source.charAt(before-1)))before--;
        if(before==0)return null;last=source.charAt(before-1);
        if(code&&(last=='('||last==','))return "parameters";
        return followingParameters&&last!=';'&&last!='{'&&last!='}'?"parameters":null;
    }
    void scan(Component component) {
        if(closed)return;
        if(component instanceof JTextComponent && isJavaEditor((JTextComponent)component)) {
            JTextComponent text=(JTextComponent)component;
            if(!bindings.containsKey(text)) {Binding binding=new Binding(text);bindings.put(text,binding);binding.refresh();}
        }
        if(component instanceof Container)for(Component child:((Container)component).getComponents())scan(child);
    }
    @SuppressWarnings("unchecked") private List<Map<String,String>> analyze(Map<String,Object> request) throws Exception {
        if(engine==null) {
            URL jar=JavaEditorAssist.class.getProtectionDomain().getCodeSource().getLocation();
            compilerLoader=new URLClassLoader(new URL[]{jar},ClassLoader.getPlatformClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                    if(name.equals("cn.lucanet.enhancement.exit.JavaIntelligence")||name.startsWith("cn.lucanet.enhancement.exit.JavaIntelligence$")
                        ||name.equals("cn.lucanet.enhancement.exit.JavaTypeActions")||name.startsWith("cn.lucanet.enhancement.exit.JavaTypeActions$")
                        ||name.equals("cn.lucanet.enhancement.exit.JavaGeneratedNames")||name.startsWith("cn.lucanet.enhancement.exit.JavaGeneratedNames$")
                        ||name.equals("cn.lucanet.enhancement.exit.JavaTypeSpelling")||name.startsWith("cn.lucanet.enhancement.exit.JavaTypeSpelling$")
                        ||name.equals("cn.lucanet.enhancement.exit.JavaVariableInference")||name.startsWith("cn.lucanet.enhancement.exit.JavaVariableInference$")
                        ||name.equals("cn.lucanet.enhancement.exit.JavaMemberGeneration")||name.startsWith("cn.lucanet.enhancement.exit.JavaMemberGeneration$")) {
                        Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                    }
                    return super.loadClass(name,resolve);
                }
            };
            engine=(Function<Map<String,Object>,List<Map<String,String>>>)Class.forName("cn.lucanet.enhancement.exit.JavaIntelligence",true,compilerLoader).getConstructor().newInstance();
        }
        return engine.apply(request);
    }
    @Override public void close() {
        if(!EventQueue.isDispatchThread()){EventQueue.invokeLater(this::close);return;}
        if(closed)return;closed=true;settings.shutdownNow();
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        for(Binding binding:new ArrayList<>(bindings.values()))binding.close();bindings.clear();
        if(UIManager.get(ACTIVE)==this)UIManager.put(ACTIVE,null);
        submitAnalysis(2,()->{try{if(engine instanceof AutoCloseable)((AutoCloseable)engine).close();if(compilerLoader!=null)compilerLoader.close();}catch(Exception ignored){}});
        analysis.shutdown();
    }

    private Future<?> submitAnalysis(int priority,Runnable work){
        AnalysisTask task=new AnalysisTask(priority,requestOrder++,work);
        analysis.execute(task);
        return task;
    }
    private void cancelAnalysis(Future<?> future){
        if(future!=null){future.cancel(false);analysis.remove((AnalysisTask)future);}
    }
    private static final class AnalysisTask extends FutureTask<Void> implements Comparable<AnalysisTask> {
        final int priority;
        final long order;
        AnalysisTask(int priority,long order,Runnable work){super(work,null);this.priority=priority;this.order=order;}
        @Override public int compareTo(AnalysisTask other){
            int rank=Integer.compare(priority,other.priority);
            return rank!=0?rank:Long.compare(order,other.order);
        }
    }
    private static boolean sameContext(Map<String,Object> first,Map<String,Object> second){
        for(String key:List.of("source","sources","classpath","libraryRevision","partBody","partSignature"))
            if(!Objects.equals(first.get(key),second.get(key)))return false;
        return true;
    }

    final class Binding implements DocumentListener,PropertyChangeListener {
        final WeakReference<JTextComponent> text;
        final javax.swing.Timer delay;
        final javax.swing.Timer importDelay;
        final javax.swing.Timer diagnosticDelay;
        final JavaSourceDiagnostics diagnostics;
        Future<?> diagnosticPending;
        Map<String,Object> diagnosticInput;
        int diagnosticGeneration;
        WeakReference<Object> observedCode=new WeakReference<>(null);
        Map<String,List<String>> observedImports;
        boolean organizing;
        final DefaultListModel<Map<String,String>> model=new DefaultListModel<>();
        final JList<Map<String,String>> list=new JList<>(model);
        final JavaCompletionPopup popup=new JavaCompletionPopup(list);
        JPopupMenu generationMenu;JDialog generationDialog;
        final Map<String,Boolean> nativeOptions=new LinkedHashMap<>();
        InputMap originalInput,overlayInput;ActionMap originalActions,overlayActions;
        Document document;boolean installed,editing,detached;int generation;
        Future<?> pending;int replaceStart,replaceEnd;String snapshot;boolean parameterPopup;String lookupPrefix="";
        final FocusListener focus=new FocusAdapter(){@Override public void focusLost(FocusEvent e){dismiss();}};
        final CaretListener caret=e->caretChanged();
        private void caretChanged(){if(!editing){generation++;cancelAnalysis(pending);hide();if(parameterPopup&&installed)delay.restart();}}
        final KeyEventDispatcher popupKeys;
        private KeyEventDispatcher createPopupKeys(){return event->{
            JTextComponent target=text.get();
            if(!installed||target==null||event.getComponent()!=target||!popup.isVisible()||event.getID()!=KeyEvent.KEY_PRESSED)return false;
            int code=event.getKeyCode();
            if(code==KeyEvent.VK_ESCAPE){dismiss();event.consume();return true;}
            if(parameterPopup||list.getSelectedValue()!=null&&"info".equals(list.getSelectedValue().get("kind")))return false;
            if(code==KeyEvent.VK_DOWN||code==KeyEvent.VK_UP){int next=list.getSelectedIndex()+(code==KeyEvent.VK_DOWN?1:-1);list.setSelectedIndex(Math.max(0,Math.min(model.size()-1,next)));list.ensureIndexIsVisible(list.getSelectedIndex());event.consume();return true;}
            if(code==KeyEvent.VK_ENTER||code==KeyEvent.VK_TAB){accept();event.consume();return true;}
            return false;
        };}
        Binding(JTextComponent editor) {
            text=new WeakReference<>(editor);popupKeys=createPopupKeys();delay=new javax.swing.Timer(100,e->requestAutomatic());delay.setRepeats(false);
            importDelay=new javax.swing.Timer(400,e->organizeImports(false));importDelay.setRepeats(false);
            Object display=editor.getClientProperty(JavaSourceDiagnostics.DISPLAY);
            diagnostics=display instanceof JavaSourceDiagnostics?(JavaSourceDiagnostics)display:null;
            diagnosticDelay=new javax.swing.Timer(500,e->checkDiagnostics());diagnosticDelay.setRepeats(false);
            list.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2&&!parameterPopup)accept();}});
            editor.addPropertyChangeListener(this);
        }
        private void requestAutomatic() {
            JTextComponent target=text.get();if(target==null)return;
            String operation=automaticOperation(target.getText(),target.getCaretPosition(),parameterPopup);
            if(operation==null){parameterPopup=false;hide();return;}
            request(operation,false);
        }
        void refresh() {
            JTextComponent target=text.get();if(target==null){close();return;}
            boolean active=enabled&&!closed&&!detached&&target.isEditable()&&target.isEnabled();
            if(active&&!installed)install(target);else if(!active&&installed)uninstall(target);
            popup.fontSize(completionFontSize);
            if(generationMenu!=null&&generationMenu.getFont().getSize()!=completionFontSize){JavaGenerationDialog.font(generationMenu,completionFontSize);if(generationMenu.isVisible())generationMenu.pack();}
            if(generationDialog!=null&&generationDialog.isDisplayable()&&generationDialog.getContentPane().getFont().getSize()!=completionFontSize){JavaGenerationDialog.font(generationDialog,completionFontSize);JavaGenerationDialog.fit(generationDialog);}
        }
        void checkImports() {
            JTextComponent target=text.get();if(!installed||editing||organizing||target==null||!target.isShowing())return;
            try {
                JavaEditorContext context=new JavaEditorContext(target);
                if(context.unit==null&&(context.code==null||!Boolean.TRUE.equals(JavaEditorContext.call(context.editor,"isUsable"))))return;
                Map<String,List<String>> state=context.importState();
                if(context.code==observedCode.get()&&state.equals(observedImports))return;
                boolean firstUnitSnapshot=context.unit!=null&&observedImports==null;
                observedCode=new WeakReference<>(context.code);observedImports=state;
                if(firstUnitSnapshot)return; // Keep the native template's imports exactly as created.
                Map<String,Integer> counts=new HashMap<>();
                for(String name:state.get("editableImports")) {
                    if(name.startsWith("static ")||name.endsWith(".*")||name.lastIndexOf('.')<0)continue;
                    String pkg=name.substring(0,name.lastIndexOf('.'));
                    if(counts.merge(pkg,1,Integer::sum)>=2||state.get("importNames").contains(pkg+".*")){importDelay.restart();return;}
                }
            }catch(Throwable unavailable){/* Native editors may be between selections; the next snapshot retries. */}
        }
        private void install(JTextComponent target) {
            installed=true;originalInput=target.getInputMap();originalActions=target.getActionMap();
            overlayInput=new InputMap();overlayInput.setParent(originalInput);overlayActions=new ActionMap();overlayActions.setParent(originalActions);
            target.setInputMap(JComponent.WHEN_FOCUSED,overlayInput);target.setActionMap(overlayActions);
            bind("control SPACE",()->request("complete",true));bind("control P",()->request("parameters",true));
            bind("alt ENTER",()->request(diagnostics==null?"import":"quickFix",true));
            if(diagnostics!=null){bind("control O",()->request("override",true));bind("alt INSERT",this::showGenerationMenu);}
            bind("control D",()->edit(JavaEditing::duplicate));bind("control Y",()->edit(JavaEditing::deleteLines));
            bind("control SLASH",()->edit(JavaEditing::comment));bind("control shift SLASH",()->edit(JavaEditing::blockComment));
            bind("alt shift UP",()->edit(t->JavaEditing.move(t,-1)));bind("alt shift DOWN",()->edit(t->JavaEditing.move(t,1)));
            bind("shift ENTER",()->edit(JavaEditing::newLine));bind("control shift J",()->edit(JavaEditing::join));
            bind("control alt I",()->edit(JavaEditing::indent));
            bind("control shift Z",()->originalAction(target,KeyStroke.getKeyStroke("control Y")));
            bind("control alt O",()->organizeImports(true));
            for(char delimiter:"()[]{}\"'".toCharArray())bind(KeyStroke.getKeyStroke(delimiter),()->{
                edit(t->JavaEditing.typePair(t,delimiter));
                if(delimiter=='(')request("parameters",false);
            });
            bind("BACK_SPACE",()->{final boolean[] handled={false};edit(t->handled[0]=JavaEditing.backspacePair(t));if(!handled[0])originalAction(target,KeyStroke.getKeyStroke("BACK_SPACE"));});
            bind("ENTER",()->{final boolean[] handled={false};edit(t->handled[0]=JavaEditing.enterPair(t));if(!handled[0])originalAction(target,KeyStroke.getKeyStroke("ENTER"));});
            bind("TAB",()->{
                String before=target.getText().substring(0,target.getCaretPosition());
                if(before.matches("(?s).*\\.(new|sout|return|nn|null|if|not|var)$")&&JavaEditing.codeAt(target.getText(),target.getCaretPosition())){request("postfix",true);return;}
                final boolean[] expanded={false};edit(t->expanded[0]=JavaEditing.template(t));
                if(!expanded[0])originalAction(target,KeyStroke.getKeyStroke("TAB"));
            });
            option(target,"CloseCurlyBraces","getCloseCurlyBraces");option(target,"BracketMatchingEnabled","isBracketMatchingEnabled");
            option(target,"PaintMatchedBracketPair","getPaintMatchedBracketPair");option(target,"MarkOccurrences","getMarkOccurrences");
            document=target.getDocument();document.addDocumentListener(this);target.addFocusListener(focus);target.addCaretListener(caret);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(popupKeys);
            if(diagnostics!=null)diagnosticDelay.restart();
        }
        private void bind(String key,Runnable action) {
            bind(KeyStroke.getKeyStroke(key),action);
        }
        private void bind(KeyStroke key,Runnable action) {
            String id=ACTIVE+"."+key;overlayInput.put(key,id);
            overlayActions.put(id,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){if(installed&&!closed)action.run();}});
        }
        private void originalAction(JTextComponent target,KeyStroke key) {Object id=originalInput.get(key);Action action=id==null?null:originalActions.get(id);if(action!=null)action.actionPerformed(new ActionEvent(target,ActionEvent.ACTION_PERFORMED,""));}
        private void option(JTextComponent target,String property,String getter) {
            try{boolean old=(Boolean)JavaEditorContext.call(target,getter);nativeOptions.put(property,old);JavaEditorContext.call(target,"set"+property,true);}catch(Throwable ignored){}
        }
        private void edit(Consumer<JTextComponent> change) {
            JTextComponent target=text.get();if(target==null||!target.isEditable()||!target.isEnabled())return;
            dismiss();editing=true;
            try{JavaEditing.atomic(target,()->change.accept(target));}finally{editing=false;}
        }
        @Override public void insertUpdate(DocumentEvent e){changed();}
        @Override public void removeUpdate(DocumentEvent e){changed();}
        @Override public void changedUpdate(DocumentEvent e){}
        private void changed(){
            generation++;
            cancelAnalysis(pending);
            hide();
            if(!editing&&installed)delay.restart();
            if(diagnostics!=null){
                diagnosticGeneration++;
                diagnosticInput=null;
                cancelAnalysis(diagnosticPending);
                diagnostics.clear();
                if(installed)diagnosticDelay.restart();
            }
        }
        @Override public void propertyChange(PropertyChangeEvent e) {
            if(e.getPropertyName().equals("document")&&installed){uninstall(text.get());refresh();}
            else if(e.getPropertyName().equals("editable")||e.getPropertyName().equals("enabled"))refresh();
        }
        void request(String operation,boolean manual) {
            request(operation,manual,0);
        }
        private void request(String operation,boolean manual,int retry) {
            request(operation,manual,retry,Map.of());
        }
        private void request(String operation,boolean manual,int retry,Map<String,Object> options) {
            JTextComponent target=text.get();delay.stop();
            if(!installed||target==null||(!manual&&!target.isFocusOwner()))return;
            int at=target.getCaretPosition();String source=target.getText();
            if(!operation.startsWith("generate:")&&!Set.of("parameters","quickFix","override").contains(operation)&&!JavaEditing.codeAt(source,at)){if(manual)hint("注释和字符串中不提供 Java 补全");return;}
            if(!operation.equals("parameters"))parameterPopup=false;
            if(!manual && operation.equals("complete") && at>0 && (source.charAt(at-1)=='('||source.charAt(at-1)==',')){request("parameters",false);return;}
            if(!manual&&operation.equals("complete")&&(at==0||!(Character.isJavaIdentifierPart(source.charAt(at-1))||source.charAt(at-1)=='.'||source.charAt(at-1)=='@')))return;
            try {
                JavaEditorContext captured=new JavaEditorContext(target);
                Map<String,Object> input=captured.request(target,operation);
                input.putAll(options);
                int end=at;
                if(operation.equals("import")||operation.equals("quickFix")){while(end<source.length()&&Character.isJavaIdentifierPart(source.charAt(end)))end++;input.put("caret",end);}
                int begin=end;while(begin>0&&Character.isJavaIdentifierPart(source.charAt(begin-1)))begin--;
                int replaceTo=end;while(replaceTo<source.length()&&Character.isJavaIdentifierPart(source.charAt(replaceTo)))replaceTo++;
                final int start=begin,finish=replaceTo,token=++generation,originalCaret=at;
                final String prefix=source.substring(begin,end);
                cancelAnalysis(pending);
                pending=submitAnalysis(0,()->{
                    try {
                        List<Map<String,String>> values=new ArrayList<>(analyze(input));
                        if(operation.equals("postfix"))values.removeIf(value->!value.get("kind").equals("postfix")||!value.get("name").equals(prefix));
                        EventQueue.invokeLater(()->{
                            JTextComponent editor=text.get();
                            if(!installed||closed||generation!=token||editor==null||!editor.getText().equals(source)||editor.getCaretPosition()!=originalCaret)return;
                            try{JavaEditorContext now=new JavaEditorContext(editor);Map<String,Object> current=now.request(editor,operation);
                                if(now.code!=captured.code||now.part!=captured.part)return;
                                if(!sameContext(input,current)){if(retry<2)request(operation,manual,retry+1,options);return;}
                            }catch(Throwable changed){return;}
                            if(values.isEmpty()){
                                parameterPopup=false;
                                if(manual)hint(noResult(operation));
                                return;
                            }
                            if(operation.equals("override")){showOverrides(editor,source,input,values);return;}
                            if(operation.startsWith("generate:")){
                                if("info".equals(values.get(0).get("kind"))){hint(values.get(0).get("label"));return;}
                                if("generated".equals(values.get(0).get("kind"))){edit(targetText->JavaUnitImports.generated(targetText,values));rememberGeneratedImports();editor.requestFocusInWindow();return;}
                                showGenerationFields(editor,source,input,operation,values);return;
                            }
                            replaceStart=start;replaceEnd=finish;snapshot=source;lookupPrefix=prefix;parameterPopup=operation.equals("parameters");
                            show(values);
                            if(operation.equals("postfix")&&values.size()==1)accept();
                        });
                    }catch(Throwable error){EventQueue.invokeLater(()->{if(installed&&generation==token)failure(error,manual);});}
                });
            }catch(Throwable error){failure(error,manual);}
        }
        private void showGenerationMenu(){
            JTextComponent editor=text.get();if(!installed||editor==null||!editor.isShowing())return;dismiss();
            if(generationMenu!=null)generationMenu.setVisible(false);
            final int caret=editor.getCaretPosition();final String source=editor.getText();
            generationMenu=new JavaGenerationMenu(completionFontSize,kind->{if(!source.equals(editor.getText()))return;editor.setCaretPosition(caret);request(kind.equals("override")?"override":"generate:"+kind,true);});
            try{java.awt.geom.Rectangle2D rect=editor.modelToView2D(caret);if(rect!=null)generationMenu.show(editor,(int)rect.getX(),(int)rect.getMaxY());}catch(BadLocationException ignored){}
        }
        private void showGenerationFields(JTextComponent editor,String source,Map<String,Object> input,String operation,List<Map<String,String>> values){
            final int caret=editor.getCaretPosition();
            generationDialog=JavaGenerationDialog.create(editor,values,completionFontSize,options->{
                if(!installed||!source.equals(editor.getText()))return;
                try{if(!sameContext(input,new JavaEditorContext(editor).request(editor,operation)))return;editor.setCaretPosition(caret);editor.requestFocusInWindow();request(operation,true,0,options);}catch(Throwable error){failure(error,true);}
            });
            generationDialog.setVisible(true);
        }
        private String noResult(String operation){
            switch(operation){
                case "parameters":return "当前调用暂无可解析的参数信息";
                case "quickFix":return "此处没有可用的快速修复";
                case "override":return "此处没有可重写的方法";
                default:return "没有匹配项，请继续输入或检查导包";
            }
        }
        void checkDiagnostics(){
            JTextComponent target=text.get();
            if(diagnostics==null||!installed||target==null||!target.isShowing()||diagnosticDelay.isRunning())return;
            try{
                Map<String,Object> input=new JavaEditorContext(target).request(target,"diagnostics");
                if(diagnosticInput!=null&&sameContext(input,diagnosticInput))return;
                diagnosticInput=input;
                int version=++diagnosticGeneration;
                cancelAnalysis(diagnosticPending);
                diagnosticPending=submitAnalysis(2,()->{
                    try{
                        List<Map<String,String>> errors=analyze(input);
                        EventQueue.invokeLater(()->{
                            JTextComponent editor=text.get();
                            if(!installed||editor==null||version!=diagnosticGeneration)return;
                            try{
                                if(sameContext(input,new JavaEditorContext(editor).request(editor,"diagnostics")))
                                    diagnostics.show(editor,errors);
                                else diagnosticInput=null;
                            }catch(Throwable unavailable){diagnosticInput=null;}
                        });
                    }catch(Throwable error){EnhancementLog.error("JAVA_SOURCE_DIAGNOSTICS_FAILED",error);}
                });
            }catch(Throwable unavailable){/* The current script may be switching. */}
        }
        private void showOverrides(JTextComponent editor,String source,Map<String,Object> input,List<Map<String,String>> methods){
            Window owner=SwingUtilities.getWindowAncestor(editor);
            JDialog picker=new JDialog(owner,"重写或实现方法",Dialog.ModalityType.DOCUMENT_MODAL);
            generationDialog=picker;if(owner!=null)picker.setIconImages(owner.getIconImages());
            picker.setName("customJavaOverrides");
            picker.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            JList<Map<String,String>> choices=new JList<>(new Vector<>(methods));
            choices.setName("customJavaOverrideMethods");
            choices.setFont(choices.getFont().deriveFont((float)completionFontSize));
            choices.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            choices.setSelectedIndex(0);
            choices.setCellRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    Map<?,?> row=(Map<?,?>)value;
                    JLabel label=(JLabel)super.getListCellRendererComponent(list,row.get("label")+"   —   "+row.get("detail"),index,selected,focus);
                    label.setIcon(JavaCompletionIcons.forItem(Map.of("icon",String.valueOf(row.get("icon"))),18));
                    label.setBorder(BorderFactory.createEmptyBorder(3,8,3,8));
                    return label;
                }
            });
            JButton add=new JButton("生成"),cancel=new JButton("取消");
            add.setName("customJavaGenerateOverride");
            add.setFont(choices.getFont());cancel.setFont(choices.getFont());
            choices.addListSelectionListener(event->add.setEnabled(!choices.isSelectionEmpty()));
            add.addActionListener(event->{
                List<Map<String,String>> selected=choices.getSelectedValuesList();
                picker.dispose();
                if(selected.isEmpty()||!installed||!source.equals(editor.getText()))return;
                try{
                    if(!sameContext(input,new JavaEditorContext(editor).request(editor,"override")))return;
                    edit(target->JavaUnitImports.generated(target,selected));
                    rememberGeneratedImports();
                    editor.requestFocusInWindow();
                }catch(Throwable error){failure(error,true);}
            });
            cancel.addActionListener(event->picker.dispose());
            choices.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent event){
                if(event.getClickCount()==2&&!choices.isSelectionEmpty())add.doClick();
            }});
            JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttons.add(cancel);
            buttons.add(add);
            JScrollPane rows=new JScrollPane(choices);
            Rectangle monitor=editor.getGraphicsConfiguration().getBounds();
            rows.setPreferredSize(new Dimension(Math.min(780,monitor.width-60),Math.min(360,Math.max(130,methods.size()*(completionFontSize+12)))));
            picker.add(rows,BorderLayout.CENTER);
            picker.add(buttons,BorderLayout.SOUTH);
            picker.getRootPane().setDefaultButton(add);
            picker.getRootPane().registerKeyboardAction(event->picker.dispose(),KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW);
            picker.pack();
            picker.setLocationRelativeTo(owner);
            picker.setVisible(true);
        }
        void show(List<Map<String,String>> values) {
            JTextComponent target=text.get();if(target==null||!target.isShowing())return;
            model.clear();for(Map<String,String> value:values)model.addElement(value);list.setSelectedIndex(0);
            popup.setInvoker(target);popup.content(lookupPrefix,parameterPopup);
            try{java.awt.geom.Rectangle2D rect=target.modelToView2D(target.getCaretPosition());if(rect!=null)popup.show(target,(int)rect.getX(),(int)rect.getMaxY());}catch(BadLocationException ignored){}
        }
        void accept() {
            JTextComponent target=text.get();Map<String,String> item=list.getSelectedValue();
            if(!installed||target==null||!target.isEditable()||!target.isEnabled()||item==null||parameterPopup||"info".equals(item.get("kind"))||!target.getText().equals(snapshot)){hide();return;}
            try {
                String insert=item.get("insert");String qualified=item.get("import");
                int cursor=Integer.parseInt(item.get("caret"));
                if(qualified!=null&&!qualified.isEmpty()) {
                    int oldCaret=target.getCaretPosition();String imported=item.containsKey("unitReference")?item.get("unitReference"):new JavaEditorContext(target).importType(qualified);
                    if(item.containsKey("typeName")){insert="new "+imported+"()";cursor=imported.length()+5;}else{insert=imported;cursor=insert.length();}
                    // Native model listeners may refresh the document. Only apply against the same content.
                    if(!target.getText().equals(snapshot)){hint("导包已更新，请重新触发补全");return;}
                    target.setCaretPosition(Math.min(oldCaret,target.getDocument().getLength()));
                }
                final int start=Integer.parseInt(item.getOrDefault("replaceStart",String.valueOf(replaceStart)));
                int finish=Integer.parseInt(item.getOrDefault("replaceEnd",String.valueOf(replaceEnd)));
                boolean call=item.containsKey("call")||item.get("kind").equals("postfix")&&item.get("name").equals("new");
                if(call){
                    String stem=insert.endsWith("()")?insert.substring(0,insert.length()-2):insert;
                    JavaEditing.CallInsertion plan=JavaEditing.callInsertion(snapshot,finish,stem,!"method".equals(item.get("call")));
                    insert=plan.text;finish=plan.end;cursor=plan.caret;call=plan.parameters;
                }
                if(item.get("kind").equals("postfix")&&(insert.endsWith(";")||"true".equals(item.get("statement")))&&finish<snapshot.length()&&snapshot.charAt(finish)==';')finish++;
                final String value=insert;final int position=cursor,end=finish;
                edit(editor->{JavaEditing.replace(editor,start,end,value);editor.setCaretPosition(start+position);
                    if(item.containsKey("selectionStart"))editor.select(start+Integer.parseInt(item.get("selectionStart")),start+position);
                    for(String name:item.getOrDefault("imports","").split("\n"))if(!name.isBlank())JavaUnitImports.add(editor,name);
                    if(!item.containsKey("unitReference")&&qualified!=null&&!qualified.isEmpty()&&editor.getClientProperty(CustomJavaModel.DOCUMENT) instanceof Map&&!value.equals(qualified)&&!value.equals("new "+qualified+"()"))JavaUnitImports.add(editor,qualified);
                });
                if(item.containsKey("imports")&&!item.containsKey("unitReference"))rememberGeneratedImports();
                if(call)request("parameters",false);
                if(qualified!=null&&!qualified.isEmpty())organizeImports(false);
            }catch(Throwable error){failure(error,true);}
        }
        private void rememberGeneratedImports(){
            // Keep generated imports in the same undo step. A later timer must not add a
            // separate wildcard-normalization edit on top of the generated methods.
            importDelay.stop();JTextComponent target=text.get();if(target==null)return;
            try{JavaEditorContext context=new JavaEditorContext(target);if(context.unit!=null){observedCode=new WeakReference<>(context.code);observedImports=context.importState();}}catch(Throwable unavailable){observedImports=null;}
        }
        @SuppressWarnings("unchecked") private void organizeImports(boolean manual) {
            importDelay.stop();JTextComponent target=text.get();if(!installed||target==null||organizing)return;
            try {
                JavaEditorContext context=new JavaEditorContext(target);
                if(context.sourceEditor!=null)JavaEditorContext.call(context.sourceEditor,"commitChanges");
                Map<String,Object> input=context.request(target,"organize");String content=target.getText();
                if(((List<?>)input.getOrDefault("importNames",Collections.emptyList())).isEmpty())return;
                organizing=true;
                submitAnalysis(manual?0:1,()->{try {
                    List<Map<String,String>> values=analyze(input);List<String> requested=new ArrayList<>();for(Map<String,String> row:values)requested.add(row.get("import"));
                    EventQueue.invokeLater(()->{
                        try{if(!installed||closed||text.get()!=target)return;
                            if(!target.getText().equals(content)){observedImports=null;return;}
                            JavaEditorContext now=new JavaEditorContext(target);if(now.code!=context.code){observedImports=null;return;}
                            Map<String,Object> current=now.request(target,"organize");if(!sameContext(input,current)){observedImports=null;return;}
                            now.applyImports((List<String>)input.get("importNames"),requested);if(manual)hint("导包已整理");
                        }catch(Throwable error){failure(error,manual);}finally{organizing=false;}
                    });
                }catch(Throwable error){EventQueue.invokeLater(()->{organizing=false;if(installed)failure(error,manual);});}});
            }catch(Throwable error){organizing=false;failure(error,manual);}
        }
        private void hint(String message){parameterPopup=false;lookupPrefix="";Map<String,String> row=new HashMap<>();row.put("label",message);row.put("kind","info");show(Collections.singletonList(row));}
        private void failure(Throwable error,boolean manual){EnhancementLog.error("JAVA_EDITOR_ASSIST_UNAVAILABLE",error);if(manual)hint("编码辅助暂不可用；原编辑与保存仍可使用");}
        void hide(){popup.setVisible(false);}
        void dismiss(){parameterPopup=false;generation++;delay.stop();cancelAnalysis(pending);hide();}
        private void uninstall(JTextComponent target) {
            installed=false;dismiss();importDelay.stop();observedImports=null;observedCode.clear();
            if(generationMenu!=null)generationMenu.setVisible(false);if(generationDialog!=null)generationDialog.dispose();
            diagnosticDelay.stop();diagnosticGeneration++;diagnosticInput=null;cancelAnalysis(diagnosticPending);
            if(diagnostics!=null)diagnostics.clear();
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(popupKeys);
            if(document!=null){document.removeDocumentListener(this);document=null;}
            if(target==null)return;
            target.removeFocusListener(focus);target.removeCaretListener(caret);
            if(target.getInputMap()==overlayInput)target.setInputMap(JComponent.WHEN_FOCUSED,originalInput);
            if(target.getActionMap()==overlayActions)target.setActionMap(originalActions);
            for(Map.Entry<String,Boolean> option:nativeOptions.entrySet())try{JavaEditorContext.call(target,"set"+option.getKey(),option.getValue());}catch(Throwable ignored){}
            nativeOptions.clear();
        }
        void close(){if(detached)return;detached=true;JTextComponent target=text.get();if(installed)uninstall(target);if(target!=null){target.removePropertyChangeListener(this);bindings.remove(target);}}
    }
}
