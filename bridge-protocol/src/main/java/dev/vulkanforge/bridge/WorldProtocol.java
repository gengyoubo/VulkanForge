package dev.vulkanforge.bridge;

import java.io.*;

/** Bounded commands, big-endian metadata and little-endian Position/Color/UV vertex bytes. */
public final class WorldProtocol {
    public static final int STRIDE = 28;
    public static final int MAX_MESH_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ATLAS_BYTES = 64 * 1024 * 1024;
    public sealed interface Command permits Reset, Atlas, Mesh, Remove, Camera, TexturePatch { long epoch(); }
    public record Reset(long epoch) implements Command { }
    public record Atlas(long epoch, int width, int height, byte[] rgba) implements Command { }
    public record Mesh(long epoch, long key, int x, int y, int z, byte[] vertices) implements Command { }
    public record Remove(long epoch, long key) implements Command { }
    public record Camera(long epoch, double x, double y, double z, float[] matrix, float red, float green, float blue) implements Command { }
    public static final int BLOCK_ATLAS = 0, LIGHT_MAP = 1;
    /** target=0 updates an atlas subregion; target=1 carries the complete 16x16 vanilla lightmap. */
    public record TexturePatch(long epoch, int target, int x, int y, int width, int height, byte[] rgba) implements Command { }

    private WorldProtocol() { }

    public static void write(DataOutputStream out, Command command) throws IOException {
        out.writeByte(command instanceof Reset ? 1 : command instanceof Atlas ? 2 : command instanceof Mesh ? 3 : command instanceof Remove ? 4 : command instanceof Camera ? 5 : 6);
        out.writeLong(command.epoch());
        if (command instanceof Atlas atlas) {
            out.writeInt(atlas.width()); out.writeInt(atlas.height()); out.writeInt(atlas.rgba().length); out.write(atlas.rgba());
        } else if (command instanceof Mesh mesh) {
            out.writeLong(mesh.key()); out.writeInt(mesh.x()); out.writeInt(mesh.y()); out.writeInt(mesh.z());
            out.writeInt(mesh.vertices().length); out.write(mesh.vertices());
        } else if (command instanceof TexturePatch patch) {
            out.writeInt(patch.target()); out.writeInt(patch.x()); out.writeInt(patch.y());
            out.writeInt(patch.width()); out.writeInt(patch.height()); out.writeInt(patch.rgba().length); out.write(patch.rgba());
        } else if (command instanceof Remove remove) out.writeLong(remove.key());
        else if (command instanceof Camera camera) {
            out.writeDouble(camera.x()); out.writeDouble(camera.y()); out.writeDouble(camera.z());
            if (camera.matrix().length != 16) throw new IOException("Camera matrix must contain 16 floats");
            for (float value : camera.matrix()) out.writeFloat(value);
            out.writeFloat(camera.red()); out.writeFloat(camera.green()); out.writeFloat(camera.blue());
        }
    }

    public static Command read(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        long epoch = in.readLong();
        return switch (type) {
            case 1 -> new Reset(epoch);
            case 2 -> {
                int width = in.readInt(), height = in.readInt(), size = in.readInt();
                if (width < 1 || height < 1 || (long) width * height * 4 != size || size > MAX_ATLAS_BYTES) throw new IOException("Invalid atlas dimensions");
                byte[] data = new byte[size]; in.readFully(data);
                yield new Atlas(epoch, width, height, data);
            }
            case 3 -> {
                long key = in.readLong(); int x = in.readInt(), y = in.readInt(), z = in.readInt(), size = in.readInt();
                if (size < 0 || size > MAX_MESH_BYTES || size % (STRIDE * 3) != 0) throw new IOException("Invalid section mesh size");
                byte[] data = new byte[size]; in.readFully(data);
                yield new Mesh(epoch, key, x, y, z, data);
            }
            case 4 -> new Remove(epoch, in.readLong());
            case 5 -> {
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IOException("Invalid camera position");
                float[] matrix = new float[16];
                for (int i = 0; i < 16; i++) { matrix[i] = in.readFloat(); if (!Float.isFinite(matrix[i])) throw new IOException("Invalid camera matrix"); }
                float red = in.readFloat(), green = in.readFloat(), blue = in.readFloat();
                if (!Float.isFinite(red) || !Float.isFinite(green) || !Float.isFinite(blue)) throw new IOException("Invalid clear color");
                yield new Camera(epoch, x, y, z, matrix, red, green, blue);
            }
            case 6 -> {
                int target = in.readInt(), x = in.readInt(), y = in.readInt(), width = in.readInt(), height = in.readInt(), size = in.readInt();
                if ((target != BLOCK_ATLAS && target != LIGHT_MAP) || x < 0 || y < 0 || width < 1 || height < 1 ||
                        (long) width * height * 4 != size || size > MAX_ATLAS_BYTES || (long) x + width > 4096 || (long) y + height > 4096)
                    throw new IOException("Invalid texture patch");
                if (target == LIGHT_MAP && (x != 0 || y != 0 || width != 16 || height != 16)) throw new IOException("Lightmap must be 16x16");
                byte[] data = new byte[size]; in.readFully(data);
                yield new TexturePatch(epoch, target, x, y, width, height, data);
            }
            default -> throw new IOException("Unknown world command: " + type);
        };
    }
}
