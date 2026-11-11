package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import com.lucanet.lisa.widgets.processlogger.ui.ProcessLoggerExecutionPanel;
import com.lucanet.summa.client.sections.script.dialogs.ScriptExecutionDialogCreator;

public final class ScriptControlsTest {
    private static final KeyStroke ALT_R = KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK);
    private static final KeyStroke CTRL_K = KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    private static final String PREFIX = "cn.lucanet.enhancement.script.v1.";
    public static void main(String[] args) throws Exception {
        Path settings = Path.of(System.getProperty("lucanet.enhancement.features")); Files.createDirectories(settings.getParent());
        Files.deleteIfExists(settings);
        ProcessLoggerExecutionPanel panel = edt(ProcessLoggerExecutionPanel::new);
        edt(() -> { panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ALT_R,"original"); return null; });
        // 让类转换触发的观察器先安装，再将面板放入容器，走真实 AWT ContainerEvent 注册。
        edt(() -> null);
        edt(() -> { panel.setProcessLoggerTask(new ScriptExecutionDialogCreator.a(),"script",null);new JPanel().add(panel);return null; });
        waitFor(() -> !panel.button().isVisible() && bound(panel,ALT_R),"default hidden, Alt+R installed");
        check(panel.executions == 0,"opening/configuration never executes script");
        Files.writeString(settings,"hideScriptStart=false\nscriptRunShortcut=ALT+R\n");
        waitFor(() -> panel.button().isVisible(),"explicit display preference preserved");
        Files.writeString(settings,"linksInNewWindow=false\nhideScriptStart=true\nscriptRunShortcut=ALT+R\n");
        waitFor(() -> !panel.button().isVisible(),"live hide on existing panel");
        edt(() -> { fire(panel,ALT_R); return null; });
        check(panel.executions == 1 && panel.canStartChecks == 1,"hidden button uses original guarded listener");
        edt(() -> { fire(panel,ALT_R); rearm(panel); fire(panel,ALT_R); return null; });
        check(panel.executions == 1,"repeat key does not rerun while busy or held");
        edt(() -> { release(panel,KeyEvent.VK_R);fire(panel,ALT_R);return null; });
        check(panel.executions == 2,"physical release permits next explicit run");
        Files.writeString(settings,"linksInNewWindow=false\nhideScriptStart=true\nscriptRunShortcut=CTRL+SHIFT+K\n");
        waitFor(() -> bound(panel,CTRL_K) && "original".equals(panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(ALT_R)),"live rebind and restore previous mapping");
        edt(() -> { rearm(panel); panel.showing=false;fire(panel,CTRL_K);return null; });
        check(panel.executions == 2,"background panel never executes");
        edt(() -> { panel.showing=true;panel.button().setEnabled(false);fire(panel,CTRL_K);return null; });
        check(panel.executions == 2,"disabled native button respected");
        edt(() -> { panel.button().setEnabled(true);panel.allowed=false;fire(panel,CTRL_K);return null; });
        check(panel.executions == 2 && panel.canStartChecks == 3,"original canStart can refuse execution");
        edt(() -> { release(panel,KeyEvent.VK_K);panel.allowed=true;fire(panel,CTRL_K);return null; });
        check(panel.executions == 3,"new shortcut uses unchanged original execution");
        Files.writeString(settings,"hideScriptStart=false\nscriptRunShortcut=CTRL+SHIFT+K\n");
        Thread.sleep(350);edt(() -> null);
        check(!edt(() -> panel.button().isVisible()),"unhide does not expose native busy button");
        edt(() -> { rearm(panel);return null; });
        waitFor(() -> panel.button().isVisible(),"unhide restores original ready button");
        edt(() -> { panel.button().doClick(0);return null; });
        check(panel.executions == 4,"visible mouse button retains original action");
        ProcessLoggerExecutionPanel other = edt(ProcessLoggerExecutionPanel::new);
        Files.writeString(settings,"hideScriptStart=true\nscriptRunShortcut=ALT+R\n");
        edt(() -> { other.setProcessLoggerTask(new OtherTask(),"not script",null);new JPanel().add(other);rearm(panel);return null; });
        waitFor(() -> !panel.button().isVisible(),"hide changed back");
        check(edt(() -> other.button().isVisible() && other.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(ALT_R)==null),"unrelated processing dialog unchanged");
        edt(() -> {other.button().setVisible(false);return null;});Thread.sleep(350);
        check(!edt(() -> other.button().isVisible()),"unrelated task's own visibility is never overwritten");
        Files.writeString(settings,"hideScriptStart=true\nscriptRunShortcut=invalid\n");
        waitFor(() -> panel.button().isVisible() && bound(panel,ALT_R),"invalid settings recover visible button and default shortcut");
        edt(() -> { ((AutoCloseable)UIManager.get(PREFIX+"controller")).close();return null; });
        check(edt(() -> "original".equals(panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(ALT_R)) && panel.button().isVisible()),"module cleanup restores original UI/bindings");
        java.lang.reflect.Method parse = Class.forName("cn.lucanet.enhancement.exit.ScriptShortcut").getDeclaredMethod("parse",String.class);parse.setAccessible(true);
        for(String invalid:new String[]{"R","SHIFT+R","CTRL+CTRL+R","ALT+F4","CTRL+ENTER","WIN+R"}) {
            try { parse.invoke(null,invalid);throw new AssertionError("Invalid shortcut accepted: "+invalid); }
            catch(java.lang.reflect.InvocationTargetException expected) { if(!(expected.getCause() instanceof IllegalArgumentException))throw expected; }
        }
        System.out.println("PASS: script-only live hide/unhide, direct original click, Alt+R/rebind, native guards, no auto-run/repeat/background execution, invalid settings recovery and cleanup");
    }
    private static final class OtherTask implements com.lucanet.lisa.widgets.processlogger.ProcessLoggerTask { }
    private static void rearm(ProcessLoggerExecutionPanel panel) { panel.setProcessLoggerTask(new ScriptExecutionDialogCreator.a(),"script",null); }
    private static boolean bound(JComponent panel,KeyStroke stroke) { Object key=panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);return key!=null && !key.equals("original") && panel.getActionMap().get(key)!=null; }
    private static void fire(JComponent panel,KeyStroke stroke) { Object key=panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);if(key==null)throw new AssertionError("Shortcut not installed");panel.getActionMap().get(key).actionPerformed(new ActionEvent(panel,0,"shortcut")); }
    private static void release(Component panel,int key) { ((AWTEventListener)UIManager.get(PREFIX+"controller")).eventDispatched(new KeyEvent(panel,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),0,key,KeyEvent.CHAR_UNDEFINED)); }
    private static <T>T edt(Callable<T> call)throws Exception { FutureTask<T> task=new FutureTask<>(call);EventQueue.invokeAndWait(task);return task.get(); }
    private static void waitFor(Callable<Boolean> condition,String description)throws Exception { long deadline=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<deadline){if(edt(condition))return;Thread.sleep(25);}throw new AssertionError(description); }
    private static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
