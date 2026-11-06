package cn.lucanet.enhancement.exit;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 被转换后的客户端类调用的固定实现；没有自定义类名、回调或插件加载接口。 */
public final class NativeBridge {
    static String agentJar;
    private static final String CLIENT_MAIN = "com/lucanet/assembly/summa/client/SummaClientStart";
    private static final Set<String> MAINS = Set.of(CLIENT_MAIN,
            "com/lucanet/lnswmgr_ui/Main", "com/lucanet/lnswmgr_kickstart/Main",
            "com/lucanet/lnswmgr_console/ConsoleMain");
    private static final ClassValue<AtomicBoolean> ARCHIVING = new ClassValue<>() {
        @Override protected AtomicBoolean computeValue(Class<?> type) { return new AtomicBoolean(); }
    };

    private NativeBridge() { }

    /** 在 Manager/KickStart 生成下一段原生启动配置时，传递同一个 Agent。 */
    public static String augmentConfiguration(String configuration) {
        try {
            if (configuration == null || agentJar == null) { return configuration; }
            String main = null;
            for (String line : configuration.split("\\R")) {
                if (line.startsWith("mainClass=")) { main = line.substring(10).trim().replace('.', '/'); }
            }
            if (main == null || !MAINS.contains(main)) { return configuration; }
            String option = "jdkParameter=-javaagent:" + agentJar;
            if (configuration.lines().anyMatch(option::equals)) { return configuration; }
            EnhancementLog.info("PROPAGATED: agent to " + main);
            return configuration + (configuration.endsWith("\n") ? "" : "\n") + option + "\n";
        } catch (RuntimeException failure) {
            EnhancementLog.error("PROPAGATION_FAILED", failure);
            return configuration;
        }
    }

    /** 在 Application.stop 开始、原文件检查之前归档所有文件。 */
    public static void beforeStop(Class<?> application) {
        if (!isFinancialClient(application.getClassLoader()) || !ARCHIVING.get(application).compareAndSet(false, true)) { return; }
        try {
            if (!state(application).equals("RUNNING")) { return; }
            EnhancementLog.info("EXIT_ARCHIVE_BEGIN");
            Object paths = application.getMethod("getApplicationPaths").invoke(null);
            Class<?> pathsType = Class.forName("ln.application.ApplicationPathes", false, application.getClassLoader());
            File directory = (File) pathsType.getMethod("getTempDir").invoke(paths);
            Path archive = Path.of(System.getProperty("lucanet.exit.archiveDir",
                    Path.of(System.getProperty("user.home"), "LucaNet-exit-archive").toString()));
            TempFileArchiver.archiveAllFiles(directory == null ? null : directory.toPath(), archive);
            EnhancementLog.info("EXIT_ARCHIVE_FINISHED");
        } catch (Exception | LinkageError failure) {
            // 无法归档的文件保留在原处。后面的退出弹窗处理仍可静默返回“保留”。
            EnhancementLog.error("EXIT_ARCHIVE_FAILED: original files retained", failure);
        } finally {
            ARCHIVING.get(application).set(false);
        }
    }

    /** 仅在退出阶段屏蔽“未知文件”对话框；运行/启动阶段和其他业务对话框保留原行为。 */
    public static boolean suppressExitDialog(Class<?> dialogClass) {
        try {
            ClassLoader loader = dialogClass.getClassLoader();
            if (!isFinancialClient(loader)) { return false; }
            String current = state(Class.forName("ln.application.Application", false, loader));
            if (current.equals("PREPARE_STOP") || current.equals("STOPPING") || current.equals("DISPOSING")) {
                EnhancementLog.info("EXIT_DIALOG_SUPPRESSED: remaining file kept in place");
                return true;
            }
        } catch (Exception | LinkageError failure) {
            EnhancementLog.error("DIALOG_STATE_UNAVAILABLE", failure);
        }
        return false;
    }

    private static String state(Class<?> application) throws ReflectiveOperationException {
        Object value = application.getMethod("getApplicationState").invoke(null);
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    private static boolean isFinancialClient(ClassLoader loader) {
        return loader != null && loader.getResource(CLIENT_MAIN + ".class") != null;
    }
}
