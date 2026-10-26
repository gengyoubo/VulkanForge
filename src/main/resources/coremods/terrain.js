function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var FieldInsnNode = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var hook = 'github/com/gengyoubo/vulkanforge/client/TerrainMeshes';
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
    }, 'vulkanforge_chunk_upload': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.chunk.ChunkRenderDispatcher' },
        'transformer': function(node) {
            var count = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m), insns = method.instructions.toArray();
                for (var i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (insn instanceof MethodInsnNode && insn.getOpcode() === Opcodes.INVOKEVIRTUAL
                            && insn.owner === 'com/mojang/blaze3d/vertex/VertexBuffer' && insn.desc === '(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V') {
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'upload',
                            '(Lcom/mojang/blaze3d/vertex/VertexBuffer;Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V', false)); count++;
                    }
                }
            }
            if (count !== 1) throw new Error('VulkanForge section upload ASM mismatch: ' + count);
            return node;
        }
    }, 'vulkanforge_chunk_commit': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk$RebuildTask' },
        'transformer': function(node) {
            var owner = 'net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk', outer = null, count = 0;
            for (var f = 0; f < node.fields.size(); f++) if (node.fields.get(f).desc === 'L' + owner + ';') outer = node.fields.get(f);
            if (outer === null) throw new Error('VulkanForge missing RebuildTask RenderChunk field');
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m), insns = method.instructions.toArray();
                for (var i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (insn instanceof MethodInsnNode && insn.owner === 'java/util/concurrent/atomic/AtomicReference' && insn.desc === '(Ljava/lang/Object;)V') {
                        var load = new InsnList(); load.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        load.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, outer.name, outer.desc));
                        method.instructions.insertBefore(insn, load);
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'compiled',
                            '(Ljava/util/concurrent/atomic/AtomicReference;Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;L' + owner + ';)V', false)); count++;
                    }
                }
            }
            if (count !== 1) throw new Error('VulkanForge section commit ASM mismatch: ' + count);
            return node;
        }
    }, 'vulkanforge_chunk_recycle': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.chunk.ChunkRenderDispatcher$RenderChunk' },
        'transformer': function(node) {
            var count = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                if (method.desc !== '()V') continue;
                var insns = method.instructions.toArray();
                var reset = false;
                for (var j = 0; j < insns.length; j++) if (insns[j] instanceof FieldInsnNode && insns[j].getOpcode() === Opcodes.GETSTATIC
                        && insns[j].desc === 'Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher$CompiledChunk;') reset = true;
                if (!reset) continue;
                for (var i = 0; i < insns.length; i++) if (insns[i].getOpcode() === Opcodes.RETURN) {
                    var load = new InsnList(); load.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    load.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook, 'moved', '(L' + node.name + ';)V', false));
                    method.instructions.insertBefore(insns[i], load); count++;
                }
            }
            if (count !== 1) throw new Error('VulkanForge section recycle ASM mismatch: ' + count);
            return node;
        }
    }, 'vulkanforge_view_area': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.ViewArea' },
        'transformer': function(node) {
            var count = 0, releases = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                var insns = method.instructions.toArray();
                var release = false;
                for (var j = 0; j < insns.length; j++) if (insns[j] instanceof MethodInsnNode
                        && insns[j].owner === 'net/minecraft/client/renderer/chunk/ChunkRenderDispatcher$RenderChunk' && insns[j].desc === '()V') release = true;
                if (method.name !== '<init>' && !release) continue;
                for (var i = 0; i < insns.length; i++) if (insns[i].getOpcode() === Opcodes.RETURN) {
                    var load = new InsnList(); load.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    load.add(new MethodInsnNode(Opcodes.INVOKESTATIC, hook, release ? 'released' : 'area', '(L' + node.name + ';)V', false));
                    method.instructions.insertBefore(insns[i], load); if (release) releases++; else count++;
                }
            }
            if (count !== 1 || releases !== 1) throw new Error('VulkanForge ViewArea ASM mismatch: ' + count + '/' + releases);
            return node;
        }
    }};
}
