package eta.build;

import java.util.Map;
import org.gradle.api.GradleException;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Avoid focusability work for nodes already absent from the current pruned tree. */
final class ComposeTraversalFocusPatch implements Opcodes {
    static final String TARGET = "androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat_androidKt";
    private static final String MAP = "androidx/collection/IntObjectMap";
    private static final String NODE = "androidx/compose/ui/semantics/SemanticsNode";
    private static final String RES = "android/content/res/Resources";
    private static final String BRIDGE = "eta$visibleScreenReaderFocusable";
    private static final String DESC = "(L" + MAP + ";L" + RES + ";L" + NODE + ";)Z";

    static byte[] patch(byte[] bytes) {
        ClassNode c = new ClassNode(ASM9); new ClassReader(bytes).accept(c, 0);
        int sites = 0;
        for (MethodNode m : c.methods) {
            if (!m.name.equals("setTraversalValues")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (!(i instanceof InvokeDynamicInsnNode indy)) continue;
                if (!indy.desc.equals("(L" + RES + ";)Lkotlin/jvm/functions/Function1;")) continue;
                require(indy.bsmArgs.length == 3 && indy.bsmArgs[1] instanceof Handle, "lambda bootstrap");
                Handle h = (Handle) indy.bsmArgs[1];
                require(h.getOwner().equals(TARGET) && h.getName().equals("setTraversalValues$lambda$1")
                        && h.getDesc().equals("(L" + RES + ";L" + NODE + ";)Z"), "focus lambda");
                // Resources is already on the stack. Add the immutable query map capture.
                InsnList capture = new InsnList(); capture.add(new VarInsnNode(ALOAD, 0)); capture.add(new InsnNode(SWAP));
                m.instructions.insertBefore(indy, capture);
                indy.desc = "(L" + MAP + ";L" + RES + ";)Lkotlin/jvm/functions/Function1;";
                indy.bsmArgs[1] = new Handle(H_INVOKESTATIC, TARGET, BRIDGE, DESC, false);
                sites++;
            }
        }
        require(sites == 1, "focus capture count");
        MethodNode v = new MethodNode(ASM9, ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, BRIDGE, DESC, null, null);
        Label visible = new Label(); v.visitCode();
        v.visitVarInsn(ALOAD, 0); v.visitVarInsn(ALOAD, 2);
        v.visitMethodInsn(INVOKEVIRTUAL, NODE, "getId", "()I", false);
        v.visitMethodInsn(INVOKEVIRTUAL, MAP, "containsKey", "(I)Z", false);
        v.visitJumpInsn(IFNE, visible); v.visitInsn(ICONST_0); v.visitInsn(IRETURN);
        v.visitLabel(visible); v.visitFrame(F_SAME, 0, null, 0, null);
        v.visitVarInsn(ALOAD, 2); v.visitVarInsn(ALOAD, 1);
        v.visitMethodInsn(INVOKESTATIC, TARGET, "isScreenReaderFocusable", "(L" + NODE + ";L" + RES + ";)Z", false);
        v.visitInsn(IRETURN); v.visitMaxs(2, 3); v.visitEnd(); c.methods.add(v);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS); c.accept(w); return w.toByteArray();
    }
    static void verify(Map<String, byte[]> classes) {
        byte[] bytes = classes.get(TARGET + ".class"); require(bytes != null, "missing target");
        ClassNode c = new ClassNode(ASM9); new ClassReader(bytes).accept(c, 0);
        int wired = 0, guards = 0, focus = 0;
        for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions) {
            if (m.name.equals("setTraversalValues") && i instanceof InvokeDynamicInsnNode indy
                    && indy.bsmArgs.length == 3 && indy.bsmArgs[1] instanceof Handle h && h.getName().equals(BRIDGE)) {
                require(h.getOwner().equals(TARGET) && h.getDesc().equals(DESC)
                        && indy.desc.equals("(L" + MAP + ";L" + RES + ";)Lkotlin/jvm/functions/Function1;"), "capture wiring");
                wired++;
            }
            if (m.name.equals(BRIDGE) && m.desc.equals(DESC) && i instanceof MethodInsnNode call) {
                if (call.owner.equals(MAP) && call.name.equals("containsKey")) guards++;
                if (call.owner.equals(TARGET) && call.name.equals("isScreenReaderFocusable")) focus++;
            }
        }
        require(wired == 1 && guards == 1 && focus == 1, "focus consumer shape");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new GradleException("Compose traversal focus patch changed: " + message);
    }
}
