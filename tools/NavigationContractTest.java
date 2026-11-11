import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** 对真实 JAR 检查公开 API 及“先新窗口、后加载目标”的原生调用顺序。 */
public final class NavigationContractTest {
    public static void main(String[] args) throws Exception {
        check(args[0],"com/lucanet/lisa/ui/UIController","createOpenHyperlinkUserAction","(Lcom/lucanet/lisa/ui/UIContext;Ljava/lang/String;Z)Lcom/lucanet/lisa/useraction/AbstractUserAction;");
        check(args[0],"com/lucanet/lisa/useraction/AbstractUserAction","executeNow","(Ljava/awt/event/ActionEvent;)V");
        check(args[0],"com/lucanet/lisa/applicationframe/UIContainer","getUIContext","()Lcom/lucanet/lisa/ui/UIContext;");
        List<String> calls=new ArrayList<>();
        reader(args[1],"com/lucanet/sarah/client/misc/OpenHyperlinkUserAction").accept(new ClassVisitor(Opcodes.ASM9){
            public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){
                if(!n.equals("doExecute"))return null;
                return new MethodVisitor(Opcodes.ASM9){public void visitMethodInsn(int o,String owner,String name,String desc,boolean itf){calls.add(owner+"."+name+desc);}};
            }
        },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        int window=-1,load=-1,navigate=-1;
        for(int i=0;i<calls.size();i++){
            if(calls.get(i).contains("OpenHyperlinkUserAction.")&&calls.get(i).endsWith("()Lcom/lucanet/lisa/applicationframe/UIContainer;"))window=i;
            if(calls.get(i).contains(".loadNavigationPoint("))load=i;
            if(calls.get(i).contains("UIContext.navigateTo("))navigate=i;
        }
        if(!(window>=0&&load>window&&navigate>load))throw new AssertionError("Native navigation sequence changed: "+calls);
        System.out.println("PASS: real UI API contracts; new container -> load navigation point -> navigate on its context");
    }
    static ClassReader reader(String file,String type)throws Exception{try(JarFile jar=new JarFile(file)){return new ClassReader(jar.getInputStream(jar.getJarEntry(type+".class")).readAllBytes());}}
    static void check(String file,String type,String method,String descriptor)throws Exception{
        boolean[] found={false};reader(file,type).accept(new ClassVisitor(Opcodes.ASM9){public MethodVisitor visitMethod(int a,String n,String d,String s,String[] e){if(n.equals(method)&&d.equals(descriptor)&&(a&Opcodes.ACC_PUBLIC)!=0)found[0]=true;return null;}},ClassReader.SKIP_CODE);
        if(!found[0])throw new AssertionError(type+"."+method+descriptor);
    }
}
