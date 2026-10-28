package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reads folders or ZIPs in place. No extraction, game classes, or graphics API dependencies. */
public final class ShaderPackArchive implements AutoCloseable {
    private static final int MAX_ASSET=64<<20,MAX_ENTRIES=65536;
    private final Path source,folder;
    private final ZipFile zip;
    private final String prefix;
    private final SortedSet<String> files;
    private ShaderPackArchive(Path source,Path folder,ZipFile zip,String prefix,SortedSet<String> files){this.source=source;this.folder=folder;this.zip=zip;this.prefix=prefix;this.files=Collections.unmodifiableSortedSet(files);}
    public static ShaderPackArchive open(Path requested)throws IOException {
        Path source=requested.toRealPath();
        if(Files.isDirectory(source)){
            Path shaders=source.getFileName().toString().equals("shaders")?source:source.resolve("shaders");
            if(!Files.isDirectory(shaders))throw new ShaderPackException("No shaders/ directory in "+source.getFileName());
            shaders=shaders.toRealPath();SortedSet<String> files=new TreeSet<>();
            try(var walk=Files.walk(shaders)){for(Path file:walk.filter(Files::isRegularFile).toList()){if(files.size()>=MAX_ENTRIES)throw new ShaderPackException("Too many shader pack files");files.add(shaders.relativize(file).toString().replace('\\','/'));}}
            return new ShaderPackArchive(source,shaders,null,"",files);
        }
        ZipFile zip=new ZipFile(source.toFile(),StandardCharsets.UTF_8);
        try {
            SortedSet<String> roots=new TreeSet<>();int count=0;
            for(var iterator=zip.entries();iterator.hasMoreElements();){ZipEntry entry=iterator.nextElement();if(++count>MAX_ENTRIES)throw new ShaderPackException("Too many shader pack ZIP entries");String name=entry.getName();int at=name.indexOf("shaders/");if(at>=0&&(at==0||name.charAt(at-1)=='/'))roots.add(name.substring(0,at+8));}
            if(roots.size()!=1)throw new ShaderPackException(roots.isEmpty()?"ZIP contains no shaders/ directory":"ZIP contains multiple shaders/ directories");
            String root=roots.first();SortedSet<String> files=new TreeSet<>();
            for(var iterator=zip.entries();iterator.hasMoreElements();){ZipEntry entry=iterator.nextElement();if(!entry.isDirectory()&&entry.getName().startsWith(root)){String name=entry.getName().substring(root.length());if(!normalize(name).equals(name))throw new ShaderPackException("Non-canonical ZIP entry: "+name);files.add(name);}}
            return new ShaderPackArchive(source,null,zip,root,files);
        }catch(Throwable error){zip.close();throw error;}
    }
    public Path source(){return source;}
    public SortedSet<String> files(){return files;}
    public boolean exists(String path){return files.contains(normalize(path));}
    public byte[] bytes(String path)throws IOException {
        String name=normalize(path);if(!files.contains(name))throw new FileNotFoundException("Shader pack asset: "+name);
        if(zip!=null){ZipEntry entry=zip.getEntry(prefix+name);if(entry.getSize()>MAX_ASSET)throw new ShaderPackException("Shader pack asset exceeds 64 MiB: "+name);try(InputStream input=zip.getInputStream(entry)){return bounded(input,name);}}
        Path file=folder.resolve(name).toRealPath();if(!file.startsWith(folder))throw new ShaderPackException("Asset escapes shader pack directory: "+name);
        if(Files.size(file)>MAX_ASSET)throw new ShaderPackException("Shader pack asset exceeds 64 MiB: "+name);try(InputStream input=Files.newInputStream(file)){return bounded(input,name);}
    }
    public String text(String path)throws IOException {String text=new String(bytes(path),StandardCharsets.UTF_8);return text.startsWith("\ufeff")?text.substring(1):text;}
    private static byte[] bounded(InputStream input,String name)throws IOException {byte[] bytes=input.readNBytes(MAX_ASSET+1);if(bytes.length>MAX_ASSET)throw new ShaderPackException("Shader pack asset exceeds 64 MiB: "+name);return bytes;}
    public static String resolve(String parent,String include){
        if(include.startsWith("/"))return normalize(include.substring(1));int slash=parent.lastIndexOf('/');return normalize((slash<0?"":parent.substring(0,slash+1))+include);
    }
    public static String normalize(String path){
        if(path.indexOf('\\')>=0||path.indexOf(':')>=0||path.indexOf('\0')>=0||path.startsWith("/"))throw new ShaderPackException("Invalid shader pack path: "+path);
        Deque<String> parts=new ArrayDeque<>();for(String part:path.split("/")){if(part.isEmpty()||part.equals("."))continue;if(part.equals("..")){if(parts.isEmpty())throw new ShaderPackException("Path escapes shaders/: "+path);parts.removeLast();}else parts.addLast(part);}return String.join("/",parts);
    }
    @Override public void close()throws IOException {if(zip!=null)zip.close();}
}
