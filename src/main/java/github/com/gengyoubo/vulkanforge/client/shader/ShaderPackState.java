package github.com.gengyoubo.vulkanforge.client.shader;

import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Raw API state is restored exactly; pack rendering does not mutate Minecraft's Java GL caches. */
final class ShaderPackState implements AutoCloseable {
    private final int program=glGetInteger(GL_CURRENT_PROGRAM),vao=glGetInteger(GL_VERTEX_ARRAY_BINDING),array=glGetInteger(GL_ARRAY_BUFFER_BINDING),read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
    private final int depthFunc=glGetInteger(GL_DEPTH_FUNC),frontFace=glGetInteger(GL_FRONT_FACE),src=glGetInteger(GL_BLEND_SRC_RGB),dst=glGetInteger(GL_BLEND_DST_RGB),srcAlpha=glGetInteger(GL_BLEND_SRC_ALPHA),dstAlpha=glGetInteger(GL_BLEND_DST_ALPHA),equation=glGetInteger(GL_BLEND_EQUATION_RGB),equationAlpha=glGetInteger(GL_BLEND_EQUATION_ALPHA);
    private final boolean depthMask=glGetBoolean(GL_DEPTH_WRITEMASK);
    private final int[] viewport=new int[4],textures;
    private final float[] clear=new float[4];
    private final boolean[] mask=new boolean[4],enabled;
    private static final int[] FLAGS={GL_BLEND,GL_DEPTH_TEST,GL_CULL_FACE,GL_SCISSOR_TEST,GL_POLYGON_OFFSET_FILL,GL_STENCIL_TEST,GL_COLOR_LOGIC_OP};
    private final int originalTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
    ShaderPackState(int textureUnits){
        textures=new int[textureUnits];enabled=new boolean[FLAGS.length];for(int i=0;i<FLAGS.length;i++)enabled[i]=glIsEnabled(FLAGS[i]);
        try(MemoryStack stack=MemoryStack.stackPush()){
            var box=stack.mallocInt(4);glGetIntegerv(GL_VIEWPORT,box);box.get(viewport);
            var color=stack.mallocFloat(4);glGetFloatv(GL_COLOR_CLEAR_VALUE,color);color.get(clear);
            var bits=stack.malloc(4);glGetBooleanv(GL_COLOR_WRITEMASK,bits);for(int i=0;i<4;i++)mask[i]=bits.get(i)!=0;
        }
        for(int i=0;i<textureUnits;i++){glActiveTexture(GL_TEXTURE0+i);textures[i]=glGetInteger(GL_TEXTURE_BINDING_2D);}glActiveTexture(active);
    }
    static void fullscreen(){for(int flag:FLAGS)glDisable(flag);glDepthMask(false);glDepthFunc(GL_LEQUAL);glFrontFace(GL_CCW);glColorMask(true,true,true,true);glBlendFuncSeparate(GL_ONE,GL_ZERO,GL_ONE,GL_ZERO);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);}
    @Override public void close(){
        glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,array);
        glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        for(int i=0;i<textures.length;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,textures[i]);}glActiveTexture(active);glBindTexture(GL_TEXTURE_2D,originalTexture);
        for(int i=0;i<FLAGS.length;i++)if(enabled[i])glEnable(FLAGS[i]);else glDisable(FLAGS[i]);glDepthMask(depthMask);glDepthFunc(depthFunc);glFrontFace(frontFace);glColorMask(mask[0],mask[1],mask[2],mask[3]);glBlendFuncSeparate(src,dst,srcAlpha,dstAlpha);glBlendEquationSeparate(equation,equationAlpha);glClearColor(clear[0],clear[1],clear[2],clear[3]);
    }
}
