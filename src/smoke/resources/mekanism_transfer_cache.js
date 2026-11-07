// Development-only experiment. Never included or registered without its explicit Gradle property.
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var M = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var F = Java.type('org.objectweb.asm.tree.FieldInsnNode');
    var V = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var L = Java.type('org.objectweb.asm.tree.InsnList');
    var API = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var helper = 'github/com/gengyoubo/vulkanforge/server/MekanismTransferCachePrototype';
    var cache = 'mekanism/common/lib/transmitter/acceptor/NetworkAcceptorCache';
    function network(name) {
        return {'target': {'type':'CLASS', 'name':name}, 'transformer': function(node) {
            var found = 0;
            for (var i = 0; i < node.methods.size(); i++) {
                var method = node.methods.get(i);
                if (method.name !== 'tickEmit') continue;
                for (var ins = method.instructions.getFirst(); ins !== null; ins = ins.getNext()) {
                    if (ins.getOpcode() === O.INVOKEVIRTUAL && ins.owner === cache && ins.name === 'getAcceptorValues' && ins.desc === '()Ljava/util/Collection;') {
                        method.instructions.insert(ins, new M(O.INVOKESTATIC, helper, 'select', '(Ljava/util/Collection;)Ljava/util/Collection;', false));
                        found++;
                    }
                }
            }
            if (found !== 1) throw new Error('Unexpected transfer emit method in ' + name);
            API.log('INFO', 'VF transfer cache emit hook installed: ' + name); return node;
        }};
    }
    return {
        'vf_transfer_cache_mutations': {'target': {'type':'CLASS','name':'mekanism.common.lib.transmitter.acceptor.NetworkAcceptorCache'},
            'transformer': function(node) {
                var found = 0, fieldFound = false;
                for (var f = 0; f < node.fields.size(); f++) if (node.fields.get(f).name === 'cachedAcceptors' && node.fields.get(f).desc === 'Ljava/util/Map;') fieldFound = true;
                if (!fieldFound) throw new Error('Mekanism acceptor map differs from tested version');
                for (var i = 0; i < node.methods.size(); i++) {
                    var method = node.methods.get(i);
                    if (method.name !== 'updateTransmitterOnSide' && method.name !== 'adoptAcceptors' && method.name !== 'deregister') continue;
                    var ins = new L();
                    ins.add(new V(O.ALOAD, 0));
                    ins.add(new F(O.GETFIELD, node.name, 'cachedAcceptors', 'Ljava/util/Map;'));
                    ins.add(new M(O.INVOKEINTERFACE, 'java/util/Map', 'values', '()Ljava/util/Collection;', true));
                    ins.add(new M(O.INVOKESTATIC, helper, 'markDirty', '(Ljava/util/Collection;)V', false));
                    method.instructions.insertBefore(method.instructions.getFirst(), ins);
                    method.maxStack = Math.max(method.maxStack, 1); found++;
                }
                if (found !== 3) throw new Error('Mekanism acceptor mutation methods differ from tested version');
                API.log('INFO', 'VF transfer cache mutation hooks installed'); return node;
            }},
        'vf_energy_transfer_cache': network('mekanism.common.content.network.EnergyNetwork'),
        'vf_fluid_transfer_cache': network('mekanism.common.content.network.FluidNetwork'),
        'vf_chemical_transfer_cache': network('mekanism.common.content.network.BoxedChemicalNetwork')
    };
}
