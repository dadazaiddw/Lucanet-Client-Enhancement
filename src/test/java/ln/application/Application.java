package ln.application;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import com.lucanet.lisa.util.filehandling.UnknownFileDialogCreator;

/** 只模拟已核实的事件顺序；真实客户端验证单独进行。 */
public final class Application {
    private static volatile ApplicationState state = ApplicationState.EMPTY;
    public static final List<String> events = new CopyOnWriteArrayList<>();

    public static ApplicationState getApplicationState() {
        if (Boolean.getBoolean("fixture.stateUnavailable")) {
            throw new IllegalStateException("Session has expired; application state unavailable");
        }
        return state;
    }

    public static ApplicationPathes getApplicationPaths() {
        return new ApplicationPathes() {
            @Override
            public File getTempDir() {
                return new File(System.getProperty("fixture.tempDir"));
            }
        };
    }

    public static void fire(ApplicationState next) {
        state = next;
    }

    public static void stop() {
        fire(ApplicationState.PREPARE_STOP);
        try (Stream<Path> files = Files.walk(Path.of(System.getProperty("fixture.tempDir")))) {
            if (files.anyMatch(Files::isRegularFile) && !Boolean.getBoolean("fixture.allowRemaining")) {
                throw new AssertionError("Temporary files still exist when original file check runs");
            }
        } catch (IOException failure) {
            throw new AssertionError("Cannot check fixture temp directory", failure);
        }
        events.add("unknown-file-check");
        if (Boolean.getBoolean("fixture.allowRemaining") && UnknownFileDialogCreator.show("remaining") != 3) {
            throw new AssertionError("Remaining file dialog must silently keep the file");
        }
        fire(ApplicationState.STOPPING);
        fire(ApplicationState.IDLE);
    }

    public static void dispose() {
        // 模拟客户端内部重启前回到 EMPTY；本增强不再注册自定义监听器。
        state = ApplicationState.EMPTY;
    }
}
