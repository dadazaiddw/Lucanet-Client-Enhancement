import java.nio.file.*;

/** 只读解析真实 Manager 配置，再调用真实序列化方法，验证 Agent 随原生配置传递。 */
public final class RealNativeConfigTest {
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        Class<?> directory = Class.forName("com.lucanet.file.DirectoryPath");
        Class<?> file = Class.forName("com.lucanet.file.FilePath");
        Class<?> os = Class.forName("com.lucanet.base.misc.OperatingSystem");
        Class<?> paths = Class.forName("com.lucanet.program.ProgramPaths");
        Object installation = directory.getMethod("of", String.class).invoke(null, args[0]);
        Object data = directory.getMethod("of", String.class).invoke(null, args[2]);
        Object programPaths = paths.getMethod("of",directory,directory,os).invoke(null,installation,data,Enum.valueOf((Class)os,"WINDOWS"));
        Class<?> configClass = Class.forName("com.lucanet.program.ProgramLaunchConfiguration");
        Object config = configClass.getMethod("parse",paths,file).invoke(null, programPaths,file.getMethod("of",String.class).invoke(null,args[1]));
        String generated = (String)configClass.getMethod("toString",boolean.class,boolean.class).invoke(config,false,false);
        if (generated.lines().filter(s -> s.startsWith("jdkParameter=-javaagent:")).count()!=1) { throw new AssertionError(generated); }
        if (!generated.contains("mainClass=com/lucanet/lnswmgr_ui/Main")) { throw new AssertionError(generated); }
        Files.writeString(Path.of(args[2],"generated-manager.ln10"),generated);
        System.out.println("PASS: real ProgramLaunchConfiguration parser/serializer propagates native Agent parameter");
    }
}
