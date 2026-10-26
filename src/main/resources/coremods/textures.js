function initializeCoreMod(){
    var Opcodes=Java.type('org.objectweb.asm.Opcodes'),MethodInsnNode=Java.type('org.objectweb.asm.tree.MethodInsnNode');
    return {'vulkanforge_cpu_texture_source':{'target':{'type':'CLASS','name':'com.mojang.blaze3d.platform.GlStateManager'},'transformer':function(node){
        var counts=[0,0,0,0,0,0],hook='github/com/gengyoubo/vulkanforge/client/CpuTextures';
        for(var m=0;m<node.methods.size();m++){
            var method=node.methods.get(m),array=method.instructions.toArray();
            for(var i=0;i<array.length;i++){
                var insn=array[i];if(!(insn instanceof MethodInsnNode)||insn.getOpcode()!==Opcodes.INVOKESTATIC)continue;
                var name=null,index=-1;
                if(insn.owner==='org/lwjgl/opengl/GL11'){
                    if(insn.name==='glTexImage2D'&&insn.desc==='(IIIIIIIILjava/nio/IntBuffer;)V'){name='image';index=0;}
                    if(insn.name==='glTexSubImage2D'&&insn.desc==='(IIIIIIIIJ)V'){name='subImage';index=1;}
                    if(insn.name==='glTexSubImage2D'&&insn.desc==='(IIIIIIIILjava/nio/IntBuffer;)V'){name='subImageBuffer';index=5;}
                    if(insn.name==='glDeleteTextures'&&insn.desc==='(I)V'){name='delete';index=2;}
                    if(insn.name==='glDeleteTextures'&&insn.desc==='([I)V'){name='deleteMany';index=3;}
                }
                if(insn.owner==='org/lwjgl/opengl/GL30'&&insn.name==='glFramebufferTexture2D'&&insn.desc==='(IIIII)V'){name='framebuffer';index=4;}
                if(name!==null){method.instructions.set(insn,new MethodInsnNode(Opcodes.INVOKESTATIC,hook,name,insn.desc,false));counts[index]++;}
            }
        }
        for(var c=0;c<counts.length;c++)if(counts[c]!==1)throw new Error('CPU texture ASM mismatch '+c+': '+counts[c]);
        return node;
    }}};
}
