package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;

/** GLSL preprocessor symbols supplied by the loader for color-format metadata. Values are API enum constants. */
public final class ShaderPackFormats {
    public static final Map<String,Integer> ENUMS;
    static{
        Map<String,Integer> values=new TreeMap<>();
        String[] names={"RGBA","RGB","RGBA8","RGB8","RGBA16","RGBA32F","RGB32F","RGBA16F","RGB16F","R11F_G11F_B10F","RGB10_A2","RGB8_SNORM","RGB16_SNORM","RGBA8_SNORM","RGBA16_SNORM"};
        int[] numbers={6408,6407,32856,32849,32859,34836,34837,34842,34843,35898,32857,36758,36762,36759,36763};
        for(int i=0;i<names.length;i++)values.put(names[i],numbers[i]);
        names=new String[]{"R8","R16","RG8","RG16","R16F","R32F","RG16F","RG32F","R8I","R8UI","R16I","R16UI","R32I","R32UI","RG8I","RG8UI","RG16I","RG16UI","RG32I","RG32UI"};
        for(int i=0;i<names.length;i++)values.put(names[i],33321+i);
        values.putAll(Map.of("RGBA8I",36238,"RGBA8UI",36220,"RGBA16I",36232,"RGBA16UI",36214,"RGBA32I",36226,"RGBA32UI",36208));
        ENUMS=Collections.unmodifiableMap(values);
    }
    private ShaderPackFormats(){ }
    static String defines(String source){StringBuilder output=new StringBuilder();ENUMS.forEach((name,value)->{if(source.contains(name))output.append("#ifndef ").append(name).append("\n#define ").append(name).append(' ').append(value).append("\n#endif\n");});return output.toString();}
}
