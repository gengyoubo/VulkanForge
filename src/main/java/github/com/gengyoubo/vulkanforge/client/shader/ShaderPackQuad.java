package github.com.gengyoubo.vulkanforge.client.shader;

import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

final class ShaderPackQuad implements AutoCloseable {
    private final int array,vertices,indices;
    private boolean closed;
    ShaderPackQuad(){
        array=glGenVertexArrays();vertices=glGenBuffers();indices=glGenBuffers();
        try(MemoryStack stack=MemoryStack.stackPush()){
            var vertex=stack.malloc(80).order(ByteOrder.nativeOrder());for(float[] corner:new float[][]{{0,0},{1,0},{1,1},{0,1}})vertex.putFloat(corner[0]).putFloat(corner[1]).putFloat(0).putFloat(corner[0]).putFloat(corner[1]);vertex.flip();
            var index=stack.malloc(12).order(ByteOrder.nativeOrder());for(int i:new int[]{0,1,2,2,3,0})index.putShort((short)i);index.flip();
            glBindVertexArray(array);glBindBuffer(GL_ARRAY_BUFFER,vertices);glBufferData(GL_ARRAY_BUFFER,vertex,GL_STATIC_DRAW);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,indices);glBufferData(GL_ELEMENT_ARRAY_BUFFER,index,GL_STATIC_DRAW);
            glVertexAttribPointer(0,3,GL_FLOAT,false,20,0L);glVertexAttribPointer(1,2,GL_FLOAT,false,20,12L);glEnableVertexAttribArray(0);glEnableVertexAttribArray(1);
        }catch(Throwable error){close();throw error;}
    }
    void draw(){glBindVertexArray(array);glDrawElements(GL_TRIANGLES,6,GL_UNSIGNED_SHORT,0L);}
    @Override public void close(){if(closed)return;closed=true;glDeleteVertexArrays(array);glDeleteBuffers(vertices);glDeleteBuffers(indices);}
}
