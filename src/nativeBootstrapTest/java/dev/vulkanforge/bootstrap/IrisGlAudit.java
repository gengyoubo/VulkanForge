package dev.vulkanforge.bootstrap;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import org.objectweb.asm.*;

/** Inventories actual renderer API calls before adding the optional Iris/Oculus execution integration. */
public final class IrisGlAudit {
    public static void main(String[] args)throws Exception{
        Map<String,Set<String>> calls=new TreeMap<>();Set<String> capabilities=new TreeSet<>(),references=new TreeSet<>();
        try(JarFile jar=new JarFile(args[0])){for(var entries=jar.entries();entries.hasMoreElements();){var entry=entries.nextElement();if(!entry.getName().endsWith(".class"))continue;
            try(var input=jar.getInputStream(entry)){new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9){
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions){return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean itf){if(owner.startsWith("org/lwjgl/opengl/"))calls.computeIfAbsent(method,key->new TreeSet<>()).add(descriptor);}
                    @Override public void visitFieldInsn(int opcode,String owner,String field,String descriptor){if(owner.equals("org/lwjgl/opengl/GLCapabilities"))capabilities.add(field);}
                    @Override public void visitInvokeDynamicInsn(String name,String descriptor,Handle bootstrap,Object... arguments){for(Object argument:arguments)if(argument instanceof Handle handle&&handle.getOwner().startsWith("org/lwjgl/opengl/"))references.add(handle.getOwner()+"."+handle.getName()+handle.getDesc());}
                };}
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);}
        }}
        Set<String> implemented=new HashSet<>();Matcher cases=Pattern.compile("case \\\"(\\w+)\\\"").matcher(Files.readString(Path.of(args[1])));while(cases.find())implemented.add(cases.group(1));
        List<String> report=new ArrayList<>();for(var entry:calls.entrySet())report.add((implemented.contains(entry.getKey())?"OK ":"MISSING ")+entry.getKey()+" "+entry.getValue());
        report.add("CAPABILITIES="+capabilities);report.add("METHOD_REFERENCES="+references);Files.write(Path.of(args[2]),report);report.stream().filter(line->line.startsWith("MISSING")||line.startsWith("METHOD_REFERENCES")||line.startsWith("CAPABILITIES")).forEach(System.out::println);
    }
}
