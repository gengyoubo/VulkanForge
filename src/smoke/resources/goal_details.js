// Diagnostic-only per-class Goal method timing.
function initializeCoreMod() {
    var O=Java.type('org.objectweb.asm.Opcodes'), M=Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var V=Java.type('org.objectweb.asm.tree.VarInsnNode'), I=Java.type('org.objectweb.asm.tree.InsnNode');
    var L=Java.type('org.objectweb.asm.tree.InsnList'), API=Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var hook='github/com/gengyoubo/vulkanforge/server/GoalDetailCounter';
    return {'vulkanforge_goal_detail': {
        'target': {'type':'CLASS','name':'net.minecraft.world.entity.ai.goal.WrappedGoal'},
        'transformer': function(node) {
            var specs=[{id:0,srg:'m_8036_',desc:'()Z'}, {id:1,srg:'m_8045_',desc:'()Z'}, {id:2,srg:'m_8037_',desc:'()V'}];
            for(var s=0;s<specs.length;s++) {
                var spec=specs[s], matches=0;
                for(var i=0;i<node.methods.size();i++) {
                    var method=node.methods.get(i);
                    if(method.name!==API.mapMethod(spec.srg)||method.desc!==spec.desc)continue;
                    var slot=method.maxLocals;method.maxLocals+=2;
                    var head=new L();head.add(new M(O.INVOKESTATIC,hook,'begin','()J',false));head.add(new V(O.LSTORE,slot));
                    method.instructions.insertBefore(method.instructions.getFirst(),head);
                    var arr=method.instructions.toArray(), returns=0;
                    for(var j=0;j<arr.length;j++)if(arr[j].getOpcode()===O.RETURN||arr[j].getOpcode()===O.IRETURN){
                        var tail=new L();tail.add(new V(O.ALOAD,0));tail.add(new I(O.ICONST_0+spec.id));tail.add(new V(O.LLOAD,slot));
                        tail.add(new M(O.INVOKESTATIC,hook,'end','(Lnet/minecraft/world/entity/ai/goal/WrappedGoal;IJ)V',false));
                        method.instructions.insertBefore(arr[j],tail);returns++;
                    }
                    if(!returns)throw new Error('Goal detail method has no return '+spec.srg);
                    method.maxStack+=4;matches++;
                }
                if(matches!==1)throw new Error('Goal detail mismatch '+spec.srg+': '+matches);
            }
            API.log('INFO','VulkanForge development goal detail hooks installed');return node;
        }
    }};
}
