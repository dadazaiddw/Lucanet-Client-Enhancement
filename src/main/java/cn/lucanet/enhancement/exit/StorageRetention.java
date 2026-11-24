package cn.lucanet.enhancement.exit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/** Only plugin-owned batches/logs are eligible; never traverse a redirected directory. */
final class StorageRetention {
    static final String ARCHIVE_MARKER=".lucanet-enhancement-archive";
    private static final Pattern ARCHIVE=Pattern.compile("\\d{8}-\\d{6}-pid(\\d+)-\\d+");
    private static final Pattern LOG=Pattern.compile("client-exit-(\\d+)\\.log");
    private StorageRetention(){}
    static void markArchive(Path directory)throws IOException{
        Files.writeString(marker(directory),"lucanet-archive-v1\n"+Instant.now().toEpochMilli(),StandardOpenOption.CREATE_NEW);
    }
    static Path marker(Path directory){return directory.resolveSibling(directory.getFileName()+ARCHIVE_MARKER);}
    static void archives(Path directory,int days){clean(directory,days,true);}
    static void logs(Path directory,int days){clean(directory,days,false);}
    private static void clean(Path directory,int days,boolean archives){
        if(days<1||days>FeatureSettings.MAX_RETENTION_DAYS)return;
        try{
            if(!Files.isDirectory(directory))return;
            Path base=directory.toRealPath();Instant cutoff=Instant.now().minus(days,ChronoUnit.DAYS);
            try(DirectoryStream<Path> entries=Files.newDirectoryStream(base)){
                for(Path entry:entries)try{
                    Matcher name=(archives?ARCHIVE:LOG).matcher(entry.getFileName().toString());
                    if(!name.matches()||alive(name.group(1))||!direct(entry,base))continue;
                    if(archives){
                        if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))continue;
                        Path marker=marker(entry);
                        if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||!direct(marker,base)||Files.size(marker)>128)continue;
                        String[] metadata=Files.readString(marker).split("\n");
                        if(metadata.length!=2||!metadata[0].equals("lucanet-archive-v1")||!Instant.ofEpochMilli(Long.parseLong(metadata[1])).isBefore(cutoff))continue;
                        removeBatch(entry,base);
                        if(direct(marker,base))Files.deleteIfExists(marker);
                    }else if(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)&&Files.getLastModifiedTime(entry,LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)&&ownedLog(entry,name.group(1))){
                        if(direct(entry,base))Files.deleteIfExists(entry);
                    }
                }catch(IOException|RuntimeException failure){report(entry,failure);}
            }
        }catch(IOException|RuntimeException failure){report(directory,failure);}
    }
    private static boolean alive(String value){
        try{return ProcessHandle.of(Long.parseLong(value)).map(ProcessHandle::isAlive).orElse(false);}
        catch(RuntimeException unavailable){return true;}
    }
    private static boolean direct(Path path,Path base)throws IOException{
        return !Files.isSymbolicLink(path)&&path.toAbsolutePath().normalize().startsWith(base)&&path.toRealPath().equals(path.toAbsolutePath().normalize());
    }
    private static boolean ownedLog(Path file,String pid)throws IOException{
        try(java.io.Reader reader=Files.newBufferedReader(file)){
            char[] prefix=new char[256];int count=reader.read(prefix);
            return count>0&&new String(prefix,0,count).matches("(?s)^\\d{4}-\\d\\d-\\d\\dT[^\\r\\n]+ \\[(INFO|ERROR)\\] \\[pid="+pid+"\\] .*" );
        }
    }
    private static void removeBatch(Path batch,Path base)throws IOException{
        List<Path> paths=new ArrayList<>();
        Files.walkFileTree(batch,new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes)throws IOException{
                if(!direct(directory,base))throw new IOException("Redirected archive directory: "+directory);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException{
                if(!attributes.isRegularFile()||!direct(file,base))throw new IOException("Unmanaged archive entry: "+file);
                paths.add(file);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure)throws IOException{
                if(failure!=null)throw failure;paths.add(directory);return FileVisitResult.CONTINUE;
            }
        });
        for(Path path:paths){if(!direct(path,base))throw new IOException("Archive entry changed: "+path);Files.deleteIfExists(path);}
    }
    private static void report(Path path,Exception failure){System.err.println("[lucanet-enhancement] Retention skipped: "+path+"; "+failure);}
}
