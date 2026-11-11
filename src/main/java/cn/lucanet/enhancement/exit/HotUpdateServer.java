package cn.lucanet.enhancement.exit;

import java.nio.file.*;
import java.util.Properties;
import java.util.UUID;

/** 已增强 JVM 的事务服务。固定更新协议不提供任意回调代码接口。 */
final class HotUpdateServer {
    static void start(String installedJar, String version) throws Exception {
        Path endpoint = HotUpdateFiles.root().resolve("processes").resolve(Long.toString(ProcessHandle.current().pid()));
        Files.createDirectories(endpoint);
        String token = UUID.randomUUID().toString();
        Properties state = HotUpdateFiles.values("protocol", "1", "agent", Path.of(installedJar).toAbsolutePath().toString(),
                "started", Long.toString(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()),
                "token", token, "version", version);
        HotUpdateFiles.write(endpoint.resolve("state.properties"), state);
        Thread worker = new Thread(() -> {
            String transaction = null, pendingVersion = null, oldVersion = null;
            long touched = 0;
            while (true) {
                Path request = endpoint.resolve("request.properties");
                try {
                    if (transaction != null && System.nanoTime() - touched > 120_000_000_000L) { AgentRuntime.finish(); transaction = null; }
                    if (Files.exists(request)) {
                        Properties message = HotUpdateFiles.read(request); Files.delete(request);
                        String id = message.getProperty("id", "");
                        Properties response = HotUpdateFiles.values("id", id, "ok", "false");
                        try {
                            if (!token.equals(message.getProperty("token"))) { throw new SecurityException("Stale process token"); }
                            String operation = message.getProperty("operation", "");
                            if (operation.equals("prepare")) {
                                if (transaction != null) { throw new IllegalStateException("Another update is in progress"); }
                                Path candidate = Path.of(message.getProperty("jar"));
                                if (!HotUpdateFiles.hash(candidate).equals(message.getProperty("sha256"))) { throw new SecurityException("Agent checksum mismatch"); }
                                Path copy = HotUpdateFiles.snapshot(candidate);
                                if (!HotUpdateFiles.hash(copy).equals(message.getProperty("sha256"))) { throw new SecurityException("Agent changed during staging"); }
                                pendingVersion = HotUpdateFiles.version(copy);
                                if (HotUpdateFiles.compareVersions(pendingVersion, state.getProperty("version")) < 0) { throw new IllegalArgumentException("Runtime downgrade refused"); }
                                AgentRuntime.prepare(copy); transaction = message.getProperty("transaction");
                                oldVersion = state.getProperty("version");
                            } else {
                                if (transaction == null || !transaction.equals(message.getProperty("transaction"))) { throw new IllegalStateException("Unknown update transaction"); }
                                switch (operation) {
                                    case "commit": AgentRuntime.commit(); state.setProperty("version", pendingVersion); break;
                                    case "rollback": AgentRuntime.rollback(); state.setProperty("version", oldVersion); transaction = null; break;
                                    case "finish": AgentRuntime.finish(); transaction = null; break;
                                    default: throw new IllegalArgumentException("Unsupported update operation");
                                }
                            }
                            touched = System.nanoTime(); HotUpdateFiles.write(endpoint.resolve("state.properties"), state);
                            response.setProperty("ok", "true"); response.setProperty("version", state.getProperty("version"));
                        } catch (Exception | LinkageError failure) { response.setProperty("error", failure.toString()); }
                        HotUpdateFiles.write(endpoint.resolve("response.properties"), response);
                    }
                    Thread.sleep(250);
                } catch (InterruptedException done) { return; }
                catch (Exception failure) { EnhancementLog.error("HOT_UPDATE_CHANNEL_FAILED", failure); try { Thread.sleep(1000); } catch (InterruptedException done) { return; } }
            }
        }, "client-enhancement-updates");
        worker.setDaemon(true); worker.setContextClassLoader(null); worker.start();
    }
}
