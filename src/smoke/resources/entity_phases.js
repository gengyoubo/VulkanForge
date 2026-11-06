// Development-only inclusive server-thread timing of selected entity methods.
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var M = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var V = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var I = Java.type('org.objectweb.asm.tree.InsnNode');
    var L = Java.type('org.objectweb.asm.tree.InsnList');
    var API = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var hook = 'github/com/gengyoubo/vulkanforge/server/EntityPhaseCounter';
    function transform(node, specs) {
        for (var s = 0; s < specs.length; s++) {
            var spec = specs[s], matches = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var m = node.methods.get(i);
                if (m.name !== API.mapMethod(spec.srg) || m.desc !== spec.desc) continue;
                var slot = m.maxLocals; m.maxLocals += 2;
                var head = new L(); head.add(new I(O.ICONST_0 + spec.id));
                head.add(new M(O.INVOKESTATIC, hook, 'begin', '(I)J', false)); head.add(new V(O.LSTORE, slot));
                m.instructions.insertBefore(m.instructions.getFirst(), head);
                var nodes = m.instructions.toArray(), returns = 0;
                for (var j = 0; j < nodes.length; j++) {
                    var opcode = nodes[j].getOpcode();
                    if (opcode !== O.RETURN && opcode !== O.ARETURN) continue;
                    var tail = new L(); tail.add(new I(O.ICONST_0 + spec.id)); tail.add(new V(O.LLOAD, slot));
                    tail.add(new M(O.INVOKESTATIC, hook, 'end', '(IJ)V', false));
                    m.instructions.insertBefore(nodes[j], tail); returns++;
                }
                if (!returns) throw new Error('Entity phase method has no normal return: ' + spec.srg);
                m.maxStack += 3; matches++;
            }
            if (matches !== 1) throw new Error('Entity phase method mismatch ' + spec.srg + ': ' + matches);
        }
        API.log('INFO', 'VulkanForge development entity phase hooks installed for ' + node.name);
        return node;
    }
    return {
        'vulkanforge_entity_tick_timing': { 'target': {'type':'CLASS','name':'net.minecraft.server.level.ServerLevel'},
            'transformer': function(n) { return transform(n, [{id:0,srg:'m_8647_',desc:'(Lnet/minecraft/world/entity/Entity;)V'}]); } },
        'vulkanforge_goal_timing': { 'target': {'type':'CLASS','name':'net.minecraft.world.entity.ai.goal.GoalSelector'},
            'transformer': function(n) { return transform(n, [{id:1,srg:'m_25373_',desc:'()V'},{id:2,srg:'m_186081_',desc:'(Z)V'}]); } },
        'vulkanforge_push_timing': { 'target': {'type':'CLASS','name':'net.minecraft.world.entity.LivingEntity'},
            'transformer': function(n) { return transform(n, [{id:3,srg:'m_6138_',desc:'()V'}]); } },
        'vulkanforge_collision_timing': { 'target': {'type':'CLASS','name':'net.minecraft.world.entity.Entity'},
            'transformer': function(n) { return transform(n, [{id:4,srg:'m_20272_',desc:'(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;'}]); } }
    };
}
