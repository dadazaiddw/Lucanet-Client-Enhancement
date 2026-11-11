package ln.application;

/** 测试替身，不打入交付 JAR。 */
public enum ApplicationState {
    EMPTY, STARTING, RUNNING, PREPARE_STOP, STOPPING, DISPOSING, IDLE
}
