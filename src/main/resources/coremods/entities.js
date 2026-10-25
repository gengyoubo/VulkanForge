// P7/P8: RenderType.end(BufferBuilder, VertexSorting) uploads with BufferUploader.drawWithShader(RenderedBuffer) right after
// setupRenderState(). Redirect that single call (matched by owner/descriptor, no mapped names) and pass `this` along.
function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    return { 'vulkanforge_entities': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.RenderType' },
        'transformer': function(node) {
            var count = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                if (method.desc !== '(Lcom/mojang/blaze3d/vertex/BufferBuilder;Lcom/mojang/blaze3d/vertex/VertexSorting;)V' || (method.access & Opcodes.ACC_STATIC) !== 0) continue;
                var insns = method.instructions.toArray();
                for (var i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKESTATIC) continue;
                    if (insn.owner !== 'com/mojang/blaze3d/vertex/BufferUploader' || insn.desc !== '(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V') continue;
                    method.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.insertBefore(insn, new InsnNode(Opcodes.SWAP));
                    method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, 'github/com/gengyoubo/vulkanforge/client/EntityDraws', 'draw',
                        '(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V', false));
                    method.maxStack = method.maxStack + 1;
                    count++;
                }
            }
            if (count !== 1) throw new Error('VulkanForge entity ASM expected one RenderType upload, found ' + count);
            ASMAPI.log('INFO', 'VulkanForge ASM installed RenderType entity capture');
            return node;
        }
    }};
}
