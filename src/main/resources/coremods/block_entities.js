// GL 4.6 block-entity mesh cache: BlockEntityRenderDispatcher.setupAndRender(BlockEntityRenderer, BlockEntity, float, PoseStack,
// MultiBufferSource) computes the light and calls renderer.render(entity, partial, pose, buffers, light, overlay) once.
// Redirect that interface call (owner/descriptor match, no mapped names) to BlockEntityMeshes.render with the receiver first.
// The item-rendering lambda (renderItem) is left alone.
function initializeCoreMod() {
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var SETUP = '(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V';
    var RENDER = '(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V';
    return { 'vulkanforge_block_entities': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher' },
        'transformer': function(node) {
            var count = 0;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m);
                if (method.desc !== SETUP || (method.access & Opcodes.ACC_STATIC) === 0) continue;
                var insns = method.instructions.toArray();
                for (var i = 0; i < insns.length; i++) {
                    var insn = insns[i];
                    if (!(insn instanceof MethodInsnNode) || insn.getOpcode() !== Opcodes.INVOKEINTERFACE) continue;
                    if (insn.owner !== 'net/minecraft/client/renderer/blockentity/BlockEntityRenderer' || insn.desc !== RENDER) continue;
                    method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, 'github/com/gengyoubo/vulkanforge/client/BlockEntityMeshes', 'render',
                        '(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;' + RENDER.substring(1), false));
                    count++;
                }
            }
            if (count !== 1) throw new Error('VulkanForge block-entity ASM expected one renderer call in setupAndRender, found ' + count);
            ASMAPI.log('INFO', 'VulkanForge ASM installed block-entity mesh cache hook');
            return node;
        }
    }};
}
