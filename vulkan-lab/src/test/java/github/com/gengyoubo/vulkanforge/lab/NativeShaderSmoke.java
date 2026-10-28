package github.com.gengyoubo.vulkanforge.lab;

import java.util.Map;
import org.lwjgl.vulkan.VK;

/** Real SPIR-V input interface and pipeline-cache checks, with OpenGL/GLFW forbidden by VkOnlyLauncher. */
public final class NativeShaderSmoke {
    public static void main(String[] ignored) {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        try(VulkanContext context=new VulkanContext()) {
            context.initialize(0,VK.getInstanceVersionSupported(),Boolean.getBoolean("vulkanforge.requireValidation"));
            String vertex="#version 150\nin vec3 Position;\nin vec4 Color;\nin vec2 UV1;\nuniform mat3 NormalMat;\nuniform vec4 ColorModulator;\nout vec4 vertexColor;\nvoid main(){gl_Position=vec4(NormalMat*Position,1);vertexColor=Color*ColorModulator;}";
            String fragment="#version 150\nin vec4 vertexColor;\nout vec4 fragColor;\nvoid main(){fragColor=vertexColor;}";
            try(NativeShaderProgram program=new NativeShaderProgram(context,vertex,fragment,Map.of("Position",0,"Color",1,"UV1",2))) {
                int[] state={0,1,0,1,0,32774,32774,1,1,515,0,2305,15,6914,0,4,0};
                int[][] attributes={{0,3,5126,0,0,0,20,41},{1,4,5121,1,0,12,20,41},{2,2,5122,0,1,16,20,41}};
                long first=program.pipeline(state,attributes,20);
                int[][] replacement=java.util.Arrays.stream(attributes).map(int[]::clone).toArray(int[][]::new);for(int[] attribute:replacement)attribute[7]=99;
                require(program.pipeline(state,replacement,20)==first,"Changing only a VBO name created a new native pipeline");
                try{program.pipeline(state,new int[][]{attributes[1],attributes[2]},20);throw new AssertionError("Missing shader input was silently accepted");}
                catch(UnsupportedOperationException expected){require(expected.getMessage().contains("vertex inputs"),"Missing input diagnostic");}
                var packed=java.nio.FloatBuffer.allocate(512);
                program.writeValues(new Object[]{new Object[]{"NormalMat",new float[]{1,2,3,4,5,6,7,8,9}},new Object[]{"ColorModulator",new float[]{.2f,.3f,.4f,.5f}}},packed,0);
                require(packed.get(0)==1&&packed.get(3)==0&&packed.get(4)==4&&packed.get(8)==7&&packed.get(12)==.2f,"Uniform std140 matrix/vector layout");
                program.writeValues(new Object[0],packed,256);
                for(int i=256;i<512;i++)require(packed.get(i)==0,"Uniform scratch leaked a previous draw's values");
                require(packed.get(12)==.2f,"Next draw overwrote the preceding uniform range");
                require(context.validationErrors()==0,"Optimized unused float input with an integer VBO attribute caused a validation error");
            }
            try(NativeShaderProgram program=new NativeShaderProgram(context,"#version 330\nin uint Packed;\nvoid main(){gl_Position=vec4(float(Packed & 1u),0,0,1);}","#version 330\nout vec4 fragColor;\nvoid main(){fragColor=vec4(1);}",Map.of("Packed",0))) {
                int[] state={0,1,0,1,0,32774,32774,1,1,515,0,2305,15,6914,0,4,0};
                require(program.pipeline(state,new int[][]{{0,1,5125,0,1,0,4,1}},4)!=0,"Unsigned packed Embeddium vertex input");
            }
            try(NativeShaderProgram program=new NativeShaderProgram(context,"#version 330\nin uvec4 a_PosId;\nin ivec2 a_LightCoord;\nuniform vec3 u_RegionOffset;\nvoid main(){gl_Position=vec4(vec3(a_PosId.xyz)+u_RegionOffset+vec3(a_LightCoord,0),1);}","#version 330\nout vec4 fragColor;\nvoid main(){fragColor=vec4(1);}",Map.of("a_PosId",0,"a_LightCoord",3))) {
                int[] state={0,1,0,1,0,32774,32774,1,1,515,0,2305,15,6914,0,4,0};
                require(program.pipeline(state,new int[][]{{0,4,5123,0,1,0,12,1},{3,2,5123,0,1,8,12,1}},12)!=0,"Embeddium unsigned16 light coordinates must widen to shader int32");
            }
            require(context.validationErrors()==0,"Native shader resource shutdown validation error");
        }
        System.out.println("NATIVE_SHADER_SMOKE=OK (unused float/integer input removal, missing-input rejection, VBO-independent pipeline reuse, zero validation errors)");
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
