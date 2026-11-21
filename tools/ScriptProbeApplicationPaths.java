import java.io.File;
import ln.application.ApplicationPathes;

final class ScriptProbeApplicationPaths extends ApplicationPathes {
    private final File directory;

    ScriptProbeApplicationPaths(File directory) { this.directory = directory; }

    public File getConfigDir() { return directory; }
    public File getLogDir() { return directory; }
    public File getTempDir() { return directory; }
    public File getLinkDir() { return directory; }
    public File getWorkingDir() { return directory; }
}
