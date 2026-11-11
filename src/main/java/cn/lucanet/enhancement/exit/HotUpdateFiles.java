package cn.lucanet.enhancement.exit;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.UUID;
import java.util.jar.JarFile;

/** 当前用户的本地更新通道，不监听网络、不启用 JVM Attach。 */
final class HotUpdateFiles {
    static Path root() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(System.getProperty("lucanet.enhancement.runtime",
                Path.of(local == null ? System.getProperty("user.home") : local, "Lucanet", "ClientEnhancement", "runtime").toString()));
    }
    static Path snapshot(Path jar) throws Exception {
        String digest = hash(jar);
        Path directory = root().resolve("modules");
        Files.createDirectories(directory);
        Path copy = directory.resolve(digest + ".jar");
        if (Files.exists(copy)) {
            if (!hash(copy).equals(digest)) { throw new SecurityException("Cached module checksum mismatch"); }
            return copy;
        }
        Path temporary = directory.resolve(UUID.randomUUID() + ".tmp");
        try {
            Files.copy(jar, temporary);
            if (!hash(temporary).equals(digest)) { throw new SecurityException("Module changed during copy"); }
            try { Files.move(temporary, copy); }
            catch (java.io.IOException race) {
                if (!Files.isRegularFile(copy) || !hash(copy).equals(digest)) { throw race; }
            }
            return copy;
        } finally { Files.deleteIfExists(temporary); }
    }
    static Path active(Path bootstrap) throws Exception {
        Path pointer = bootstrap.resolveSibling("active.properties");
        if (!Files.isRegularFile(pointer)) { return bootstrap; }
        Properties info = read(pointer);
        String digest = info.getProperty("sha256", "");
        if (!digest.matches("[a-f0-9]{64}")) { throw new IllegalArgumentException("Invalid active module digest"); }
        Path candidate = bootstrap.resolveSibling("versions").resolve(digest + ".jar");
        if (!hash(candidate).equals(digest)) { throw new SecurityException("Active module checksum mismatch"); }
        version(candidate); return candidate;
    }
    static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = input.read(buffer)) >= 0) { digest.update(buffer, 0, read); }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) { result.append(String.format("%02x", value & 255)); }
        return result.toString();
    }
    static String version(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var attributes = file.getManifest().getMainAttributes();
            if (!"1".equals(attributes.getValue("Enhancement-Hot-Protocol"))) { throw new IllegalArgumentException("Update requires a client restart (incompatible hot-update protocol)"); }
            String version = attributes.getValue("Implementation-Version");
            if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) { throw new IllegalArgumentException("Invalid agent version"); }
            return version;
        }
    }
    static int compareVersions(String left, String right) {
        String[] a = left.split("\\."), b = right.split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (result != 0) { return result; }
        }
        return 0;
    }
    static Properties read(Path file) throws Exception {
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(file)) { result.load(input); }
        return result;
    }
    static void write(Path file, Properties properties) throws Exception {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "Lucanet enhancement runtime protocol 1"); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static Properties values(String... pairs) {
        Properties result = new Properties();
        for (int i = 0; i < pairs.length; i += 2) { result.setProperty(pairs[i], pairs[i + 1]); }
        return result;
    }
}
