package github.com.gengyoubo.vulkanforge.client;

import net.minecraft.client.renderer.PostChain;

/** Explicit, opt-in ablations. Omission cases intentionally change requested rendering work. */
public final class TailDiagnostics {
    public static final boolean ENABLED=Boolean.getBoolean("vulkanforge.glTailDiagnostics");
    private static String selected=System.getProperty("vulkanforge.glTailCase","A");
    private static long executed,skipped;
    static {configure();}
    private TailDiagnostics(){}
    public static void initialize(){}
    public static String selected(){return selected;}
    public static void select(String value){
        if(!ENABLED||!NativeMainWindow.glTailCompatibility())throw new IllegalStateException("GL tail diagnostics are not active");
        selected=value;configure();
    }
    private static void configure(){
        if(!java.util.Set.of("A","B","C","D").contains(selected))throw new IllegalArgumentException("Unknown GL tail case "+selected);
        if(ENABLED)System.setProperty("vulkanforge.glTailOmitTerrain",Boolean.toString(selected.equals("B")));
    }
    /** Redirect only GameRenderer's optional effect call; mandatory LevelRenderer chains stay untouched. */
    public static void processOptional(PostChain chain,float time){
        if(ENABLED&&selected.equals("C")&&NativeMainWindow.glTailCompatibility()
                &&chain.getName().equals("minecraft:shaders/post/desaturate.json")) {skipped++;return;}
        if(ENABLED)executed++;
        chain.process(time);
    }
    static long[] postStats(){return new long[]{executed,skipped};}
}
