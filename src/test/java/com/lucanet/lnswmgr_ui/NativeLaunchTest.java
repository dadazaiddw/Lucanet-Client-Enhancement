package com.lucanet.lnswmgr_ui;
import com.lucanet.program.ProgramLaunchConfiguration;
import java.nio.file.*;
import java.util.*;

/** 从 Manager 配置转换中取出 agent 参数，启动完全未继承 JAVA_TOOL_OPTIONS 的客户端。 */
public final class NativeLaunchTest {
    public static void main(String[] args) throws Exception {
        List<String> clients = List.of("com/lucanet/assembly/summa/client/SummaClientStart",
                "com/lucanet/assembly/summa/admin/SarahAdminClientStart");
        for (String client : clients) { launchClient(client, clients); }
        for (String other : List.of("com/server/Main", "com/lucanet/assembly/summa/server/SummaServerStart",
                "com/lucanet/assembly/summa/admin/SarahAdminClientStartOther")) {
            if (new ProgramLaunchConfiguration(other).toString(false,false).contains("javaagent")) {
                throw new AssertionError("Unrelated launch was modified: " + other);
            }
        }
        System.out.println("PASS: Manager propagates Agent to Financial and Administrator; each child has only its own entry, no environment/wrapper; server unaffected");
    }

    private static void launchClient(String client, List<String> clients) throws Exception {
        String config = new ProgramLaunchConfiguration(client).toString(false, false);
        String option = config.lines().filter(s -> s.startsWith("jdkParameter=-javaagent:")).findFirst().orElseThrow();
        if (config.lines().filter(s -> s.startsWith("jdkParameter=-javaagent:")).count() != 1) { throw new AssertionError(config); }
        Path testRoot = Path.of(System.getProperty("fixture.tempDir")).getParent();
        String kind = client.contains("/admin/") ? "administrator" : "financial";
        Path scoped = testRoot.resolve("scoped-clients").resolve(kind);
        Path originalClasses = Path.of(NativeLaunchTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = Files.walk(originalClasses)) {
            for (Path file : files.filter(Files::isRegularFile).toArray(Path[]::new)) {
                String relative = originalClasses.relativize(file).toString().replace('\\', '/');
                if (clients.stream().anyMatch(main -> !main.equals(client) && relative.equals(main + ".class"))) { continue; }
                Path target = scoped.resolve(relative); Files.createDirectories(target.getParent()); Files.copy(file, target);
            }
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> command = new ArrayList<>(List.of(java, option.substring("jdkParameter=".length()), "-Djava.awt.headless=true"));
        for (String property : List.of("lucanet.exit.logDir", "lucanet.enhancement.runtime")) {
            command.add("-D" + property + "=" + System.getProperty(property));
        }
        command.addAll(List.of("-Dfixture.tempDir=" + testRoot.resolve(kind + " temp"),
                "-Dlucanet.exit.archiveDir=" + testRoot.resolve(kind + " archive"), "-Dfixture.clientMain=" + client,
                "-cp", scoped.toString(), client.replace('/', '.')));
        ProcessBuilder child = new ProcessBuilder(command).inheritIO();
        child.environment().remove("JAVA_TOOL_OPTIONS");
        child.environment().remove("JDK_JAVA_OPTIONS");
        child.environment().remove("_JAVA_OPTIONS");
        if (child.start().waitFor() != 0) { throw new AssertionError("Native child test failed"); }
    }
}
