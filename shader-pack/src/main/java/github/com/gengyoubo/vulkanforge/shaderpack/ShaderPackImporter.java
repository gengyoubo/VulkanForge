package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

/** Imports validated ZIPs/folders without overwriting installed packs or exposing partial copies. */
public final class ShaderPackImporter {
    private ShaderPackImporter(){ }
    public static String copy(Path source,Path directory)throws IOException {
        Path input=source.toRealPath();Files.createDirectories(directory);Path root=directory.toRealPath();
        String name=input.getFileName().toString();Path destination=root.resolve(name).normalize();
        if(!destination.getParent().equals(root)||input.equals(root)||root.startsWith(input))throw new ShaderPackException("Invalid shader pack import: "+name);
        if(Files.exists(destination))throw new FileAlreadyExistsException(name);
        try(var archive=ShaderPackArchive.open(input)){if(archive.files().stream().noneMatch(file->file.endsWith(".fsh")||file.endsWith(".csh")))throw new ShaderPackException("No shader programs in "+name);}
        Path staging=Files.createTempDirectory(root,".import-");
        try{
            Path payload=staging.resolve(name);
            if(Files.isDirectory(input))Files.walkFileTree(input,new SimpleFileVisitor<>(){
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attributes)throws IOException {Files.createDirectory(payload.resolve(input.relativize(dir)));return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {if(!attributes.isRegularFile())throw new ShaderPackException("Non-regular shader pack asset: "+file.getFileName());Files.copy(file,payload.resolve(input.relativize(file)));return FileVisitResult.CONTINUE;}
            });else Files.copy(input,payload);
            // No REPLACE_EXISTING: a simultaneous import must not overwrite an installed pack.
            Files.move(payload,destination);return name;
        }finally{try(var paths=Files.walk(staging)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
