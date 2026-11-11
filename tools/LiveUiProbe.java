import java.awt.*;
import java.awt.event.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** 维护探针：临时读取当前 Swing 对象和双击来源，不点击、不执行任何业务动作。 */
public final class LiveUiProbe {
    private static Instrumentation instrumentation;
    private static Path output;
    private static AWTEventListener listener;
    private static javax.swing.Timer lifetime;
    public static void premain(String options,Instrumentation inst)throws Exception{agentmain(options,inst);}
    public static void agentmain(String options,Instrumentation inst)throws Exception {
        instrumentation=inst;
        String path=options.startsWith("b64:")?new String(Base64.getDecoder().decode(options.substring(4)),java.nio.charset.StandardCharsets.UTF_8):options;
        output=Path.of(path).toAbsolutePath();Files.createDirectories(output.getParent());
        EventQueue.invokeLater(()->{
            try {
                write("ATTACHED pid="+ProcessHandle.current().pid()+" java="+System.getProperty("java.version"));
                snapshot("INITIAL");
                listener=event->{if(event instanceof MouseEvent){MouseEvent mouse=(MouseEvent)event;
                    if(mouse.getID()==MouseEvent.MOUSE_CLICKED && mouse.getClickCount()==2){
                        write("MOUSE_DOUBLE button="+mouse.getButton()+" source="+mouse.getComponent().getClass().getName()+" point="+mouse.getX()+","+mouse.getY());
                        EventQueue.invokeLater(()->{inspectSource(mouse.getComponent());snapshot("AFTER_DOUBLE");});
                    }
                }};
                Toolkit.getDefaultToolkit().addAWTEventListener(listener,AWTEvent.MOUSE_EVENT_MASK);
                lifetime=new javax.swing.Timer(120000,event->stop());lifetime.setRepeats(false);lifetime.start();
            }catch(Exception failure){write("PROBE_ERROR "+failure);stop();}
        });
    }
    private static void stop(){if(listener!=null)Toolkit.getDefaultToolkit().removeAWTEventListener(listener);if(lifetime!=null)lifetime.stop();write("STOPPED");}
    private static void snapshot(String phase){
        write("SNAPSHOT "+phase);
        Object handler=UIManager.get("cn.lucanet.enhancement.script.doubleClick.handler");
        write("HANDLER "+(handler==null?"null":handler.getClass().getName()));
        for(Window window:Window.getWindows())if(window.isDisplayable()){
            write("WINDOW "+window.getClass().getName()+" active="+window.isActive()+" showing="+window.isShowing());walk(window,0);
        }
    }
    private static void walk(Component value,int depth){
        if(depth>35)return;
        String name=value.getClass().getName();
        if(name.contains("ScriptGeneralPanel")||name.contains("ScriptProgramElementDPSection")){
            write("PANEL "+name+" showing="+value.isShowing()+" displayable="+value.isDisplayable());
            Object context=null;
            if(value instanceof JComponent)context=((JComponent)value).getClientProperty("cn.lucanet.enhancement.script.doubleClick.context");
            if(context==null)context=invoke(value,"getDetailPanelContext");
            write("CONTEXT "+kind(context));
            if(context!=null){write("CONTAINER "+kind(invoke(context,"getUiContainer")));node(invoke(context,"getCurrentExplorerNode"),"CURRENT",0);}
            Object model=invoke(value,"getDPSectionModel");write("MODEL "+kind(model));
            if(model!=null)for(Class<?> type=model.getClass();type!=null;type=type.getSuperclass()){
                try{Field stored=type.getDeclaredField("node");open(type);stored.setAccessible(true);node(stored.get(model),"MODEL_NODE",0);break;}catch(NoSuchFieldException absent){}catch(Exception failure){write("MODEL_READ_UNAVAILABLE "+failure);break;}
            }
            if(value instanceof JComponent){Object button=((JComponent)value).getClientProperty("cn.lucanet.enhancement.script.doubleClick.button");
                if(button instanceof AbstractButton)write("RUN_BUTTON "+button.getClass().getName()+" visible="+((AbstractButton)button).isVisible()+" showing="+((AbstractButton)button).isShowing()+" enabled="+((AbstractButton)button).isEnabled());
                else write("RUN_BUTTON missing");}
        }
        if(name.equals("com.lucanet.lisa.applicationframe.section.explorer.Explorer"))write("EXPLORER showing="+value.isShowing()+" bounds="+value.getBounds());
        if(name.equals("com.lucanet.lisa.widgets.treetable.LNTreeTable") && value.isShowing())inspectSelection(value);
        if(value instanceof Container)for(Component child:((Container)value).getComponents())walk(child,depth+1);
    }
    private static void inspectSource(Component source){
        for(Component value=source;value!=null;value=value.getParent()){
            write("ANCESTOR "+value.getClass().getName()+" showing="+value.isShowing());
            if(value instanceof JTable)write("TABLE selectedRow="+((JTable)value).getSelectedRow()+" selectedColumn="+((JTable)value).getSelectedColumn());
            if(value instanceof JTree){var path=((JTree)value).getSelectionPath();node(path==null?null:path.getLastPathComponent(),"JTREE",0);}
            if(value.getClass().getName().equals("com.lucanet.lisa.widgets.treetable.LNTreeTable")){
                inspectSelection(value);
            }
        }
    }
    private static void inspectSelection(Object tree){
        Object selection=invoke(invoke(tree,"getSelectionModel"),"getSelection");write("SELECTION "+kind(selection));
        Object nodes=invoke(selection,"getSelectedNodes");
        if(nodes instanceof Object[])for(Object item:(Object[])nodes)node(item,"TREE_SELECTED",0);
        Object cell=invoke(selection,"getFocusedCell");write("CELL "+kind(cell)+" column="+invoke(cell,"getColumn"));
        node(invoke(cell,"getNode"),"CELL_NODE",0);
    }
    private static void node(Object value,String label,int depth){
        write(label+" "+kind(value));if(value==null||depth>2)return;
        Object oid=invoke(value,"getObjectID"),delegate=invoke(value,"getDelegateClass");
        if(oid!=null||delegate!=null)write(label+" oid="+oid+" delegate="+delegate);
        if(value.getClass().getName().contains("MultiDimNode"))node(invoke(value,"getViewNode"),label+".view",depth+1);
        if(hasMethod(value,"getIdentifiableView"))node(invoke(value,"getIdentifiableView"),label+".identity",depth+1);
        if(value.getClass().getName().contains("MenuNode")){
            for(Class<?> type=value.getClass();type!=null && type!=Object.class;type=type.getSuperclass())for(Field field:type.getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers()))continue;
                try{open(type);field.setAccessible(true);Object content=field.get(value);write(label+" field="+field.getName()+" type="+kind(content));}catch(Exception failure){write("FIELD_UNAVAILABLE "+field.getName());}
            }
        }
    }
    private static boolean hasMethod(Object target,String name){try{target.getClass().getMethod(name);return true;}catch(Exception absent){return false;}}
    private static Object invoke(Object target,String name){
        if(target==null)return null;
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{
            Method method=type.getDeclaredMethod(name);open(type);method.setAccessible(true);return method.invoke(target);
        }catch(NoSuchMethodException missing){}catch(Exception failure){write("READ_UNAVAILABLE "+target.getClass().getName()+"."+name+" "+failure.getClass().getName());return null;}
        return null;
    }
    private static void open(Class<?> type){Module module=type.getModule();if(module.isNamed())instrumentation.redefineModule(module,Set.of(),Map.of(),Map.of(type.getPackageName(),Set.of(LiveUiProbe.class.getModule())),Set.of(),Map.of());}
    private static String kind(Object value){return value==null?"null":value.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(value));}
    private static synchronized void write(String value){try{Files.writeString(output,value+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception failure){System.err.println("UI_PROBE_WRITE_FAILED "+failure);}}
    public static void main(String[] args)throws Exception {
        if(args.length==1 && args[0].equals("--selftest")){
            EventQueue.invokeAndWait(()->{});
            EventQueue.invokeAndWait(()->{JPanel panel=new JPanel();listener.eventDispatched(new MouseEvent(panel,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,1,1,2,false,MouseEvent.BUTTON1));});
            EventQueue.invokeAndWait(()->{});EventQueue.invokeAndWait(()->stop());
            String log=Files.readString(output);if(!log.contains("MOUSE_DOUBLE")||!log.contains("AFTER_DOUBLE"))throw new AssertionError("Probe did not capture passive diagnostic event");
            System.out.println("PASS: passive UI probe snapshot/event logging and cleanup; no business action invoked");return;
        }
        var vm=com.sun.tools.attach.VirtualMachine.attach(args[0]);
        String logPath=Base64.getEncoder().encodeToString(Path.of(args[2]).toAbsolutePath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try{vm.loadAgent(Path.of(args[1]).toAbsolutePath().toString(),"b64:"+logPath);}finally{vm.detach();}
    }
}
