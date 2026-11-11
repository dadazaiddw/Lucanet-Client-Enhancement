package cn.lucanet.enhancement.exit;

import javax.swing.KeyStroke;

final class ScriptPreferences {
    final boolean hideStart;
    final KeyStroke shortcut;
    ScriptPreferences(boolean hideStart, KeyStroke shortcut) { this.hideStart = hideStart; this.shortcut = shortcut; }
    @Override public boolean equals(Object other) {
        return other instanceof ScriptPreferences && ((ScriptPreferences) other).hideStart == hideStart
                && shortcut.equals(((ScriptPreferences) other).shortcut);
    }
    @Override public int hashCode() { return shortcut.hashCode() * 31 + (hideStart ? 1 : 0); }
}
