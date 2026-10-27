function initializeCoreMod(){
    var O=Java.type('org.objectweb.asm.Opcodes'),I=Java.type('org.objectweb.asm.tree.InsnList'),V=Java.type('org.objectweb.asm.tree.VarInsnNode'),M=Java.type('org.objectweb.asm.tree.MethodInsnNode'),J=Java.type('org.objectweb.asm.tree.JumpInsnNode'),L=Java.type('org.objectweb.asm.tree.LabelNode'),N=Java.type('org.objectweb.asm.tree.InsnNode');
    return {'vulkanforge_post':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.PostPass'},'transformer':function(node){
        var count=0;for(var i=0;i<node.methods.size();i++){var method=node.methods.get(i);if(method.desc!=='(F)V')continue;
            var code=new I(),fallback=new L();code.add(new V(O.ALOAD,0));code.add(new V(O.FLOAD,1));
            code.add(new M(O.INVOKESTATIC,'github/com/gengyoubo/vulkanforge/client/PostEffects','process','(Lnet/minecraft/client/renderer/PostPass;F)Z',false));code.add(new J(O.IFEQ,fallback));code.add(new N(O.RETURN));code.add(fallback);method.instructions.insert(code);count++;
        }if(count!==1)throw new Error('PostPass ASM mismatch '+count);return node;
    }},'vulkanforge_post_chain':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.PostChain'},'transformer':function(node){
        var T=Java.type('org.objectweb.asm.tree.TryCatchBlockNode'),count=0,hook='github/com/gengyoubo/vulkanforge/client/PostEffects';
        for(var i=0;i<node.methods.size();i++){
            var method=node.methods.get(i);if(method.desc!=='(F)V')continue;
            var original=method.instructions.toArray(),start=new L(),end=new L(),handler=new L(),normal=new L();
            var head=new I();head.add(new M(O.INVOKESTATIC,hook,'beginChain','()V',false));head.add(start);method.instructions.insert(head);
            for(var k=0;k<original.length;k++)if(original[k].getOpcode()===O.RETURN)method.instructions.set(original[k],new J(O.GOTO,normal));
            method.instructions.add(end);method.instructions.add(normal);method.instructions.add(new M(O.INVOKESTATIC,hook,'endChain','()V',false));method.instructions.add(new N(O.RETURN));
            var local=method.maxLocals;method.maxLocals++;
            method.instructions.add(handler);method.instructions.add(new V(O.ASTORE,local));method.instructions.add(new V(O.ALOAD,local));method.instructions.add(new M(O.INVOKESTATIC,hook,'endChainOnFailure','(Ljava/lang/Throwable;)V',false));method.instructions.add(new V(O.ALOAD,local));method.instructions.add(new N(O.ATHROW));
            method.tryCatchBlocks.add(new T(start,end,handler,null));count++;
        }
        if(count!==1)throw new Error('PostChain ASM mismatch '+count);return node;
    }}};
}
