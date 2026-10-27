package github.com.gengyoubo.vulkanforge.client;

import java.nio.file.*;
import java.util.*;
import javax.script.*;
import org.openjdk.nashorn.api.scripting.JSObject;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Runs the shipped transformer on actual Minecraft classes and verifies its exceptional exit. */
public final class PostChainTransformSmoke {
    public static void main(String[] args) throws Exception {
        ScriptEngine engine=new org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory().getScriptEngine();engine.eval(Files.readString(Path.of("src/main/resources/coremods/post.js")));
        Map<?,?> rules=(Map<?,?>)((Invocable)engine).invokeFunction("initializeCoreMod");
        for(String name:List.of("PostPass","PostChain")) {
            ClassNode node=new ClassNode();try(var in=PostChainTransformSmoke.class.getResourceAsStream("/net/minecraft/client/renderer/"+name+".class")){new ClassReader(in).accept(node,0);}
            JSObject rule=(JSObject)rules.get(name.equals("PostChain")?"vulkanforge_post_chain":"vulkanforge_post");((JSObject)rule.getMember("transformer")).call(null,node);
            ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);node.accept(writer);ClassNode verified=new ClassNode();new ClassReader(writer.toByteArray()).accept(verified,ClassReader.EXPAND_FRAMES);
            for(MethodNode method:verified.methods)if(method.desc.equals("(F)V"))new Analyzer<>(new BasicVerifier()).analyze(verified.name,method);
            System.out.println(name+" actual mapped bytecode: transformed and verified");
        }
        ClassNode fake=new ClassNode();fake.version=Opcodes.V17;fake.access=Opcodes.ACC_PUBLIC;fake.name="PostChainExitProbe";fake.superName="java/lang/Object";
        MethodNode method=new MethodNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"process","(F)V",null,null);LabelNode normal=new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.FLOAD,0));method.instructions.add(new InsnNode(Opcodes.FCONST_0));method.instructions.add(new InsnNode(Opcodes.FCMPG));method.instructions.add(new JumpInsnNode(Opcodes.IFGE,normal));method.instructions.add(new TypeInsnNode(Opcodes.NEW,"java/lang/IllegalArgumentException"));method.instructions.add(new InsnNode(Opcodes.DUP));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,"java/lang/IllegalArgumentException","<init>","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));method.instructions.add(normal);method.instructions.add(new InsnNode(Opcodes.RETURN));method.maxLocals=1;method.maxStack=2;fake.methods.add(method);
        JSObject chain=(JSObject)rules.get("vulkanforge_post_chain");((JSObject)chain.getMember("transformer")).call(null,fake);
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);fake.accept(writer);byte[] bytes=writer.toByteArray();
        Class<?> type=new ClassLoader(PostChainTransformSmoke.class.getClassLoader()){Class<?> define(){return defineClass(null,bytes,0,bytes.length);}}.define();
        var process=type.getMethod("process",float.class);process.invoke(null,1f);
        try{process.invoke(null,-1f);throw new AssertionError("Expected failure");}catch(java.lang.reflect.InvocationTargetException expected){if(!(expected.getCause() instanceof IllegalArgumentException))throw expected;}
        var field=PostEffects.class.getDeclaredField("chainScopes");field.setAccessible(true);if(!((java.util.ArrayDeque<?>)field.get(null)).isEmpty())throw new AssertionError("PostChain scope leaked after throw");
        System.out.println("POST_CHAIN_TRANSFORM_SMOKE=OK (actual classes, JVM verification, success and exception scope exits)");
    }
}
