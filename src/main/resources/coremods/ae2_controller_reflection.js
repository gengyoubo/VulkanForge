// Narrow reflection optimization verified with AE2 Overclocked 1.2.3-fix3.
function initializeCoreMod() {
    var O = Java.type('org.objectweb.asm.Opcodes');
    var V = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var M = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var J = Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var I = Java.type('org.objectweb.asm.tree.InsnNode');
    var Label = Java.type('org.objectweb.asm.tree.LabelNode');
    var L = Java.type('org.objectweb.asm.tree.InsnList');
    var API = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var helper = 'github/com/gengyoubo/vulkanforge/ae2experiment/Ae2ControllerReflectionFastPath';
    return {'vf_ae2_controller_reflection': {
        'target': {'type':'CLASS','name':'moakiee.support.EnergyCardRuntime'},
        'transformer': function(node) {
            var expected = 0;
            for (var k = 0; k < node.methods.size(); k++) {
                var check = node.methods.get(k);
                if ((check.name === 'tryGetInstalledFromGetUpgrades' && check.desc === '(Ljava/lang/Object;)Ljava/lang/Integer;') ||
                    (check.name === 'tryInvokeNoArg' && check.desc === '(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;') ||
                    (check.name === 'tryGetField' && check.desc === '(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;')) expected++;
            }
            if (expected !== 3) {
                API.log('WARN', 'VF AE2 controller reflection skipped: incompatible EnergyCardRuntime methods');
                return node;
            }
            var count = 0;
            for (var j = 0; j < node.methods.size(); j++) {
                var m = node.methods.get(j), method = null, two = false;
                if (m.name === 'tryGetInstalledFromGetUpgrades' && m.desc === '(Ljava/lang/Object;)Ljava/lang/Integer;') method = 'skipUpgrades';
                if (m.name === 'tryInvokeNoArg' && m.desc === '(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;') { method = 'skipHostMethod'; two = true; }
                if (m.name === 'tryGetField' && m.desc === '(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;') { method = 'skipHostField'; two = true; }
                if (method === null) continue;
                var next = new Label(), list = new L();
                list.add(new V(O.ALOAD, 0)); if (two) list.add(new V(O.ALOAD, 1));
                list.add(new M(O.INVOKESTATIC, helper, method, two ? '(Ljava/lang/Object;Ljava/lang/String;)Z' : '(Ljava/lang/Object;)Z', false));
                list.add(new J(O.IFEQ, next)); list.add(new I(O.ACONST_NULL)); list.add(new I(O.ARETURN)); list.add(next);
                m.instructions.insertBefore(m.instructions.getFirst(), list); m.maxStack = Math.max(m.maxStack, two ? 2 : 1); count++;
            }
            API.log('INFO', 'VF AE2 controller reflection experiment installed'); return node;
        }
    }};
}
