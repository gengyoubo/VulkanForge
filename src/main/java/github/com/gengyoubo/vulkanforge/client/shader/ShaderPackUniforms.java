package github.com.gengyoubo.vulkanforge.client.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Frame globals use actual camera/projection state, shared by either graphics backend. */
final class ShaderPackUniforms {
    private final Matrix4f projection=new Matrix4f(),view=new Matrix4f(),previousProjection=new Matrix4f(),previousView=new Matrix4f();
    private final Vector3d previousCamera=new Vector3d();
    private long started=System.nanoTime(),last=started;
    private int frame;
    private boolean matrices;
    void matrices(Matrix4f projection,Matrix4f view){this.projection.set(projection);this.view.set(view);if(!matrices){previousProjection.set(projection);previousView.set(view);matrices=true;}}
    static Set<String> supported(){return Set.of("viewWidth","viewHeight","viewSize","aspectRatio","pixelSize","frameCounter","frameTime","frameTimeCounter","worldTime","worldDay","moonPhase","rainStrength","wetness","thunderStrength","sunAngle","shadowAngle","near","far","eyeAltitude","isEyeInWater","cameraPosition","previousCameraPosition","gbufferProjection","gbufferProjectionInverse","gbufferPreviousProjection","gbufferModelView","gbufferModelViewInverse","gbufferPreviousModelView","sunPosition","moonPosition","shadowLightPosition","upPosition","fogColor","fogStart","fogEnd","skyColor","screenSize");}
    Map<String,float[]> next(float partialTick,int width,int height){
        Minecraft mc=Minecraft.getInstance();if(mc.level==null)throw new IllegalStateException("Shader pack world globals require a level");
        long now=System.nanoTime();float seconds=(now-last)/1e9f;last=now;
        if(!matrices)matrices(RenderSystem.getProjectionMatrix(),RenderSystem.getModelViewMatrix());
        var camera=mc.gameRenderer.getMainCamera().getPosition();if(frame==0)previousCamera.set(camera.x,camera.y,camera.z);
        Map<String,float[]> values=new HashMap<>();
        put(values,"viewWidth",width);put(values,"viewHeight",height);put(values,"viewSize",width,height);put(values,"screenSize",width,height);put(values,"aspectRatio",(float)width/height);put(values,"pixelSize",1f/width,1f/height);
        put(values,"frameCounter",frame++);put(values,"frameTime",seconds);put(values,"frameTimeCounter",(float)((now-started)/1e9%3600));
        long worldTime=mc.level.getDayTime();put(values,"worldTime",worldTime%24000);put(values,"worldDay",worldTime/24000);put(values,"moonPhase",mc.level.getMoonPhase());
        put(values,"rainStrength",mc.level.getRainLevel(partialTick));put(values,"wetness",mc.level.getRainLevel(partialTick));put(values,"thunderStrength",mc.level.getThunderLevel(partialTick));
        float angle=mc.level.getTimeOfDay(partialTick);put(values,"sunAngle",angle);put(values,"shadowAngle",angle<.5f?angle:angle-.5f);
        put(values,"near",projection.m32()/(projection.m22()-1));put(values,"far",projection.m32()/(projection.m22()+1));put(values,"eyeAltitude",(float)camera.y);
        String fluid=mc.gameRenderer.getMainCamera().getFluidInCamera().name();put(values,"isEyeInWater",fluid.equals("WATER")?1:fluid.equals("LAVA")?2:0);
        put(values,"cameraPosition",(float)camera.x,(float)camera.y,(float)camera.z);put(values,"previousCameraPosition",(float)previousCamera.x,(float)previousCamera.y,(float)previousCamera.z);
        values.put("gbufferProjection",projection.get(new float[16]));values.put("gbufferProjectionInverse",new Matrix4f(projection).invert().get(new float[16]));values.put("gbufferPreviousProjection",previousProjection.get(new float[16]));
        values.put("gbufferModelView",view.get(new float[16]));values.put("gbufferModelViewInverse",new Matrix4f(view).invert().get(new float[16]));values.put("gbufferPreviousModelView",previousView.get(new float[16]));
        Vector3f sun=new Vector3f((float)-Math.sin(angle*Math.PI*2)*100,(float)Math.cos(angle*Math.PI*2)*100,0);view.transformDirection(sun);put(values,"sunPosition",sun.x,sun.y,sun.z);put(values,"moonPosition",-sun.x,-sun.y,-sun.z);put(values,"shadowLightPosition",sun.x*(angle<.5?1:-1),sun.y*(angle<.5?1:-1),sun.z*(angle<.5?1:-1));
        Vector3f up=view.transformDirection(new Vector3f(0,100,0));put(values,"upPosition",up.x,up.y,up.z);
        float[] fog=RenderSystem.getShaderFogColor();put(values,"fogColor",fog[0],fog[1],fog[2]);put(values,"fogStart",RenderSystem.getShaderFogStart());put(values,"fogEnd",RenderSystem.getShaderFogEnd());
        var sky=mc.level.getSkyColor(camera,partialTick);put(values,"skyColor",(float)sky.x,(float)sky.y,(float)sky.z);
        previousCamera.set(camera.x,camera.y,camera.z);previousProjection.set(projection);previousView.set(view);return values;
    }
    private static void put(Map<String,float[]> values,String name,float...data){values.put(name,data);}
}
