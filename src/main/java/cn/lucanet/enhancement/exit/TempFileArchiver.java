package cn.lucanet.enhancement.exit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 临时文件归档功能：在蓝科检查之前，同步递归移动当前客户端临时目录内的全部普通文件。
 *
 * <p>文件名、后缀、隐藏属性均不参与筛选。文件内容保存在独立归档目录中，目录结构保留。
 * 不删除临时根目录及子目录，由蓝科继续管理；不沿符号链接/目录联接访问其他位置。
 */
final class TempFileArchiver {
    private TempFileArchiver() { }

    /**
     * 归档当前客户端临时目录内的所有普通文件（包含所有层级的子目录）。
     *
     * @param configuredRoot 当前蓝科临时根目录，不扫描系统 TEMP；路径不可用时传 null
     * @param archiveDirectory 归档根目录，必须在蓝科临时目录之外
     * @return 本次成功移动的文件数；失败/跳过的详细信息见 ARCHIVE_* 日志
     * @throws IOException 根路径或归档配置无效；单文件失败会记日志并继续扫描
     */
    static int archiveAllFiles(Path configuredRoot, Path archiveDirectory) throws IOException {
        if (configuredRoot == null) {
            EnhancementLog.info("ARCHIVE_SKIPPED: application temp directory is unavailable");
            return 0;
        }
        if (Files.notExists(configuredRoot)) {
            EnhancementLog.info("ARCHIVE_SKIPPED: application temp directory does not exist: " + configuredRoot);
            return 0;
        }
        Path root = configuredRoot.toRealPath();
        if (root.getParent() == null || !Files.isDirectory(root)) {
            throw new IOException("Refusing to scan an invalid application temp directory: " + root);
        }
        // 在创建目录/移动文件前解析真实目的地，拒绝指向临时目录内部的路径（含链接）。
        Path archiveBase = resolveDestination(archiveDirectory.toAbsolutePath().normalize());
        if (archiveBase.startsWith(root)) {
            throw new IOException("Archive directory must be outside the application temp directory: " + archiveBase);
        }
        ArchiveVisitor visitor = new ArchiveVisitor(root, archiveBase);
        EnhancementLog.info("ARCHIVE_BEGIN: all regular files; source=" + root + "; archiveBase=" + archiveBase);
        // 默认不 FOLLOW_LINKS；进一步用真实路径检查 Windows junction 等目录重定向。
        Files.walkFileTree(root, visitor);
        EnhancementLog.info("ARCHIVE_SUMMARY: moved=" + visitor.moved + ", failed=" + visitor.failed
                + ", skippedLinks=" + visitor.skippedLinks + ", skippedSpecial=" + visitor.skippedSpecial);
        return visitor.moved;
    }

    /** 目标目录尚未创建时，也先沿已有父目录解析链接，避免归档又落回临时目录中。 */
    private static Path resolveDestination(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return path.toRealPath();
        }
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Cannot resolve archive directory: " + path);
        }
        return resolveDestination(parent).resolve(path.getFileName());
    }

    private static final class ArchiveVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final Path archiveBase;
        private Path runDirectory;
        private int moved;
        private int failed;
        private int skippedLinks;
        private int skippedSpecial;

        private ArchiveVisitor(Path root, Path archiveBase) {
            this.root = root;
            this.archiveBase = archiveBase;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            try {
                Path real = directory.toRealPath();
                if (!real.startsWith(root) || !real.equals(directory.toAbsolutePath().normalize())) {
                    skippedLinks++;
                    EnhancementLog.info("ARCHIVE_SKIPPED: redirected directory: " + directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
            } catch (IOException failure) {
                // 某个目录不可访问时跳过该分支，仍继续处理其他目录。
                recordFailure(directory, failure);
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (attributes.isSymbolicLink()) {
                skippedLinks++;
                EnhancementLog.info("ARCHIVE_SKIPPED: symbolic link: " + file);
                return FileVisitResult.CONTINUE;
            }
            if (!attributes.isRegularFile()) {
                skippedSpecial++;
                EnhancementLog.info("ARCHIVE_SKIPPED: not a regular file: " + file);
                return FileVisitResult.CONTINUE;
            }
            try {
                Path source = file.toRealPath();
                if (!source.startsWith(root) || !source.equals(file.toAbsolutePath().normalize())) {
                    throw new IOException("File resolves outside its expected location: " + file);
                }
                Path destination = getRunDirectory().resolve(root.relativize(source)).normalize();
                if (!destination.startsWith(runDirectory)) {
                    throw new IOException("Invalid archive destination: " + destination);
                }
                Files.createDirectories(destination.getParent());
                if (!destination.getParent().toRealPath().startsWith(runDirectory)) {
                    throw new IOException("Archive parent was redirected: " + destination.getParent());
                }
                // 不使用 REPLACE_EXISTING，也不先删除源文件。
                // 成功移动才记为已归档；占用/无权限等失败记日志后继续处理其他文件。
                Files.move(source, destination);
                moved++;
                EnhancementLog.info("ARCHIVED: " + source + " -> " + destination);
            } catch (IOException failure) {
                recordFailure(file, failure);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException failure) {
            recordFailure(file, failure);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
            if (failure != null) {
                recordFailure(directory, failure);
            }
            return FileVisitResult.CONTINUE;
        }

        /** 遇到首个可归档文件时才创建目录；不同退出批次使用不同目录，避免相互覆盖。 */
        private Path getRunDirectory() throws IOException {
            if (runDirectory == null) {
                Path resolved = resolveDestination(archiveBase);
                if (resolved.startsWith(root)) {
                    throw new IOException("Archive directory now resolves inside the temp directory: " + resolved);
                }
                Files.createDirectories(resolved);
                Path realBase = resolved.toRealPath();
                if (realBase.startsWith(root)) {
                    throw new IOException("Archive directory must stay outside the temp directory: " + realBase);
                }
                String prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + "-pid" + ProcessHandle.current().pid() + "-";
                runDirectory = Files.createTempDirectory(realBase, prefix).toRealPath();
                if (!runDirectory.startsWith(realBase) || runDirectory.startsWith(root)) {
                    throw new IOException("Invalid archive run directory: " + runDirectory);
                }
            }
            return runDirectory;
        }

        private void recordFailure(Path file, IOException failure) {
            failed++;
            EnhancementLog.info("ARCHIVE_FAILED: " + file + "; " + failure);
        }
    }
}
