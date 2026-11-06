// Experimental opt-in only. Production defaults retain vanilla bytecode.
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var M = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var F = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var V = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var I = Java.type('org.objectweb.asm.tree.InsnNode');
    var L = Java.type('org.objectweb.asm.tree.InsnList');
    var API = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var hook = 'github/com/gengyoubo/vulkanforge/server/SupportBlockProbe';
    var shape = 'net/minecraft/world/phys/shapes/VoxelShape';
    return {
        'vulkanforge_support_query': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.CollisionGetter' },
            'transformer': function(node) {
                var count = 0;
                for (var n = 0; n < node.methods.size(); n++) {
                    var m = node.methods.get(n);
                    if (m.desc !== '(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;') continue;
                    m.instructions.clear(); m.tryCatchBlocks.clear();
                    m.instructions.add(new V(O.ALOAD, 0)); m.instructions.add(new V(O.ALOAD, 1)); m.instructions.add(new V(O.ALOAD, 2));
                    m.instructions.add(new M(O.INVOKESTATIC, hook, 'find', '(Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;', false));
                    m.instructions.add(new I(O.ARETURN)); m.maxStack = 3; m.maxLocals = 3; count++;
                }
                if (count !== 1) throw new Error('Support query shape mismatch: ' + count);
                API.log('INFO', 'VulkanForge experimental support query hook installed');
                return node;
            }
        },
        'vulkanforge_support_collision': {
            'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.BlockCollisions' },
            'transformer': function(node) {
                var provider = null;
                for (var f = 0; f < node.fields.size(); f++) {
                    var field = node.fields.get(f);
                    if (field.desc === 'Ljava/util/function/BiFunction;') {
                        if (provider !== null) throw new Error('Multiple collision result providers');
                        provider = field.name;
                    }
                }
                var count = 0;
                for (var n = 0; n < node.methods.size(); n++) {
                    var m = node.methods.get(n);
                    if (m.desc !== '()Ljava/lang/Object;') continue;
                    var array = m.instructions.toArray(), moves = [];
                    for (var i = 0; i < array.length; i++) {
                        var insn = array[i];
                        if (insn instanceof M && insn.owner === shape && insn.desc === '(DDD)L' + shape + ';') moves.push(insn);
                    }
                    if (moves.length !== 2 || provider === null) throw new Error('Collision shape move mismatch: ' + moves.length);
                    var extra = new L(); extra.add(new V(O.ALOAD, 0)); extra.add(new F(O.GETFIELD, node.name, provider, 'Ljava/util/function/BiFunction;'));
                    m.instructions.insertBefore(moves[0], extra);
                    m.instructions.set(moves[0], new M(O.INVOKESTATIC, hook, 'moveFullBlock', '(L' + shape + ';DDDLjava/util/function/BiFunction;)L' + shape + ';', false));
                    m.maxStack++; count++;
                }
                if (count !== 1) throw new Error('Collision method shape mismatch: ' + count);
                API.log('INFO', 'VulkanForge experimental collision hook installed');
                return node;
            }
        }
    };
}
