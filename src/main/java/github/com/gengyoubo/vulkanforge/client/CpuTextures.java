package github.com.gengyoubo.vulkanforge.client;

import java.nio.*;
import java.util.*;
import org.lwjgl.system.MemoryUtil;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;

/** Tracks original CPU uploads; render targets and unsupported upload sources deliberately retain GPU-readback compatibility. */
public final class CpuTextures {
    private static final long LIMIT=256L*1024*1024;
    private static final class Page {
        final int width,height;final byte[] rgba;long version;TextureCapture.Snapshot snapshot;long snapshotVersion;
        Page(int width,int height){this.width=width;this.height=height;rgba=new byte[width*height*4];}
    }
    private static final Map<Integer,Page> pages=new HashMap<>();
    private static final Set<Integer> targets=new HashSet<>();
    private static long bytes,sequence,uploads,snapshots,uploadedBytes;
    private CpuTextures(){}
    public static void image(int target,int level,int internal,int width,int height,int border,int format,int type,IntBuffer data){
        if(internal==GL_DEPTH_COMPONENT&&type==GL_FLOAT)internal=org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F;
        glTexImage2D(target,level,internal,width,height,border,format,type,data);
        if(target!=GL_TEXTURE_2D||level!=0)return;
        int id=glGetInteger(GL_TEXTURE_BINDING_2D);forget(id);targets.remove(id);
        if(width>0&&height>0&&width<=4096&&height<=4096&&(long)width*height*4+bytes<=LIMIT&&format!=GL_DEPTH_COMPONENT){
            Page page=new Page(width,height);pages.put(id,page);bytes+=page.rgba.length;
            if(data!=null)capture(id,0,0,width,height,format,type,MemoryUtil.memAddress(data));
        }
    }
    public static void subImage(int target,int level,int x,int y,int width,int height,int format,int type,long data){
        if(target==GL_TEXTURE_2D&&level==0)capture(glGetInteger(GL_TEXTURE_BINDING_2D),x,y,width,height,format,type,data);
        glTexSubImage2D(target,level,x,y,width,height,format,type,data);
    }
    public static void subImageBuffer(int target,int level,int x,int y,int width,int height,int format,int type,IntBuffer data){
        if(target==GL_TEXTURE_2D&&level==0)capture(glGetInteger(GL_TEXTURE_BINDING_2D),x,y,width,height,format,type,MemoryUtil.memAddress(data));
        glTexSubImage2D(target,level,x,y,width,height,format,type,data);
    }
    static void unknown(int id){if(pages.containsKey(id))forget(id);}
    private static void capture(int id,int x,int y,int width,int height,int format,int type,long data){
        Page page=pages.get(id);if(page==null||width==0||height==0)return;
        int channels=format==GL_RGBA||format==GL_BGRA?4:format==GL_RGB||format==GL_BGR?3:format==GL_LUMINANCE_ALPHA?2:format==GL_RED||format==GL_ALPHA||format==GL_LUMINANCE?1:0;
        if(channels==0||type!=GL_UNSIGNED_BYTE||data==0||glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING)!=0||x<0||y<0||x+width>page.width||y+height>page.height){forget(id);return;}
        int rowLength=glGetInteger(GL_UNPACK_ROW_LENGTH);if(rowLength==0)rowLength=width;
        int alignment=glGetInteger(GL_UNPACK_ALIGNMENT),rowBytes=(rowLength*channels+alignment-1)/alignment*alignment;
        int skipRows=glGetInteger(GL_UNPACK_SKIP_ROWS),skipPixels=glGetInteger(GL_UNPACK_SKIP_PIXELS);
        for(int row=0;row<height;row++){
            ByteBuffer source=MemoryUtil.memByteBuffer(data+(long)(skipRows+row)*rowBytes+(long)skipPixels*channels,width*channels);
            int out=((y+row)*page.width+x)*4;
            for(int col=0;col<width;col++,out+=4){
                byte a=-1,r=0,g=0,b=0;
                if(channels>=3){r=source.get();g=source.get();b=source.get();if(format==GL_BGRA||format==GL_BGR){byte swap=r;r=b;b=swap;}if(channels==4)a=source.get();}
                else if(format==GL_ALPHA)a=source.get();
                else {r=source.get();if(format==GL_LUMINANCE||format==GL_LUMINANCE_ALPHA)g=b=r;if(channels==2)a=source.get();}
                page.rgba[out]=r;page.rgba[out+1]=g;page.rgba[out+2]=b;page.rgba[out+3]=a;
            }
        }
        page.version=++sequence;page.snapshot=null;uploads++;uploadedBytes+=(long)width*height*channels;
    }
    public static void framebuffer(int target,int attachment,int textureTarget,int texture,int level){
        if(texture>0){targets.add(texture);forget(texture);}
        org.lwjgl.opengl.GL30.glFramebufferTexture2D(target,attachment,textureTarget,texture,level);
    }
    public static void delete(int id){forget(id);targets.remove(id);glDeleteTextures(id);}
    public static void deleteMany(int[] ids){for(int id:ids){forget(id);targets.remove(id);}glDeleteTextures(ids);}
    private static void forget(int id){Page page=pages.remove(id);if(page!=null)bytes-=page.rgba.length;EntityTextures.deleted(id);}
    static long version(int id){Page page=pages.get(id);return page==null?0:page.version;}
    static TextureCapture.Snapshot snapshot(int id){
        Page page=pages.get(id);if(page==null||page.version==0)return null;
        if(page.snapshot==null){page.snapshot=new TextureCapture.Snapshot(page.width,page.height,page.rgba.clone());page.snapshotVersion=page.version;snapshots++;}
        return page.snapshot;
    }
    static long[] stats(){return new long[]{uploads,uploadedBytes,snapshots,bytes,pages.size()};}
}
