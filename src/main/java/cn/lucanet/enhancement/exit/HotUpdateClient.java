package cn.lucanet.enhancement.exit;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;

/** GUI 后台的事务更新器，只更新同一安装目录、同一用户且支持协议 1 的 JVM。 */
public final class HotUpdateClient {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) { throw new IllegalArgumentException("Expected installed-agent, candidate-agent, comma-separated process IDs"); }
        Path installed = Path.of(args[0]).toAbsolutePath(), candidate = Path.of(args[1]).toAbsolutePath();
        String version = HotUpdateFiles.version(candidate), digest = HotUpdateFiles.hash(candidate);
        if (HotUpdateFiles.compareVersions(version, HotUpdateFiles.version(HotUpdateFiles.active(installed))) < 0) {
            throw new IllegalArgumentException("A newer version is already installed; downgrade refused");
        }
        ArrayList<Path> endpoints = new ArrayList<>();
        for (String pid : args[2].split(",")) {
            ProcessHandle process = ProcessHandle.of(Long.parseLong(pid)).orElseThrow(() -> new IllegalStateException("Process exited; refresh and retry"));
            Path endpoint = HotUpdateFiles.root().resolve("processes").resolve(pid);
            if (!Files.isRegularFile(endpoint.resolve("state.properties"))) { throw new IllegalStateException("PID " + pid + " has no hot-update support. Close Lucanet normally and install this version once."); }
            Properties state = HotUpdateFiles.read(endpoint.resolve("state.properties"));
            if (!"1".equals(state.getProperty("protocol")) || !installed.equals(Path.of(state.getProperty("agent")))
                    || !Long.toString(process.info().startInstant().orElseThrow().toEpochMilli()).equals(state.getProperty("started"))) {
                throw new IllegalStateException("Process identity or update protocol mismatch: " + pid);
            }
            endpoints.add(endpoint);
        }
        if (endpoints.isEmpty()) { throw new IllegalArgumentException("No running agents"); }
        Path lockPath = installed.resolveSibling("update.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) { throw new IllegalStateException("Another update is in progress"); }
            apply(installed, candidate, digest, endpoints);
        }
        System.out.println("HOT_UPDATE_OK: v" + version + "; processes=" + endpoints.size());
    }
    private static void apply(Path installed, Path candidate, String digest, List<Path> endpoints) throws Exception {
        String transaction = UUID.randomUUID().toString();
        Path pointer = installed.resolveSibling("active.properties");
        Properties before = Files.exists(pointer) ? HotUpdateFiles.read(pointer) : null;
        // 启动 JAR 被 JVM 锁定时保持其不动。稳定 bootstrap 在下次启动读取这个校验过的版本指针。
        Path versions = installed.resolveSibling("versions"); Files.createDirectories(versions);
        Path versioned = versions.resolve(digest + ".jar");
        if (!Files.exists(versioned)) {
            Path temporary = versions.resolve(transaction + ".tmp");
            try { Files.copy(candidate, temporary); Files.move(temporary, versioned); }
            finally { Files.deleteIfExists(temporary); }
        }
        if (!HotUpdateFiles.hash(versioned).equals(digest)) { throw new SecurityException("Staged module checksum mismatch"); }
        ArrayList<Path> attempted = new ArrayList<>(); boolean replaced = false;
        try {
            for (Path endpoint : endpoints) { attempted.add(endpoint); request(endpoint, "prepare", transaction, versioned, digest); }
            HotUpdateFiles.write(pointer, HotUpdateFiles.values("sha256", digest, "version", HotUpdateFiles.version(versioned))); replaced = true;
            for (Path endpoint : endpoints) { request(endpoint, "commit", transaction, versioned, digest); }
        } catch (Exception failure) {
            if (replaced) { try { if (before == null) { Files.deleteIfExists(pointer); } else { HotUpdateFiles.write(pointer, before); } } catch (Exception rollback) { failure.addSuppressed(rollback); } }
            for (Path endpoint : attempted) { try { request(endpoint, "rollback", transaction, candidate, digest); } catch (Exception rollback) { failure.addSuppressed(rollback); } }
            throw failure;
        }
        for (Path endpoint : endpoints) { try { request(endpoint, "finish", transaction, candidate, digest); } catch (Exception failure) { System.err.println("Update committed; cleanup pending: " + failure); } }
    }
    private static void request(Path endpoint, String operation, String transaction, Path jar, String hash) throws Exception {
        Properties state = HotUpdateFiles.read(endpoint.resolve("state.properties"));
        String id = UUID.randomUUID().toString();
        HotUpdateFiles.write(endpoint.resolve("request.properties"), HotUpdateFiles.values("id", id, "token", state.getProperty("token"),
                "operation", operation, "transaction", transaction, "jar", jar.toString(), "sha256", hash));
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            Path file = endpoint.resolve("response.properties");
            if (Files.exists(file)) {
                Properties response = HotUpdateFiles.read(file);
                if (id.equals(response.getProperty("id"))) {
                    if (!"true".equals(response.getProperty("ok"))) { throw new IllegalStateException(response.getProperty("error")); }
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Hot update timed out: " + endpoint.getFileName() + " / " + operation);
    }
}
