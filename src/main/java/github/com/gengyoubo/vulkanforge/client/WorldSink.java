package github.com.gengyoubo.vulkanforge.client;

import dev.vulkanforge.bridge.WorldProtocol;

/** Destination for immutable mesh/texture updates, independent of process transport. */
interface WorldSink {
    boolean send(WorldProtocol.Command command);
    default void reset(long epoch) { send(new WorldProtocol.Reset(epoch)); }
    default void camera(WorldProtocol.Camera camera) { send(camera); }
    /** False for a sink in the game's own GL context: it samples vanilla's atlas and lightmap, so no readback or patches. */
    default boolean mirrorsTextures() { return true; }
    /** True: publish sections through {@link #quads} instead of triangle-expanded {@link WorldProtocol.Mesh} commands. */
    default boolean rawQuads() { return false; }
    /**
     * Vanilla BLOCK-format quads (DefaultVertexFormat.BLOCK, 4 vertices each) per layer: solid, cutoutMipped, cutout, translucent.
     * The arrays are immutable snapshots. False defers the section to a later frame.
     */
    default boolean quads(long epoch, long key, int x, int y, int z, byte[][] layers) { throw new UnsupportedOperationException(); }
}
