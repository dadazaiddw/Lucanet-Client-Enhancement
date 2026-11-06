package com.lucanet.assembly.summa.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import ln.application.Application;
import ln.application.ApplicationState;
import com.lucanet.lisa.util.filehandling.UnknownFileDialogCreator;

/** 独立子进程测试 Agent 自动发现；类名对应真实客户端入口。 */
public final class SummaClientStart {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("fixture.tempDir"));
        Path archive = Path.of(System.getProperty("lucanet.exit.archiveDir"));
        long archivedBefore = countFiles(archive);
        write(root.resolve("temp9/unknown-" + UUID.randomUUID() + ".bin"), "binary report");
        write(root.resolve("tempClient/deep/nested.csv"), "nested report");
        write(root.resolve("no-extension"), "unknown name");
        Application.fire(ApplicationState.RUNNING);
        if (UnknownFileDialogCreator.show("runtime-file") != 4 || UnknownFileDialogCreator.shown != 1) {
            throw new AssertionError("Running-state dialog changed");
        }
        Application.stop();
        if (!Application.events.equals(List.of("unknown-file-check"))) {
            throw new AssertionError("Original check was not executed");
        }
        if (countFiles(root) != 0 || countFiles(archive) != archivedBefore + 3) {
            throw new AssertionError("Not all files were archived by built-in enhancement");
        }
        Application.dispose();
        write(root.resolve("second-cycle/another-" + UUID.randomUUID()), "second lifecycle");
        Application.fire(ApplicationState.RUNNING);
        Application.stop();
        if (countFiles(root) != 0 || countFiles(archive) != archivedBefore + 4) {
            throw new AssertionError("Internal restart did not archive its new files");
        }
        String log = Files.readString(Path.of(System.getProperty("lucanet.exit.logDir"),
                "client-exit-" + ProcessHandle.current().pid() + ".log"));
        if (log.split("EXIT_ARCHIVE_FINISHED", -1).length != 3 || !log.contains("ARCHIVED:")) {
            throw new AssertionError("Missing built-in handling evidence: " + log);
        }
        Application.fire(ApplicationState.PREPARE_STOP);
        if (UnknownFileDialogCreator.show("leftover-file") != 3 || UnknownFileDialogCreator.shown != 1) {
            throw new AssertionError("Exit dialog was shown or did not keep the file");
        }
        // 模拟不可用归档位置：退出继续，所有源文件保留，弹窗静默返回保留。
        Path blockedArchive = root.getParent().resolve("blocked-archive-" + UUID.randomUUID());
        Files.writeString(blockedArchive, "not a directory");
        System.setProperty("lucanet.exit.archiveDir", blockedArchive.toString());
        write(root.resolve("must-stay.txt"), "keep this content");
        System.setProperty("fixture.allowRemaining", "true");
        Application.fire(ApplicationState.RUNNING);
        Application.stop();
        if (!Files.readString(root.resolve("must-stay.txt")).equals("keep this content") || UnknownFileDialogCreator.shown != 1) {
            throw new AssertionError("Archive failure must keep file without showing exit dialog");
        }
        System.out.println("PASS: all-file archive before checks, restart, exit dialog suppression, failed archive retains data; running dialog unchanged");
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static long countFiles(Path root) throws Exception {
        if (Files.notExists(root)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

}
