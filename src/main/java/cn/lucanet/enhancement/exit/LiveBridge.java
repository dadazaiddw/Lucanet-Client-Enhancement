package cn.lucanet.enhancement.exit;

/** 原生类只引用这个 bootstrap 稳定桥；热更新更换行为实现，不更换登录会话。 */
public final class LiveBridge {
    static volatile AgentModule current;
    private LiveBridge() { }
    public static void beforeStop(Class<?> type) {
        try { current.invoke("beforeStop", new Object[]{type}); }
        catch (RuntimeException | LinkageError failure) { EnhancementLog.error("EXIT_BRIDGE_FAILED", failure); }
    }
    public static boolean suppressTemporaryFileDialog(Class<?> type) {
        try { return Boolean.TRUE.equals(current.invoke("suppressTemporaryFileDialog", new Object[]{type})); }
        catch (RuntimeException | LinkageError failure) { EnhancementLog.error("DIALOG_BRIDGE_FAILED", failure); return false; }
    }
    public static String augmentConfiguration(String text) {
        try { return (String) current.invoke("augmentConfiguration", new Object[]{text}); }
        catch (RuntimeException | LinkageError failure) { EnhancementLog.error("LAUNCH_BRIDGE_FAILED", failure); return text; }
    }
    public static boolean openLinkInNewWindow(Class<?> type, String uri) {
        try { return Boolean.TRUE.equals(current.invoke("openLinkInNewWindow", new Object[]{type, uri})); }
        catch (RuntimeException | LinkageError failure) { EnhancementLog.error("LINK_BRIDGE_FAILED", failure); return false; }
    }
}
