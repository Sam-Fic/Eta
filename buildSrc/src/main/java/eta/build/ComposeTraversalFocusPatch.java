package eta.build;

import java.util.Map;
import org.gradle.api.GradleException;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Compose 1.13.0-alpha01 起，上游已自带「按查询表裁剪语义树」的焦点判定：
 * setTraversalValues$lambda$0 会调用 IntObjectMap.containsKey 过滤掉已不在当前
 * 语义树中的节点，isScreenReaderFocusable 也随之新增了第三参数。
 *
 * 因此 Eta 原有的 focus 补丁不再需要，这里改为「仅校验上游守卫存在」，
 * 若未来上游回退该优化则构建失败，避免静默回退到低效路径。
 */
final class ComposeTraversalFocusPatch implements Opcodes {
    static final String TARGET = "androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat_androidKt";
    private static final String MAP = "androidx/collection/IntObjectMap";

    /** 上游已实现该优化，无需改写字节码。 */
    static byte[] patch(byte[] bytes) {
        return bytes;
    }

    static void verify(Map<String, byte[]> classes) {
        byte[] bytes = classes.get(TARGET + ".class");
        require(bytes != null, "missing target");
        ClassNode c = new ClassNode(ASM9); new ClassReader(bytes).accept(c, 0);
        boolean guard = false;
        for (MethodNode m : c.methods) {
            if (!m.name.startsWith("setTraversalValues$lambda")) continue;
            for (AbstractInsnNode i : m.instructions)
                if (i instanceof MethodInsnNode call && call.owner.equals(MAP)
                        && call.name.equals("containsKey")) guard = true;
        }
        require(guard, "upstream traversal guard missing");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new GradleException("Compose traversal focus patch changed: " + message);
    }
}
