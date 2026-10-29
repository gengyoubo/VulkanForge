package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Custom PNG and noise texture decoding into RGBA8 rows (top row first); no graphics API is touched here. */
public final class ShaderPackTextures {
    public record Image(int width,int height,byte[] rgba,boolean blur,boolean clamp){
        public Image{rgba=rgba.clone();if(width<1||height<1||rgba.length!=width*height*4)throw new ShaderPackException("Invalid texture dimensions");}
        @Override public byte[] rgba(){return rgba.clone();}
    }
    private static final int MAX_SIZE=8192;
    private static final Pattern FLAG=Pattern.compile("\"(blur|clamp)\"\\s*:\\s*(true|false)");
    private ShaderPackTextures(){ }
    /** Namespaced values such as {@code minecraft:textures/misc/x.png} come from the game's resources, not the pack. */
    public static boolean resource(String value){return value.strip().matches("[a-z0-9_.-]+:[a-z0-9_./-]+\\.png");}
    /** Raw binary layouts and dynamic game textures are not implemented; those definitions are rejected by preflight. */
    public static boolean supported(String value){String path=value.strip();return !path.matches(".*\\s.*")&&path.toLowerCase(Locale.ROOT).endsWith(".png");}
    public static Image load(ShaderPackArchive archive,String value)throws IOException{
        if(!supported(value)||resource(value))throw new ShaderPackException("Unsupported pack texture definition: "+value);
        String path=value.strip();if(path.startsWith("/"))path=path.substring(1);path=ShaderPackArchive.normalize(path);
        if(!archive.exists(path))throw new ShaderPackException("Missing custom texture: "+path);
        return decode(archive.bytes(path),archive.exists(path+".mcmeta")?archive.bytes(path+".mcmeta"):null,path);
    }
    public static Image decode(byte[] png,byte[] mcmeta,String name)throws IOException{
        var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(png));if(image==null)throw new ShaderPackException("Not a readable PNG texture: "+name);
        int width=image.getWidth(),height=image.getHeight();if(width>MAX_SIZE||height>MAX_SIZE)throw new ShaderPackException("Custom texture exceeds "+MAX_SIZE+" pixels: "+name);
        byte[] rgba=new byte[width*height*4];int at=0;
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int argb=image.getRGB(x,y);rgba[at++]=(byte)(argb>>16);rgba[at++]=(byte)(argb>>8);rgba[at++]=(byte)argb;rgba[at++]=(byte)(argb>>>24);}
        boolean blur=false,clamp=false;
        if(mcmeta!=null){Matcher flags=FLAG.matcher(new String(mcmeta,StandardCharsets.UTF_8));while(flags.find())if(flags.group(1).equals("blur"))blur=Boolean.parseBoolean(flags.group(2));else clamp=Boolean.parseBoolean(flags.group(2));}
        return new Image(width,height,rgba,blur,clamp);
    }
    /** Seeded so every load of a pack samples identical noise; linear filtered and repeating like a standard noisetex. */
    public static Image noise(int resolution){
        if(resolution<1||resolution>MAX_SIZE)throw new ShaderPackException("Invalid noise texture resolution: "+resolution);
        Random random=new Random(0);byte[] rgba=new byte[resolution*resolution*4];
        for(int i=0;i<rgba.length;i+=4){int value=random.nextInt();rgba[i]=(byte)(value>>16);rgba[i+1]=(byte)(value>>8);rgba[i+2]=(byte)value;rgba[i+3]=(byte)255;}
        return new Image(resolution,resolution,rgba,true,false);
    }
}
