using System;
using System.IO;
using System.Text;
using System.Web.Script.Serialization;

// 只保存本管理界面的偏好，与 Lucanet 配置、JDK 和安装状态无关。
internal sealed class GuiPreferences {
    public bool AutoRefresh { get; set; }
    public int RefreshSeconds { get; set; }
    public bool AutoCheckUpdates { get; set; }
    public string LastUpdateCheck { get; set; }
    public GuiPreferences() { AutoRefresh = true; RefreshSeconds = 8; AutoCheckUpdates = false; LastUpdateCheck = ""; }
}

internal sealed class GuiSettingsStore {
    internal const int MinimumSeconds = 2;
    internal const int MaximumSeconds = 300;
    private readonly string path;
    internal GuiSettingsStore(string path) { this.path = path; }
    internal FeatureSettingsStore Features { get { return new FeatureSettingsStore(Path.Combine(Path.GetDirectoryName(path), "features.properties")); } }
    internal static GuiSettingsStore ForCurrentUser() {
        return new GuiSettingsStore(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Lucanet", "ClientEnhancement", "ui.json"));
    }
    internal GuiPreferences Load() {
        try {
            if (File.Exists(path)) {
                var settings = new JavaScriptSerializer().Deserialize<GuiPreferences>(File.ReadAllText(path, Encoding.UTF8));
                if (settings != null && settings.RefreshSeconds >= MinimumSeconds && settings.RefreshSeconds <= MaximumSeconds)
                    return settings;
            }
        } catch (IOException) { } catch (UnauthorizedAccessException) { } catch (ArgumentException) { } catch (InvalidOperationException) { }
        return new GuiPreferences();
    }
    internal void Save(GuiPreferences settings) {
        if (settings.RefreshSeconds < MinimumSeconds || settings.RefreshSeconds > MaximumSeconds)
            throw new ArgumentOutOfRangeException("RefreshSeconds");
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        string temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try {
            File.WriteAllText(temporary, new JavaScriptSerializer().Serialize(settings), new UTF8Encoding(false));
            if (File.Exists(path)) File.Replace(temporary, path, null);
            else File.Move(temporary, path);
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}

// 与 Java 端共享的即时配置，独立于窗口/刷新偏好，避免刷新设置覆盖功能开关。
internal sealed class FeatureSettingsStore {
    private readonly string path;
    internal FeatureSettingsStore(string path) { this.path = path; }
    internal bool LoadLinksInNewWindow() {
        if (!File.Exists(path)) return true;
        foreach (string line in File.ReadAllLines(path, Encoding.UTF8)) {
            if (line.Trim().StartsWith("linksInNewWindow=", StringComparison.Ordinal))
                return line.Trim().Substring("linksInNewWindow=".Length).Trim().Equals("true", StringComparison.OrdinalIgnoreCase);
        }
        return true;
    }
    internal void SaveLinksInNewWindow(bool enabled) {
        SaveValues(new System.Collections.Generic.Dictionary<string,string> {{"linksInNewWindow", enabled ? "true" : "false"}});
    }
    internal bool LoadScriptDoubleClick() {
        if(File.Exists(path))foreach(string source in File.ReadAllLines(path,Encoding.UTF8)) {
            string line=source.Trim();
            if(line.StartsWith("scriptDoubleClick=",StringComparison.Ordinal))return line.Substring("scriptDoubleClick=".Length).Equals("true",StringComparison.OrdinalIgnoreCase);
        }
        return true;
    }
    internal void SaveScriptDoubleClick(bool enabled) {
        SaveValues(new System.Collections.Generic.Dictionary<string,string>{{"scriptDoubleClick",enabled ? "true" : "false"}});
    }
    internal ScriptFeatureSettings LoadScript() {
        bool hidden = true; string shortcut = "ALT+R";
        if (File.Exists(path)) foreach (string source in File.ReadAllLines(path, Encoding.UTF8)) {
            string line = source.Trim();
            if (line.StartsWith("hideScriptStart=", StringComparison.Ordinal)) hidden = line.Substring("hideScriptStart=".Length).Equals("true", StringComparison.OrdinalIgnoreCase);
            if (line.StartsWith("scriptRunShortcut=", StringComparison.Ordinal)) shortcut = line.Substring("scriptRunShortcut=".Length);
        }
        try { return new ScriptFeatureSettings(hidden, ScriptShortcutSetting.Normalize(shortcut)); }
        catch (ArgumentException) { return new ScriptFeatureSettings(false, "ALT+R"); }
    }
    internal void SaveScript(ScriptFeatureSettings settings) {
        string shortcut = ScriptShortcutSetting.Normalize(settings.Shortcut);
        SaveValues(new System.Collections.Generic.Dictionary<string,string> {
            {"hideScriptStart",settings.HideStart ? "true" : "false"}, {"scriptRunShortcut",shortcut}
        });
    }
    private void SaveValues(System.Collections.Generic.Dictionary<string,string> values) {
        Directory.CreateDirectory(Path.GetDirectoryName(path));
        var lines = new System.Collections.Generic.List<string>();
        if (File.Exists(path)) foreach (string line in File.ReadAllLines(path, Encoding.UTF8)) {
            bool replaced = false;
            foreach (string key in values.Keys) if (line.Trim().StartsWith(key+"=", StringComparison.Ordinal)) { replaced = true; break; }
            if (!replaced) lines.Add(line);
        }
        foreach (var value in values) lines.Add(value.Key + "=" + value.Value);
        string temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try {
            File.WriteAllLines(temporary, lines, new UTF8Encoding(false));
            if (File.Exists(path)) File.Replace(temporary, path, null); else File.Move(temporary, path);
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
