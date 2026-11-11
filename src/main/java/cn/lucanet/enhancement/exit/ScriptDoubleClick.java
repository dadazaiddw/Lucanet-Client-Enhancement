package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import javax.swing.*;

/** 双击只点击原“运行”按钮打开弹窗，不调用开始动作或脚本引擎。 */
final class ScriptDoubleClick implements BiPredicate<Object,Object>, Consumer<Object>, AutoCloseable {
    static final String PREFIX="cn.lucanet.enhancement.script.doubleClick.";
    static final String HANDLER=PREFIX+"handler", REGISTER=PREFIX+"register", BUTTON=PREFIX+"button", CONTEXT=PREFIX+"context";
    private static final String UI="com.lucanet.lisa.ui.UIContext";
    private static final String DETAIL="com.lucanet.lisa.applicationframe.context.DetailPanelContext";
    private static final String CELL="com.lucanet.lisa.widgets.treetable.LNTreeTableCell";
    private static final String NODE="com.lucanet.identifiable.shared.datamodel.IdentifiableView";
    private static ScriptDoubleClick instance;
    private final Instrumentation instrumentation;
    private final Map<JComponent,Boolean> panels=new WeakHashMap<>();
    private boolean closed, opening;
    static void ensureInstalled(Instrumentation instrumentation) {
        EventQueue.invokeLater(() -> {
            if(instance!=null && !instance.closed && UIManager.get(HANDLER)==instance)return;
            Object old=UIManager.get(HANDLER);
            if(old instanceof AutoCloseable)try{((AutoCloseable)old).close();}catch(Exception failure){EnhancementLog.error("SCRIPT_DOUBLE_CLICK_CLEANUP_FAILED",failure);}
            instance=new ScriptDoubleClick(instrumentation);
            UIManager.put(HANDLER,instance);UIManager.put(REGISTER,instance);
        });
    }
    private ScriptDoubleClick(Instrumentation instrumentation){this.instrumentation=instrumentation;}
    @Override public void accept(Object value) {
        if(!EventQueue.isDispatchThread()){EventQueue.invokeLater(()->accept(value));return;}
        if(!closed && value instanceof JComponent) {
            JComponent panel=(JComponent)value;
            if(panel.getClientProperty(BUTTON)==null && panel.getClass().getName().equals("com.lucanet.summa.client.sections.script.panels.ScriptGeneralPanel")) {
                // 热更新不重新执行已存在面板的 initGeneralPanel；补读已核实的按钮和上下文字段。
                // 这里只读取 UI 对象，原生“运行”动作仍在反射返回后直接 doClick。
                try {
                    Field button=null;
                    for(Field field:panel.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers()) && AbstractButton.class.isAssignableFrom(field.getType())) {
                        if(button!=null)return;button=field;
                    }
                    Method getter=null;
                    for(Class<?> type=panel.getClass();type!=null && getter==null;type=type.getSuperclass()) {
                        try{getter=type.getDeclaredMethod("getDetailPanelContext");}catch(NoSuchMethodException missing){}
                    }
                    if(button==null || getter==null)return;
                    openForUiRead(button.getDeclaringClass());openForUiRead(getter.getDeclaringClass());
                    button.setAccessible(true);getter.setAccessible(true);
                    Object context=getter.invoke(panel),run=button.get(panel);
                    if(context!=null && run instanceof AbstractButton){panel.putClientProperty(CONTEXT,context);panel.putClientProperty(BUTTON,run);}
                }catch(ReflectiveOperationException|RuntimeException failure){EnhancementLog.error("SCRIPT_DOUBLE_CLICK_PANEL_UNAVAILABLE",failure);return;}
            }
            if(panel.getClientProperty(BUTTON) instanceof AbstractButton && panel.getClientProperty(CONTEXT)!=null)panels.put(panel,Boolean.TRUE);
        }
    }
    private void openForUiRead(Class<?> type) {
        Module module=type.getModule();Module consumer=getClass().getModule();
        if(module.isNamed() && !module.isOpen(type.getPackageName(),consumer))instrumentation.redefineModule(module,Collections.emptySet(),Collections.emptyMap(),
                Map.of(type.getPackageName(),Set.of(consumer)),Collections.emptySet(),Collections.emptyMap());
    }
    private void scan(Component component) {
        accept(component);
        if(component instanceof Container)for(Component child:((Container)component).getComponents())scan(child);
    }
    @Override public boolean test(Object explorerContext,Object cell) {
        if(closed || opening || !EventQueue.isDispatchThread() || !FeatureSettings.openScriptOnDoubleClick())return false;
        try {
            if(explorerContext==null || cell==null)return false;
            ClassLoader loader=cell.getClass().getClassLoader();
            Object clicked=read(loader,CELL,cell,"getNode");
            if(clicked==null || !"com.lucanet.summa.database.model.script.ScriptNode".equals(read(loader,NODE,clicked,"getDelegateClass")))return false;
            Object oid=read(loader,NODE,clicked,"getObjectID");
            Object container=read(loader,UI,explorerContext,"getUiContainer");
            // 覆盖首次打开及运行中热更新的面板；注册表是弱引用，不保留已关闭的窗口。
            for(Window window:Window.getWindows())if(window.isDisplayable())scan(window);
            AbstractButton run=null;
            for(JComponent panel:new ArrayList<>(panels.keySet())) {
                Object context=panel.getClientProperty(CONTEXT);
                if(!panel.isShowing()) {
                    // 概况页签未选中时仍可运行，但已退出的工作区/已销毁面板不能成为目标。
                    if(!panel.isDisplayable())continue;
                    Object detail=read(loader,DETAIL,context,"getDetailPanel");
                    if(!(detail instanceof Component) || !((Component)detail).isShowing())continue;
                }
                if(read(loader,UI,context,"getUiContainer")!=container)continue;
                Object current=read(loader,DETAIL,context,"getCurrentExplorerNode");
                if(current==null || !oid.equals(read(loader,NODE,current,"getObjectID")))continue;
                AbstractButton candidate=(AbstractButton)panel.getClientProperty(BUTTON);
                if(!candidate.isVisible() || !candidate.isEnabled())continue;
                if(run!=null && run!=candidate)return false; // 多个可见目标不猜测。
                run=candidate;
            }
            if(run==null)return false;
            opening=true;
            try {
                // 所有只读反射已返回。原生运行监听器执行时没有 Method.invoke 栈帧。
                run.doClick(0);
                EnhancementLog.info("SCRIPT_DOUBLE_CLICK: native run button clicked");
                return true;
            } finally { opening=false; }
        } catch(ReflectiveOperationException|RuntimeException|LinkageError failure) {
            EnhancementLog.error("SCRIPT_DOUBLE_CLICK_FAILED: original tree behavior retained",failure);return false;
        }
    }
    private Object read(ClassLoader loader,String typeName,Object target,String methodName)throws ReflectiveOperationException {
        Class<?> type=Class.forName(typeName,false,loader);
        if(!type.isInstance(target))throw new IllegalArgumentException("Unexpected script tree context");
        Method method=type.getMethod(methodName);
        Class<?> owner=method.getDeclaringClass();Module module=owner.getModule();Module consumer=getClass().getModule();
        if(module.isNamed() && !module.isExported(owner.getPackageName(),consumer)) {
            instrumentation.redefineModule(module,Collections.emptySet(),Map.of(owner.getPackageName(),Set.of(consumer)),
                    Collections.emptyMap(),Collections.emptySet(),Collections.emptyMap());
        }
        return method.invoke(target);
    }
    @Override public void close() {
        if(!EventQueue.isDispatchThread()){EventQueue.invokeLater(this::close);return;}
        closed=true;panels.clear();
        if(UIManager.get(HANDLER)==this)UIManager.put(HANDLER,null);
        if(UIManager.get(REGISTER)==this)UIManager.put(REGISTER,null);
    }
}
