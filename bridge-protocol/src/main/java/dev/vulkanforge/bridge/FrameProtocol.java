package dev.vulkanforge.bridge;

/** Big-endian header: magic, width, height. Each frame: sequence (long), size (int), RGBA8 pixels. */
public final class FrameProtocol {
    public static final int MAGIC = 0x56464b31; // VFK1
    public static final int WIDTH = 640;
    public static final int HEIGHT = 360;
    public static final int BYTES = WIDTH * HEIGHT * 4;
    public static final int FPS = 30;
    private FrameProtocol() { }
}
