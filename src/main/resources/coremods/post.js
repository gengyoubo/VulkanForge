function initializeCoreMod(){
    var O=Java.type('org.objectweb.asm.Opcodes'),I=Java.type('org.objectweb.asm.tree.InsnList'),V=Java.type('org.objectweb.asm.tree.VarInsnNode'),M=Java.type('org.objectweb.asm.tree.MethodInsnNode'),J=Java.type('org.objectweb.asm.tree.JumpInsnNode'),L=Java.type('org.objectweb.asm.tree.LabelNode'),N=Java.type('org.objectweb.asm.tree.InsnNode');
    return {'vulkanforge_post':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.PostPass'},'transformer':function(node){
        var count=0;for(var i=0;i<node.methods.size();i++){var method=node.methods.get(i);if(method.desc!=='(F)V')continue;
            var code=new I(),fallback=new L();code.add(new V(O.ALOAD,0));code.add(new V(O.FLOAD,1));
            code.add(new M(O.INVOKESTATIC,'github/com/gengyoubo/vulkanforge/client/PostEffects','process','(Lnet/minecraft/client/renderer/PostPass;F)Z',false));code.add(new J(O.IFEQ,fallback));code.add(new N(O.RETURN));code.add(fallback);method.instructions.insert(code);count++;
        }if(count!==1)throw new Error('PostPass ASM mismatch '+count);return node;
    }}};
}
