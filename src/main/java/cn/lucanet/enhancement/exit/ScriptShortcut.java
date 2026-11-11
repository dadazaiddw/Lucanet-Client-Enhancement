package cn.lucanet.enhancement.exit;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import javax.swing.KeyStroke;

/** 与 GUI 共用的简洁配置格式：CTRL/ALT/SHIFT + 字母、数字或 F1-F12。 */
final class ScriptShortcut {
    static KeyStroke parse(String value) {
        String[] parts = value.toUpperCase(Locale.ROOT).replace(" ", "").split("\\+", -1);
        int modifiers = 0;
        for (int i = 0; i < parts.length - 1; i++) {
            int flag;
            switch (parts[i]) {
                case "CTRL": flag = InputEvent.CTRL_DOWN_MASK; break;
                case "ALT": flag = InputEvent.ALT_DOWN_MASK; break;
                case "SHIFT": flag = InputEvent.SHIFT_DOWN_MASK; break;
                default: throw new IllegalArgumentException("Unsupported shortcut modifier");
            }
            if ((modifiers & flag) != 0) { throw new IllegalArgumentException("Duplicate shortcut modifier"); }
            modifiers |= flag;
        }
        if ((modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0) {
            throw new IllegalArgumentException("Shortcut requires Ctrl or Alt");
        }
        String key = parts[parts.length - 1]; int code;
        if (key.matches("[A-Z0-9]")) { code = key.charAt(0); }
        else if (key.matches("F([1-9]|1[0-2])")) { code = KeyEvent.VK_F1 + Integer.parseInt(key.substring(1)) - 1; }
        else { throw new IllegalArgumentException("Unsupported shortcut key"); }
        if (code == KeyEvent.VK_F4 && (modifiers & InputEvent.ALT_DOWN_MASK) != 0
                || code == KeyEvent.VK_DELETE && (modifiers & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) != 0) {
            throw new IllegalArgumentException("Reserved system shortcut");
        }
        return KeyStroke.getKeyStroke(code, modifiers);
    }
}
