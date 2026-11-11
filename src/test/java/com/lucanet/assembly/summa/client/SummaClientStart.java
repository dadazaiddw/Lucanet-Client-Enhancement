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
        // 会话过期不一定停止 JVM：覆盖仍 RUNNING、会话重建/清理等所有状态。
        // 每次检查源文件内容和归档数量，确保这里只静默 KEEP、不触发运行中归档。
        for (ApplicationState state : ApplicationState.values()) {
            Application.fire(state);
            if (UnknownFileDialogCreator.show("expired-session-" + UUID.randomUUID()) != 3 || UnknownFileDialogCreator.shown != 0) {
                throw new AssertionError("Unknown file dialog displayed in state=" + state);
            }
            if (countFiles(root) != 3 || countFiles(archive) != archivedBefore
                    || !Files.readString(root.resolve("no-extension")).equals("unknown name")
                    || !Files.readString(root.resolve("tempClient/deep/nested.csv")).equals("nested report")) {
                throw new AssertionError("Dialog handling moved/deleted/changed a file in state=" + state);
            }
        }
        System.setProperty("fixture.stateUnavailable", "true");
        try {
            if (UnknownFileDialogCreator.show("session-state-unavailable") != 3 || UnknownFileDialogCreator.shown != 0) {
                throw new AssertionError("Session-expired dialog incorrectly depends on Application state access");
            }
        } finally {
            System.clearProperty("fixture.stateUnavailable");
        }
        Application.fire(ApplicationState.RUNNING);
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
        if (UnknownFileDialogCreator.show("leftover-file") != 3 || UnknownFileDialogCreator.shown != 0) {
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
        if (!Files.readString(root.resolve("must-stay.txt")).equals("keep this content") || UnknownFileDialogCreator.shown != 0) {
            throw new AssertionError("Archive failure must keep file without showing exit dialog");
        }
        String finalLog = Files.readString(Path.of(System.getProperty("lucanet.exit.logDir"),
                "client-exit-" + ProcessHandle.current().pid() + ".log"));
        if (!finalLog.contains("TEMP_FILE_DIALOG_SUPPRESSED")) { throw new AssertionError("Missing temporary-file suppression record"); }
        System.out.println("PASS: KEEP without UI in every client state and with unavailable session state; no runtime file changes; exit archive/restart/failure retained");
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
