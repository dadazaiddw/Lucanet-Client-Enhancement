package cn.lucanet.enhancement.exit;

import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** 只叠加脚本面板的显示和键盘操作，不调用脚本引擎、不改变原生 start/canStart。 */
final class ScriptControls implements AutoCloseable, AWTEventListener {
    static final String PREFIX = "cn.lucanet.enhancement.script.v1.";
    static final String BUTTON = PREFIX + "button", TASK = PREFIX + "task", VISIBLE = PREFIX + "visible", REFRESH = PREFIX + "refresh";
    private static final String ACTIVE = PREFIX + "controller";
    private static ScriptControls instance;
    private final Map<JComponent, Binding> bindings = new WeakHashMap<>();
    private final ScheduledExecutorService watcher;
    private volatile ScriptPreferences preferences = new ScriptPreferences(true, ScriptShortcut.parse("ALT+R"));
    private volatile boolean hasPanels, closed;

    static void ensureInstalled() {
        EventQueue.invokeLater(() -> {
            if (instance != null && UIManager.get(ACTIVE) == instance && !instance.closed) { return; }
            Object previous = UIManager.get(ACTIVE);
            if (previous instanceof AutoCloseable) {
                try { ((AutoCloseable) previous).close(); } catch (Exception failure) { EnhancementLog.error("SCRIPT_CONTROLS_CLEANUP_FAILED", failure); }
            }
            instance = new ScriptControls(); UIManager.put(ACTIVE, instance);
            for (Window window : Window.getWindows()) { if (window.isDisplayable()) { instance.scan(window); } }
        });
    }
    private ScriptControls() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.WINDOW_EVENT_MASK | AWTEvent.CONTAINER_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "client-enhancement-script-settings"); thread.setDaemon(true); thread.setContextClassLoader(null); return thread;
        });
        watcher.scheduleWithFixedDelay(() -> {
            if (!hasPanels || closed) { return; }
            ScriptPreferences next = FeatureSettings.scriptPreferences();
            EventQueue.invokeLater(() -> {
                if (closed) { return; }
                preferences = next;
                for (Binding binding : new ArrayList<>(bindings.values())) { binding.apply(); }
                hasPanels = !bindings.isEmpty();
            });
        }, 0, 250, TimeUnit.MILLISECONDS);
    }
    @Override public void eventDispatched(AWTEvent event) {
        if (closed) { return; }
        if (event instanceof ContainerEvent && event.getID() == ContainerEvent.COMPONENT_ADDED) {
            scan(((ContainerEvent) event).getChild());
        } else if (event instanceof WindowEvent) {
            Window window = ((WindowEvent) event).getWindow();
            if (event.getID() == WindowEvent.WINDOW_OPENED) { scan(window); }
            if (event.getID() == WindowEvent.WINDOW_DEACTIVATED || event.getID() == WindowEvent.WINDOW_CLOSED) {
                for (Binding binding : new ArrayList<>(bindings.values())) {
                    binding.held = false;
                    JComponent panel = binding.panel.get();
                    if (event.getID() == WindowEvent.WINDOW_CLOSED && panel != null && SwingUtilities.getWindowAncestor(panel) == window) { binding.close(); }
                }
            }
        } else if (event instanceof KeyEvent && event.getID() == KeyEvent.KEY_RELEASED) {
            for (Binding binding : bindings.values()) {
                if (binding.stroke != null && binding.stroke.getKeyCode() == ((KeyEvent) event).getKeyCode()) { binding.held = false; }
            }
        }
    }
    private void scan(Component component) {
        if (component instanceof JComponent) {
            JComponent panel = (JComponent) component;
            if (panel.getClientProperty(BUTTON) instanceof AbstractButton && !bindings.containsKey(panel)) {
                Binding binding = new Binding(panel, (AbstractButton) panel.getClientProperty(BUTTON));
                bindings.put(panel, binding); hasPanels = true; binding.apply();
            }
        }
        if (component instanceof Container) { for (Component child : ((Container) component).getComponents()) { scan(child); } }
    }
    @Override public void close() {
        if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater(this::close); return; }
        if (closed) { return; } closed = true; watcher.shutdownNow();
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
        for (Binding binding : new ArrayList<>(bindings.values())) { binding.close(); }
        bindings.clear(); hasPanels = false;
    }
    private final class Binding implements PropertyChangeListener {
        final WeakReference<JComponent> panel;
        final WeakReference<AbstractButton> button;
        final Object actionKey = new Object();
        KeyStroke stroke; Object previousKey; boolean previousLocal, held, detached, wasScript;
        Binding(JComponent panel, AbstractButton button) {
            this.panel = new WeakReference<>(panel); this.button = new WeakReference<>(button);
            panel.addPropertyChangeListener(this);
        }
        private boolean isScript(JComponent component) {
            Object task = component.getClientProperty(TASK);
            if (task == null) { return false; }
            String type = task.getClass().getName();
            return type.startsWith("com.lucanet.summa.client.sections.script.dialogs.ScriptExecutionDialogCreator$")
                    || type.equals("com.lucanet.common.dom.script.ScriptDesignerSectionConfig$c");
        }
        @Override public void propertyChange(PropertyChangeEvent event) {
            if (REFRESH.equals(event.getPropertyName()) || TASK.equals(event.getPropertyName())) { apply(); }
        }
        void apply() {
            if (closed || detached) { return; }
            if (!EventQueue.isDispatchThread()) { EventQueue.invokeLater(this::apply); return; }
            JComponent target = panel.get(); AbstractButton start = button.get();
            if (target == null || start == null) { return; }
            boolean script = isScript(target), nativeVisible = Boolean.TRUE.equals(start.getClientProperty(VISIBLE));
            boolean visible = nativeVisible && (!script || !preferences.hideStart);
            if ((script || wasScript) && start.isVisible() != visible) { start.setVisible(visible); target.revalidate(); target.repaint(); }
            wasScript = script;
            KeyStroke next = script ? preferences.shortcut : null;
            if (!Objects.equals(stroke, next)) { unbind(); if (next != null) { bind(next); } }
        }
        private void bind(KeyStroke next) {
            JComponent target = panel.get(); if (target == null) { return; }
            InputMap input = target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            previousLocal = input.keys() != null && Arrays.asList(input.keys()).contains(next);
            previousKey = input.get(next); stroke = next;
            input.put(next, actionKey);
            target.getActionMap().put(actionKey, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    JComponent component = panel.get(); AbstractButton start = button.get();
                    if (held || component == null || start == null || !component.isShowing() || !isScript(component)
                            || !start.isEnabled() || !Boolean.TRUE.equals(start.getClientProperty(VISIBLE))) { return; }
                    held = true;
                    // doClick 使用原按钮模型及监听器；调用栈无反射，不越过原生 canStart/运行锁。
                    start.doClick(0);
                }
            });
        }
        private void unbind() {
            JComponent target = panel.get();
            if (target != null && stroke != null) {
                InputMap input = target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
                if (input.get(stroke) == actionKey) { if (previousLocal) { input.put(stroke, previousKey); } else { input.remove(stroke); } }
                target.getActionMap().remove(actionKey);
            }
            stroke = null; previousKey = null; held = false;
        }
        void close() {
            if (detached) { return; } detached = true;
            JComponent target = panel.get(); AbstractButton start = button.get();
            unbind();
            if (target != null) {
                target.removePropertyChangeListener(this); bindings.remove(target);
                if (start != null && wasScript) { start.setVisible(Boolean.TRUE.equals(start.getClientProperty(VISIBLE))); }
            }
        }
    }
}
