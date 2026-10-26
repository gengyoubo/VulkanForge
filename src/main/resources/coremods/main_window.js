// Forge 1.20.1: redirect only stable Forge/GLFW static calls; no mapped method names.
// Keep the original descriptors and operand stack unchanged. Runtime work stays in Java.
function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var hook = 'github/com/gengyoubo/vulkanforge/client/NativeMainWindow';
    var forge = 'net/minecraftforge/fml/loading/ImmediateWindowHandler';
    var glfw = 'org/lwjgl/glfw/GLFW';

    function redirect(node, rules) {
        var counts = [];
        for (var r = 0; r < rules.length; r++) counts[r] = 0;
        for (var m = 0; m < node.methods.size(); m++) {
            var method = node.methods.get(m);
            var insns = method.instructions.toArray();
            for (var i = 0; i < insns.length; i++) {
                var insn = insns[i];
                if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKESTATIC) continue;
                for (var r = 0; r < rules.length; r++) {
                    var rule = rules[r];
                    if (insn.owner === rule.owner && insn.name === rule.name && insn.desc === rule.desc && (!rule.ctor || method.name === '<init>')) {
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook, rule.to, rule.desc, false));
                        counts[r]++;
                        break;
                    }
                }
            }
        }
        for (var r = 0; r < rules.length; r++) {
            if (counts[r] !== 1) throw new Error('VulkanForge ASM expected one ' + rules[r].name + ' in ' + node.name + ', found ' + counts[r]);
        }
        ASMAPI.log('INFO', 'VulkanForge ASM installed main-window hooks in ' + node.name);
        return node;
    }

    return {
        'vulkanforge_window': {
            'target': { 'type': 'CLASS', 'name': 'com.mojang.blaze3d.platform.Window' },
            'transformer': function(node) {
                return redirect(node, [
                    {owner: forge, name: 'setupMinecraftWindow', desc: '(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J', to: 'createWindow', ctor: true},
                    {owner: forge, name: 'positionWindow', desc: '(Ljava/util/Optional;Ljava/util/function/IntConsumer;Ljava/util/function/IntConsumer;Ljava/util/function/IntConsumer;Ljava/util/function/IntConsumer;)Z', to: 'positionWindow', ctor: true},
                    {owner: glfw, name: 'glfwMakeContextCurrent', desc: '(J)V', to: 'makeContextCurrent', ctor: true},
                    {owner: glfw, name: 'glfwDestroyWindow', desc: '(J)V', to: 'destroyWindow'},
                    {owner: 'com/mojang/blaze3d/platform/GLX', name: '_shouldClose', desc: '(Lcom/mojang/blaze3d/platform/Window;)Z', to: 'shouldClose'}
                ]);
            }
        },
        'vulkanforge_present': {
            'target': { 'type': 'CLASS', 'name': 'com.mojang.blaze3d.systems.RenderSystem' },
            'transformer': function(node) {
                return redirect(node, [{owner: glfw, name: 'glfwSwapBuffers', desc: '(J)V', to: 'swapBuffers'}]);
            }
        },
        'vulkanforge_screen_blit': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.Minecraft' },
            'transformer': function(node) {
                var count = 0;
                for (var m = 0; m < node.methods.size(); m++) {
                    var method = node.methods.get(m), insns = method.instructions.toArray();
                    for (var i = 0; i < insns.length; i++) {
                        var insn = insns[i];
                        if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKEVIRTUAL || insn.desc !== '(II)V') continue;
                        if (insn.owner !== 'com/mojang/blaze3d/pipeline/RenderTarget' && insn.owner !== 'com/mojang/blaze3d/pipeline/MainTarget') continue;
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'blitToScreen',
                                '(Lcom/mojang/blaze3d/pipeline/RenderTarget;II)V', false)); count++;
                    }
                }
                if (count !== 1) throw new Error('VulkanForge screen blit ASM expected one call, found ' + count);
                return node;
            }
        }
    };
}
