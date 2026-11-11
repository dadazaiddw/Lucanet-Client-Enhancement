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
        String input = Files.readString(Path.of(args[1]));
        String[] mains = {"com/lucanet/lnswmgr_ui/Main", "com/lucanet/assembly/summa/client/SummaClientStart",
                "com/lucanet/assembly/summa/admin/SarahAdminClientStart", "com/lucanet/assembly/summa/server/SummaServerStart"};
        for (int i = 0; i < mains.length; i++) {
            String main = mains[i];
            Path parsedFile = Path.of(args[2], "input-" + i + ".ln10");
            Files.writeString(parsedFile, input.replaceAll("(?m)^mainClass=[^\\r\\n]+", "mainClass=" + main));
            Object config = configClass.getMethod("parse",paths,file).invoke(null, programPaths,file.getMethod("of",String.class).invoke(null,parsedFile.toString()));
            String generated = (String)configClass.getMethod("toString",boolean.class,boolean.class).invoke(config,false,false);
            long agents = generated.lines().filter(s -> s.startsWith("jdkParameter=-javaagent:")).count();
            if (agents != (i == 3 ? 0 : 1) || !generated.contains("mainClass=" + main)) { throw new AssertionError(generated); }
            Files.writeString(Path.of(args[2],"generated-" + i + ".ln10"),generated);
        }
        System.out.println("PASS: real native configuration propagates to Manager/Financial/Administrator, server unchanged");
    }
}
