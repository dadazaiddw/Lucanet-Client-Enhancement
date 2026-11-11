package com.lucanet.lnswmgr_ui;
import com.lucanet.program.ProgramLaunchConfiguration;
import java.nio.file.*;
import java.util.*;

/** 从 Manager 配置转换中取出 agent 参数，启动完全未继承 JAVA_TOOL_OPTIONS 的客户端。 */
public final class NativeLaunchTest {
    public static void main(String[] args) throws Exception {
        String client = "com/lucanet/assembly/summa/client/SummaClientStart";
        String config = new ProgramLaunchConfiguration(client).toString(false, false);
        String option = config.lines().filter(s -> s.startsWith("jdkParameter=-javaagent:")).findFirst().orElseThrow();
        if (config.lines().filter(s -> s.startsWith("jdkParameter=-javaagent:")).count() != 1) { throw new AssertionError(config); }
        if (new ProgramLaunchConfiguration("com/server/Main").toString(false,false).contains("javaagent")) {
            throw new AssertionError("Server launch was modified");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        List<String> command = new ArrayList<>(List.of(java, option.substring("jdkParameter=".length()), "-Djava.awt.headless=true"));
        for (String property : List.of("fixture.tempDir", "lucanet.exit.archiveDir", "lucanet.exit.logDir", "lucanet.enhancement.runtime")) {
            command.add("-D" + property + "=" + System.getProperty(property));
        }
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), client.replace('/', '.')));
        ProcessBuilder child = new ProcessBuilder(command).inheritIO();
        child.environment().remove("JAVA_TOOL_OPTIONS");
        child.environment().remove("JDK_JAVA_OPTIONS");
        child.environment().remove("_JAVA_OPTIONS");
        if (child.start().waitFor() != 0) { throw new AssertionError("Native child test failed"); }
        System.out.println("PASS: native configuration propagates agent to child without environment or wrapper; server unaffected");
    }
}
