// Development-only PathFinder search duration and heap-pop counts; results include instrumentation overhead.
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var M = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var V = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var L = Java.type('org.objectweb.asm.tree.InsnList');
    var API = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var hook = 'github/com/gengyoubo/vulkanforge/server/AiNodeCounter';
    return { 'vulkanforge_ai_nodes': {
        'target': { 'type': 'CLASS', 'name': 'net.minecraft.world.level.pathfinder.PathFinder' },
        'transformer': function(node) {
            var starts = 0, pops = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var method = node.methods.get(i);
                if (method.desc === '(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;') {
                    var slot = method.maxLocals; method.maxLocals += 2;
                    var head = new L(); head.add(new M(O.INVOKESTATIC, hook, 'started', '()J', false)); head.add(new V(O.LSTORE, slot));
                    method.instructions.insertBefore(method.instructions.getFirst(), head);
                    var returns = method.instructions.toArray();
                    for (var k = 0; k < returns.length; k++) if (returns[k].getOpcode() === O.ARETURN) {
                        var tail = new L(); tail.add(new V(O.LLOAD, slot)); tail.add(new M(O.INVOKESTATIC, hook, 'finished', '(J)V', false));
                        method.instructions.insertBefore(returns[k], tail);
                    }
                    method.maxStack += 2; starts++;
                }
                if (method.desc !== '(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/world/level/pathfinder/Node;Ljava/util/Map;FIF)Lnet/minecraft/world/level/pathfinder/Path;') continue;
                var array = method.instructions.toArray();
                for (var j = 0; j < array.length; j++) {
                    var insn = array[j];
                    if (insn instanceof M && insn.owner === 'net/minecraft/world/level/pathfinder/BinaryHeap' && insn.desc === '()Lnet/minecraft/world/level/pathfinder/Node;') {
                        method.instructions.insertBefore(insn, new M(O.INVOKESTATIC, hook, 'popped', '()V', false)); pops++;
                    }
                }
            }
            if (starts !== 1 || pops !== 1) throw new Error('AI node counter bytecode mismatch: starts=' + starts + ' pops=' + pops);
            API.log('INFO', 'VulkanForge development AI search counters installed');
            return node;
        }
    }};
}
