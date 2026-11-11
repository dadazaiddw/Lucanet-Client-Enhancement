package com.lucanet.assembly.summa.client;

/** 独立客户端入口替身；不进入交付 JAR。 */
public final class SummaClientStart {
    public static void main(String[] args) throws Exception {
        cn.lucanet.enhancement.exit.ClientLifecycleScenario.run();
    }
}
