package cn.lucanet.enhancement.exit;

import java.lang.instrument.Instrumentation;
import java.util.*;
import org.objectweb.asm.*;

/** Hooks the source getter and the DOM double-click controller, leaving all other nodes intact. */
final class CustomJavaTransformer {
    static final String CODE="com/lucanet/common/dom/script/java/JavaCode";
    static final String INTERACTION="com/lucanet/common/dom/base/section/AbstractDOMUserInteractionController";
    static final String EDITOR="com/lucanet/common/dom/script/java/JavaCodeEditor";
    static final String RENAME="com/lucanet/common/dom/script/elements/classes/ClassElement$ClassElementEditController";
    static final String EVENT="com/lucanet/lisa/widgets/treetable/LNTreeTableUserInteractionEvent";
    static boolean target(String name){return CODE.equals(name)||INTERACTION.equals(name)||EDITOR.equals(name)||RENAME.equals(name);}
    static void openAccess(Instrumentation instrumentation,Module module) {
        if(module==null||!module.isNamed())return;
        Set<Module> target=Set.of(CustomJavaTransformer.class.getModule());
        for(Module candidate:module.getLayer()==null?Set.of(module):module.getLayer().modules()) {
            Map<String,Set<Module>> access=new HashMap<>();
            for(String pkg:candidate.getPackages())if(pkg.startsWith("com.lucanet.common.dom.base")||pkg.startsWith("com.lucanet.common.dom.script")
                    ||pkg.startsWith("com.lucanet.lisa.widgets")||pkg.equals("com.lucanet.lisa.applicationframe.section.explorer")||pkg.equals("ln.xml"))access.put(pkg,target);
            if(!access.isEmpty())instrumentation.redefineModule(candidate,Set.of(),access,access,Set.of(),Map.of());
        }
    }
    static byte[] transform(String name,byte[] bytes) {
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] matches={0};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public MethodVisitor visitMethod(int access,String method,String descriptor,String signature,String[] exceptions){
                MethodVisitor original=super.visitMethod(access,method,descriptor,signature,exceptions);
                boolean source=CODE.equals(name)&&method.equals("getSourceCode")&&descriptor.equals("()Ljava/lang/String;");
                boolean interaction=INTERACTION.equals(name)&&method.equals("userInteracted")&&descriptor.equals("(L"+EVENT+";)V");
                boolean rename=RENAME.equals(name)&&method.equals("setElementName")&&descriptor.equals("(Lcom/lucanet/common/dom/base/DOMUIContext;Ljava/lang/String;)Ljava/util/List;");
                boolean usable=EDITOR.equals(name)&&method.equals("setUsable")&&descriptor.equals("(Z)V");
                boolean changed=EDITOR.equals(name)&&method.equals("javaCodeChanged")&&descriptor.equals("()V");
                boolean commit=EDITOR.equals(name)&&method.equals("commitChanges")&&descriptor.equals("()V");
                if(!source&&!interaction&&!rename&&!usable&&!changed&&!commit)return original;matches[0]++;
                return new MethodVisitor(Opcodes.ASM9,original){
                    @Override public void visitCode(){
                        super.visitCode();Label unchanged=new Label();boolean detail=usable||changed||commit;
                        String key=source?CustomJavaModel.SOURCE_HANDLER:rename?CustomJavaDetails.RENAME:detail?CustomJavaDetails.HANDLER:CustomJavaTypes.HANDLER;
                        String callback=source||rename?"java/util/function/Function":detail?"java/util/function/Predicate":"java/util/function/BiPredicate";
                        if(interaction){mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,EVENT,"getType","()I",false);mv.visitInsn(Opcodes.ICONST_4);mv.visitJumpInsn(Opcodes.IF_ICMPNE,unchanged);}
                        handler(key);mv.visitTypeInsn(Opcodes.INSTANCEOF,callback);mv.visitJumpInsn(Opcodes.IFEQ,unchanged);
                        handler(key);mv.visitTypeInsn(Opcodes.CHECKCAST,callback);mv.visitVarInsn(Opcodes.ALOAD,0);
                        if(source||rename){
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,callback,"apply","(Ljava/lang/Object;)Ljava/lang/Object;",true);
                            Label absent=new Label();mv.visitInsn(Opcodes.DUP);mv.visitJumpInsn(Opcodes.IFNULL,absent);mv.visitTypeInsn(Opcodes.CHECKCAST,source?"java/lang/String":"java/util/List");mv.visitInsn(Opcodes.ARETURN);
                            mv.visitLabel(absent);mv.visitFrame(Opcodes.F_SAME1,0,null,1,new Object[]{"java/lang/Object"});mv.visitInsn(Opcodes.POP);
                        }else if(detail){
                            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,callback,"test","(Ljava/lang/Object;)Z",true);mv.visitJumpInsn(Opcodes.IFEQ,unchanged);
                            if(usable){mv.visitInsn(Opcodes.ICONST_0);mv.visitVarInsn(Opcodes.ISTORE,1);}
                            else {if(changed){mv.visitVarInsn(Opcodes.ALOAD,0);mv.visitInsn(Opcodes.ICONST_0);mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,EDITOR,"setUsable","(Z)V",false);}mv.visitInsn(Opcodes.RETURN);}
                        }else{
                            mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,callback,"test","(Ljava/lang/Object;Ljava/lang/Object;)Z",true);mv.visitJumpInsn(Opcodes.IFEQ,unchanged);
                            mv.visitVarInsn(Opcodes.ALOAD,1);mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,EVENT,"consume","()V",false);mv.visitInsn(Opcodes.RETURN);
                        }
                        mv.visitLabel(unchanged);mv.visitFrame(Opcodes.F_SAME,0,null,0,null);
                    }
                    private void handler(String key){mv.visitLdcInsn(key);mv.visitMethodInsn(Opcodes.INVOKESTATIC,"javax/swing/UIManager","get","(Ljava/lang/Object;)Ljava/lang/Object;",false);}
                };
            }
        },0);
        if(matches[0]!=(EDITOR.equals(name)?3:1))throw new IllegalStateException("Unsupported custom Java contract: "+name);
        return writer.toByteArray();
    }
}
