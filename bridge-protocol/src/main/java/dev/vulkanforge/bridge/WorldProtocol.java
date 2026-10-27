package dev.vulkanforge.bridge;

import java.io.*;

/** Bounded commands, big-endian metadata and little-endian Position/Color/UV vertex bytes. */
public final class WorldProtocol {
    public static final int STRIDE = 28;
    public static final int MAX_MESH_BYTES = 8 * 1024 * 1024;
    public static final int MAX_ATLAS_DIMENSION = 8192;
    public static final int MAX_ATLAS_BYTES = MAX_ATLAS_DIMENSION * MAX_ATLAS_DIMENSION * 4;
    public sealed interface Command permits Reset, Atlas, Mesh, Remove, Camera, TexturePatch { long epoch(); }
    public record Reset(long epoch) implements Command { }
    public record Atlas(long epoch, int width, int height, byte[] rgba) implements Command {
        public Atlas {
            if (rgba == null || !validPixels(width, height, rgba.length)) throw new IllegalArgumentException("Invalid atlas dimensions or RGBA byte count");
        }
    }
    /** vertices: solid/cutout triangles drawn opaque; translucent: triangles (6 vertices per source quad) sorted back to front. */
    public record Mesh(long epoch, long key, int x, int y, int z, byte[] vertices, byte[] translucent) implements Command {
        public Mesh(long epoch, long key, int x, int y, int z, byte[] vertices) { this(epoch, key, x, y, z, vertices, new byte[0]); }
    }
    public record Remove(long epoch, long key) implements Command { }
    public record Camera(long epoch, double x, double y, double z, float[] matrix, float red, float green, float blue) implements Command { }
    public static final int BLOCK_ATLAS = 0, LIGHT_MAP = 1;
    /** target=0 updates an atlas subregion; target=1 carries the complete 16x16 vanilla lightmap. */
    public record TexturePatch(long epoch, int target, int x, int y, int width, int height, byte[] rgba) implements Command {
        public TexturePatch {
            if (rgba == null || !validPatch(target, x, y, width, height, rgba.length)) throw new IllegalArgumentException("Invalid texture patch");
        }
    }

    /** Checked before allocating a capture buffer, including on the in-process bridge path. */
    public static int atlasBytes(int width, int height) {
        if (!validPixels(width, height, (long) width * height * 4))
            throw new IllegalArgumentException("Unsupported atlas dimensions: " + width + "x" + height + " (maximum " + MAX_ATLAS_DIMENSION + ")");
        return width * height * 4;
    }

    private static boolean validPixels(int width, int height, long size) {
        return width > 0 && height > 0 && width <= MAX_ATLAS_DIMENSION && height <= MAX_ATLAS_DIMENSION
                && size == (long) width * height * 4 && size <= MAX_ATLAS_BYTES;
    }

    private static boolean validPatch(int target, int x, int y, int width, int height, int size) {
        return (target == BLOCK_ATLAS || target == LIGHT_MAP) && x >= 0 && y >= 0 && validPixels(width, height, size)
                && (long) x + width <= MAX_ATLAS_DIMENSION && (long) y + height <= MAX_ATLAS_DIMENSION
                && (target != LIGHT_MAP || (x == 0 && y == 0 && width == 16 && height == 16));
    }

    private WorldProtocol() { }

    /** JDK-only payloads for isolated class loaders; vertex/atlas arrays are immutable and shared, not serialized. */
    public static Object[] pack(Command command) {
        long epoch = command.epoch();
        if (command instanceof Reset) return new Object[]{1, epoch};
        if (command instanceof Atlas a) return new Object[]{2, epoch, a.width(), a.height(), a.rgba()};
        if (command instanceof Mesh m) return new Object[]{3, epoch, m.key(), m.x(), m.y(), m.z(), m.vertices(), m.translucent()};
        if (command instanceof Remove r) return new Object[]{4, epoch, r.key()};
        if (command instanceof Camera c) return new Object[]{5, epoch, c.x(), c.y(), c.z(), c.matrix(), c.red(), c.green(), c.blue()};
        TexturePatch p = (TexturePatch) command;
        return new Object[]{6, epoch, p.target(), p.x(), p.y(), p.width(), p.height(), p.rgba()};
    }

    public static Command unpack(Object[] p) {
        long epoch = (Long) p[1];
        return switch ((Integer) p[0]) {
            case 1 -> new Reset(epoch);
            case 2 -> new Atlas(epoch, (Integer)p[2], (Integer)p[3], (byte[])p[4]);
            case 3 -> new Mesh(epoch, (Long)p[2], (Integer)p[3], (Integer)p[4], (Integer)p[5], (byte[])p[6], (byte[])p[7]);
            case 4 -> new Remove(epoch, (Long)p[2]);
            case 5 -> new Camera(epoch, (Double)p[2], (Double)p[3], (Double)p[4], (float[])p[5], (Float)p[6], (Float)p[7], (Float)p[8]);
            case 6 -> new TexturePatch(epoch, (Integer)p[2], (Integer)p[3], (Integer)p[4], (Integer)p[5], (Integer)p[6], (byte[])p[7]);
            default -> throw new IllegalArgumentException("Unknown local world command");
        };
    }

    public static void write(DataOutputStream out, Command command) throws IOException {
        out.writeByte(command instanceof Reset ? 1 : command instanceof Atlas ? 2 : command instanceof Mesh ? 3 : command instanceof Remove ? 4 : command instanceof Camera ? 5 : 6);
        out.writeLong(command.epoch());
        if (command instanceof Atlas atlas) {
            out.writeInt(atlas.width()); out.writeInt(atlas.height()); out.writeInt(atlas.rgba().length); out.write(atlas.rgba());
        } else if (command instanceof Mesh mesh) {
            out.writeLong(mesh.key()); out.writeInt(mesh.x()); out.writeInt(mesh.y()); out.writeInt(mesh.z());
            out.writeInt(mesh.vertices().length); out.write(mesh.vertices());
            out.writeInt(mesh.translucent().length); out.write(mesh.translucent());
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
                if (!validPixels(width, height, size)) throw new IOException("Invalid atlas dimensions");
                byte[] data = new byte[size]; in.readFully(data);
                yield new Atlas(epoch, width, height, data);
            }
            case 3 -> {
                long key = in.readLong(); int x = in.readInt(), y = in.readInt(), z = in.readInt(), size = in.readInt();
                if (size < 0 || size > MAX_MESH_BYTES || size % (STRIDE * 3) != 0) throw new IOException("Invalid section mesh size");
                byte[] data = new byte[size]; in.readFully(data);
                int translucentSize = in.readInt();
                if (translucentSize < 0 || translucentSize > MAX_MESH_BYTES || translucentSize % (STRIDE * 6) != 0) throw new IOException("Invalid translucent mesh size");
                byte[] translucent = new byte[translucentSize]; in.readFully(translucent);
                yield new Mesh(epoch, key, x, y, z, data, translucent);
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
                if (!validPatch(target, x, y, width, height, size))
                    throw new IOException("Invalid texture patch");
                byte[] data = new byte[size]; in.readFully(data);
                yield new TexturePatch(epoch, target, x, y, width, height, data);
            }
            default -> throw new IOException("Unknown world command: " + type);
        };
    }
}
