package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import java.util.*;
import javax.script.*;
import org.openjdk.nashorn.api.scripting.JSObject;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Verifies the actual render method and both orders of PostChain transformers. */
public final class TailDiagnosticsTransformSmoke {
    public static void main(String[] ignored) throws Exception {
        Map<?,?> diagnostic=rules("tail_diagnostics.js"),post=rules("post.js");
        ClassNode game=read("GameRenderer");apply(diagnostic,"vulkanforge_tail_game",game);verify(game);
        for(boolean diagnosticsFirst:new boolean[]{false,true}){
            ClassNode chain=read("PostChain");
            if(diagnosticsFirst)apply(diagnostic,"vulkanforge_tail_post",chain);
            apply(post,"vulkanforge_post_chain",chain);
            if(!diagnosticsFirst)apply(diagnostic,"vulkanforge_tail_post",chain);
            verify(chain);
        }
        System.out.println("TAIL_DIAGNOSTICS_TRANSFORM=OK (actual GameRenderer and both PostChain transformer orders)");
    }
    private static Map<?,?> rules(String file) throws Exception {
        ScriptEngine engine=new org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory().getScriptEngine();
        engine.eval(Files.readString(Path.of("src/main/resources/coremods/"+file)));
        return (Map<?,?>)((Invocable)engine).invokeFunction("initializeCoreMod");
    }
    private static ClassNode read(String name) throws Exception {
        ClassNode node=new ClassNode();try(var in=TailDiagnosticsTransformSmoke.class.getResourceAsStream("/net/minecraft/client/renderer/"+name+".class")){new ClassReader(in).accept(node,0);}return node;
    }
    private static void apply(Map<?,?> rules,String key,ClassNode node){JSObject rule=(JSObject)rules.get(key);((JSObject)rule.getMember("transformer")).call(null,node);}
    private static void verify(ClassNode node) throws Exception {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(writer);
        ClassNode output=new ClassNode();new ClassReader(writer.toByteArray()).accept(output,ClassReader.EXPAND_FRAMES);
        for(MethodNode method:output.methods)if(method.desc.equals("(F)V")||method.desc.equals("(FJZ)V"))new Analyzer<>(new BasicVerifier()).analyze(output.name,method);
    }
}
