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
