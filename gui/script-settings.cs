using System;
using System.Collections.Generic;
using System.Drawing;
using System.Text.RegularExpressions;
using System.Windows.Forms;

internal sealed class ScriptFeatureSettings {
    internal readonly bool HideStart; internal readonly string Shortcut;
    internal ScriptFeatureSettings(bool hideStart, string shortcut) { HideStart = hideStart; Shortcut = shortcut; }
}

internal static class ScriptShortcutSetting {
    internal static string Normalize(string value) {
        string[] parts = (value ?? "").ToUpperInvariant().Replace(" ","").Split('+');
        var modifiers = new HashSet<string>();
        for (int i = 0; i < parts.Length-1; i++) {
            string part = parts[i];
            if ((part != "CTRL" && part != "ALT" && part != "SHIFT") || !modifiers.Add(part)) throw new ArgumentException("快捷键修饰键无效。");
        }
        string key = parts[parts.Length-1];
        if ((!modifiers.Contains("CTRL") && !modifiers.Contains("ALT")) || !Regex.IsMatch(key,@"^([A-Z0-9]|F([1-9]|1[0-2]))$"))
            throw new ArgumentException("请使用 Ctrl 或 Alt，配合字母、数字或 F1–F12；可同时按 Shift。");
        if (modifiers.Contains("ALT") && key == "F4") throw new ArgumentException("Alt+F4 是系统关闭快捷键。");
        var normalized = new List<string>();
        foreach (string modifier in new[]{"CTRL","ALT","SHIFT"}) if (modifiers.Contains(modifier)) normalized.Add(modifier);
        normalized.Add(key); return String.Join("+",normalized);
    }
    internal static string Capture(Keys keys) {
        Keys key = keys & Keys.KeyCode; string name;
        if (key >= Keys.A && key <= Keys.Z || key >= Keys.D0 && key <= Keys.D9) name = ((char)(int)key).ToString();
        else if (key >= Keys.F1 && key <= Keys.F12) name = "F" + ((int)key-(int)Keys.F1+1);
        else throw new ArgumentException("请使用字母、数字或 F1–F12。");
        return Normalize(((keys & Keys.Control) != 0 ? "CTRL+" : "") + ((keys & Keys.Alt) != 0 ? "ALT+" : "")
            + ((keys & Keys.Shift) != 0 ? "SHIFT+" : "") + name);
    }
}

// 点击后直接按组合键；只保存完整、有效的组合，未完成的修饰键不会覆盖现有设置。
internal sealed class ShortcutBox : TextBox {
    private string shortcut = "ALT+R";
    internal event EventHandler ShortcutChanged;
    internal event Action<string> ShortcutRejected;
    internal string Shortcut {
        get { return shortcut; }
        set {
            string normalized = ScriptShortcutSetting.Normalize(value);
            bool changed = shortcut != normalized; shortcut = normalized;
            Text = shortcut.Replace("CTRL","Ctrl").Replace("ALT","Alt").Replace("SHIFT","Shift").Replace("+"," + ");
            if (changed && ShortcutChanged != null) ShortcutChanged(this,EventArgs.Empty);
        }
    }
    internal ShortcutBox() {
        Name = "scriptRunShortcut"; ReadOnly = true; ShortcutsEnabled = false; TextAlign = HorizontalAlignment.Center;
        Width = 180; BackColor = Color.White; AccessibleName = "脚本开始快捷键";
        AccessibleDescription = "点击后按下 Ctrl 或 Alt 加字母、数字或功能键，可同时使用 Shift。";
        Shortcut = "ALT+R";
    }
    protected override bool ProcessCmdKey(ref Message message, Keys keys) {
        if ((keys & Keys.KeyCode) == Keys.Tab) return base.ProcessCmdKey(ref message,keys);
        CaptureShortcut(keys); return true;
    }
    private void CaptureShortcut(Keys keys) {
        Keys key = keys & Keys.KeyCode;
        if (key == Keys.ControlKey || key == Keys.Menu || key == Keys.ShiftKey) return;
        try { Shortcut = ScriptShortcutSetting.Capture(keys); }
        catch (ArgumentException error) { if (ShortcutRejected != null) ShortcutRejected(error.Message); }
    }
    protected override void OnKeyDown(KeyEventArgs e) {
        if (e.KeyCode == Keys.Tab) { base.OnKeyDown(e); return; }
        e.SuppressKeyPress = true; e.Handled = true;
        CaptureShortcut(e.KeyData);
    }
}
