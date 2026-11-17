package cn.lucanet.enhancement.exit;

import org.objectweb.asm.*;

/** 场景完成原生内容组装后注册外观；不改 builder、cancel、模态和场景执行。 */
final class SceneDialogTransformer {
    static byte[] transform(byte[] bytes) {
        ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
        int[] matches={0};boolean[] root={false};
        reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
            @Override public FieldVisitor visitField(int access,String name,String desc,String signature,Object value){
                if(name.equals("a") && desc.equals("Lcom/lucanet/lisa/widgets/LNPanel;") && (access&Opcodes.ACC_STATIC)==0)root[0]=true;
                return super.visitField(access,name,desc,signature,value);
            }
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){
                MethodVisitor original=super.visitMethod(access,name,desc,signature,exceptions);
                if(!name.equals("a") || !desc.equals("(Lln/ui_x/dialog/XDialog;)V") || (access&Opcodes.ACC_PRIVATE)==0 || (access&Opcodes.ACC_STATIC)!=0)return original;
                matches[0]++;
                return new MethodVisitor(Opcodes.ASM9,original){
                    @Override public void visitInsn(int opcode){
                        if(opcode==Opcodes.RETURN){
                            Label skip=new Label();
                            super.visitLdcInsn(SceneDialogStyle.ACTIVE);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"javax/swing/UIManager","get","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                            super.visitTypeInsn(Opcodes.INSTANCEOF,"java/util/function/Consumer");super.visitJumpInsn(Opcodes.IFEQ,skip);
                            super.visitLdcInsn(SceneDialogStyle.ACTIVE);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,"javax/swing/UIManager","get","(Ljava/lang/Object;)Ljava/lang/Object;",false);
                            super.visitTypeInsn(Opcodes.CHECKCAST,"java/util/function/Consumer");
                            super.visitVarInsn(Opcodes.ALOAD,0);super.visitFieldInsn(Opcodes.GETFIELD,SceneDialogStyle.CREATOR,"a","Lcom/lucanet/lisa/widgets/LNPanel;");
                            super.visitMethodInsn(Opcodes.INVOKEINTERFACE,"java/util/function/Consumer","accept","(Ljava/lang/Object;)V",true);
                            // RETURN 后不再使用局部变量；显式帧避免依赖原方法最后一帧的局部变量数量。
                            super.visitLabel(skip);super.visitFrame(Opcodes.F_FULL,2,new Object[]{SceneDialogStyle.CREATOR,"ln/ui_x/dialog/XDialog"},0,null);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        },0);
        if(matches[0]!=1 || !root[0])throw new IllegalStateException("Unsupported scene dialog layout contract");
        return writer.toByteArray();
    }
}
