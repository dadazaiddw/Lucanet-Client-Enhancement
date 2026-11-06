import java.lang.reflect.*;

/** 退出阶段调用真实对话框方法，若仍构建 UI，则 headless 环境会立即失败。 */
public final class RealDialogTest {
    public static void main(String[] args) throws Exception {
        Class<?> app=Class.forName("ln.application.Application");
        Class<?> builder=Class.forName("ln.application.spi.ApplicationComponentBuilder");
        Class<?> listener=Class.forName("ln.application.ApplicationStateListener");
        Class<?> dialog=Class.forName("com.lucanet.lisa.util.filehandling.UnknownFileDialogCreator");
        Method show=java.util.Arrays.stream(dialog.getDeclaredMethods()).filter(m -> m.getName().equals("a")&&Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==3).findFirst().orElseThrow();
        show.setAccessible(true);
        app.getMethod("runWith",Array.newInstance(builder,0).getClass()).invoke(null,Array.newInstance(builder,0));
        boolean[] handled={false};
        Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class[]{listener},(p,m,a)->{
            if(m.getName().equals("applicationStateChanged")&&((Enum<?>)a[0]).name().equals("PREPARE_STOP")) {
                Object result=show.invoke(null,null,null,"locked-or-leftover-unknown-file");
                Class<?> key=Class.forName(dialog.getName()+"$Key");
                Object action=key.getField("ACTION_TYPE").get(null);
                Object value=result.getClass().getMethod("get",Object.class).invoke(result,action);
                if(!Byte.valueOf((byte)3).equals(value)) throw new AssertionError("Expected silent KEEP, got "+value);
                handled[0]=true;
            }
            return null;
        });
        app.getMethod("addApplicationStateListener",listener).invoke(null,proxy);
        app.getMethod("stop").invoke(null);
        if(!handled[0]) throw new AssertionError("Real PREPARE_STOP listener was not called");
        System.out.println("PASS: real UnknownFileDialogCreator returns KEEP in real PREPARE_STOP with no GUI/headless exception");
    }
}
