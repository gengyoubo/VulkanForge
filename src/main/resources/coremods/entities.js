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
    },
    // P9: Tesselator.end() is BufferUploader.drawWithShader(builder.end()); particles and rain/snow flush through it.
    'vulkanforge_tesselator': {
        'target': { 'type': 'CLASS', 'name': 'com.mojang.blaze3d.vertex.Tesselator' },
        'transformer': function(node) {
            var count = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                if (method.desc !== '()V' || (method.access & Opcodes.ACC_STATIC) !== 0) continue;
                var insns = method.instructions.toArray();
                for (var i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKESTATIC) continue;
                    if (insn.owner !== 'com/mojang/blaze3d/vertex/BufferUploader' || insn.desc !== '(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V') continue;
                    method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, 'github/com/gengyoubo/vulkanforge/client/EntityDraws', 'drawImmediate',
                        '(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V', false));
                    count++;
                }
            }
            if (count !== 1) throw new Error('VulkanForge Tesselator ASM expected one upload in end(), found ' + count);
            ASMAPI.log('INFO', 'VulkanForge ASM installed Tesselator particle/weather capture');
            return node;
        }
    },
    // P10: renderSky's sunrise/sun/moon call BufferUploader directly; the sky disc, stars, dark disc and clouds are VertexBuffers
    // uploaded and drawn only from LevelRenderer. Chunk sections upload elsewhere and draw with VertexBuffer.draw(), untouched here.
    'vulkanforge_sky': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.LevelRenderer' },
        'transformer': function(node) {
            var hook = 'github/com/gengyoubo/vulkanforge/client/';
            var vertexBuffer = 'com/mojang/blaze3d/vertex/VertexBuffer', rendered = 'Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;';
            var drawDesc = '(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V';
            var immediate = 0, uploads = 0, draws = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var insns = node.methods.get(m).instructions;
                var array = insns.toArray();
                for (var i = 0; i < array.length; i++) {
                    var insn = array[i];
                    if (!(insn instanceof MethodInsnNode)) continue;
                    if (insn.getOpcode() === Opcodes.INVOKESTATIC && insn.owner === 'com/mojang/blaze3d/vertex/BufferUploader' && insn.desc === '(' + rendered + ')V') {
                        insns.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook + 'EntityDraws', 'drawImmediate', '(' + rendered + ')V', false)); immediate++;
                    } else if (insn.getOpcode() === Opcodes.INVOKEVIRTUAL && insn.owner === vertexBuffer && insn.desc === '(' + rendered + ')V') {
                        insns.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook + 'SkyBuffers', 'upload', '(L' + vertexBuffer + ';' + rendered + ')V', false)); uploads++;
                    } else if (insn.getOpcode() === Opcodes.INVOKEVIRTUAL && insn.owner === vertexBuffer && insn.desc === drawDesc) {
                        insns.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, hook + 'SkyBuffers', 'draw', '(L' + vertexBuffer + ';' + drawDesc.substring(1), false)); draws++;
                    }
                }
            }
            if (immediate < 3 || uploads !== 4 || draws !== 4) throw new Error('VulkanForge sky ASM mismatch: immediate=' + immediate + ', uploads=' + uploads + ', draws=' + draws);
            ASMAPI.log('INFO', 'VulkanForge ASM installed sky/cloud capture: immediate=' + immediate + ', uploads=' + uploads + ', draws=' + draws);
            return node;
        }
    }};
}
