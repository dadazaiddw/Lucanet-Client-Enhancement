import java.lang.reflect.*;
import java.lang.invoke.*;
import java.net.*;
import java.nio.file.*;
import java.util.jar.*;
import javax.swing.SwingUtilities;

/** 使用真实外部监听器字节码，UI 依赖用测试替身，不访问数据库/客户窗口。 */
public final class RealNavigationTest {
    public static void main(String[] args) throws Exception {
        byte[] bytes;
        String name = "com.lucanet.assembly.summa.client.SummaClient$a";
        try (JarFile jar = new JarFile(args[0])) { bytes = jar.getInputStream(jar.getJarEntry(name.replace('.','/')+".class")).readAllBytes(); }
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Path.of(args[1]).toUri().toURL(),Path.of(args[2]).toUri().toURL()}, null) {
            protected Class<?> findClass(String requested) throws ClassNotFoundException {
                return requested.equals(name) ? defineClass(name,bytes,0,bytes.length) : super.findClass(requested);
            }
        }) {
            Class<?> listener = loader.loadClass(name);
            // 构造器会获取真实 Application 的登录 URL，跳过它仅调用已核实的已登录导航入口。
            Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            Field field=unsafeType.getDeclaredField("theUnsafe");field.setAccessible(true);
            Object instance=unsafeType.getMethod("allocateInstance",Class.class).invoke(field.get(null),listener);
            Method navigate=listener.getDeclaredMethod("a",String.class);navigate.setAccessible(true);
            // 原监听器由 EDT 直接调用。测试也不能人为给业务调用栈添加 Method.invoke。
            MethodHandle directNavigation=MethodHandles.lookup().unreflect(navigate).bindTo(instance);
            Class<?> controller=loader.loadClass("com.lucanet.lisa.ui.UIController");
            Class<?> actionType=loader.loadClass("com.lucanet.lisa.useraction.AbstractUserAction");
            Runnable nativeGuard=RealReflectionGuard.load(Path.of(args[3]),Path.of(args[2]),Path.of(args[4]));
            actionType.getField("serverGuard").set(null,nativeGuard);
            String uri="ln:goto/LNFC/lncn_xz?oid=2466470";
            SwingUtilities.invokeAndWait(()->{try{directNavigation.invoke(uri);}catch(Throwable e){throw new RuntimeException(e);}});
            if(controller.getField("newWindows").getInt(null)!=1 || !uri.equals(controller.getField("lastUri").get(null)))throw new AssertionError("Real class not redirected");
            String normalized="lns://server/goto/LNFC/lncn_xz?oid=2466470";
            SwingUtilities.invokeAndWait(()->{try{directNavigation.invoke(normalized);}catch(Throwable e){throw new RuntimeException(e);}});
            if(controller.getField("newWindows").getInt(null)!=2 || !normalized.equals(controller.getField("lastUri").get(null)))throw new AssertionError("Normalized link not redirected");
            System.out.println("PASS: real external navigation class + active, unmodified VirtualSocket reflection guard; direct action accepts both URI forms: "+Path.of(args[0]).getFileName());
        }
    }
}
