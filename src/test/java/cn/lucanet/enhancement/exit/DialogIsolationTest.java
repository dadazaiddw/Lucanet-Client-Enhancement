package cn.lucanet.enhancement.exit;

import java.net.URL;
import java.net.URLClassLoader;

/** 独立类加载器验证：没有 Application 状态也能 KEEP；非客户端仍走原方法。 */
public final class DialogIsolationTest {
    private static final String CLIENT_RESOURCE = "com/lucanet/assembly/summa/client/SummaClientStart.class";
    private static final String DIALOG = "com.lucanet.lisa.util.filehandling.UnknownFileDialogCreator";

    public static void main(String[] args) throws Exception {
        URL classes = DialogIsolationTest.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader clientWithoutState = isolatedLoader(classes, true)) {
            Class<?> dialog = Class.forName(DIALOG, true, clientWithoutState);
            Object action = dialog.getMethod("show", String.class).invoke(null, "expired-session-without-Application");
            if (!Byte.valueOf((byte) 3).equals(action) || dialog.getField("shown").getInt(null) != 0) {
                throw new AssertionError("Client suppression incorrectly depends on loading Application");
            }
        }
        try (URLClassLoader nonClient = isolatedLoader(classes, false)) {
            Class<?> dialog = Class.forName(DIALOG, true, nonClient);
            Object action = dialog.getMethod("show", String.class).invoke(null, "non-financial-client-file");
            if (!Byte.valueOf((byte) 4).equals(action) || dialog.getField("shown").getInt(null) != 1) {
                throw new AssertionError("Non-Financial-Client behavior was changed");
            }
        }
        System.out.println("PASS: Financial Client KEEP without Application class; non-client dialog follows original method");
    }

    private static URLClassLoader isolatedLoader(URL classes, boolean financialClient) {
        return new URLClassLoader(new URL[] { classes }, ClassLoader.getPlatformClassLoader()) {
            @Override
            public URL getResource(String name) {
                return !financialClient && CLIENT_RESOURCE.equals(name) ? null : super.getResource(name);
            }

            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("ln.application.")) {
                    throw new ClassNotFoundException("Application unavailable after session cleanup: " + name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }
}
