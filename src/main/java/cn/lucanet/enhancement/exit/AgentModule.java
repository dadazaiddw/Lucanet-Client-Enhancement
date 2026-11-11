package cn.lucanet.enhancement.exit;

import java.lang.instrument.ClassFileTransformer;

/** 热更新协议 1 的稳定边界。实现类及 ASM 在每个版本独立的类加载器中加载。 */
public interface AgentModule {
    ClassFileTransformer transformer();
    boolean isTarget(String internalName);
    void verifyTransformations();
    Object invoke(String operation, Object[] arguments);
}
