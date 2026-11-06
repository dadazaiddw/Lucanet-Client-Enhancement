package cn.lucanet.enhancement.exit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 只操作测试目录：验证未知名称的所有临时文件均被处理，内容完整且不会越过目录边界。 */
public final class TempFileArchiverTest {
    public static void main(String[] args) throws Exception {
        Path work = Files.createDirectories(Path.of(args[0]).toAbsolutePath());
        Path root = Files.createDirectories(work.resolve("client temp"));
        Path archive = work.resolve("archive output");
        Map<Path, byte[]> expected = new LinkedHashMap<>();
        put(root, expected, "temp9/同名报表.xlsx", new byte[] {0, 1, 2, -1, 8});
        put(root, expected, "temp19/同名报表.xlsx", new byte[] {9, 4, 7});
        put(root, expected, "tempClient/deep/其他业务数据.csv", new byte[] {10, 20, 30});
        put(root, expected, "未知文件-" + UUID.randomUUID(), new byte[] {42});
        put(root, expected, ".hidden", new byte[] {13, 14});
        put(root, expected, "empty-file.tmp", new byte[0]);
        put(root, expected, "directory/01.总账v1.0/other.txt", new byte[] {66, 67});
        Path hidden = root.resolve(".hidden");
        DosFileAttributeView dos = Files.getFileAttributeView(hidden, DosFileAttributeView.class);
        if (dos != null) {
            dos.setHidden(true);
        }
        Path emptyDirectory = Files.createDirectories(root.resolve("empty-directory"));
        Path outside = write(work.resolve("outside-temp/other.tmp"), "outside must stay");

        int count = TempFileArchiver.archiveAllFiles(root, archive);
        check(count == expected.size(), "all extensions, unknown names, hidden and zero-byte files archived");
        check(files(root).isEmpty(), "original scanner sees no remaining regular files");
        check(Files.exists(emptyDirectory) && Files.isDirectory(root.resolve("temp9")), "directory lifecycle remains with client");
        check(Files.readString(outside).equals("outside must stay"), "files outside temp root remain untouched");
        List<Path> firstRuns = directories(archive);
        check(firstRuns.size() == 1, "one archive batch for the callback");
        Path firstRun = firstRuns.get(0);
        check(files(firstRun).size() == expected.size(), "all archived files are present");
        for (Map.Entry<Path, byte[]> entry : expected.entrySet()) {
            check(Arrays.equals(Files.readAllBytes(firstRun.resolve(entry.getKey())), entry.getValue()),
                    "content and relative path preserved: " + entry.getKey());
        }
        Path first = write(root.resolve("temp9/同名报表.xlsx"), "next session");
        check(TempFileArchiver.archiveAllFiles(root, archive) == 1, "repeat run");
        check(directories(archive).size() == 2 && files(archive).size() == expected.size() + 1,
                "new batch does not overwrite existing archives");
        check(Arrays.equals(Files.readAllBytes(firstRun.resolve("temp9/同名报表.xlsx")),
                expected.get(Path.of("temp9/同名报表.xlsx"))), "previous same-name file unchanged");
        Path unused = work.resolve("unused archive");
        check(TempFileArchiver.archiveAllFiles(root, unused) == 0 && Files.notExists(unused),
                "empty temp tree creates no empty archive");

        write(first, "source must survive rejected destination");
        expectRejected(root, root.resolve("archive"));
        expectRejected(root, root);
        check(Files.readString(first).equals("source must survive rejected destination"), "rejection retains source");
        check(Files.notExists(root.resolve("archive")), "rejection occurs before creating internal archive");
        Path blocked = write(work.resolve("not-a-directory"), "existing file");
        check(TempFileArchiver.archiveAllFiles(root, blocked) == 0, "unusable archive destination returns zero");
        check(Files.exists(first) && Files.readString(blocked).equals("existing file"), "failed move keeps existing data");
        check(TempFileArchiver.archiveAllFiles(null, unused) == 0, "missing temp path skips");
        check(TempFileArchiver.archiveAllFiles(work.resolve("missing temp"), unused) == 0,
                "absent temp directory skips");
        check(Files.notExists(unused), "skip does not create archive directory");

        // 可选：由 PowerShell 在测试根目录内创建 junction，验证不跟随它处理外部文件。
        if (args.length > 1) {
            Path linkedRoot = Path.of(args[1]);
            check(TempFileArchiver.archiveAllFiles(linkedRoot, work.resolve("links archive")) == 0,
                    "do not move files through Windows junctions");
        }
        String log = Files.readString(Path.of(System.getProperty("lucanet.exit.logDir"),
                "client-exit-" + ProcessHandle.current().pid() + ".log"));
        check(log.contains("ARCHIVED:") && log.contains("ARCHIVE_FAILED:"), "success and failure logged");
        System.out.println("PASS: all-file recursive archive including unknown names, hidden, empty and binary files; "
                + "content, relative paths, collisions, failures and directory boundary");
    }

    private static void expectRejected(Path root, Path destination) throws Exception {
        try {
            TempFileArchiver.archiveAllFiles(root, destination);
            throw new AssertionError("must reject archive inside temp directory");
        } catch (IOException expected) {
            check(expected.getMessage().contains("outside"), "clear rejection reason");
        }
    }

    private static void put(Path root, Map<Path, byte[]> expected, String relative, byte[] content) throws IOException {
        Path path = Path.of(relative);
        Files.createDirectories(root.resolve(path).getParent());
        Files.write(root.resolve(path), content);
        expected.put(path, content);
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    private static List<Path> directories(Path root) throws IOException {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).collect(Collectors.toList());
        }
    }

    private static void check(boolean value, String name) {
        if (!value) {
            throw new AssertionError(name);
        }
    }
}
