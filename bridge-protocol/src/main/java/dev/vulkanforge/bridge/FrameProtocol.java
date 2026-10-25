package dev.vulkanforge.bridge;

/** Big-endian header: magic, width, height. Each frame: sequence, world epoch (longs), size, RGBA8. */
public final class FrameProtocol {
    public static final int MAGIC = 0x56464b33; // VFK3: world vertices also carry packed light coordinates
    public static final int WIDTH = 640;
    public static final int HEIGHT = 360;
    public static final int BYTES = WIDTH * HEIGHT * 4;
    public static final int FPS = 60;
    private FrameProtocol() { }
}
