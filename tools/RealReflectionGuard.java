import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** 只调用真实 VirtualSocket 的无网络栈检查；不构造连接、不设置 QFTest、不改写该类。 */
final class RealReflectionGuard {
    static Runnable load(Path sarah, Path ui, Path resources) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(sarah.toUri().toURL()); urls.add(ui.toUri().toURL());
        try (var files = Files.list(resources)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar")).sorted().toArray(Path[]::new)) {
                if (compatible(path)) { urls.add(path.toUri().toURL()); }
            }
        }
        // 保持 loader 到进程退出，使异步 EDT 中的实际调用可按需加载依赖。
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        Class<?> socket = Class.forName("com.lucanet.sarah.client.communication.VirtualSocket", false, loader);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Object instance = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, socket);
        Field walker = Arrays.stream(socket.getDeclaredFields()).filter(f -> f.getType() == StackWalker.class).findFirst().orElseThrow();
        walker.setAccessible(true); walker.set(instance, StackWalker.getInstance(StackWalker.Option.SHOW_REFLECT_FRAMES));
        // 2311/2505 均由 javap 核实该方法只做原生反射栈检查。
        Method check = socket.getDeclaredMethod("i"); check.setAccessible(true);
        MethodHandle call = MethodHandles.lookup().unreflect(check).bindTo(instance);
        Runnable guard = () -> {
            try { call.invokeExact(); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable e) { throw new IllegalStateException(e); }
        };
        guard.run();
        // 必须确认检查仍开启：反射路径确实被真实原生类拒绝。
        try { check.invoke(instance); throw new AssertionError("Real reflection guard was not active"); }
        catch (InvocationTargetException expected) {
            if (!(expected.getCause() instanceof IllegalStateException) || !"no reflection allowed!".equals(expected.getCause().getMessage())) { throw expected; }
        }
        return guard;
    }
    private static boolean compatible(Path path) throws Exception {
        // 同一缓存含 Java 17 / 21 的多套库；旧客户端回归不能误选新库。
        try (JarFile jar = new JarFile(path.toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")
                        && !entry.getName().startsWith("META-INF/")) {
                    try (var input = jar.getInputStream(entry)) {
                        byte[] header = input.readNBytes(8);
                        return header.length == 8 && ((header[6] & 255) * 256 + (header[7] & 255)) <= Runtime.version().feature() + 44;
                    }
                }
            }
        }
        return true;
    }
}
