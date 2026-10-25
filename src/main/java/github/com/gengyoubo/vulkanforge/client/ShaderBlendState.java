package github.com.gengyoubo.vulkanforge.client;

import com.mojang.blaze3d.shaders.BlendMode;
import net.minecraft.client.renderer.ShaderInstance;
import java.lang.reflect.*;
import java.util.Arrays;

/** ShaderInstance.apply has a second blend cache above GlStateManager. Preserve both caches around interop draws. */
final class ShaderBlendState {
    private static final Field LAST = field(BlendMode.class, true), SHADER = field(ShaderInstance.class, false);
    private static Field field(Class<?> owner, boolean isStatic) {
        Field result = Arrays.stream(owner.getDeclaredFields()).filter(f -> f.getType() == BlendMode.class
                && Modifier.isStatic(f.getModifiers()) == isStatic).reduce((a, b) -> { throw new IllegalStateException("Ambiguous blend field"); }).orElseThrow();
        result.setAccessible(true); return result;
    }
    static Object save() { try { return LAST.get(null); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    static void restore(Object state) { try { LAST.set(null, state); } catch (IllegalAccessException e) { throw new IllegalStateException(e); } }
    static void apply(ShaderInstance shader) {
        try { ((BlendMode) SHADER.get(shader)).apply(); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
