import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;

/** Test library outside java.class.path, exposed only by the current Script's provider. */
final class JavaLibraryProbe {
    private List<File> files;

    static JavaLibraryProbe attach(Object script,Path build)throws Exception {
        Class<?> type;
        try{type=script.getClass().getMethod("getAdditionalLibrariesProvider").getReturnType();}
        catch(NoSuchMethodException legacy){return null;}
        Path work=Files.createTempDirectory(build,"script-libraries-");
        Path source=work.resolve("EasyExcel.java");
        Files.writeString(source,"package com.alibaba.excel; public class EasyExcel { public static int fixtureRead() { return 42; } }");
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","--release","11","-d",work.toString(),source.toString())!=0)
            throw new AssertionError("Could not build the additional library fixture");
        String entry="com/alibaba/excel/EasyExcel.class";
        Path jar=work.resolve("easyexcel-fixture.jar");
        try(JarOutputStream archive=new JarOutputStream(Files.newOutputStream(jar))){
            archive.putNextEntry(new JarEntry(entry));
            Files.copy(work.resolve(entry),archive);
            archive.closeEntry();
        }
        JavaLibraryProbe fixture=new JavaLibraryProbe();
        fixture.files=List.of(jar.toFile());
        Object provider=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getName().equals("getAdditionalLibrariesForCompilation"))return fixture.files;
            if(method.getName().equals("getAdditionalLibrariesClassLoader"))return args[0];
            throw new UnsupportedOperationException(method.getName());
        });
        for(Field field:script.getClass().getDeclaredFields())if(field.getType()==type){
            field.setAccessible(true);
            field.set(script,provider);
            return fixture;
        }
        throw new AssertionError("Could not attach the Script's library provider");
    }

    void detachLibrary(){files=List.of();}
}
