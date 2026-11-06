package cn.lucanet.enhancement.exit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** 内置增强日志：仅写本地维护记录，不显示额外用户界面。 */
final class EnhancementLog {
    private EnhancementLog() { }

    static void info(String message) {
        write("INFO", message, null);
    }

    static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static synchronized void write(String level, String message, Throwable error) {
        String line = Instant.now() + " [" + level + "] [pid=" + ProcessHandle.current().pid()
                + "] " + message + System.lineSeparator();
        if (error != null) {
            StringWriter text = new StringWriter();
            error.printStackTrace(new PrintWriter(text));
            line += text;
        }
        try {
            String configured = System.getProperty("lucanet.exit.logDir");
            Path directory = configured == null || configured.isBlank()
                    ? Paths.get(System.getProperty("user.home"), ".lucanet-exit-enhancement", "logs")
                    : Paths.get(configured);
            Files.createDirectories(directory);
            Files.write(directory.resolve("client-exit-" + ProcessHandle.current().pid() + ".log"),
                    line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception logFailure) {
            System.err.print("[lucanet-exit] " + line);
            System.err.println("[lucanet-exit] Cannot write log: " + logFailure);
        }
    }
}
