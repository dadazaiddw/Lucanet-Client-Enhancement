package cn.lucanet.enhancement.exit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.Reader;
import java.util.Properties;

/** GUI 原子写入，客户端每次收到外部链接时读取；不缓存开关，无需注销。 */
final class FeatureSettings {
    static boolean openLinksInNewWindow() {
        String local = System.getenv("LOCALAPPDATA");
        Path file = Path.of(System.getProperty("lucanet.enhancement.features",
                Path.of(local == null ? System.getProperty("user.home") : local,
                        "Lucanet", "ClientEnhancement", "features.properties").toString()));
        if (!Files.exists(file)) { return true; }
        try (Reader reader = Files.newBufferedReader(file)) {
            Properties settings = new Properties();
            settings.load(reader);
            return "true".equalsIgnoreCase(settings.getProperty("linksInNewWindow", "true").trim());
        } catch (Exception failure) {
            // 设置暂不可读时保留原生导航，避免配置故障阻断用户操作。
            EnhancementLog.error("FEATURE_SETTINGS_UNAVAILABLE", failure);
            return false;
        }
    }
}
