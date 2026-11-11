package cn.lucanet.enhancement.exit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** 只转换已核实的方法签名，不写回/替换 Lucanet 专有 JAR。 */
final class NativeTransformer implements ClassFileTransformer {
    private static final String APPLICATION = "ln/application/Application";
    private static final String CONFIG = "com/lucanet/program/ProgramLaunchConfiguration";
    private static final String DIALOG = "com/lucanet/lisa/util/filehandling/UnknownFileDialogCreator";
    private static final String RESULT = "ln/ui_x/dialog/result/ResultMap";
    private static final String KEY = DIALOG + "$Key";
    private static final String LINK = "com/lucanet/assembly/summa/client/SummaClient$a";
    private static final String BRIDGE = "cn/lucanet/enhancement/exit/LiveBridge";
    private static final String DIALOG_DESCRIPTOR = "(Lln/ui_x/XWindow;Lcom/lucanet/lisa/ui/UIContext;Ljava/lang/String;)L" + RESULT + ";";
    private final Instrumentation instrumentation;
    private volatile String failure;
    void verifyTransformations() {
        if (failure != null) { throw new IllegalStateException(failure); }
    }

    NativeTransformer(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    static boolean isTarget(String name) {
        return APPLICATION.equals(name) || CONFIG.equals(name) || DIALOG.equals(name) || LINK.equals(name);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (!isTarget(name)) { return null; }
        try {
            AgentRuntime.connectModule(instrumentation, module, name);
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] count = {0};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor original = super.visitMethod(access, method, descriptor, signature, exceptions);
                    boolean stop = APPLICATION.equals(name) && method.equals("stop") && descriptor.equals("()V")
                            && (access & Opcodes.ACC_STATIC) != 0;
                    boolean config = CONFIG.equals(name) && method.equals("toString") && descriptor.equals("(ZZ)Ljava/lang/String;");
                    boolean dialog = DIALOG.equals(name) && method.equals("a") && descriptor.equals(DIALOG_DESCRIPTOR)
                            && (access & Opcodes.ACC_STATIC) != 0;
                    // 2311 / 2505 已核实：此私有方法仅由登录完成后的外部链接监听器在 EDT 上调用。
                    boolean link = LINK.equals(name) && method.equals("a") && descriptor.equals("(Ljava/lang/String;)V")
                            && (access & Opcodes.ACC_PRIVATE) != 0 && (access & Opcodes.ACC_STATIC) == 0;
                    if (!stop && !config && !dialog && !link) { return original; }
                    count[0]++;
                    return new MethodVisitor(Opcodes.ASM9, original) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (link) {
                                Label unchanged = new Label();
                                super.visitLdcInsn(Type.getObjectType(LINK));
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "openLinkInNewWindow", "(Ljava/lang/Class;Ljava/lang/String;)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, unchanged);
                                super.visitInsn(Opcodes.RETURN);
                                super.visitLabel(unchanged);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                            if (stop) {
                                super.visitLdcInsn(Type.getObjectType(APPLICATION));
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "beforeStop", "(Ljava/lang/Class;)V", false);
                            }
                            if (dialog) {
                                Label unchanged = new Label();
                                super.visitLdcInsn(Type.getObjectType(DIALOG));
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "suppressTemporaryFileDialog", "(Ljava/lang/Class;)Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, unchanged);
                                super.visitTypeInsn(Opcodes.NEW, RESULT);
                                super.visitInsn(Opcodes.DUP);
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitMethodInsn(Opcodes.INVOKESPECIAL, RESULT, "<init>", "(Z)V", false);
                                super.visitInsn(Opcodes.DUP);
                                super.visitFieldInsn(Opcodes.GETSTATIC, KEY, "ACTION_TYPE", "L" + KEY + ";");
                                // 所有客户端状态均返回已核实的“保留文件”，覆盖会话过期等非退出调用。
                                // 不归档或删除正在运行中的业务文件，不涉及登录/保存等其他对话框。
                                super.visitInsn(Opcodes.ICONST_3);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "set", "(Ljava/lang/Object;Ljava/lang/Object;)L" + RESULT + ";", false);
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.DUP);
                                super.visitFieldInsn(Opcodes.GETSTATIC, KEY, "NEW_FILE", "L" + KEY + ";");
                                super.visitInsn(Opcodes.ACONST_NULL);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "set", "(Ljava/lang/Object;Ljava/lang/Object;)L" + RESULT + ";", false);
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ARETURN);
                                super.visitLabel(unchanged);
                                super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (config && opcode == Opcodes.ARETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "augmentConfiguration", "(Ljava/lang/String;)Ljava/lang/String;", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            if (count[0] != 1) {
                failure = "Unsupported method signature: " + name;
                EnhancementLog.info("UNSUPPORTED_CLASS: " + name + "; matchingMethods=" + count[0]);
                return null;
            }
            EnhancementLog.info("TRANSFORMED: " + name);
            return writer.toByteArray();
        } catch (Exception | LinkageError failure) {
            this.failure = "Transformation failed: " + name;
            EnhancementLog.error("TRANSFORM_FAILED: " + name, failure);
            return null;
        }
    }
}
