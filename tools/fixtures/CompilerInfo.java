package com.lucanet.common.dom.script.java;

import com.lucanet.lisa.util.compiler.DefaultModuleInfoProvider;
import com.lucanet.lisa.util.compiler.ModuleInfoProvider;
import java.util.ArrayList;
import java.util.List;

/** The isolated probe loads the real Lucanet libraries on the class path. */
public final class CompilerInfo {
    private CompilerInfo() {}

    public static final ModuleInfoProvider SCRIPT_MODULE_INFO_PROVIDER = new DefaultModuleInfoProvider("ln.script.rt") {
        @Override public List<String> getModulePathArguments(List<String> libraries) throws UnknownModulesException {
            List<String> options = new ArrayList<>(super.getModulePathArguments(libraries));
            options.addAll(List.of("--add-reads", "ln.script.rt=ALL-UNNAMED", "-proc:none"));
            return options;
        }
    };
}
