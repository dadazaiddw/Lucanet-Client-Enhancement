package cn.lucanet.enhancement.exit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

/** 每次更新重新创建本实现；新版本可增加内部类，不受 JVM 类结构重定义限制。 */
public final class RuntimeModule implements AgentModule {
    private final NativeTransformer transformer;
    public RuntimeModule(Instrumentation instrumentation, String installedAgent) {
        NativeBridge.agentJar = installedAgent;
        transformer = new NativeTransformer(instrumentation);
    }
    public ClassFileTransformer transformer() { return transformer; }
    public boolean isTarget(String name) { return NativeTransformer.isTarget(name); }
    public void verifyTransformations() { transformer.verifyTransformations(); }
    public Object invoke(String operation, Object[] args) {
        switch (operation) {
            case "beforeStop": NativeBridge.beforeStop((Class<?>) args[0]); return null;
            case "augmentConfiguration": return NativeBridge.augmentConfiguration((String) args[0]);
            case "suppressTemporaryFileDialog": return NativeBridge.suppressTemporaryFileDialog((Class<?>) args[0]);
            case "openLinkInNewWindow": return LinkNavigation.open((Class<?>) args[0], (String) args[1]);
            default: throw new IllegalArgumentException("Unsupported operation: " + operation);
        }
    }
}
