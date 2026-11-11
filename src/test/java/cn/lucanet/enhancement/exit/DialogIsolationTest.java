package cn.lucanet.enhancement.exit;

import java.net.URL;
import java.net.URLClassLoader;

/** 隔离两种入口，防止 Financial 标记掩盖管理员漏判；无会话状态仍可 KEEP。 */
public final class DialogIsolationTest {
    private static final String CLIENT_RESOURCE = "com/lucanet/assembly/summa/client/SummaClientStart.class";
    private static final String ADMIN_RESOURCE = "com/lucanet/assembly/summa/admin/SarahAdminClientStart.class";
    private static final String DIALOG = "com.lucanet.lisa.util.filehandling.UnknownFileDialogCreator";

    public static void main(String[] args) throws Exception {
        URL classes = DialogIsolationTest.class.getProtectionDomain().getCodeSource().getLocation();
        for (String resource : new String[]{CLIENT_RESOURCE, ADMIN_RESOURCE}) {
            try (URLClassLoader clientWithoutState = isolatedLoader(classes, resource)) {
                Class<?> dialog = Class.forName(DIALOG, true, clientWithoutState);
                Object action = dialog.getMethod("show", String.class).invoke(null, "expired-session-without-Application");
                if (!Byte.valueOf((byte) 3).equals(action) || dialog.getField("shown").getInt(null) != 0) {
                    throw new AssertionError("Client suppression incorrectly depends on loading Application: " + resource);
                }
            }
        }
        try (URLClassLoader nonClient = isolatedLoader(classes, null)) {
            Class<?> dialog = Class.forName(DIALOG, true, nonClient);
            Object action = dialog.getMethod("show", String.class).invoke(null, "non-financial-client-file");
            if (!Byte.valueOf((byte) 4).equals(action) || dialog.getField("shown").getInt(null) != 1) {
                throw new AssertionError("Non-client behavior was changed");
            }
        }
        System.out.println("PASS: Financial and Administrator independently KEEP without Application class; non-client dialog follows original method");
    }

    private static URLClassLoader isolatedLoader(URL classes, String clientResource) {
        return new URLClassLoader(new URL[] { classes }, ClassLoader.getPlatformClassLoader()) {
            @Override
            public URL getResource(String name) {
                if ((CLIENT_RESOURCE.equals(name) || ADMIN_RESOURCE.equals(name)) && !name.equals(clientResource)) { return null; }
                return super.getResource(name);
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
