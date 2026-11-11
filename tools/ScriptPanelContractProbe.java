import java.lang.instrument.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** 在生产 Agent 之后截获真实类的变换结果；只做加载/字节码验证，不构造业务会话。 */
public final class ScriptPanelContractProbe {
    private static final Map<String,byte[]> transformed=new HashMap<>();
    private static final String PANEL="com/lucanet/lisa/widgets/processlogger/ui/ProcessLoggerExecutionPanel";
    private static final String GENERAL="com/lucanet/summa/client/sections/script/panels/ScriptGeneralPanel";
    private static final String TREE="com/lucanet/sarah/client/misc/HierarchyElementPopupController";
    public static void premain(String args,Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer(){
            @Override public byte[] transform(Module module,ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
                if(PANEL.equals(name)||GENERAL.equals(name)||TREE.equals(name))transformed.put(name,bytes.clone());return null;
            }
        },true);
    }
    public static void main(String[] args)throws Exception {
        Class<?> panel=Class.forName(PANEL.replace('/','.'),false,ClassLoader.getSystemClassLoader());panel.getDeclaredMethods();
        if(!transformed.containsKey(PANEL))throw new AssertionError("Real panel was not observed");
        List<String> keys=new ArrayList<>();int[] nativeVisible={0},metadataWrites={0};
        new ClassReader(transformed.get(PANEL)).accept(new ClassVisitor(Opcodes.ASM9){
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
        Files.write(Path.of(args[1]),transformed.get(PANEL));
        System.out.println("PASS: real ProcessLoggerExecutionPanel loads with verified script metadata hooks; native visibility calls retained; source="+panel.getProtectionDomain().getCodeSource().getLocation());
        Class.forName(GENERAL.replace('/','.'),false,ClassLoader.getSystemClassLoader()).getDeclaredMethods();
        Class.forName(TREE.replace('/','.'),false,ClassLoader.getSystemClassLoader()).getDeclaredMethods();
        List<String> generalKeys=new ArrayList<>();List<String> runCalls=new ArrayList<>();
        new ClassReader(transformed.get(GENERAL)).accept(new ClassVisitor(Opcodes.ASM9){
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){return new MethodVisitor(Opcodes.ASM9){
                public void visitLdcInsn(Object value){if(value instanceof String)generalKeys.add((String)value);}
                public void visitMethodInsn(int o,String owner,String method,String desc,boolean itf){if(n.equals("h"))runCalls.add(owner+"."+method);}
            };}
        },0);
        for(String suffix:new String[]{"button","context","register"})if(!generalKeys.contains("cn.lucanet.enhancement.script.doubleClick."+suffix))throw new AssertionError("Missing double-click panel hook: "+suffix);
        if(!runCalls.contains("com/lucanet/summa/client/sections/script/dialogs/ScriptExecutionDialogCreator.showDialog")
                || runCalls.stream().anyMatch(call->call.endsWith(".start")||call.endsWith(".startAndShow")))throw new AssertionError("Native run must open dialog without starting: "+runCalls);
        boolean[] predicate={false},scope={false};
        new ClassReader(transformed.get(TREE)).accept(new ClassVisitor(Opcodes.ASM9){
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(!n.equals("handlePostSelectionDoubleClick"))return null;return new MethodVisitor(Opcodes.ASM9){
                public void visitLdcInsn(Object value){if("com.lucanet.summa.client.sections.script.controllers.ScriptPopupController".equals(value))scope[0]=true;}
                public void visitMethodInsn(int o,String owner,String method,String desc,boolean itf){if(owner.equals("java/util/function/BiPredicate")&&method.equals("test"))predicate[0]=true;}
            };}
        },0);
        if(!predicate[0]||!scope[0])throw new AssertionError("Real script-scoped double-click entry not transformed");
        System.out.println("PASS: real ScriptGeneralPanel and hierarchy double-click hook verify; original run opens showDialog without start");
    }
}
