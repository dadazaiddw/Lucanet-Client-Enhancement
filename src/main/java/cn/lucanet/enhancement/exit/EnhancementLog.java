package cn.lucanet.enhancement.exit;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** 内置增强日志：仅写本地维护记录，不显示额外用户界面。 */
final class EnhancementLog {
    private EnhancementLog() { }
    private static Path cleanedDirectory;
    private static int cleanedDays;
    private static long nextCleanup;
    private static String activation;

    static void info(String message) {
        write("INFO", message, null);
    }

    static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static synchronized void write(String level, String message, Throwable error) {
        if(message.startsWith("AGENT_ACTIVE:"))activation=message;
        FeatureSettings.StoragePreferences settings=FeatureSettings.logs();
        if(!settings.enabled)return;
        String line = Instant.now() + " [" + level + "] [pid=" + ProcessHandle.current().pid()
                + "] " + message + System.lineSeparator();
        if (error != null) {
            StringWriter text = new StringWriter();
            error.printStackTrace(new PrintWriter(text));
            line += text;
        }
        try {
            Path directory = settings.directory;
            Files.createDirectories(directory);
            Path file=directory.resolve("client-exit-" + ProcessHandle.current().pid() + ".log");
            String context=activation!=null?activation:NativeBridge.agentJar==null?null:"AGENT_ACTIVE: jar="+NativeBridge.agentJar;
            // A path switch must retain the installation identity used by the manager's status check.
            if(context!=null&&!message.startsWith("AGENT_ACTIVE:")&&(!Files.exists(file)||Files.size(file)==0))
                line=Instant.now()+" [INFO] [pid="+ProcessHandle.current().pid()+"] "+context+System.lineSeparator()+line;
            Files.write(file,
                    line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            long now=System.nanoTime();
            if(!directory.equals(cleanedDirectory)||settings.days!=cleanedDays||now>=nextCleanup){
                StorageRetention.logs(directory,settings.days);
                cleanedDirectory=directory;cleanedDays=settings.days;nextCleanup=now+60_000_000_000L;
            }
        } catch (Exception logFailure) {
            System.err.print("[lucanet-exit] " + line);
            System.err.println("[lucanet-exit] Cannot write log: " + logFailure);
        }
    }
}
