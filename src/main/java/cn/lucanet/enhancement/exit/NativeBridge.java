package cn.lucanet.enhancement.exit;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 被转换后的客户端类调用的固定实现；没有自定义类名、回调或插件加载接口。 */
public final class NativeBridge {
    static String agentJar;
    private static final String FINANCIAL_MAIN = "com/lucanet/assembly/summa/client/SummaClientStart";
    private static final String ADMINISTRATOR_MAIN = "com/lucanet/assembly/summa/admin/SarahAdminClientStart";
    private static final Set<String> CLIENT_MAINS = Set.of(FINANCIAL_MAIN, ADMINISTRATOR_MAIN);
    private static final Set<String> MAINS = Set.of(FINANCIAL_MAIN, ADMINISTRATOR_MAIN,
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
        if (!isDesktopClient(application.getClassLoader()) || !ARCHIVING.get(application).compareAndSet(false, true)) { return; }
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

    /**
     * 仅处理 Financial / Administrator 客户端的 UnknownFileDialogCreator，不依赖生命周期状态。
     * 登录过期/会话超时时 JVM 可能仍为 RUNNING，也可能已释放 Application 状态，
     * 因而不能用 PREPARE_STOP 等退出状态作为前提。此处只返回 KEEP，不读写业务文件；
     * 全量归档仍由 beforeStop 在真正退出时执行。
     */
    public static boolean suppressTemporaryFileDialog(Class<?> dialogClass) {
        try {
            if (!isDesktopClient(dialogClass.getClassLoader())) { return false; }
        } catch (RuntimeException | LinkageError failure) {
            // 无法确认客户端身份时保留原行为，不能扩大到 Manager/服务端等进程。
            EnhancementLog.error("DIALOG_CLIENT_UNAVAILABLE", failure);
            return false;
        }
        EnhancementLog.info("TEMP_FILE_DIALOG_SUPPRESSED: desktop client unknown temporary file kept in place");
        return true;
    }

    private static String state(Class<?> application) throws ReflectiveOperationException {
        Object value = application.getMethod("getApplicationState").invoke(null);
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    static boolean isDesktopClient(ClassLoader loader) {
        if (loader == null) { return false; }
        // 按客户端入口确认，不依赖登录用户名或会话状态。Administrator 是独立程序，
        // 不能要求其还可见 Financial Client；Manager、服务端等进程不扩大处理范围。
        for (String main : CLIENT_MAINS) {
            if (loader.getResource(main + ".class") != null) { return true; }
        }
        return false;
    }
}
