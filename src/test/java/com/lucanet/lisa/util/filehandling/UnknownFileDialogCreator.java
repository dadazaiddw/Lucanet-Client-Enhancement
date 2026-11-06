package com.lucanet.lisa.util.filehandling;
import com.lucanet.lisa.ui.UIContext;
import ln.ui_x.XWindow;
import ln.ui_x.dialog.result.ResultMap;
/** 真实弹窗方法的签名替身：被执行即计数，断言退出时没有走显示路径。 */
public final class UnknownFileDialogCreator {
    public enum Key { ACTION_TYPE, NEW_FILE }
    public static int shown;
    static ResultMap<Key> a(XWindow owner, UIContext context, String name) {
        shown++;
        return new ResultMap<Key>(false).set(Key.ACTION_TYPE, Byte.valueOf((byte)4));
    }
    public static byte show(String name) {
        return (Byte) a(null, null, name).get(Key.ACTION_TYPE);
    }
}
