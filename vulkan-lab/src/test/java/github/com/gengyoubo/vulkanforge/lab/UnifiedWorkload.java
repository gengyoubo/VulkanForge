package github.com.gengyoubo.vulkanforge.lab;

import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One canonical synthetic capture tape, shared shader bodies, command format, and an exactly-once audit contract. */
final class UnifiedWorkload {
    static final int BASELINE=0,DRIVEN=1,FULL=2,RECORD_BYTES=48,COMMAND_BYTES=20,INDEX_COUNT=36,SLOTS=2;
    static final String[] LEVELS={"BASELINE","GPU_DRIVEN","FULL_PIPELINE"};
    final int opaque,transparent,fallback,total;
    final byte[] records,mesh,indices,ids;
    final String hash;
    UnifiedWorkload(int count) throws Exception {
        if(count<2)throw new IllegalArgumentException("At least two draws are needed to exercise the audit");
        opaque=count;transparent=Math.max(1,count/4);fallback=Math.max(1,count/64);total=opaque+transparent+fallback;
        ByteBuffer r=bytes(total*RECORD_BYTES),idData=bytes(total*4);
        for(int id=0;id<total;id++) {
            int type=id<opaque?0:id<opaque+transparent?1:2,k=type==0?id:type==1?id-opaque:id-opaque-transparent;
            int n=type==0?opaque:type==1?transparent:fallback,columns=(int)Math.ceil(Math.sqrt(n));
            float cx,cy,hx,hy,z;
            if(type<2){cx=((k%columns)+.5f)/columns*2.5f-1.25f;cy=((k/columns)+.5f)/columns*2.5f-1.25f;hx=.75f/columns;hy=.75f/columns;z=type==0?.35f+(k%8)*.0625f:.2f-(k%8)*.03125f;if(k%211==0)z=1.25f;}
            else{cx=-.85f+k%8*.24f;cy=.8f-k/8*.16f;hx=.055f;hy=.055f;z=-.6f;}
            r.putFloat(cx).putFloat(cy).putFloat(hx).putFloat(hy);
            r.putFloat(((id*37)%160+64)/255f).putFloat(((id*53)%160+64)/255f).putFloat(((id*71)%160+64)/255f).putFloat(type==0?1f:type==1?.375f:.75f);
            r.putFloat(z).putFloat(.025f).putFloat(type).putFloat(id);idData.putInt(id);
        }
        records=r.array();ids=idData.array();
        ByteBuffer vertices=bytes(24*24),elements=bytes(36*4);
        float[][][] faces={{{-1,-1,-1},{1,-1,-1},{1,1,-1},{-1,1,-1}},{{1,-1,1},{-1,-1,1},{-1,1,1},{1,1,1}},{{-1,-1,1},{-1,-1,-1},{-1,1,-1},{-1,1,1}},{{1,-1,-1},{1,-1,1},{1,1,1},{1,1,-1}},{{-1,-1,1},{1,-1,1},{1,-1,-1},{-1,-1,-1}},{{-1,1,-1},{1,1,-1},{1,1,1},{-1,1,1}}};
        float[][] normals={{0,0,-1},{0,0,1},{-1,0,0},{1,0,0},{0,-1,0},{0,1,0}};
        for(int f=0;f<6;f++) {for(float[] p:faces[f]){for(float v:p)vertices.putFloat(v);for(float v:normals[f])vertices.putFloat(v);}for(int i:new int[]{0,1,2,2,3,0})elements.putInt(f*4+i);}
        mesh=vertices.array();indices=elements.array();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");for(byte[] data:new byte[][]{records,mesh,indices,ids})digest.update(data);
        for(String name:List.of("unified_common.glsl","unified_draw.vert","unified_draw.frag","unified_cull.comp","unified_post.vert","unified_post.frag"))digest.update(resource(name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        hash=HexFormat.of().formatHex(digest.digest());
    }
    int core(int level){return opaque+(level==FULL?transparent:0);}
    int captured(int level){return core(level)+(level==FULL?fallback:0);}
    boolean visible(int id) {
        ByteBuffer r=ByteBuffer.wrap(records).order(ByteOrder.LITTLE_ENDIAN);int at=id*RECORD_BYTES;
        float x=r.getFloat(at),y=r.getFloat(at+4),hx=r.getFloat(at+8),hy=r.getFloat(at+12),z=r.getFloat(at+32),hz=r.getFloat(at+36);
        return x-hx<=1&&x+hx>=-1&&y-hy<=1&&y+hy>=-1&&z-hz<=1&&z+hz>=-1;
    }
    byte[] commands(int level) {
        ByteBuffer out=bytes(core(level)*COMMAND_BYTES);for(int i=0;i<core(level);i++)out.putInt(INDEX_COUNT).putInt(1).putInt(0).putInt(0).putInt(i);return out.array();
    }
    Audit audit(int level,ByteBuffer actual,long iaVertices,long vsInvocations,long fallbackVertices,int[] actualFallbacks) throws Exception {
        return audit(level,actual,iaVertices,vsInvocations,fallbackVertices,actualFallbacks,new int[0]);
    }
    Audit audit(int level,ByteBuffer actual,long iaVertices,long vsInvocations,long fallbackVertices,int[] actualFallbacks,int[] intentionallyExcludedFallbacks) throws Exception {
        List<Integer> drawn=new ArrayList<>(),culled=new ArrayList<>();Set<Integer> seen=new HashSet<>();int duplicates=0;
        actual=actual.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<core(level);i++) {
            int at=i*COMMAND_BYTES,id=actual.getInt(at+16),count=actual.getInt(at);
            if(id<0||id>=core(level)||actual.getInt(at+4)!=1||actual.getInt(at+8)!=0||actual.getInt(at+12)!=0||(count!=0&&count!=INDEX_COUNT))throw new AssertionError("Invalid GPU draw command slot="+i);
            if(!seen.add(id))duplicates++;
            if(count==0)culled.add(id);else drawn.add(id);
        }
        List<Integer> fallbacks=new ArrayList<>();for(int id:actualFallbacks){if(level!=FULL||id<core(level)||id>=total)throw new AssertionError("Unexpected fallback draw ID "+id);fallbacks.add(id);if(!seen.add(id))duplicates++;}
        for(int id:intentionallyExcludedFallbacks){if(level!=FULL||id<core(level)||id>=total)throw new AssertionError("Only compatibility IDs may be excluded");if(!seen.add(id))duplicates++;}
        Set<Integer> expected=new TreeSet<>();for(int id=0;id<core(level);id++)if(level==BASELINE||visible(id))expected.add(id);
        if(!new TreeSet<>(drawn).equals(expected)||duplicates!=0||drawn.size()+culled.size()+fallbacks.size()+intentionallyExcludedFallbacks.length!=captured(level))throw new AssertionError("Draw IDs fail exactly-once partition");
        if(iaVertices!=(long)drawn.size()*INDEX_COUNT||fallbackVertices!=(long)fallbacks.size()*INDEX_COUNT)throw new AssertionError("GPU input-assembly vertices disagree with submitted IDs: "+iaVertices+" / "+fallbackVertices);
        String contents="drawn="+drawn+"\nculled="+culled+"\nfallback="+fallbacks+"\n";
        if(intentionallyExcludedFallbacks.length!=0)contents+="intentionallyExcludedFallback="+Arrays.toString(intentionallyExcludedFallbacks)+"\n";
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new Audit(captured(level),drawn.size(),fallbacks.size(),duplicates,culled.size(),iaVertices,vsInvocations,core(level),digest,contents);
    }
    record Audit(int captured,int submitted,int fallback,int duplicated,int culled,long vertices,long vsInvocations,int commandSlots,String hash,String ids) { }
    void verifyAuditRejectsBadWork() throws Exception {
        ByteBuffer commands=ByteBuffer.wrap(commands(BASELINE)).order(ByteOrder.LITTLE_ENDIAN);
        audit(BASELINE,commands,(long)opaque*INDEX_COUNT,(long)opaque*24,0,new int[0]);
        commands.putInt(16,1);boolean rejected=false;try{audit(BASELINE,commands,(long)opaque*INDEX_COUNT,0,0,new int[0]);}catch(AssertionError expected){rejected=true;}
        if(!rejected)throw new AssertionError("Audit accepted duplicate draw IDs");
        commands.putInt(16,0).putInt(0,0);rejected=false;try{audit(BASELINE,commands,(long)(opaque-1)*INDEX_COUNT,0,0,new int[0]);}catch(AssertionError expected){rejected=true;}
        if(!rejected)throw new AssertionError("Audit accepted missing captured work");
        UnifiedSubmissions calls=new UnifiedSubmissions(this);for(int id=0;id<opaque;id++)calls.direct(id,false);calls.completed();calls.reset();for(int id=0;id<opaque;id++)calls.direct(id==0?1:id,false);
        rejected=false;try{calls.completed();}catch(AssertionError expected){rejected=true;}if(!rejected)throw new AssertionError("API argument ledger accepted a changed frame");
        System.out.println("LEDGER_SELF_TEST=OK duplicate/missing draw injections and changed API arguments rejected");
    }
    static ByteBuffer bytes(int size){return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);}
    static String resource(String name) throws Exception {try(var in=UnifiedWorkload.class.getResourceAsStream("/shaders/"+name)){if(in==null)throw new IllegalStateException("Missing "+name);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    static String shader(String name,boolean vulkan) throws Exception {return "#version "+(vulkan?"450":"430")+"\n"+(vulkan?"#define UNIFIED_VULKAN 1\n":"")+resource(name).replace("#include \"unified_common.glsl\"",resource("unified_common.glsl"));}
}
