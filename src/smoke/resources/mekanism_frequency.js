// Development-only experiment for Mekanism 10.4.16.80. Never registered in normal builds.
function initializeCoreMod() {
    var O=Java.type('org.objectweb.asm.Opcodes'), M=Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var F=Java.type('org.objectweb.asm.tree.FieldInsnNode'), V=Java.type('org.objectweb.asm.tree.VarInsnNode');
    var I=Java.type('org.objectweb.asm.tree.InsnNode'), J=Java.type('org.objectweb.asm.tree.JumpInsnNode');
    var Label=Java.type('org.objectweb.asm.tree.LabelNode'), L=Java.type('org.objectweb.asm.tree.InsnList');
    var API=Java.type('net.minecraftforge.coremod.api.ASMAPI');
    return {'vulkanforge_mekanism_frequency': {
        'target': {'type':'CLASS','name':'mekanism.common.lib.frequency.TileComponentFrequency'},
        'transformer': function(node) {
            var fields={supportedFrequencies:'Ljava/util/Map;',needsNotify:'Z',needsSave:'Z'}, seen=0;
            for(var i=0;i<node.fields.size();i++) { var f=node.fields.get(i); if(fields[f.name]===f.desc)seen++; }
            if(seen!==3)throw new Error('Mekanism frequency fields differ from tested version');
            var matches=0;
            for(var j=0;j<node.methods.size();j++) {
                var m=node.methods.get(j);if(m.name!=='tickServer'||m.desc!=='()V')continue;
                var old=new Label(), ins=new L();
                ins.add(new V(O.ALOAD,0));ins.add(new F(O.GETFIELD,node.name,'supportedFrequencies','Ljava/util/Map;'));
                ins.add(new M(O.INVOKEINTERFACE,'java/util/Map','isEmpty','()Z',true));ins.add(new J(O.IFEQ,old));
                ins.add(new V(O.ALOAD,0));ins.add(new F(O.GETFIELD,node.name,'needsNotify','Z'));ins.add(new J(O.IFNE,old));
                ins.add(new V(O.ALOAD,0));ins.add(new F(O.GETFIELD,node.name,'needsSave','Z'));ins.add(new J(O.IFNE,old));
                ins.add(new I(O.RETURN));ins.add(old);m.instructions.insertBefore(m.instructions.getFirst(),ins);m.maxStack=Math.max(m.maxStack,1);matches++;
            }
            if(matches!==1)throw new Error('Mekanism frequency method differs from tested version');
            API.log('INFO','VulkanForge experimental Mekanism empty frequency guard installed');return node;
        }
    }};
}
