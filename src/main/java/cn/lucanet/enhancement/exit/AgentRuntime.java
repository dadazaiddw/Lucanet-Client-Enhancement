package cn.lucanet.enhancement.exit;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** 从 bootstrap loader 加载，便于 Lucanet 各模块/类加载器调用固定增强逻辑。 */
public final class AgentRuntime {
    private AgentRuntime() { }
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    public static void start(Instrumentation instrumentation, String jarPath) {
        if (!STARTED.compareAndSet(false, true)) { return; }
        NativeBridge.agentJar = jarPath;
        instrumentation.addTransformer(new NativeTransformer(instrumentation), true);
        EnhancementLog.info("AGENT_ACTIVE: built-in silent temporary-file handling; jar=" + jarPath);
        // premain 在业务类加载之前注册；重复加载/已有类也尝试转换，失败只记录具体类。
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (NativeTransformer.isTarget(type.getName().replace('.', '/')) && instrumentation.isModifiableClass(type)) {
                try {
                    instrumentation.retransformClasses(type);
                } catch (Exception failure) {
                    EnhancementLog.error("TRANSFORM_FAILED: " + type.getName(), failure);
                }
            }
        }
    }

    static void connectModule(Instrumentation instrumentation, Module module, String className) {
        if (module != null && module.isNamed()) {
            Module bridge = NativeBridge.class.getModule();
            Map<String, Set<Module>> exports = className.equals("ln/application/Application")
                    ? Map.of("ln.application", Set.of(bridge)) : Collections.emptyMap();
            instrumentation.redefineModule(module, Set.of(bridge), exports, Collections.emptyMap(),
                    Collections.emptySet(), Collections.emptyMap());
        }
    }
}
