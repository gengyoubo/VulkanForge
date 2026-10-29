package github.com.gengyoubo.vulkanforge.shaderpack;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Transactional hot reload. GPU compilation, commit and retirement run on the render executor. */
public final class ShaderPackController<R extends AutoCloseable> implements AutoCloseable {
    public record Status(String active,String requested,boolean loading,String error){ }
    @FunctionalInterface public interface Compiler<R>{R compile(ShaderPack pack)throws Exception;}
    @FunctionalInterface public interface Preparer<P>{P prepare(ShaderPack pack)throws Exception;}
    @FunctionalInterface public interface PreparedCompiler<P,R>{R compile(P prepared)throws Exception;}
    /** Optional GPU work budget. The old pack stays active until preparation finishes successfully. */
    public interface Incremental {boolean prepareFrame()throws Exception;}
    @FunctionalInterface private interface Compilation<R>{R compile()throws Exception;}
    private record Ready<R>(long revision,String name,R resource){ }
    private record Waiting<R>(long revision,String name,Compilation<R> compilation){ }
    private final Executor io,render;
    private final Preparer<Compilation<R>> preparation;
    private final Consumer<R> retire;
    private volatile Status status=new Status("","",false,"");
    private long revision;
    private volatile boolean closed;
    private R active;
    private Ready<R> ready;
    private Waiting<R> waiting;
    private BooleanSupplier gpuAllowed=()->true;
    public synchronized void gpuAllowed(BooleanSupplier allowed){gpuAllowed=Objects.requireNonNull(allowed);}
    public ShaderPackController(Executor io,Executor render,Compiler<R> compiler,Consumer<R> retire){this(io,render,pack->pack,compiler::compile,retire);}
    /** Expensive CPU work stays on IO; the prepared compiler owns GPU resources exclusively on the render executor. */
    public <P> ShaderPackController(Executor io,Executor render,Preparer<P> preparer,PreparedCompiler<P,R> compiler,Consumer<R> retire){this.io=io;this.render=render;this.preparation=pack->{P prepared=preparer.prepare(pack);return ()->compiler.compile(prepared);};this.retire=retire;}
    public Status status(){return status;}
    public R active(){return active;}
    public void request(Path path){
        final long token;final String name=path==null?"":path.getFileName().toString();
        synchronized(this){if(closed)throw new IllegalStateException("Shader pack controller closed");token=++revision;status=new Status(status.active(),name,true,"");}
        if(path==null){render.execute(()->prepare(token,name,null));return;}
        CompletableFuture.supplyAsync(()->{try{if(stale(token))return null;var pack=ShaderPack.load(path);if(stale(token))return null;return preparation.prepare(pack);}catch(Exception error){throw new CompletionException(error);}},io)
                .whenComplete((compilation,failure)->render.execute(()->{
                    if(stale(token))return;
                    if(failure!=null){failed(token,failure);return;}
                    synchronized(this){waiting=new Waiting<>(token,name,compilation);if(gpuAllowed.getAsBoolean())compileWaiting();else status=new Status(status.active(),name,false,"");}
                }));
    }
    private void compileWaiting(){
        if(waiting==null)return;var task=waiting;waiting=null;if(stale(task.revision()))return;
        status=new Status(status.active(),task.name(),true,"");R candidate=null;
        try{candidate=Objects.requireNonNull(task.compilation().compile(),"Shader pack compiler returned no resources");prepare(task.revision(),task.name(),candidate);}
        catch(Throwable error){if(candidate!=null)retire.accept(candidate);failed(task.revision(),error);}
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
        if(waiting!=null&&gpuAllowed.getAsBoolean())compileWaiting();
        if(ready==null)return false;Ready<R> candidate=ready;
        if(stale(candidate.revision())){ready=null;if(candidate.resource()!=null)retire.accept(candidate.resource());return false;}
        if(candidate.resource()!=null&&!gpuAllowed.getAsBoolean())return false;
        if(candidate.resource() instanceof Incremental incremental)try{if(!incremental.prepareFrame())return false;}catch(Throwable error){ready=null;retire.accept(candidate.resource());failed(candidate.revision(),error);return false;}
        ready=null;
        R previous=active;active=candidate.resource();status=new Status(candidate.name(),candidate.name(),false,"");
        if(previous!=null)retire.accept(previous);return true;
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;revision++;waiting=null;if(ready!=null&&ready.resource()!=null)retire.accept(ready.resource());ready=null;if(active!=null)retire.accept(active);active=null;status=new Status("","",false,"");
    }
}
