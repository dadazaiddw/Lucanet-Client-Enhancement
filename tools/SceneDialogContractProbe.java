import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** Verifies the installed Lucanet classes, without opening a database or running a scene. */
public final class SceneDialogContractProbe {
    private static final String CREATOR="com/lucanet/common/dom/dashboard/elements/ui/dashboardbuilder/SceneDialogCreator";
    private static byte[] transformed;
    public static void premain(String args,Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(Module m,ClassLoader l,String n,Class<?> c,ProtectionDomain d,byte[] bytes){
                if(CREATOR.equals(n))transformed=bytes.clone();return null;
            }
        },true);
    }
    public static void main(String[] args)throws Exception {
        Class<?> creator=Class.forName(CREATOR.replace('/','.'),false,ClassLoader.getSystemClassLoader());
        creator.getDeclaredMethods();
        if(transformed==null)throw new AssertionError("Scene class not observed");
        byte[] original;
        try(JarFile jar=new JarFile(args[0])){original=jar.getInputStream(jar.getJarEntry(CREATOR+".class")).readAllBytes();}
        Map<String,List<String>> before=calls(original),after=calls(transformed);
        String assembly="a(Lln/ui_x/dialog/XDialog;)V";
        for(String method:before.keySet()) {
            if(!method.equals(assembly) && !before.get(method).equals(after.get(method)))throw new AssertionError("Changed native method: "+method);
        }
        List<String> added=new ArrayList<>(after.get(assembly));
        List<String> nativeCalls=before.get(assembly);
        if(!added.subList(0,nativeCalls.size()).equals(nativeCalls))throw new AssertionError("Native assembly calls changed");
        added=added.subList(nativeCalls.size(),added.size());
        if(!added.equals(List.of("javax/swing/UIManager.get(Ljava/lang/Object;)Ljava/lang/Object;","javax/swing/UIManager.get(Ljava/lang/Object;)Ljava/lang/Object;","java/util/function/Consumer.accept(Ljava/lang/Object;)V")))throw new AssertionError("Unexpected hook: "+added);
        List<String> show=after.get("showDialog(Lcom/lucanet/common/dom/dashboard/widgets/DashboardUIContext;Lcom/lucanet/common/dom/dashboard/elements/ui/SceneElement;)Z");
        if(show==null || show.stream().noneMatch(c->c.contains("disableCloseByDeco")) || show.stream().noneMatch(c->c.contains("undecorated")))throw new AssertionError("Scene builder contract changed");
        Class<?> close=Class.forName(CREATOR.replace('/','.')+"$c",false,ClassLoader.getSystemClassLoader());
        if(!javax.swing.AbstractButton.class.isAssignableFrom(close))throw new AssertionError("Close button changed");
        Class<?> resize=Class.forName("com.lucanet.lisa.widgets.perspective.ResizePanel",false,ClassLoader.getSystemClassLoader());
        if(!javax.swing.JComponent.class.isAssignableFrom(resize))throw new AssertionError("Resize panel changed");
        Files.write(Path.of(args[1]),transformed);
        System.out.println("PASS: real scene class verifies; assembly/close/modal/show callbacks retained, single post-assembly hook; source="+creator.getProtectionDomain().getCodeSource().getLocation());
    }
    private static Map<String,List<String>> calls(byte[] bytes) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){
                List<String> method=new ArrayList<>();result.put(n+d,method);
                return new MethodVisitor(Opcodes.ASM9){public void visitMethodInsn(int o,String owner,String name,String desc,boolean itf){method.add(owner+"."+name+desc);}};
            }
        },0);return result;
    }
}
