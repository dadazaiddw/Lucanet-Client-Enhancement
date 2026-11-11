import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** 在生产 Agent 之后截获真实类的变换结果；只做加载/字节码验证，不构造业务会话。 */
public final class ScriptPanelContractProbe {
    private static byte[] transformed;
    private static final String PANEL="com/lucanet/lisa/widgets/processlogger/ui/ProcessLoggerExecutionPanel";
    public static void premain(String args,Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(Module module,ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(PANEL.equals(name))transformed=bytes.clone();return null;
            }
        },true);
    }
    public static void main(String[] args)throws Exception {
        Class<?> panel=Class.forName(PANEL.replace('/','.'),false,ClassLoader.getSystemClassLoader());panel.getDeclaredMethods();
        if(transformed==null)throw new AssertionError("Real panel was not observed");
        List<String> keys=new ArrayList<>();int[] nativeVisible={0},metadataWrites={0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9){
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){
                public void visitLdcInsn(Object value){if(value instanceof String && ((String)value).startsWith("cn.lucanet.enhancement.script.v1."))keys.add((String)value);}
                public void visitMethodInsn(int o,String owner,String n,String d,boolean itf){
                    if(owner.equals("javax/swing/JComponent")&&n.equals("putClientProperty"))metadataWrites[0]++;
                    if(owner.equals("com/lucanet/lisa/widgets/LNButton")&&n.equals("setVisible"))nativeVisible[0]++;
                }
            };}
        },0);
        for(String suffix:new String[]{"button","task","visible","refresh"})if(!keys.contains("cn.lucanet.enhancement.script.v1."+suffix))throw new AssertionError("Missing panel hook: "+suffix);
        if(nativeVisible[0]<2 || metadataWrites[0]<3)throw new AssertionError("Native visibility/task hooks missing");
        try(JarFile jar=new JarFile(args[0])){
            String name="com/lucanet/summa/client/sections/script/dialogs/ScriptExecutionDialogCreator$a.class";
            if(jar.getJarEntry(name)==null)throw new AssertionError("Script task type changed");
        }
        Files.write(Path.of(args[1]),transformed);
        System.out.println("PASS: real ProcessLoggerExecutionPanel loads with verified script metadata hooks; native visibility calls retained; source="+panel.getProtectionDomain().getCodeSource().getLocation());
    }
}
