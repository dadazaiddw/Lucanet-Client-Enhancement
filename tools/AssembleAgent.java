import java.nio.file.*;
import java.util.jar.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;

/** 离线构建：把 ASM 移到私有包名，避免与 Lucanet 自带 ASM/模块发生冲突。 */
public final class AssembleAgent {
    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]);
        Manifest manifest = new Manifest();
        Attributes a = manifest.getMainAttributes();
        a.putValue("Manifest-Version", "1.0");
        a.putValue("Premain-Class", "cn.lucanet.enhancement.exit.ExitAgent");
        a.putValue("Can-Retransform-Classes", "true");
        Remapper remapper = new Remapper() {
            public String map(String name) {
                return name.startsWith("org/objectweb/asm/")
                        ? name.replace("org/objectweb/asm/", "cn/lucanet/enhancement/internal/asm/") : name;
            }
        };
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[2])), manifest);
             ZipFile asm = new ZipFile(args[1]);
             var files = Files.walk(classes)) {
            for (Path path : files.filter(p -> p.startsWith(classes.resolve("cn")) && p.toString().endsWith(".class")).toArray(Path[]::new)) {
                add(out, classes.relativize(path).toString().replace('\\','/'), Files.readAllBytes(path), remapper);
            }
            for (ZipEntry entry : java.util.Collections.list(asm.entries())) {
                if (entry.getName().startsWith("org/objectweb/asm/") && entry.getName().endsWith(".class")) {
                    add(out, remapper.map(entry.getName().substring(0, entry.getName().length()-6))+".class",
                            asm.getInputStream(entry).readAllBytes(), remapper);
                }
            }
            out.putNextEntry(new JarEntry("META-INF/ASM-LICENSE.txt"));
            out.write(Files.readAllBytes(Path.of(args[3])));
            out.closeEntry();
        }
    }
    private static void add(JarOutputStream out, String name, byte[] bytes, Remapper remapper) throws Exception {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        out.putNextEntry(new JarEntry(name)); out.write(writer.toByteArray()); out.closeEntry();
    }
}
