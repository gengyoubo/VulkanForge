package dev.vulkanforge.bench;

import dev.vulkanforge.bridge.WorldProtocol;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;

/** Test-only canonical scene selection and actual draw manifests, shared by both benchmark runtimes. */
public final class Workload {
    private Workload() { }
    public static List<WorldProtocol.Command> read(Path file) throws IOException {
        List<WorldProtocol.Command> commands=new ArrayList<>();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            while(true) { try { commands.add(WorldProtocol.read(in)); } catch(EOFException done) { break; } }
        }
        return commands;
    }
    public static void load(Path file, Consumer<WorldProtocol.Command> send) throws IOException { read(file).forEach(send); }
    public static boolean visible(WorldProtocol.Mesh mesh, WorldProtocol.Camera camera) {
        byte[] source=mesh.vertices().length>0 ? mesh.vertices() : mesh.translucent();
        float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        ByteBuffer bytes=ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        for(int at=0;at<source.length;at+=WorldProtocol.STRIDE) for(int axis=0;axis<3;axis++) {
            float value=bytes.getFloat(at+axis*4); bounds[axis]=Math.min(bounds[axis],value); bounds[axis+3]=Math.max(bounds[axis+3],value);
        }
        float[] m=camera.matrix(); int outside=63;
        for(int c=0;c<8;c++) {
            float x=(float)(mesh.x()-camera.x())+bounds[(c&1)==0?0:3]+((c&1)==0?-.001f:.001f);
            float y=(float)(mesh.y()-camera.y())+bounds[(c&2)==0?1:4]+((c&2)==0?-.001f:.001f);
            float z=(float)(mesh.z()-camera.z())+bounds[(c&4)==0?2:5]+((c&4)==0?-.001f:.001f);
            float cx=m[0]*x+m[4]*y+m[8]*z+m[12],cy=m[1]*x+m[5]*y+m[9]*z+m[13],cz=m[2]*x+m[6]*y+m[10]*z+m[14],w=m[3]*x+m[7]*y+m[11]*z+m[15];
            outside&=(cx< -w?1:0)|(cx>w?2:0)|(cy< -w?4:0)|(cy>w?8:0)|(cz< -w?16:0)|(cz>w?32:0);
        }
        return outside==0;
    }
    public static void matched(Path source, Path target) throws IOException {
        List<WorldProtocol.Command> commands=read(source);
        WorldProtocol.Camera camera=(WorldProtocol.Camera)commands.stream().filter(WorldProtocol.Camera.class::isInstance).findFirst().orElseThrow();
        Map<Long,Long> opaque=new TreeMap<>(),translucent=new TreeMap<>();
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            for(var command:commands) {
                if(command instanceof WorldProtocol.Mesh mesh) {
                    if(!visible(mesh,camera)) continue;
                    if(mesh.vertices().length>0) opaque.put(mesh.key(),mesh.vertices().length/(6L*WorldProtocol.STRIDE));
                    if(mesh.translucent().length>0) translucent.put(mesh.key(),mesh.translucent().length/(6L*WorldProtocol.STRIDE));
                }
                WorldProtocol.write(out,command);
            }
        }
        audit(target.resolveSibling("expected-workload.txt"),opaque,translucent);
        Map<Long,Long> fullOpaque=new TreeMap<>(),fullTranslucent=new TreeMap<>();
        for(var command:commands) if(command instanceof WorldProtocol.Mesh mesh) {
            if(mesh.vertices().length>0&&visible(mesh,camera)) fullOpaque.put(mesh.key(),mesh.vertices().length/(6L*WorldProtocol.STRIDE));
            if(mesh.translucent().length>0) fullTranslucent.put(mesh.key(),mesh.translucent().length/(6L*WorldProtocol.STRIDE));
        }
        audit(target.resolveSibling("expected-original-workload.txt"),fullOpaque,fullTranslucent);
    }
    public static String audit(Path target, Map<Long,Long> opaque, Map<Long,Long> translucent) throws IOException {
        List<String> rows=new ArrayList<>();
        new TreeMap<>(opaque).forEach((key,count)->rows.add(key+" opaque "+count));
        new TreeMap<>(translucent).forEach((key,count)->rows.add(key+" translucent "+count));
        String contents=String.join("\n",rows)+"\n", digest;
        try { digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException error) { throw new AssertionError(error); }
        String summary="WORKLOAD opaqueSections="+opaque.size()+" opaqueQuads="+opaque.values().stream().mapToLong(Long::longValue).sum()
                +" translucentSections="+translucent.size()+" translucentQuads="+translucent.values().stream().mapToLong(Long::longValue).sum()+" SHA256="+digest;
        Files.writeString(target,summary+"\n"+contents); System.out.println(summary); return summary;
    }
}
