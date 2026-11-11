package cn.lucanet.enhancement.exit;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import com.lucanet.lisa.ui.UIController;

/** 启动两个真实 JVM，切换候选 JAR 的实际行为并验证 rollback，绝不连接真实用户会话。 */
public final class HotUpdateTest {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) { child(); return; }
        Path root = Path.of(args[0]), source = Path.of(args[1]); Files.createDirectories(root);
        boolean previousBinary = args.length > 2;
        Path installed = root.resolve("installation/client-enhancement.jar"); Files.createDirectories(installed.getParent());
        Files.copy(previousBinary ? Path.of(args[2]) : source, installed);
        Path changed = root.resolve("changed.jar"), broken = root.resolve("broken.jar"), incompatible = root.resolve("incompatible.jar");
        candidate(source, changed, 0); candidate(source, broken, 1); candidate(source, incompatible, 2);
        List<Process> processes = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                List<String> command = command(root);
                command.addAll(List.of("-javaagent:"+installed, "-Djava.awt.headless=true", "-Dlucanet.exit.logDir="+root.resolve("logs"),
                        "-Dlucanet.enhancement.features="+root.resolve("features.properties"), "-cp",Path.of(HotUpdateTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),HotUpdateTest.class.getName(),"child"));
                Process process = new ProcessBuilder(command).redirectError(root.resolve("child-"+i+".err").toFile()).start(); processes.add(process);
                reply(process, "READY");
            }
            String ids = processes.get(0).pid()+","+processes.get(1).pid();
            if (previousBinary) {
                // 实际上一版 bootstrap 仍驻留时，修复应能热切换，无需换协议或重启会话。
                for (Process p : processes) { ask(p,"navigate","DROPPED"); }
                update(root,installed,source,ids,true);
            }
            for (Process p : processes) { ask(p,"navigate","NEW"); }
            update(root,installed,changed,ids,true);
            if (!HotUpdateFiles.hash(HotUpdateFiles.active(installed)).equals(HotUpdateFiles.hash(changed))) { throw new AssertionError("Active disk version not updated"); }
            for (Process p : processes) { ask(p,"navigate","ORIGINAL"); }
            List<String> restart = command(root);
            restart.addAll(List.of("-javaagent:"+installed,"-Djava.awt.headless=true","-Dlucanet.exit.logDir="+root.resolve("logs"),
                    "-Dlucanet.enhancement.features="+root.resolve("features.properties"),"-cp",Path.of(HotUpdateTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),HotUpdateTest.class.getName(),"child"));
            Process restarted = new ProcessBuilder(restart).redirectError(root.resolve("restarted.err").toFile()).start();
            processes.add(restarted);reply(restarted,"READY");ask(restarted,"navigate","ORIGINAL");
            restarted.getOutputStream().write("exit\n".getBytes());restarted.getOutputStream().flush();
            if(!restarted.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Restart probe failed to stop");
            processes.remove(restarted);
            update(root,installed,source,ids,true);
            for (Process p : processes) { ask(p,"navigate","NEW"); }
            String originalHash = HotUpdateFiles.hash(HotUpdateFiles.active(installed));
            update(root,installed,incompatible,ids,false);
            if (!originalHash.equals(HotUpdateFiles.hash(HotUpdateFiles.active(installed)))) { throw new AssertionError("Commit failure not rolled back"); }
            for (Process p : processes) { ask(p,"navigate","NEW"); }
            update(root,installed,broken,ids,false);
            if (!originalHash.equals(HotUpdateFiles.hash(HotUpdateFiles.active(installed)))) { throw new AssertionError("Active version not rolled back"); }
            for (Process p : processes) { ask(p,"navigate","NEW"); }
            update(root,installed,changed,Long.toString(ProcessHandle.current().pid()),false);
            System.out.println("PASS: two running JVMs switch actual behavior without restarting; subsequent start uses active version; prepare/commit failures roll back; non-agent process rejected");
        } finally { for (Process p : processes) { if (p.isAlive()) { p.getOutputStream().write("exit\n".getBytes()); p.getOutputStream().flush(); if (!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); } } } }
    }
    private static List<String> command(Path root) {
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Dlucanet.enhancement.runtime="+root.resolve("runtime")));
    }
    private static void update(Path root, Path installed, Path candidate, String ids, boolean success) throws Exception {
        List<String> command = command(root);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),HotUpdateClient.class.getName(),installed.toString(),candidate.toString(),ids));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if ((p.waitFor()==0)!=success) { throw new AssertionError(output); }
    }
    private static void child() throws Exception {
        System.out.println("READY"); System.out.flush();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line; (line=input.readLine())!=null && !line.equals("exit");) {
                int count=UIController.newWindows, original=UIController.originalNavigations;
                NavigationTest.navigate("ln:goto/LNFC/db?oid=2466470");
                System.out.println(UIController.newWindows>count?"NEW":UIController.originalNavigations>original?"ORIGINAL":"DROPPED"); System.out.flush();
            }
        }
    }
    private static void ask(Process process,String line,String expected) throws Exception {
        process.getOutputStream().write((line+"\n").getBytes());process.getOutputStream().flush();reply(process,expected);
    }
    private static void reply(Process process,String expected) throws Exception {
        StringBuilder line=new StringBuilder(); long deadline=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<deadline){
            if(process.getInputStream().available()>0){int b=process.getInputStream().read();if(b=='\n')break;if(b!='\r')line.append((char)b);}
            else if(!process.isAlive())break; else Thread.sleep(10);
        }
        if(!line.toString().equals(expected))throw new AssertionError("Expected "+expected+", got "+line);
    }
    private static void candidate(Path source,Path dest,int kind) throws Exception {
        try(JarFile jar=new JarFile(source.toFile());JarOutputStream out=new JarOutputStream(Files.newOutputStream(dest),jar.getManifest())){
            for(JarEntry entry:Collections.list(jar.entries())){
                if(entry.getName().equals("META-INF/MANIFEST.MF"))continue;
                byte[] bytes=jar.getInputStream(entry).readAllBytes();
                if(entry.getName().endsWith(kind==1?"RuntimeModule.class":kind==2?"NativeTransformer$1.class":"FeatureSettings.class")){
                    // 等长 UTF-8 常量替换：修改独立实现的行为 / 造成装载失败，不改测试/原生类。
                    byte[] before=(kind==1?"NativeTransformer":kind==2?"(Ljava/lang/String;)V":"linksInNewWindow").getBytes();
                    byte[] after=(kind==1?"BrokenTransformer":kind==2?"(Ljava/lang/Object;)V":"testOffNewWindow").getBytes();
                    for(int i=0;i<=bytes.length-before.length;i++)if(Arrays.equals(Arrays.copyOfRange(bytes,i,i+before.length),before))System.arraycopy(after,0,bytes,i,after.length);
                }
                out.putNextEntry(new JarEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
        }
        // 修改过的模块读取这个键，应当使新版本行为确实发生变化。
        Files.writeString(dest.getParent().resolve("features.properties"),"linksInNewWindow=true\ntestOffNewWindow=false\n");
    }
}
