package cn.lucanet.enhancement.exit;

import java.nio.file.Files;
import java.nio.file.Path;

/** PowerShell 持有 FileShare.None 的真实 Windows 句柄时运行。 */
public final class LockedFileArchiveTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path archive = Path.of(args[1]);
        Path locked = root.resolve("busy.bin");
        Path free = root.resolve("free/unknown-name.csv");
        Files.createDirectories(free.getParent());
        Files.writeString(free, "must still be archived");
        int moved = TempFileArchiver.archiveAllFiles(root, archive);
        if (moved != 1 || !Files.exists(locked) || Files.exists(free)) {
            throw new AssertionError("Locked file must stay; other file must be archived; moved=" + moved);
        }
        String log = Files.readString(Path.of(System.getProperty("lucanet.exit.logDir"),
                "client-exit-" + ProcessHandle.current().pid() + ".log"));
        if (!log.contains("ARCHIVE_FAILED:") || !log.contains("failed=1")) {
            throw new AssertionError("Locked file failure was not recorded");
        }
        System.out.println("PASS: locked Windows file retained, other file archived, failure recorded");
    }
}
