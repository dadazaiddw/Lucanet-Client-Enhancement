package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.Border;

/** 只装饰“显示为对话框”的场景窗口，原场景、输入和 cancel 动作始终保留。 */
final class SceneDialogStyle implements Consumer<Object>, AWTEventListener, AutoCloseable {
    static final String CREATOR="com/lucanet/common/dom/dashboard/elements/ui/dashboardbuilder/SceneDialogCreator";
    static final String ACTIVE="cn.lucanet.enhancement.sceneDialog.v1.controller";
    private static final String CLOSE=CREATOR.replace('/','.')+"$c";
    private static final String RESIZE="com.lucanet.lisa.widgets.perspective.ResizePanel";
    private static final String DISPOSAL_GUARD=ACTIVE+".disposalGuard";
    private static SceneDialogStyle instance;
    private static Method getModalBlocker,setModalBlocked;
    private static MethodHandle setDialogTitle;
    private final Map<JDialog,Binding> bindings=new WeakHashMap<>();
    private final ScheduledExecutorService watcher;
    private final AtomicBoolean queued=new AtomicBoolean();
    private final Object module=activeModule();
    private volatile boolean enabled=FeatureSettings.nativeSceneDialogFrame(),closed;
    private volatile boolean hasBindings,pendingScan;
    private boolean scanning;

    static synchronized void prepareWindowAccess(Instrumentation instrumentation)throws ReflectiveOperationException {
        if(getModalBlocker!=null)return;
        // 系统 peer 重建后需更新原模态阻挡关系。只向当前增强模块开放，不要求用户添加 JVM 参数。
        instrumentation.redefineModule(Window.class.getModule(),java.util.Set.of(),Map.of(),
                Map.of("java.awt",java.util.Set.of(SceneDialogStyle.class.getModule())),java.util.Set.of(),Map.of());
        Method get=Window.class.getDeclaredMethod("getModalBlocker");
        Method set=Window.class.getDeclaredMethod("setModalBlocked",Dialog.class,boolean.class,boolean.class);
        // Lucanet 的 setTitle("") 会回退为所属窗口标题。仅清空 AWT 显示文字，保留原场景标题数据。
        MethodHandle title=MethodHandles.privateLookupIn(Dialog.class,MethodHandles.lookup())
                .findSpecial(Dialog.class,"setTitle",MethodType.methodType(void.class,String.class),Dialog.class);
        setDialogTitle=title;
        get.setAccessible(true);set.setAccessible(true);getModalBlocker=get;setModalBlocked=set;
    }

