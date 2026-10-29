function initializeCoreMod(){
    var O=Java.type('org.objectweb.asm.Opcodes'),I=Java.type('org.objectweb.asm.tree.InsnList'),V=Java.type('org.objectweb.asm.tree.VarInsnNode'),M=Java.type('org.objectweb.asm.tree.MethodInsnNode');
    var HOOKS='github/com/gengyoubo/vulkanforge/client/ShaderPackHooks',SHADER='Lnet/minecraft/client/renderer/ShaderInstance;';
    // Members are matched by descriptor and opcode only, so the same script works with official and SRG names.
    return {'vulkanforge_shader_pack_world_end':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.GameRenderer'},'transformer':function(node){
        var count=0,methods=0,clears=0,getters=0;
        for(var m=0;m<node.methods.size();m++){
            var method=node.methods.get(m),array=method.instructions.toArray();
            if(method.desc==='()'+SHADER&&(method.access&O.ACC_STATIC)!==0){
                // Every core-shader getter: the shader pack runtime may substitute a world program for the returned vanilla shader.
                for(var g=0;g<array.length;g++)if(array[g].getOpcode()===O.ARETURN){method.instructions.insertBefore(array[g],new M(O.INVOKESTATIC,HOOKS,'shader','('+SHADER+')'+SHADER,false));getters++;}
                continue;
            }
            if(method.desc!=='(FJLcom/mojang/blaze3d/vertex/PoseStack;)V')continue;
            methods++;
            for(var i=0;i<array.length;i++){
                if(array[i].getOpcode()===O.RETURN){
                    var code=new I();code.add(new V(O.FLOAD,1));code.add(new M(O.INVOKESTATIC,HOOKS,'afterLevel','(F)V',false));method.instructions.insertBefore(array[i],code);count++;
                }
                // The depth clear before the hand: SIPUSH 256 (GL_DEPTH_BUFFER_BIT), ON_OSX, RenderSystem.clear(IZ)V.
                if(array[i].getOpcode()===O.SIPUSH&&array[i].operand===256)for(var j=i+1;j<Math.min(i+4,array.length);j++){
                    var call=array[j];if(call.getOpcode()===O.INVOKESTATIC&&call.owner==='com/mojang/blaze3d/systems/RenderSystem'&&call.desc==='(IZ)V'){call.owner=HOOKS;call.name='clearHandDepth';clears++;break;}
                }
            }
        }
        if(methods!==1||count<1||clears!==1||getters<50)throw new Error('Shader pack GameRenderer ASM mismatch: methods='+methods+', returns='+count+', handClears='+clears+', getters='+getters);return node;
    }},'vulkanforge_shader_pack_level_begin':{'target':{'type':'CLASS','name':'net.minecraft.client.renderer.LevelRenderer'},'transformer':function(node){
        var count=0;
        for(var m=0;m<node.methods.size();m++){
            var method=node.methods.get(m);
            if(method.desc!=='(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V')continue;
            // Locals: 1 PoseStack, 2 partial tick, 3-4 nanos, 5 outline flag, 6 camera, 7 game renderer, 8 light texture, 9 projection.
            var code=new I();code.add(new V(O.ALOAD,1));code.add(new V(O.FLOAD,2));code.add(new V(O.ALOAD,9));code.add(new M(O.INVOKESTATIC,HOOKS,'levelBegin','(Lcom/mojang/blaze3d/vertex/PoseStack;FLorg/joml/Matrix4f;)V',false));
            method.instructions.insert(code);count++;
        }
        if(count!==1)throw new Error('Shader pack LevelRenderer ASM mismatch: renderLevel='+count);return node;
    }}};
}
