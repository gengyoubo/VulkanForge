function initializeCoreMod(){
    var O=Java.type('org.objectweb.asm.Opcodes'),I=Java.type('org.objectweb.asm.tree.InsnList'),V=Java.type('org.objectweb.asm.tree.VarInsnNode'),M=Java.type('org.objectweb.asm.tree.MethodInsnNode');
    return {'vulkanforge_shader_pack_world_end':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.GameRenderer'},'transformer':function(node){
        var count=0,methods=0;
        for(var m=0;m<node.methods.size();m++){
            var method=node.methods.get(m);if(method.desc!=='(FJLcom/mojang/blaze3d/vertex/PoseStack;)V')continue;
            methods++;
            var array=method.instructions.toArray();for(var i=0;i<array.length;i++)if(array[i].getOpcode()===O.RETURN){
                var code=new I();code.add(new V(O.FLOAD,1));code.add(new M(O.INVOKESTATIC,'github/com/gengyoubo/vulkanforge/client/ShaderPackHooks','afterLevel','(F)V',false));method.instructions.insertBefore(array[i],code);count++;
            }
        }
        if(methods!==1||count<1)throw new Error('Shader pack world boundary ASM mismatch: methods='+methods+', returns='+count);return node;
    }}};
}
