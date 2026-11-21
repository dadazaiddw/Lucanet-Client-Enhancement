package cn.lucanet.enhancement.exit;

import java.nio.file.*;
import java.io.InputStream;
import java.util.concurrent.*;

/** Exercise Windows replacement contention, not a mock of Files.move. */
public final class HotUpdateFilesTest {
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);Path response=root.resolve("response.properties");
        HotUpdateFiles.write(response,HotUpdateFiles.values("value","old"));
        OpenOption noDelete=(OpenOption)Enum.valueOf((Class<? extends Enum>)Class.forName("com.sun.nio.file.ExtendedOpenOption"),"NOSHARE_DELETE");
        ExecutorService writer=Executors.newSingleThreadExecutor();
        try{
            Future<?> write;
            try(InputStream held=Files.newInputStream(response,StandardOpenOption.READ,noDelete)){
                write=writer.submit(()->{try{HotUpdateFiles.write(response,HotUpdateFiles.values("value","new"));}catch(Exception e){throw new RuntimeException(e);}});
                Thread.sleep(200);
                if(write.isDone())throw new AssertionError("write must wait for replacement lock");
                if(!"old".equals(HotUpdateFiles.read(response).getProperty("value")))throw new AssertionError("old response must remain readable while locked");
            }
            write.get(4,TimeUnit.SECONDS);
            if(!"new".equals(HotUpdateFiles.read(response).getProperty("value")))throw new AssertionError("response not replaced after unlock");
            try(InputStream held=Files.newInputStream(response,StandardOpenOption.READ,noDelete)){
                try{HotUpdateFiles.write(response,HotUpdateFiles.values("value","failed"));throw new AssertionError("persistent lock accepted");}catch(AccessDeniedException expected){}
            }
            if(!"new".equals(HotUpdateFiles.read(response).getProperty("value")))throw new AssertionError("failed write changed existing response");
            try(java.util.stream.Stream<Path> files=Files.list(root)){if(files.anyMatch(path->path.getFileName().toString().endsWith(".tmp")))throw new AssertionError("temporary write leaked");}
        }finally{writer.shutdownNow();}
        System.out.println("PASS: atomic update response retries a real Windows replacement lock, preserves old data and cleans up after failure");
    }
}
