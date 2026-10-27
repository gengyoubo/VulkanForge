function initializeCoreMod(){
    var O=Java.type('org.objectweb.asm.Opcodes'),I=Java.type('org.objectweb.asm.tree.InsnList'),M=Java.type('org.objectweb.asm.tree.MethodInsnNode'),V=Java.type('org.objectweb.asm.tree.VarInsnNode'),L=Java.type('org.objectweb.asm.tree.LabelNode'),J=Java.type('org.objectweb.asm.tree.JumpInsnNode'),N=Java.type('org.objectweb.asm.tree.InsnNode'),T=Java.type('org.objectweb.asm.tree.TryCatchBlockNode');
    var hook='github/com/gengyoubo/vulkanforge/client/TailFrameProfiler';
    function wrap(method,begin,end){
        var original=method.instructions.toArray(),start=new L(),finish=new L(),normal=new L(),handler=new L();
        var head=new I();head.add(new M(O.INVOKESTATIC,hook,begin,'()V',false));head.add(start);method.instructions.insert(head);
        for(var k=0;k<original.length;k++)if(original[k].getOpcode()===O.RETURN)method.instructions.set(original[k],new J(O.GOTO,normal));
        method.instructions.add(finish);method.instructions.add(normal);method.instructions.add(new M(O.INVOKESTATIC,hook,end,'()V',false));method.instructions.add(new N(O.RETURN));
        var local=method.maxLocals;method.maxLocals++;
        method.instructions.add(handler);method.instructions.add(new V(O.ASTORE,local));method.instructions.add(new M(O.INVOKESTATIC,hook,end,'()V',false));method.instructions.add(new V(O.ALOAD,local));method.instructions.add(new N(O.ATHROW));
        method.tryCatchBlocks.add(new T(start,finish,handler,null));
    }
    return {'vulkanforge_tail_game':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.GameRenderer'},'transformer':function(node){
        var count=0,posts=0;
        for(var i=0;i<node.methods.size();i++){var method=node.methods.get(i);if(method.desc!=='(FJZ)V')continue;
            var original=method.instructions.toArray();
            for(var k=0;k<original.length;k++){var insn=original[k];if(insn instanceof M&&insn.owner==='net/minecraft/client/renderer/PostChain'&&insn.desc==='(F)V'){
                method.instructions.set(insn,new M(O.INVOKESTATIC,'github/com/gengyoubo/vulkanforge/client/TailDiagnostics','processOptional','(Lnet/minecraft/client/renderer/PostChain;F)V',false));posts++;
            }}
            wrap(method,'beginGameRender','endGameRender');count++;
        }
        if(count!==1||posts!==1)throw new Error('GL tail GameRenderer hook mismatch '+count+'/'+posts);return node;
    }},'vulkanforge_tail_post':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.PostChain'},'transformer':function(node){
        var count=0;for(var i=0;i<node.methods.size();i++){var method=node.methods.get(i);if(method.desc==='(F)V'){wrap(method,'beginPost','endPost');count++;}}
        if(count!==1)throw new Error('GL tail PostChain hook mismatch '+count);return node;
    }}};
}
