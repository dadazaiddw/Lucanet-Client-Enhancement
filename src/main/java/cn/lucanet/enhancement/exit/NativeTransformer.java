package cn.lucanet.enhancement.exit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
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
    private static final String PANEL = "com/lucanet/lisa/widgets/processlogger/ui/ProcessLoggerExecutionPanel";
    private static final String BUTTON = "com/lucanet/lisa/widgets/LNButton";
    private static final String SWING = "javax/swing/JComponent";
    private static final String STATE_DESCRIPTOR = "(L" + PANEL + "$State;)V";
    private static final String TASK_DESCRIPTOR = "(Lcom/lucanet/lisa/widgets/processlogger/ProcessLoggerTask;Ljava/lang/String;L" + PANEL + "$Hook;)V";
    private static final String BRIDGE = "cn/lucanet/enhancement/exit/LiveBridge";
    private static final String DIALOG_DESCRIPTOR = "(Lln/ui_x/XWindow;Lcom/lucanet/lisa/ui/UIContext;Ljava/lang/String;)L" + RESULT + ";";
    private final Instrumentation instrumentation;
    private volatile String failure;
    void verifyTransformations() {
        if (failure != null) { throw new IllegalStateException(failure); }
    }

    NativeTransformer(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    static boolean isTarget(String name) {
        return APPLICATION.equals(name) || CONFIG.equals(name) || DIALOG.equals(name) || LINK.equals(name) || PANEL.equals(name);
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining,
                            ProtectionDomain domain, byte[] bytes) {
        if (!isTarget(name)) { return null; }
        try {
            if (PANEL.equals(name) && !NativeBridge.isDesktopClient(loader)) { return null; }
            AgentRuntime.connectModule(instrumentation, module, name);
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            int[] count = {0};
            boolean[] startField = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public FieldVisitor visitField(int access, String field, String descriptor, String signature, Object value) {
                    if (field.equals("i") && descriptor.equals("L" + BUTTON + ";") && (access & Opcodes.ACC_STATIC) == 0) { startField[0] = true; }
                    return super.visitField(access, field, descriptor, signature, value);
                }
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
                    boolean scriptState = PANEL.equals(name) && method.equals("a") && descriptor.equals(STATE_DESCRIPTOR);
                    boolean scriptTask = PANEL.equals(name) && method.equals("setProcessLoggerTask") && descriptor.equals(TASK_DESCRIPTOR);
                    if (!stop && !config && !dialog && !link && !scriptState && !scriptTask) { return original; }
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
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                            if (scriptState && owner.equals(BUTTON) && method.equals("setVisible") && descriptor.equals("(Z)V")) {
                                // 记录原生想要的可见性，独立于隐藏叠层；执行中/结束时不会误恢复开始按钮。
                                super.visitInsn(Opcodes.DUP2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                                super.visitLdcInsn(ScriptControls.VISIBLE); super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SWING, "putClientProperty", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }
                            super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                        }
                        @Override
                        public void visitInsn(int opcode) {
                            if (config && opcode == Opcodes.ARETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "augmentConfiguration", "(Ljava/lang/String;)Ljava/lang/String;", false);
                            }
                            if (opcode == Opcodes.RETURN && (scriptState || scriptTask)) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitLdcInsn(scriptState ? ScriptControls.BUTTON : ScriptControls.TASK);
                                if (scriptState) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitFieldInsn(Opcodes.GETFIELD, PANEL, "i", "L" + BUTTON + ";");
                                } else { super.visitVarInsn(Opcodes.ALOAD, 1); }
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SWING, "putClientProperty", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                if (scriptState) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0); super.visitLdcInsn(ScriptControls.REFRESH);
                                    super.visitTypeInsn(Opcodes.NEW, "java/lang/Object"); super.visitInsn(Opcodes.DUP);
                                    super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SWING, "putClientProperty", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            if (count[0] != (PANEL.equals(name) ? 2 : 1) || PANEL.equals(name) && !startField[0]) {
                failure = "Unsupported method signature: " + name;
                EnhancementLog.info("UNSUPPORTED_CLASS: " + name + "; matchingMethods=" + count[0]);
                return null;
            }
            EnhancementLog.info("TRANSFORMED: " + name);
            // 运行时安装观察器，原生字节码只调用 JComponent API，不增加 bootstrap 桥协议依赖。
            if (PANEL.equals(name)) { ScriptControls.ensureInstalled(); }
            return writer.toByteArray();
        } catch (Exception | LinkageError failure) {
            this.failure = "Transformation failed: " + name;
            EnhancementLog.error("TRANSFORM_FAILED: " + name, failure);
            return null;
        }
    }
}
