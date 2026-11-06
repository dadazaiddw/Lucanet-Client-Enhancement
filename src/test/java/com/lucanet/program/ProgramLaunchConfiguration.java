package com.lucanet.program;
/** 原生配置序列化方法的签名替身。 */
public final class ProgramLaunchConfiguration {
    private final String main;
    public ProgramLaunchConfiguration(String main) { this.main = main; }
    public String toString(boolean paths, boolean options) {
        return "version=2\njdkParameter=-Xmx256m\nmainClass=" + main + "\n";
    }
}
