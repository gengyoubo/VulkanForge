function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    return { 'vulkanforge_terrain': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.LevelRenderer' },
        'transformer': function(node) {
            var count = 0, draws = 0, bufferCalls = 0, dirty = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                // Match the unique chunk-layer descriptor, independent of mapped method names/local variable slots.
                var chunkLayer = method.desc === '(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/PoseStack;DDDLorg/joml/Matrix4f;)V';
                var insns = method.instructions.toArray();
                for (var i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKEVIRTUAL) continue;
                    if (insn.owner === 'net/minecraft/client/renderer/ViewArea' && insn.desc === '(IIIZ)V') {
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            'github/com/gengyoubo/vulkanforge/client/TerrainPasses', 'markDirty', '(Lnet/minecraft/client/renderer/ViewArea;IIIZ)V', false)); dirty++;
                    }
                    if (!chunkLayer) continue;
                    if (insn.owner === 'net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk'
                            && insn.desc === '(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexBuffer;') {
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            'github/com/gengyoubo/vulkanforge/client/TerrainPasses', 'buffer',
                            '(Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk;Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexBuffer;', false)); count++;
                    } else if (insn.owner === 'com/mojang/blaze3d/vertex/VertexBuffer' && insn.desc === '()V') {
                        // The loop has exactly two such virtual calls: bind, then draw. No mapped names are needed.
                        bufferCalls++;
                        if (bufferCalls === 2) {
                            method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                                'github/com/gengyoubo/vulkanforge/client/TerrainPasses', 'draw', '(Lcom/mojang/blaze3d/vertex/VertexBuffer;)V', false)); draws++;
                        }
                    }
                }
            }
            if (count !== 1 || draws !== 1 || bufferCalls !== 2 || dirty !== 1) throw new Error('VulkanForge terrain ASM mismatch: buffers=' + count + ', draws=' + draws + ', calls=' + bufferCalls + ', dirty=' + dirty);
            return node;
        }
    }};
}
