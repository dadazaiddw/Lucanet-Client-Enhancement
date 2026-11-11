import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** 真实运行/退出阶段调用未知文件方法，若仍构建 UI，headless 环境会立即失败。 */
public final class RealDialogTest {
    public static void main(String[] args) throws Exception {
        String permitted = args.length > 0 && args[0].equals("administrator")
                ? "com/lucanet/assembly/summa/admin/SarahAdminClientStart.class"
                : "com/lucanet/assembly/summa/client/SummaClientStart.class";
        List<URL> urls = new ArrayList<>();
        if (args.length > 1) { urls.add(Path.of(args[1]).toUri().toURL()); }
        urls.addAll(Arrays.asList(Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(path -> { try { return Path.of(path).toUri().toURL(); } catch (Exception e) { throw new IllegalStateException(e); } })
                .toArray(URL[]::new)));
        try (URLClassLoader isolated = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
            @Override public URL getResource(String name) {
                if ((name.equals("com/lucanet/assembly/summa/admin/SarahAdminClientStart.class")
                        || name.equals("com/lucanet/assembly/summa/client/SummaClientStart.class")) && !name.equals(permitted)) { return null; }
                return super.getResource(name);
            }
        }) {
            URL entry = isolated.getResource(permitted);
            if (entry == null || !entry.getProtocol().equals("jar")) { throw new AssertionError("Expected real assembly entry: " + entry); }
            Class<?> probe = Class.forName("RealDialogTest", true, isolated);
            probe.getMethod("runScenario").invoke(null);
            System.out.println("Client entry: " + entry);
        }
    }

    public static void runScenario() throws Exception {
        Class<?> app=Class.forName("ln.application.Application");
        Class<?> builder=Class.forName("ln.application.spi.ApplicationComponentBuilder");
        Class<?> listener=Class.forName("ln.application.ApplicationStateListener");
        Class<?> dialog=Class.forName("com.lucanet.lisa.util.filehandling.UnknownFileDialogCreator");
        Method show=java.util.Arrays.stream(dialog.getDeclaredMethods()).filter(m -> m.getName().equals("a")&&Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==3).findFirst().orElseThrow();
        show.setAccessible(true);
        assertKeep(show, dialog, "before-application-start");
        app.getMethod("runWith",Array.newInstance(builder,0).getClass()).invoke(null,Array.newInstance(builder,0));
        if (!((Enum<?>)app.getMethod("getApplicationState").invoke(null)).name().equals("RUNNING")) {
            throw new AssertionError("Expected real Application to be RUNNING");
        }
        assertKeep(show, dialog, "session-expired-while-running");
        boolean[] handled={false};
        Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(p,m,a)->{
            if(m.getName().equals("applicationStateChanged")&&((Enum<?>)a[0]).name().equals("PREPARE_STOP")) {
                assertKeep(show, dialog, "locked-or-leftover-unknown-file");
                handled[0]=true;
            }
            return null;
        });
        app.getMethod("addApplicationStateListener",listener).invoke(null,proxy);
        app.getMethod("stop").invoke(null);
        if(!handled[0]) throw new AssertionError("Real PREPARE_STOP listener was not called");
        assertKeep(show, dialog, "session-cleaned-up-idle");
        System.out.println("PASS: real UnknownFileDialogCreator returns KEEP in EMPTY/RUNNING/PREPARE_STOP/IDLE without GUI; includes non-exit session-expiry check path");
    }

    private static void assertKeep(Method show, Class<?> dialog, String name) throws Exception {
        Object result=show.invoke(null,null,null,name);
        Class<?> key=Class.forName(dialog.getName()+"$Key");
        Method get=result.getClass().getMethod("get",Object.class);
        Object value=get.invoke(result,key.getField("ACTION_TYPE").get(null));
        if(!Byte.valueOf((byte)3).equals(value)) throw new AssertionError("Expected silent KEEP, got "+value+" for "+name);
        if(get.invoke(result,key.getField("NEW_FILE").get(null))!=null) throw new AssertionError("Unexpected move target for "+name);
    }
}
