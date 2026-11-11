package cn.lucanet.enhancement.exit;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** bootstrap 中只常驻稳定桥与更新协议，业务实现由独立类加载器加载。 */
public final class AgentRuntime {
    private AgentRuntime() { }
    private static Instrumentation instrumentation;
    private static String installedJar;
    private static AgentModule previous, prepared;
    // 已进入的旧调用允许自然结束，不提前关闭其 loader。
    private static final java.util.List<ClassLoader> loaders = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static synchronized void start(Instrumentation inst, String jarPath) throws Exception {
        if (instrumentation != null) { return; }
        instrumentation = inst; installedJar = jarPath;
        Path active;
        try { active = HotUpdateFiles.active(Path.of(jarPath)); }
        catch (Exception failure) { EnhancementLog.error("ACTIVE_MODULE_INVALID: using installed base version", failure); active = Path.of(jarPath); }
        LiveBridge.current = load(HotUpdateFiles.snapshot(active));
        inst.addTransformer(LiveBridge.current.transformer(), true);
        retransform(LiveBridge.current, null);
        EnhancementLog.info("AGENT_ACTIVE: hot-update protocol=1; jar=" + jarPath);
        try { HotUpdateServer.start(jarPath, HotUpdateFiles.version(active)); }
        catch (Exception failure) { EnhancementLog.error("HOT_UPDATE_UNAVAILABLE: existing enhancements remain active", failure); }
    }

    private static AgentModule load(Path jar) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("cn.lucanet.enhancement.") && !name.equals(AgentModule.class.getName())
                        && !name.equals(LiveBridge.class.getName()) && !name.equals(AgentRuntime.class.getName())) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) { type = findClass(name); }
                    if (resolve) { resolveClass(type); }
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            AgentModule module = (AgentModule) loader.loadClass("cn.lucanet.enhancement.exit.RuntimeModule")
                    .getConstructor(Instrumentation.class, String.class).newInstance(instrumentation, installedJar);
            loaders.add(loader); return module;
        } catch (Exception | LinkageError failure) { loader.close(); throw failure; }
    }
    static synchronized void prepare(Path candidate) throws Exception {
        if (previous != null || prepared != null) { throw new IllegalStateException("Another update is in progress"); }
        prepared = load(candidate);
    }
    static synchronized void commit() throws Exception {
        if (prepared == null) { throw new IllegalStateException("No prepared update"); }
        AgentModule old = LiveBridge.current, next = prepared;
        instrumentation.removeTransformer(old.transformer());
        instrumentation.addTransformer(next.transformer(), true); LiveBridge.current = next;
        try { retransform(old, next); next.verifyTransformations(); previous = old; prepared = null; }
        catch (Exception | LinkageError failure) {
            instrumentation.removeTransformer(next.transformer());
            instrumentation.addTransformer(old.transformer(), true); LiveBridge.current = old;
            retransform(next, old); prepared = null; throw failure;
        }
    }
    static synchronized void rollback() throws Exception {
        prepared = null;
        if (previous == null) { return; }
        AgentModule failed = LiveBridge.current;
        instrumentation.removeTransformer(failed.transformer());
        instrumentation.addTransformer(previous.transformer(), true);
        LiveBridge.current = previous; retransform(failed, previous); previous = null;
    }
    static synchronized void finish() { previous = null; prepared = null; }
    private static void retransform(AgentModule one, AgentModule two) throws Exception {
        ArrayList<Class<?>> classes = new ArrayList<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            String name = type.getName().replace('.', '/');
            if ((one.isTarget(name) || two != null && two.isTarget(name)) && instrumentation.isModifiableClass(type)) {
                classes.add(type);
            }
        }
        if (!classes.isEmpty()) { instrumentation.retransformClasses(classes.toArray(new Class<?>[0])); }
    }

    public static void connectModule(Instrumentation instrumentation, Module module, String className) {
        if (module != null && module.isNamed()) {
            Module bridge = LiveBridge.class.getModule();
            Map<String, Set<Module>> exports = new java.util.HashMap<>();
            for (String pkg : module.getPackages()) {
                if (pkg.equals("ln.application") || pkg.equals("com.lucanet.lisa.ui")
                        || pkg.equals("com.lucanet.lisa.applicationframe") || pkg.equals("com.lucanet.lisa.useraction")) {
                    Set<Module> targets = new java.util.HashSet<>(); targets.add(bridge);
                    for (ClassLoader loader : loaders) { targets.add(loader.getUnnamedModule()); }
                    exports.put(pkg, targets);
                }
            }
            instrumentation.redefineModule(module, Set.of(bridge), exports, Collections.emptyMap(),
                    Collections.emptySet(), Collections.emptyMap());
        }
    }
    public static void connectNavigationApi(Class<?>... types) {
        for (Class<?> type : types) { connectModule(instrumentation, type.getModule(), type.getName()); }
    }
}
