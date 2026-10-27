package github.com.gengyoubo.vulkanforge.lab;

import java.util.Map;
import org.lwjgl.vulkan.VK;

/** Real SPIR-V input interface and pipeline-cache checks, with OpenGL/GLFW forbidden by VkOnlyLauncher. */
public final class NativeShaderSmoke {
    public static void main(String[] ignored) {
        org.lwjgl.system.Configuration.STACK_SIZE.set(4096);
        try(VulkanContext context=new VulkanContext()) {
            context.initialize(0,VK.getInstanceVersionSupported(),Boolean.getBoolean("vulkanforge.requireValidation"));
            String vertex="#version 150\nin vec3 Position;\nin vec4 Color;\nin vec2 UV1;\nout vec4 vertexColor;\nvoid main(){gl_Position=vec4(Position,1);vertexColor=Color;}";
            String fragment="#version 150\nin vec4 vertexColor;\nout vec4 fragColor;\nvoid main(){fragColor=vertexColor;}";
            try(NativeShaderProgram program=new NativeShaderProgram(context,vertex,fragment,Map.of("Position",0,"Color",1,"UV1",2))) {
                int[] state={0,1,0,1,0,32774,32774,1,1,515,0,2305,15,6914,0,4,0};
                int[][] attributes={{0,3,5126,0,0,0,20,41},{1,4,5121,1,0,12,20,41},{2,2,5122,0,1,16,20,41}};
                long first=program.pipeline(state,attributes,20);
                int[][] replacement=java.util.Arrays.stream(attributes).map(int[]::clone).toArray(int[][]::new);for(int[] attribute:replacement)attribute[7]=99;
                require(program.pipeline(state,replacement,20)==first,"Changing only a VBO name created a new native pipeline");
                try{program.pipeline(state,new int[][]{attributes[1],attributes[2]},20);throw new AssertionError("Missing shader input was silently accepted");}
                catch(UnsupportedOperationException expected){require(expected.getMessage().contains("vertex inputs"),"Missing input diagnostic");}
                require(context.validationErrors()==0,"Optimized unused float input with an integer VBO attribute caused a validation error");
            }
            require(context.validationErrors()==0,"Native shader resource shutdown validation error");
        }
        System.out.println("NATIVE_SHADER_SMOKE=OK (unused float/integer input removal, missing-input rejection, VBO-independent pipeline reuse, zero validation errors)");
    }
    private static void require(boolean value,String reason){if(!value)throw new AssertionError(reason);}
}
