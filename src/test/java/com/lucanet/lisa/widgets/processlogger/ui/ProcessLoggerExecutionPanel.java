package com.lucanet.lisa.widgets.processlogger.ui;

import javax.swing.*;
import com.lucanet.lisa.widgets.LNButton;
import com.lucanet.lisa.widgets.processlogger.ProcessLoggerTask;

/** 已核实的字段/方法契约替身；native ready 信号与事件保持单独验证。 */
public final class ProcessLoggerExecutionPanel extends JPanel {
    private final LNButton i = new LNButton("开始");
    public int executions, canStartChecks;
    public boolean allowed = true, showing = true;
    public enum State { IDLE, ARMED, STARTED, STOPPED }
    public interface Hook { }
    public ProcessLoggerExecutionPanel() {
        add(i); i.addActionListener(event -> {
            if (StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES).walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals("java.lang.reflect.Method")))) {
                throw new IllegalStateException("no reflection allowed!");
            }
            canStartChecks++;
            if (allowed) { a(State.STARTED); executions++; }
        });
        a(State.IDLE);
    }
    public void setProcessLoggerTask(ProcessLoggerTask task, String description, Hook hook) { a(State.ARMED); }
    private void a(State state) { if (state == State.ARMED) { i.setVisible(true); } else { i.setVisible(false); } }
    public JButton button() { return i; }
    @Override public boolean isShowing() { return showing; }
}
