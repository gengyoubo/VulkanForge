package github.com.gengyoubo.vulkanforge.shaderpack;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Transactional hot reload. GPU compilation, commit and retirement run on the render executor. */
public final class ShaderPackController<R extends AutoCloseable> implements AutoCloseable {
    public record Status(String active,String requested,boolean loading,String error){ }
    @FunctionalInterface public interface Compiler<R>{R compile(ShaderPack pack)throws Exception;}
    private record Ready<R>(long revision,String name,R resource){ }
    private final Executor io,render;
    private final Compiler<R> compiler;
    private final Consumer<R> retire;
    private volatile Status status=new Status("","",false,"");
    private long revision;
    private volatile boolean closed;
    private R active;
    private Ready<R> ready;
    public ShaderPackController(Executor io,Executor render,Compiler<R> compiler,Consumer<R> retire){this.io=io;this.render=render;this.compiler=compiler;this.retire=retire;}
    public Status status(){return status;}
    public R active(){return active;}
    public void request(Path path){
        final long token;final String name=path==null?"":path.getFileName().toString();
        synchronized(this){if(closed)throw new IllegalStateException("Shader pack controller closed");token=++revision;status=new Status(status.active(),name,true,"");}
        if(path==null){render.execute(()->prepare(token,name,null));return;}
        CompletableFuture.supplyAsync(()->{try{return ShaderPack.load(path);}catch(Exception error){throw new CompletionException(error);}},io)
                .whenComplete((pack,failure)->render.execute(()->{
                    if(stale(token))return;
                    if(failure!=null){failed(token,failure);return;}
                    R candidate=null;
                    try{candidate=Objects.requireNonNull(compiler.compile(pack),"Shader pack compiler returned no resources");prepare(token,name,candidate);}
                    catch(Throwable error){if(candidate!=null)retire.accept(candidate);failed(token,error);}
                }));
    }
    private synchronized boolean stale(long token){return closed||revision!=token;}
    private synchronized void failed(long token,Throwable failure){
        if(stale(token))return;while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();
        status=new Status(status.active(),status.requested(),false,Objects.requireNonNullElse(failure.getMessage(),failure.getClass().getSimpleName()));
    }
    private synchronized void prepare(long token,String name,R candidate){
        if(stale(token)){if(candidate!=null)retire.accept(candidate);return;}
        if(ready!=null&&ready.resource()!=null)retire.accept(ready.resource());ready=new Ready<>(token,name,candidate);
    }
    /** Invoke once before recording a frame; pending shaders never replace a working pack midway through its draws. */
    public synchronized boolean beginFrame(){
        if(ready==null)return false;Ready<R> candidate=ready;ready=null;
        if(stale(candidate.revision())){if(candidate.resource()!=null)retire.accept(candidate.resource());return false;}
        R previous=active;active=candidate.resource();status=new Status(candidate.name(),candidate.name(),false,"");
        if(previous!=null)retire.accept(previous);return true;
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;revision++;if(ready!=null&&ready.resource()!=null)retire.accept(ready.resource());ready=null;if(active!=null)retire.accept(active);active=null;status=new Status("","",false,"");
    }
}
