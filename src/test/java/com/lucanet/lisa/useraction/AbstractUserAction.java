package com.lucanet.lisa.useraction;
import java.awt.event.ActionEvent;
import java.lang.StackWalker;
import javax.swing.AbstractAction;
import com.lucanet.lisa.ui.UIController;
public final class AbstractUserAction extends AbstractAction {
    public static Runnable serverGuard = () -> {
        if (StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES).walk(frames ->
                frames.anyMatch(frame -> frame.getClassName().equals("java.lang.reflect.Method")))) {
            throw new IllegalStateException("no reflection allowed!");
        }
    };
    private final String uri;
    public AbstractUserAction(String uri) { this.uri = uri; }
    @Override public boolean isEnabled() { return !UIController.disabledAction; }
    @Override public void actionPerformed(ActionEvent event) { executeNow(event); }
    public void executeNow(ActionEvent event) {
        // 对齐生产 VirtualSocket 的调用栈检查。之前的反射实现必须在本替身中失败。
        serverGuard.run();
        if (UIController.failBeforeWindow) { throw new IllegalStateException("before creating the new window"); }
        UIController.newWindows++;
        if (UIController.failAction) { throw new IllegalStateException("created window, navigation not executed"); }
        UIController.lastUri = uri;
    }
}
