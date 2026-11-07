package github.com.gengyoubo.vulkanforge.server;

import com.sun.management.ThreadMXBean;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/** Synthetic NBT shapes; this is not ChunkSerializer or a region-file benchmark. */
public final class ChunkSaveSimulation {
    private static final byte[][] PAYLOADS = new byte[32][256];
    private static final ThreadMXBean ALLOC = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean && bean.isThreadAllocatedMemorySupported() ? bean : null;
    private static volatile int sink;
    static { Random random = new Random(720451); for (byte[] payload : PAYLOADS) random.nextBytes(payload); }

    record Result(long build, long encode, long compress, long combined, long allocated, byte[] data) { }
    static CompoundTag build(int count) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3465); root.putInt("xPos", 0); root.putInt("zPos", 0);
        root.putString("Status", "minecraft:full");
        ListTag blockEntities = new ListTag();
        for (int i = 0; i < count; i++) {
            CompoundTag tile = new CompoundTag();
            tile.putString("id", "mekanism:synthetic_machine"); tile.putInt("x", i & 15); tile.putInt("y", 64 + i / 256); tile.putInt("z", (i >> 4) & 15);
            tile.putLong("energy", 1000000L + i); tile.putInt("progress", i % 200); tile.putByteArray("payload", PAYLOADS[i % PAYLOADS.length].clone());
            CompoundTag item = new CompoundTag(); item.putString("id", "minecraft:iron_ingot"); item.putByte("Count", (byte)(1 + i % 64));
            ListTag inventory = new ListTag(); inventory.add(item); tile.put("inventory", inventory); blockEntities.add(tile);
        }
        root.put("block_entities", blockEntities); return root;
    }
    static long allocated() { return ALLOC == null ? -1 : ALLOC.getThreadAllocatedBytes(Thread.currentThread().getId()); }
    static Result run(int count, boolean streaming) throws Exception {
        long a0 = allocated(), start = System.nanoTime(); CompoundTag tag = build(count); long built = System.nanoTime();
        byte[] compressed; long encoded = 0, compress = 0, combined = 0;
        if (streaming) {
            ByteArrayOutputStream dest = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(dest)))) { NbtIo.write(tag, out); }
            compressed = dest.toByteArray(); combined = System.nanoTime() - built;
        } else {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(raw)) { NbtIo.write(tag, out); }
            byte[] bytes = raw.toByteArray(); long afterEncode = System.nanoTime(); encoded = afterEncode - built;
            ByteArrayOutputStream dest = new ByteArrayOutputStream();
            try (DeflaterOutputStream out = new DeflaterOutputStream(dest)) { out.write(bytes); }
            compressed = dest.toByteArray(); compress = System.nanoTime() - afterEncode;
        }
        long a1 = allocated(); sink = compressed.length;
        return new Result(built - start, encoded, compress, combined, a0 < 0 ? -1 : a1 - a0, compressed);
    }
    static long percentile(long[] values, double p) { long[] copy = values.clone(); Arrays.sort(copy); return copy[(int)Math.ceil(p * copy.length) - 1]; }
    static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> Math.max(0, g.getCollectionCount())).sum(); }
    static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g -> Math.max(0, g.getCollectionTime())).sum(); }
    static void verify(int count) throws Exception {
        Result a = run(count, false), b = run(count, true);
        if (!Arrays.equals(a.data, b.data)) throw new AssertionError("compressed bytes differ for " + count);
        CompoundTag decoded;
        try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(b.data)))) { decoded = NbtIo.read(in); }
        if (!build(count).equals(decoded)) throw new AssertionError("roundtrip differs for " + count);
        System.out.println("ROUNDTRIP_PASS blocks=" + count + " compressed_bytes=" + a.data.length);
    }
    static void measure(int count) throws Exception {
        for (int i = 0; i < 70; i++) { run(count, false); run(count, true); }
        int n = 200; Result[][] values = new Result[2][n]; long gc0 = gcCount(), ms0 = gcMillis();
        for (int i = 0; i < n; i++) {
            if ((i & 1) == 0) { values[0][i] = run(count, false); values[1][i] = run(count, true); }
            else { values[1][i] = run(count, true); values[0][i] = run(count, false); }
        }
        for (int mode = 0; mode < 2; mode++) {
            long[] build = new long[n], encode = new long[n], compress = new long[n], total = new long[n], alloc = new long[n];
            for (int i = 0; i < n; i++) {
                Result r = values[mode][i]; build[i] = r.build; encode[i] = r.encode; compress[i] = r.compress;
                total[i] = r.build + r.encode + r.compress + r.combined; alloc[i] = r.allocated;
            }
            System.out.printf(java.util.Locale.ROOT,
                  "RESULT blocks=%d mode=%s samples=%d build_p50_ns=%d encode_p50_ns=%d compress_p50_ns=%d total_p50_ns=%d total_p95_ns=%d allocated_p50_bytes=%d%n",
                  count, mode == 0 ? "staged" : "stream", n, percentile(build,.5), percentile(encode,.5), percentile(compress,.5), percentile(total,.5), percentile(total,.95), percentile(alloc,.5));
        }
        System.out.println("GC_MIXED blocks=" + count + " collections=" + (gcCount() - gc0) + " time_ms=" + (gcMillis() - ms0));
    }
    static void io(int count, Path directory) throws Exception {
        byte[] bytes = run(count, true).data; Files.createDirectories(directory); Path path = Files.createTempFile(directory, "chunk-save-", ".tmp");
        long[] write = new long[30], force = new long[30];
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE)) {
            for (int i = 0; i < write.length; i++) {
                long start = System.nanoTime(); file.position(0); file.truncate(0); ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                long middle = System.nanoTime(); file.force(false); long end = System.nanoTime();
                write[i] = middle - start; force[i] = end - middle;
            }
        } finally { Files.deleteIfExists(path); }
        System.out.printf(java.util.Locale.ROOT, "IO blocks=%d bytes=%d writes=30 write_p50_ns=%d write_p95_ns=%d force_p50_ns=%d force_p95_ns=%d%n",
              count, bytes.length, percentile(write,.5), percentile(write,.95), percentile(force,.5), percentile(force,.95));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected temporary output directory");
        if (ALLOC != null && !ALLOC.isThreadAllocatedMemoryEnabled()) ALLOC.setThreadAllocatedMemoryEnabled(true);
        Path tmp = Path.of(args[0]);
        for (int count : new int[] {0, 64, 512}) { verify(count); measure(count); io(count, tmp); }
    }
}
