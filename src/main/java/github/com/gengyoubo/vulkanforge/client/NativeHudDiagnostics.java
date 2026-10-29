package github.com.gengyoubo.vulkanforge.client;

import java.util.*;

/** Explicit HUD ablations only. No methods are skipped unless the diagnostic bootstrap and a case are both selected. */
public final class NativeHudDiagnostics {
    private static final String[] SCOPES={"hands","hotbar","vanillaHealth","hud","forgeHealth","forgeFood","forgeArmor","forgeAir"};
    private static final long[] calls=new long[8],skipped=new long[8],nanos=new long[8],packets=new long[8];
    private static final long[][] started=new long[8][16],firstPacket=new long[8][16];
    private static final int[] depth=new int[8];
    private static final boolean ENABLED=Boolean.getBoolean("vulkanforge.hudDiagnostics");
    private static String selected="BASELINE";
    private NativeHudDiagnostics() { }
    public static void select(String value){if(!ENABLED)throw new IllegalStateException("HUD diagnostics not enabled");if(!Set.of("BASELINE","NO_HANDS","NO_HOTBAR","NO_HEALTH","NO_HUD","F1","NO_HANDS_HUD").contains(value))throw new IllegalArgumentException(value);for(int d:depth)if(d!=0)throw new IllegalStateException("Switch HUD case only between frames");selected=value;}
    public static String selected(){return selected;}
    public static boolean enter(int scope){
        calls[scope]++;boolean skip=switch(selected){case "NO_HANDS"->scope==0;case "NO_HOTBAR"->scope==1;case "NO_HEALTH"->scope==2||scope>=4;case "NO_HUD"->scope==3;case "NO_HANDS_HUD"->scope==0||scope==3;default->false;};
        if(skip){skipped[scope]++;return true;}int level=depth[scope]++;if(level>=16)throw new IllegalStateException("Recursive HUD scope overflow");started[scope][level]=System.nanoTime();firstPacket[scope][level]=NativeDeviceState.hudPacketSerial();return false;
    }
    public static void exit(int scope){int level=--depth[scope];if(level<0)throw new IllegalStateException("Unbalanced HUD scope");nanos[scope]+=System.nanoTime()-started[scope][level];packets[scope]+=NativeDeviceState.hudPacketSerial()-firstPacket[scope][level];}
    static Map<String,Long> diagnostics(){Map<String,Long> result=new LinkedHashMap<>();if(ENABLED)for(int i=0;i<SCOPES.length;i++){String prefix="hud_"+SCOPES[i];result.put(prefix+"Calls",calls[i]);result.put(prefix+"Skipped",skipped[i]);result.put(prefix+"Ns",nanos[i]);result.put(prefix+"Packets",packets[i]);}return result;}
}
