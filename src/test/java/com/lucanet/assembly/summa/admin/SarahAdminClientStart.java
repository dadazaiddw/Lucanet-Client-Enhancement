package com.lucanet.assembly.summa.admin;

/** 独立客户端入口替身；不进入交付 JAR。 */
public final class SarahAdminClientStart {
    public static void main(String[] args) throws Exception {
        cn.lucanet.enhancement.exit.ClientLifecycleScenario.run();
    }
}
