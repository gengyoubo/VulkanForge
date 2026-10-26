function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes'), M = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var F = Java.type('org.objectweb.asm.tree.FieldInsnNode'), V = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var I = Java.type('org.objectweb.asm.tree.InsnList'), N = Java.type('org.objectweb.asm.tree.InsnNode');
    var Method = Java.type('org.objectweb.asm.tree.MethodNode');
    return { 'vulkanforge_shared_render_targets': {
        'target': { 'type': 'CLASS', 'name': 'com.mojang.blaze3d.pipeline.RenderTarget' },
        'transformer': function(node) {
            var hook = 'github/com/gengyoubo/vulkanforge/client/NativeRenderTargets', fields = [], status = null, destroy = null;
            for (var m = 0; m < node.methods.size(); m++) {
                var method = node.methods.get(m), array = method.instructions.toArray();
                if (method.name === '<init>') {
                    // The only three int fields initialized to -1 are framebuffer, color texture, depth texture, in that order.
                    for (var i = 1; i < array.length; i++) if (array[i] instanceof F && array[i].getOpcode() === O.PUTFIELD
                            && array[i].desc === 'I' && array[i-1].getOpcode() === O.ICONST_M1) fields.push(array[i].name);
                }
                if (method.desc !== '()V') continue;
                for (var i = 0; i < array.length; i++) if (array[i] instanceof M) {
                    if (array[i].owner === 'com/mojang/blaze3d/platform/GlStateManager' && array[i].desc === '(I)I') status = method;
                    if (array[i].owner === 'com/mojang/blaze3d/platform/TextureUtil' && array[i].desc === '(I)V') destroy = method;
                }
            }
            if (fields.length !== 3 || status === null || destroy === null) throw new Error('Shared RenderTarget ASM layout mismatch');
            var accessor = new Method(O.ACC_PUBLIC | O.ACC_SYNTHETIC, 'vulkanforge$setTextures', '(II)V', null, null);
            for (var i = 0; i < 2; i++) {
                accessor.instructions.add(new V(O.ALOAD, 0)); accessor.instructions.add(new V(O.ILOAD, i+1));
                accessor.instructions.add(new F(O.PUTFIELD, node.name, fields[i+1], 'I'));
            }
            accessor.instructions.add(new N(O.RETURN)); accessor.maxStack = 2; accessor.maxLocals = 3;
            node.methods.add(accessor); node.interfaces.add('github/com/gengyoubo/vulkanforge/client/RenderTargetTextures');
            function inject(method, name) {
                var code = new I(); code.add(new V(O.ALOAD, 0));
                code.add(new M(O.INVOKESTATIC, hook, name, '(Lcom/mojang/blaze3d/pipeline/RenderTarget;)V', false));
                method.instructions.insert(code);
            }
            inject(status, 'prepare'); inject(destroy, 'destroy');
            return node;
        }
    }};
}
