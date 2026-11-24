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
    static boolean nativeSceneDialogFrame() {
        try { return Boolean.parseBoolean(load().getProperty("nativeSceneDialogFrame", "true").trim()); }
        catch (Exception failure) { return false; }
    }
    static boolean javaEditorAssist() {
        try { return Boolean.parseBoolean(load().getProperty("javaEditorAssist", "true").trim()); }
        catch (Exception failure) { return false; }
    }
    static boolean customJavaTypes() {
        try { return Boolean.parseBoolean(load().getProperty("customJavaTypes", "false").trim()); }
        catch(Exception unavailable){return false;}
    }
    static final int DEFAULT_JAVA_FONT_SIZE=20;
    static int javaCompletionFontSize() {
        try { int value=Integer.parseInt(load().getProperty("javaCompletionFontSize","").trim());return value>=12&&value<=30?value:DEFAULT_JAVA_FONT_SIZE; }
        catch(Exception failure){return DEFAULT_JAVA_FONT_SIZE;}
    }
    static final String DEFAULT_ARCHIVE_DIRECTORY=Path.of(System.getProperty("user.home"),".lucanet-exit-enhancement","archive").toString();
    static final String DEFAULT_LOG_DIRECTORY=Path.of(System.getProperty("user.home"),".lucanet-cilent-enhancement","log").toString();
    static final int DEFAULT_RETENTION_DAYS=7, MAX_RETENTION_DAYS=36500;
    static final class StoragePreferences {
        final boolean enabled;final Path directory;final int days;
        StoragePreferences(boolean enabled,Path directory,int days){this.enabled=enabled;this.directory=directory;this.days=days;}
    }
    static StoragePreferences archive(){return storage("archive",DEFAULT_ARCHIVE_DIRECTORY,"lucanet.exit.archiveDir");}
    static StoragePreferences logs(){return storage("enhancementLog",DEFAULT_LOG_DIRECTORY,"lucanet.exit.logDir");}
    private static StoragePreferences storage(String prefix,String defaultDirectory,String override){
        Properties settings;
        try{settings=load();}catch(Exception unavailable){return new StoragePreferences(false,expandDirectory(defaultDirectory),DEFAULT_RETENTION_DAYS);}
        String configured=System.getProperty(override);
        if(configured==null||configured.isBlank())configured=settings.getProperty(prefix+"Directory",defaultDirectory);
        Path directory;
        try{directory=expandDirectory(configured);}catch(RuntimeException invalid){directory=expandDirectory(defaultDirectory);}
        int days=DEFAULT_RETENTION_DAYS;
        try{int value=Integer.parseInt(settings.getProperty(prefix+"RetentionDays","").trim());if(value>=1&&value<=MAX_RETENTION_DAYS)days=value;}catch(NumberFormatException ignored){}
        return new StoragePreferences(Boolean.parseBoolean(settings.getProperty(prefix+"Enabled","true").trim()),directory,days);
    }
    static Path expandDirectory(String value){
        String path=value.trim();
        if(path.equals("~"))path=System.getProperty("user.home");
        else if(path.startsWith("~/")||path.startsWith("~\\"))path=Path.of(System.getProperty("user.home"),path.substring(2)).toString();
        Path result=Path.of(path);
        if(!result.isAbsolute())throw new IllegalArgumentException("Storage directory must be absolute or start with ~/.");
        return result.normalize();
    }
}
