package github.com.gengyoubo.vulkanforge.lab;

import java.util.*;

/** GL-independent frame description. Unsupported categories are explicit and must never be silently omitted. */
record NativeFramePlan(List<PostDraw> posts,List<UnsupportedDraw> unsupported) {
    record PostDraw(String name,String vertex,String fragment,float[] uniforms,int[] blend,float[] clear) {
        PostDraw {if(uniforms==null||uniforms.length>256||blend==null||blend.length!=5||clear==null||clear.length!=4)throw new IllegalArgumentException("Invalid native post description");uniforms=uniforms.clone();blend=blend.clone();clear=clear.clone();}
        Object[] packet(int input,int output,int width,int height){return new Object[]{name,vertex,fragment,new int[]{input,width,height,0,0},uniforms,new int[]{output,width,height},blend,clear};}
    }
    record UnsupportedDraw(long id,String category,String name) { }
    NativeFramePlan {posts=List.copyOf(posts);unsupported=List.copyOf(unsupported);}
    static NativeFramePlan terrain(){return new NativeFramePlan(List.of(),List.of());}
    void requireSupported(){if(!unsupported.isEmpty())throw new UnsupportedOperationException("VK native refuses unsupported draws: "+unsupported);}
}