    static void ensureInstalled() {
        Runnable install=()->{
            Object active=activeModule();
            if(active!=null && active.getClass().getClassLoader()!=SceneDialogStyle.class.getClassLoader())return;
            if(instance!=null && !instance.closed && UIManager.get(ACTIVE)==instance)return;
            Object old=UIManager.get(ACTIVE);
            if(old instanceof AutoCloseable)try{((AutoCloseable)old).close();}catch(Exception failure){EnhancementLog.error("SCENE_STYLE_CLEANUP_FAILED",failure);}
            instance=new SceneDialogStyle();UIManager.put(ACTIVE,instance);
            for(Window window:Window.getWindows())if(window.isDisplayable())instance.accept(window);
            // 旧模块可能已自行退休并清空 ACTIVE，但仍等待子窗口关闭。
            instance.pendingScan=true;
        };
        if(EventQueue.isDispatchThread())install.run();else EventQueue.invokeLater(install);
    }
    private SceneDialogStyle() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this,AWTEvent.WINDOW_EVENT_MASK);
        watcher=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"client-enhancement-scene-style");thread.setDaemon(true);thread.setContextClassLoader(null);return thread;
        });
        watcher.scheduleWithFixedDelay(()->{
            if(!closed)enabled=FeatureSettings.nativeSceneDialogFrame();
            // 没有窗口时不持续唤醒 EDT；否则会阻止 JVM 的正常 AWT 自动退出。
            boolean retired=module!=null && activeModule()!=module;
            if(!hasBindings && !pendingScan && !retired)return;
            if(!queued.compareAndSet(false,true))return;
            EventQueue.invokeLater(()->{
                try {
                    if(retired)close();
                    // 前一模块可能等待其子窗口关闭后才恢复外观；恢复后再接管该窗口。
                    if(pendingScan && !closed) {
                        pendingScan=false;
                        for(Window window:Window.getWindows())if(window.isDisplayable()) {
                            accept(window);
                            if(!bindings.containsKey(window) && findClose(window)!=null)pendingScan=true;
                        }
                    }
                    for(Binding binding:new ArrayList<>(bindings.values())) {
                        if(closed)binding.detach();else binding.apply(enabled);
                    }
                    hasBindings=!bindings.isEmpty();
                    if(closed && bindings.isEmpty())watcher.shutdownNow();
                } finally{queued.set(false);}
            });
        },0,250,TimeUnit.MILLISECONDS);
    }
    @Override public void accept(Object value) {
        if(!EventQueue.isDispatchThread()){EventQueue.invokeLater(()->accept(value));return;}
        if(closed || scanning || !(value instanceof Component))return;
        Component component=(Component)value;
        Window window=component instanceof Window?(Window)component:SwingUtilities.getWindowAncestor(component);
        if(!(window instanceof JDialog) || bindings.containsKey(window))return;
        scanning=true;
        try {
            AbstractButton close=findClose(window);
            if(close==null || !(close.getParent() instanceof JComponent))return;
            JComponent title=(JComponent)close.getParent();
            if(!(title.getParent() instanceof JComponent) || !title.getParent().getClass().getName().equals(RESIZE))return;
            JDialog dialog=(JDialog)window;
            if(!dialog.isUndecorated() || !title.isVisible())return;
            installDisposalGuard(dialog);
            Binding binding=new Binding(dialog,title,(JComponent)title.getParent(),close);
            bindings.put(dialog,binding);hasBindings=true;dialog.addWindowListener(binding);
            dialog.addPropertyChangeListener("title",binding);binding.clearTitle();binding.apply(enabled);
        } catch(RuntimeException failure){EnhancementLog.error("SCENE_STYLE_REGISTER_FAILED",failure);}
        finally{scanning=false;}
    }
    private static AbstractButton findClose(Component component) {
        if(component instanceof AbstractButton && component.getClass().getName().equals(CLOSE))return (AbstractButton)component;
        if(component instanceof Container)for(Component child:((Container)component).getComponents()){
            AbstractButton found=findClose(child);if(found!=null)return found;
        }
        return null;
    }
    @Override public void eventDispatched(AWTEvent event) {
        if(!closed && event.getID()==WindowEvent.WINDOW_OPENED)accept(((WindowEvent)event).getWindow());
    }
    @Override public void close() {
        if(!EventQueue.isDispatchThread()){EventQueue.invokeLater(this::close);return;}
        if(closed)return;closed=true;Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        for(Binding binding:new ArrayList<>(bindings.values()))binding.detach();
        if(bindings.isEmpty())watcher.shutdownNow();
        if(UIManager.get(ACTIVE)==this)UIManager.put(ACTIVE,null);
    }
    private final class Binding extends WindowAdapter implements PropertyChangeListener {
        final WeakReference<JDialog> window;
        final WeakReference<JComponent> title,resize;
        final WeakReference<AbstractButton> close;
        final Border border;
        final boolean resizable;
        String originalTitle;
        boolean applied,changing,failed;
        Binding(JDialog dialog,JComponent title,JComponent resize,AbstractButton close) {
            this.window=new WeakReference<>(dialog);this.title=new WeakReference<>(title);this.resize=new WeakReference<>(resize);this.close=new WeakReference<>(close);
            border=resize.getBorder();resizable=dialog.isResizable();
            originalTitle=dialog.getTitle();
        }
        void clearTitle() {
            JDialog dialog=window.get();
            if(dialog!=null && !"".equals(dialog.getTitle()))setWindowTitle(dialog,"");
        }
        @Override public void propertyChange(PropertyChangeEvent event) {
            if("".equals(event.getNewValue()))return;
            originalTitle=(String)event.getNewValue();clearTitle();
        }
        void apply(boolean next) {
            if(next==applied || changing || failed)return;
            JDialog dialog=window.get();JComponent titlePanel=title.get(),resizePanel=resize.get();
            if(dialog==null || titlePanel==null || resizePanel==null)return;
            // 子对话框/原生下拉框显示时等其关闭，不能拆掉其所属窗口的 native peer。
            if(hasVisibleOwnedWindow(dialog))return;
            if(dialog.getOpacity()!=1F || dialog.getShape()!=null || dialog.getBackground()!=null && dialog.getBackground().getAlpha()!=255)return;
            boolean wasApplied=applied;
            changing=true;
            try {
                decorate(dialog,titlePanel,resizePanel,next);applied=next;
            } catch(RuntimeException failure) {
                try{decorate(dialog,titlePanel,resizePanel,wasApplied);}catch(RuntimeException recovery){failure.addSuppressed(recovery);}
                failed=true;EnhancementLog.error("SCENE_STYLE_APPLY_FAILED",failure);
            } finally{changing=false;}
        }
        private void decorate(JDialog dialog,JComponent titlePanel,JComponent resizePanel,boolean next) {
            Component focus=dialog.getFocusOwner();Rectangle bounds=dialog.getBounds();
            boolean displayable=dialog.isDisplayable();
            if(focus!=null)KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            // peer 重建可能吞掉原生 focusLost；先停光标闪烁，重新获焦时 Swing 自行恢复。
            // 不触碰文本、选区和业务监听器，避免旧光标 Timer 在窗口关闭后继续唤醒 EDT。
            if(displayable)suspendCarets(dialog);
            // removeNotify/addNotify 只重建同一窗口的 peer，不调用 Lucanet dispose/show/cancel。
            // 因此模态循环、场景上下文、输入模型和关闭监听器不会被重新创建或清理。
            ArrayList<Window> hiddenPeers=new ArrayList<>();
            synchronized(dialog.getTreeLock()) {
                if(displayable){
                    collectHiddenPeers(dialog,hiddenPeers);
                    for(int i=hiddenPeers.size()-1;i>=0;i--)hiddenPeers.get(i).removeNotify();
                    dialog.removeNotify();
                }
                try {
                    dialog.setUndecorated(!next);dialog.setResizable(next || resizable);
                    titlePanel.setVisible(!next);resizePanel.setBorder(next?null:border);
                } finally {
                    if(displayable){
                        dialog.addNotify();for(Window child:hiddenPeers)child.addNotify();
                        hiddenPeers.add(dialog);refreshModalPeers(hiddenPeers);
                    }
                }
            }
            dialog.setBounds(bounds);dialog.validate();dialog.repaint();
            if(focus!=null && !closed)EventQueue.invokeLater(()->{if(!closed && focus.isShowing() && dialog.isActive())focus.requestFocusInWindow();});
        }
        @Override public void windowClosing(WindowEvent event) {
            if(!applied || changing)return;
            AbstractButton button=close.get();
            if(button!=null && button.isEnabled())button.doClick(0);
        }
        @Override public void windowClosed(WindowEvent event){suspendCarets(event.getWindow());detach();}
        void detach() {
            JDialog dialog=window.get();
            if(dialog!=null){
                if(dialog.isDisplayable()) {
                    apply(false);
                    // 子窗口仍打开或恢复失败时保留原关闭动作，稍后再恢复。
                    if(applied)return;
                }
                dialog.removeWindowListener(this);dialog.removePropertyChangeListener("title",this);
                setWindowTitle(dialog,originalTitle);bindings.remove(dialog);
                hasBindings=!bindings.isEmpty();
            }
        }
    }
    private static void setWindowTitle(JDialog dialog,String title) {
        try{setDialogTitle.invokeExact((Dialog)dialog,title);}
        catch(RuntimeException | Error failure){throw failure;}
        catch(Throwable failure){throw new IllegalStateException("Cannot update scene caption",failure);}
    }
    private static boolean hasVisibleOwnedWindow(Window window) {
        for(Window child:window.getOwnedWindows())if(child.isDisplayable() && (child.isVisible() || hasVisibleOwnedWindow(child)))return true;
        return false;
    }
    private static void refreshModalPeers(ArrayList<Window> rebuilt) {
        try {
            for(Window window:Window.getWindows())if(window.isDisplayable()) {
                Dialog blocker=(Dialog)getModalBlocker.invoke(window);
                if(blocker!=null && blocker.isDisplayable() && (rebuilt.contains(window)||rebuilt.contains(blocker)))
                    setModalBlocked.invoke(window,blocker,true,true);
            }
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot restore original modal owner",failure);}
    }
    private static void installDisposalGuard(JDialog dialog) {
        if(dialog.getRootPane().getClientProperty(DISPOSAL_GUARD)!=null)return;
        // 卸载/回退已恢复原边框后仍需收尾一次：被重建 peer 的光标可能收不到 focusLost。
        // 每个场景只有一个此监听器，不参与 cancel/业务关闭，并在窗口关闭时自行移除。
        WindowAdapter guard=new WindowAdapter(){@Override public void windowClosed(WindowEvent event){
            suspendCarets(event.getWindow());event.getWindow().removeWindowListener(this);
            ((JDialog)event.getWindow()).getRootPane().putClientProperty(DISPOSAL_GUARD,null);
        }};
        dialog.getRootPane().putClientProperty(DISPOSAL_GUARD,guard);dialog.addWindowListener(guard);
    }
    private static void suspendCarets(Component component) {
        if(component instanceof javax.swing.text.JTextComponent) {
            javax.swing.text.Caret caret=((javax.swing.text.JTextComponent)component).getCaret();
            if(caret instanceof javax.swing.text.DefaultCaret)caret.setVisible(false);
        }
        if(component instanceof Container)for(Component child:((Container)component).getComponents())suspendCarets(child);
    }
    private static Object activeModule() {
        // 已安装的旧 bootstrap 也有此协议字段，不依赖升级其常驻类或增加桥接 API。
        try {java.lang.reflect.Field field=LiveBridge.class.getDeclaredField("current");field.setAccessible(true);return field.get(null);}
        catch(ReflectiveOperationException | RuntimeException unavailable){return null;}
    }
    private static void collectHiddenPeers(Window window,ArrayList<Window> peers) {
        for(Window child:window.getOwnedWindows())if(child.isDisplayable()){
            peers.add(child);collectHiddenPeers(child,peers);
        }
    }
}
