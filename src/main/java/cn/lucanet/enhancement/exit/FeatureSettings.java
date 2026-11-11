package cn.lucanet.enhancement.exit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.util.Properties;

/** GUI 原子写入的共享配置：导航按次读取，脚本控制在后台检查，无需注销。 */
final class FeatureSettings {
    private static Path file() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(System.getProperty("lucanet.enhancement.features",
                Path.of(local == null ? System.getProperty("user.home") : local,
                        "Lucanet", "ClientEnhancement", "features.properties").toString()));
    }
    private static Properties load() throws Exception {
        Path file = file();
        Properties settings = new Properties();
        if (!Files.exists(file)) { return settings; }
        try (Reader reader = Files.newBufferedReader(file)) {
            settings.load(reader);
            return settings;
        }
    }
    static boolean openLinksInNewWindow() {
        try {
            Properties settings = load();
            return "true".equalsIgnoreCase(settings.getProperty("linksInNewWindow", "true").trim());
        } catch (Exception failure) {
            // 设置暂不可读时保留原生导航，避免配置故障阻断用户操作。
            EnhancementLog.error("FEATURE_SETTINGS_UNAVAILABLE", failure);
            return false;
        }
    }
    static ScriptPreferences scriptPreferences() {
        try {
            Properties settings = load();
            return new ScriptPreferences(Boolean.parseBoolean(settings.getProperty("hideScriptStart", "true")),
                    ScriptShortcut.parse(settings.getProperty("scriptRunShortcut", "ALT+R")));
        } catch (Exception failure) {
            // 设置无法读取/快捷键无效时恢复原按钮，避免把唯一可用入口隐藏。
            return new ScriptPreferences(false, ScriptShortcut.parse("ALT+R"));
        }
    }
    static boolean openScriptOnDoubleClick() {
        try { return Boolean.parseBoolean(load().getProperty("scriptDoubleClick", "true")); }
        catch (Exception failure) { return false; }
    }
}
