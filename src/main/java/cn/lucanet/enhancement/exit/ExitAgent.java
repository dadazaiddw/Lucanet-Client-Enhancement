package cn.lucanet.enhancement.exit;

import java.lang.instrument.Instrumentation;
import java.io.File;
import java.net.JarURLConnection;
import java.util.jar.JarFile;

/**
 * 内置安装入口：Manager/原生客户端 JVM 启动时加载 Agent，转换固定的 Lucanet 类。
 * 不暴露客户代码回调，不读取自定义类名；全部行为由 NativeBridge 固定实现。
 */
public final class ExitAgent {
    private ExitAgent() { }

    public static void premain(String options, Instrumentation instrumentation) {
        try {
            JarURLConnection connection = (JarURLConnection) ExitAgent.class.getResource("ExitAgent.class").openConnection();
            File jar = new File(connection.getJarFileURL().toURI());
            // 使用实际安装路径动态加入 bootstrap，不将构建时 JAR 文件名写死在 manifest。
            // Runtime 和桥接类只由 bootstrap 加载；原生模块可见同一个固定实现。
            // bootstrap 读取不可变快照，安装位置的 JAR 才能在运行中原子替换。
            java.nio.file.Path copy = HotUpdateFiles.snapshot(jar.toPath());
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(copy.toFile()));
            Class.forName("cn.lucanet.enhancement.exit.AgentRuntime", true, null)
                    .getMethod("start", Instrumentation.class, String.class)
                    .invoke(null, instrumentation, jar.getAbsolutePath());
        } catch (Exception | LinkageError failure) {
            System.err.println("[client-enhancement] AGENT_DISABLED: " + failure);
            failure.printStackTrace(System.err);
        }
    }
}
