package cn.lucanet.enhancement.exit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import ln.application.AbstractApplicationComponent;
import ln.application.Application;
import ln.application.ApplicationComponentID;
import ln.application.ApplicationPathes;
import ln.application.spi.DefaultApplicationComponentBuilder;

/** 在独立 JVM 使用本机真实的生命周期库；不启动 Lucanet 业务组件，不连接服务器。 */
public final class RealLifecycleTest {
    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("fixture.administrator")) {
            ClassLoader loader = RealLifecycleTest.class.getClassLoader();
            if (loader.getResource("com/lucanet/assembly/summa/client/SummaClientStart.class") != null
                    || loader.getResource("com/lucanet/assembly/summa/admin/SarahAdminClientStart.class") == null) {
                throw new AssertionError("Administrator lifecycle requires its own assembly only");
            }
        }
        List<String> order = new ArrayList<>();
        File directory = new File(System.getProperty("fixture.tempDir"));
        Files.createDirectories(directory.toPath());
        Path report = directory.toPath().resolve("arbitrary-report.tmp");
        Files.writeString(report, "keep contents");
        Application.setApplicationPaths(new ApplicationPathes() {
            public File getConfigDir() { return directory; }
            public File getLogDir() { return directory; }
            public File getTempDir() { return directory; }
            public File getLinkDir() { return directory; }
            public File getWorkingDir() { return directory; }
        });
        DefaultApplicationComponentBuilder component = new DefaultApplicationComponentBuilder(
                ApplicationComponentID.createWithUniqueID(13987642), true, () -> new AbstractApplicationComponent() {
                    protected void executeStart() { order.add("component-start"); }
                    protected void executePrepareStop() {
                        if (Files.exists(report)) { throw new AssertionError("Real prepareStop preceded archive"); }
                        order.add("component-prepare-stop");
                    }
                    protected void executeStop() { order.add("component-stop"); }
                });
        Application.runWith(component);
        Application.stop();
        List<String> expected = List.of("component-start", "component-prepare-stop", "component-stop");
        if (!order.equals(expected)) {
            throw new AssertionError("Unexpected order: " + order);
        }
        Application.dispose(true);
        Files.writeString(report, "second lifecycle");
        Application.runWith(component);
        Application.stop();
        if (!order.subList(3, 6).equals(expected)) {
            throw new AssertionError("Unexpected second lifecycle: " + order);
        }
        System.out.println("PASS: real Lucanet Application.stop transformed; built-in archive precedes prepareStop and survives restart");
        System.out.println("Application library: " + Application.class.getProtectionDomain().getCodeSource().getLocation());
    }
}
