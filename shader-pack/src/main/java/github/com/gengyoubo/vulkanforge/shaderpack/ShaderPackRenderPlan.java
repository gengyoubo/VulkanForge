package github.com.gengyoubo.vulkanforge.shaderpack;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Backend-independent render graph built from the pack's active declarations and effective options. */
public final class ShaderPackRenderPlan {
    public record Color(int index,String format,boolean clear,float[] clearColor,float scaleX,float scaleY,boolean fogClear){
        public Color(int index,String format,boolean clear,float[] clearColor,float scaleX,float scaleY){this(index,format,clear,clearColor,scaleX,scaleY,false);}
        public Color{clearColor=clearColor.clone();}
        @Override public float[] clearColor(){return clearColor.clone();}
        public int width(int width){return Math.max(1,Math.round(width*scaleX));}
        public int height(int height){return Math.max(1,Math.round(height*scaleY));}
        public boolean integer(){return format.matches(".*\\dU?I");}
    }
    public record Blend(String source,String destination,String sourceAlpha,String destinationAlpha,boolean enabled){
        public Blend(String source,String destination,String sourceAlpha,String destinationAlpha){this(source,destination,sourceAlpha,destinationAlpha,true);}
    }
    /** {@code alphaTest.<program>}: fragments failing {@code alpha <function> reference} are discarded. */
    public record AlphaTest(String function,float reference){ }
    /** {@code alternate} lists buffers sampled from their alternate image when the pass runs; outputs are written to the other image. */
    public record Pass(ShaderPack.Program source,int[] outputs,Set<Integer> mipmaps,Set<Integer> flips,Set<Integer> alternate,Blend blend,Set<Integer> blendOff,float[] viewport,AlphaTest alphaTest){
        public Pass{outputs=outputs.clone();mipmaps=Set.copyOf(mipmaps);flips=Set.copyOf(flips);alternate=Set.copyOf(alternate);blendOff=Set.copyOf(blendOff);viewport=viewport.clone();}
        @Override public int[] outputs(){return outputs.clone();}
        @Override public float[] viewport(){return viewport.clone();}
        Pass scheduled(Set<Integer> state){return new Pass(source,outputs,mipmaps,flips,state,blend,blendOff,viewport,alphaTest);}
        /** Custom texture stage of this pass; the final pass shares the composite stage. */
        public String stage(){return switch(source.phase()){case PREPARE->"prepare";case DEFERRED->"deferred";case COMPOSITE,FINAL->"composite";case GBUFFER->"gbuffers";case SHADOW->"shadow";case SHADOW_COMPOSITE->"shadowcomp";case BEGIN->"begin";case SETUP->"setup";};}
    }
    /**
     * {@code flipped} is the alternate-image set after the last pass; persistent buffers in it are copied back for the next frame.
     * World programs never flip: opaque geometry draws into the images current after prepare, translucent geometry into those current after deferred.
     */
    public record Dimension(Map<Integer,Color> colors,List<Pass> shadow,List<Pass> prepare,Map<String,Pass> world,List<Pass> deferred,List<Pass> composite,Pass last,int shadowResolution,float shadowDistance,float sunRotation,int noiseResolution,Set<Integer> flipped,Set<Integer> opaque,Set<Integer> translucent,Map<Integer,Color> shadowColors,boolean[] shadowFiltering,float shadowInterval){
        public Dimension{colors=Map.copyOf(colors);shadow=List.copyOf(shadow);prepare=List.copyOf(prepare);world=Map.copyOf(world);deferred=List.copyOf(deferred);composite=List.copyOf(composite);flipped=Set.copyOf(flipped);opaque=Set.copyOf(opaque);translucent=Set.copyOf(translucent);shadowColors=Map.copyOf(shadowColors);shadowFiltering=shadowFiltering.clone();}
        /** Hardware depth comparison for shadowtex0/shadowtex1 ({@code shadowHardwareFiltering[01]}). */
        @Override public boolean[] shadowFiltering(){return shadowFiltering.clone();}
        /** Shadow-map programs by name (shadow, shadow_solid, ...); shadowcomp passes are not included. */
        public Map<String,Pass> shadowPrograms(){Map<String,Pass> result=new HashMap<>();for(Pass pass:shadow)if(pass.source().phase()==ShaderPack.Phase.SHADOW)result.put(pass.source().name(),pass);return result;}
        /** Fullscreen programs in execution order: prepare, deferred, composite, final. */
        public List<Pass> chain(){List<Pass> result=new ArrayList<>();prepare.stream().filter(pass->pass.source().phase()==ShaderPack.Phase.PREPARE).forEach(result::add);result.addAll(deferred);result.addAll(composite);if(last!=null)result.add(last);return List.copyOf(result);}
    }
    private final Map<String,Dimension> dimensions=new LinkedHashMap<>();
    private final Map<String,Map<String,String>> textures;
    private final String noise;
    private final ShaderPackConfiguration configuration;
    private final ShaderPackExpressions expressions;
    private static final Map<String,Integer> ALIASES=Map.of("gcolor",0,"gdepth",1,"gnormal",2,"composite",3,"gaux1",4,"gaux2",5,"gaux3",6,"gaux4",7);
    private static final Pattern SAMPLERS=Pattern.compile("\\buniform\\s+[iu]?sampler2D\\s+(\\w+(?:\\s*,\\s*\\w+)*)\\s*;");
    private static final Pattern DECLARATION=Pattern.compile("\\bconst\\s+(int|float|bool|vec4)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Set<String> FORMATS=Set.of("RGBA","RGBA8","RGB","RGB8","RGBA16","RGBA16F","RGB16F","RGBA32F","RGB32F","R8","R16","RG8","RG16","R16F","R32F","RG16F","RG32F","R11F_G11F_B10F","RGB10_A2","RGB8_SNORM","RGB16_SNORM","RGBA8_SNORM","RGBA16_SNORM","R8I","R8UI","R16I","R16UI","R32I","R32UI","RG8I","RG8UI","RG16I","RG16UI","RG32I","RG32UI","RGBA8I","RGBA8UI","RGBA16I","RGBA16UI","RGBA32I","RGBA32UI");
    private static final Set<String> ALPHA_FUNCTIONS=Set.of("NEVER","LESS","EQUAL","LEQUAL","GREATER","NOTEQUAL","GEQUAL","ALWAYS");
    private static final Set<String> FACTORS=Set.of("ZERO","ONE","SRC_COLOR","ONE_MINUS_SRC_COLOR","DST_COLOR","ONE_MINUS_DST_COLOR","SRC_ALPHA","ONE_MINUS_SRC_ALPHA","DST_ALPHA","ONE_MINUS_DST_ALPHA","SRC_ALPHA_SATURATE");
    public ShaderPackRenderPlan(ShaderPack pack,Map<String,String> selected)throws IOException{
        this(pack,new ShaderPackConfiguration(pack,selected));
    }
    public ShaderPackRenderPlan(ShaderPack pack,ShaderPackConfiguration configuration){
        this.configuration=configuration;expressions=new ShaderPackExpressions(configuration.properties());
        Map<String,Map<String,String>> declaredTextures=new TreeMap<>();String noisePath=null;
        for(var entry:configuration.properties().entrySet()){
            String key=entry.getKey(),value=entry.getValue().strip();
            if(key.equals("texture.noise"))noisePath=value;
            else if(key.startsWith("texture.")){String[] parts=key.split("\\.",3);if(parts.length!=3||parts[2].isEmpty())throw new ShaderPackException("Invalid custom texture key: "+key);declaredTextures.computeIfAbsent(parts[1],unused->new TreeMap<>()).put(parts[2],value);}
            else if(key.startsWith("customTexture.")&&key.length()>14)declaredTextures.computeIfAbsent("*",unused->new TreeMap<>()).put(key.substring(14),value);
        }
        Map<String,Map<String,String>> frozen=new TreeMap<>();declaredTextures.forEach((stage,samplers)->frozen.put(stage,Map.copyOf(samplers)));textures=Map.copyOf(frozen);noise=noisePath;
        for(String name:new TreeSet<>(pack.dimensions())){
            Map<Integer,MutableColor> colors=new TreeMap<>();Set<Integer> usedColors=new HashSet<>();Map<String,Pass> world=new LinkedHashMap<>();List<Pass> shadows=new ArrayList<>(),prepare=new ArrayList<>(),deferred=new ArrayList<>(),composite=new ArrayList<>();Pass last=null;
            int shadowResolution=1024,noiseResolution=256;float shadowDistance=128,sunRotation=0,shadowInterval=2;boolean[] filtering=new boolean[2];
            // Shadow color images default to white, so an unwritten texel means "fully lit".
            Map<Integer,MutableColor> shadowColors=new TreeMap<>();
            for(var program:new TreeMap<>(pack.programs(name)).values()){
                if(!configuration.enabled(name,program.name()))continue;
                Set<Integer> mips=new TreeSet<>();
                for(var source:program.stages().values()){
                    // OptiFine pack metadata is deliberately placed inside block comments in many existing packs.
                    String active=configuration.active(source).replaceAll("//[^\\r\\n]*"," ");Matcher declarations=DECLARATION.matcher(active);
                    Matcher samplers=SAMPLERS.matcher(active);while(samplers.find())for(String sampler:samplers.group(1).split(",")){int index=colorIndex(sampler.strip());if(index>=0){usedColors.add(index);colors.computeIfAbsent(index,MutableColor::new);}int shadowIndex=shadowColorIndex(sampler.strip());if(shadowIndex>=0)shadowColors.computeIfAbsent(shadowIndex,ShaderPackRenderPlan::shadowColor);}
                    while(declarations.find()){
                        String field=declarations.group(2),value=declarations.group(3).strip();
                        if(field.equals("shadowMapResolution")){shadowResolution=integer(value,field);continue;}if(field.equals("shadowDistance")){shadowDistance=decimal(value,field);continue;}if(field.equals("sunPathRotation")){sunRotation=decimal(value,field);continue;}if(field.equals("noiseTextureResolution")){noiseResolution=integer(value,field);continue;}
                        if(field.equals("shadowIntervalSize")){shadowInterval=decimal(value,field);continue;}
                        if(field.equals("shadowHardwareFiltering")){filtering[0]=filtering[1]=bool(value,field);continue;}if(field.matches("shadowHardwareFiltering[01]")){filtering[field.charAt(field.length()-1)-'0']=bool(value,field);continue;}
                        Matcher shadowField=Pattern.compile("(shadowcolor[01]?)(Format|Clear|ClearColor)").matcher(field);
                        if(shadowField.matches()){int index=shadowColorIndex(shadowField.group(1));MutableColor target=shadowColors.computeIfAbsent(index,ShaderPackRenderPlan::shadowColor);switch(shadowField.group(2)){case "Format"->{if(!FORMATS.contains(value))throw new ShaderPackException(program.name()+": unknown shadow color format "+value);target.format=value;}case "Clear"->target.clear=bool(value,field);default->target.color=vector(value,field);}continue;}
                        for(String suffix:List.of("Format","Clear","ClearColor","MipmapEnabled"))if(field.endsWith(suffix)){
                            int index=colorIndex(field.substring(0,field.length()-suffix.length()));if(index<0)continue;MutableColor target=colors.computeIfAbsent(index,MutableColor::new);
                            switch(suffix){case "Format"->{if(!FORMATS.contains(value))throw new ShaderPackException(program.name()+": unknown color format "+value);target.format=value;}case "Clear"->target.clear=bool(value,field);case "ClearColor"->{target.color=vector(value,field);target.colored=true;}case "MipmapEnabled"->{if(bool(value,field))mips.add(index);}default->throw new AssertionError();}
                        }
                    }
                }
                int[] outputs=ShaderPack.drawBuffers(program,configuration);Set<Integer> flips=new TreeSet<>();
                if(outputs.length>8)throw new ShaderPackException(program.name()+": too many simultaneous color outputs");
                // Shadow-map programs write shadowcolorN; every other program writes colortexN.
                if(program.phase()==ShaderPack.Phase.SHADOW)for(int target:outputs){if(target<0||target>1)throw new ShaderPackException(program.name()+": shadow color target out of range: "+target);shadowColors.computeIfAbsent(target,ShaderPackRenderPlan::shadowColor);}
                else for(int target:outputs){if(target<0||target>=32)throw new ShaderPackException(program.name()+": color target out of range: "+target);usedColors.add(target);colors.computeIfAbsent(target,MutableColor::new);}
                // Written buffers flip unless disabled; an explicit flip=true also flips a buffer the pass did not write.
                for(int target:outputs)flips.add(target);
                for(var entry:configuration.properties().entrySet())if(entry.getKey().startsWith("flip."+program.name()+".")){int index=colorIndex(entry.getKey().substring(program.name().length()+6));if(index<0)throw new ShaderPackException("Invalid flip directive: "+entry.getKey());if(bool(entry.getValue(),entry.getKey()))flips.add(index);else flips.remove(index);}
                Pass pass=new Pass(program,outputs,mips,flips,Set.of(),blend(program.name()),blendOff(program.name(),outputs),viewport(program.name()),alphaTest(program.name()));
                switch(program.phase()){
                    case GBUFFER->world.put(program.name(),pass);
                    case SHADOW,SHADOW_COMPOSITE->shadows.add(pass);
                    case DEFERRED->deferred.add(pass);
                    case COMPOSITE->composite.add(pass);
                    case FINAL->last=pass;
                    default->prepare.add(pass);
                }
            }
            if(shadowResolution<1||shadowResolution>16384||shadowDistance<=0||!Float.isFinite(shadowDistance))throw new ShaderPackException("Invalid shadow dimensions");
            if(noiseResolution<1||noiseResolution>8192)throw new ShaderPackException("Invalid noiseTextureResolution: "+noiseResolution);
            if(!(shadowInterval>=0)||!Float.isFinite(shadowInterval))throw new ShaderPackException("Invalid shadowIntervalSize: "+shadowInterval);
            Map<Integer,Color> shadowImages=new TreeMap<>();shadowColors.forEach((index,color)->shadowImages.put(index,new Color(index,color.format,color.clear,color.color,1,1)));
            Map<Integer,Color> immutable=new TreeMap<>();colors.forEach((index,color)->{if(!usedColors.contains(index))return;String scale=configuration.properties().get("size.buffer.colortex"+index);if(scale!=null){String[] values=scale.strip().split("\\s+");if(values.length!=2)throw new ShaderPackException("Invalid colortex size: "+scale);color.x=decimal(values[0],"size");color.y=decimal(values[1],"size");if(color.x<=0||color.y<=0||color.x>4||color.y>4)throw new ShaderPackException("Unsupported relative colortex size: "+scale);}if(index==1&&!color.colored)color.color=new float[]{1,1,1,1};immutable.put(index,new Color(index,color.format,color.clear,color.color,color.x,color.y,index==0&&!color.colored));});
            Comparator<Pass> order=Comparator.comparingInt((Pass p)->p.source().order()).thenComparing(p->p.source().name());prepare.sort(order);shadows.sort(order);deferred.sort(order);composite.sort(order);
            // Every fullscreen pass starts from the main images; the schedule is static, so it is resolved once here.
            Set<Integer> state=new TreeSet<>();
            prepare=schedule(prepare,state,true);Set<Integer> opaque=Set.copyOf(state);deferred=schedule(deferred,state,false);Set<Integer> translucent=Set.copyOf(state);composite=schedule(composite,state,false);if(last!=null)last=last.scheduled(state);
            dimensions.put(name,new Dimension(immutable,shadows,prepare,world,deferred,composite,last,shadowResolution,shadowDistance,sunRotation,noiseResolution,state,opaque,translucent,shadowImages,filtering,shadowInterval));
        }
    }
    private static List<Pass> schedule(List<Pass> passes,Set<Integer> state,boolean prepareOnly){
        List<Pass> result=new ArrayList<>();
        for(Pass pass:passes){
            if(prepareOnly&&pass.source().phase()!=ShaderPack.Phase.PREPARE){result.add(pass);continue;}
            result.add(pass.scheduled(state));for(int target:pass.flips())if(!state.remove(target))state.add(target);
        }return result;
    }
    public ShaderPackConfiguration configuration(){return configuration;}
    public ShaderPackExpressions expressions(){return expressions;}
    public Map<String,Dimension> dimensions(){return Map.copyOf(dimensions);}
    /** Custom textures by stage ({@code prepare}, {@code deferred}, {@code composite}, ...; {@code *} for customTexture.*), then sampler name. */
    public Map<String,Map<String,String>> textures(){return textures;}
    public Optional<String> noise(){return Optional.ofNullable(noise);}
    public Optional<String> texture(String stage,String sampler){String value=textures.getOrDefault(stage,Map.of()).get(sampler);if(value==null)value=textures.getOrDefault("*",Map.of()).get(sampler);return Optional.ofNullable(value);}
    public Dimension dimension(String name){Dimension result=dimensions.getOrDefault(name,dimensions.get(""));if(result==null)throw new ShaderPackException("No render graph for "+name);return result;}
    public static int colorIndex(String name){if(ALIASES.containsKey(name))return ALIASES.get(name);if(name.matches("colortex\\d+")){int index=Integer.parseInt(name.substring(8));if(index<32)return index;throw new ShaderPackException("Color target out of range: "+name);}return -1;}
    private Blend blend(String program){
        String value=configuration.properties().get("blend."+program);if(value==null)return null;if(value.strip().equals("off"))return new Blend("ONE","ZERO","ONE","ZERO",false);
        String[] factors=value.strip().split("\\s+");if(factors.length!=4||!FACTORS.containsAll(Arrays.asList(factors)))throw new ShaderPackException("Invalid blend."+program+": "+value);
        return new Blend(factors[0],factors[1],factors[2],factors[3]);
    }
    private Set<Integer> blendOff(String program,int[] outputs){
        Set<Integer> result=new TreeSet<>();String prefix="blend."+program+".";
        for(var entry:configuration.properties().entrySet())if(entry.getKey().startsWith(prefix)){
            int index=colorIndex(entry.getKey().substring(prefix.length()));if(index<0)throw new ShaderPackException("Invalid per-buffer blend key: "+entry.getKey());
            if(!entry.getValue().strip().equals("off"))throw new ShaderPackException(entry.getKey()+": per-buffer blend factors are not implemented, only off");result.add(index);
        }
        result.removeIf(index->Arrays.stream(outputs).noneMatch(output->output==index));return result;
    }
    private AlphaTest alphaTest(String program){
        String value=configuration.properties().get("alphaTest."+program);if(value==null)return null;value=value.strip();if(value.equals("off")||value.equals("false"))return null;
        String[] parts=value.split("\s+");if(parts.length!=2||!ALPHA_FUNCTIONS.contains(parts[0]))throw new ShaderPackException("Invalid alphaTest."+program+": "+value);
        return new AlphaTest(parts[0],decimal(parts[1],"alphaTest."+program));
    }
    private float[] viewport(String program){
        String value=configuration.properties().get("scale."+program);if(value==null)return new float[]{1,0,0};
        String[] parts=value.strip().split("\\s+");if(parts.length!=1&&parts.length!=3)throw new ShaderPackException("Invalid scale."+program+": "+value);
        float[] result={decimal(parts[0],"scale."+program),parts.length==3?decimal(parts[1],"scale."+program):0,parts.length==3?decimal(parts[2],"scale."+program):0};
        if(!(result[0]>0&&result[0]<=1)||result[1]<0||result[2]<0||result[1]>=1||result[2]>=1)throw new ShaderPackException("Unsupported scale."+program+": "+value);return result;
    }
    /** {@code shadowcolor}/{@code shadowcolor0}/{@code shadowcolor1}; -1 for any other name. */
    public static int shadowColorIndex(String name){return name.equals("shadowcolor")?0:name.matches("shadowcolor[01]")?name.charAt(11)-'0':-1;}
    private static MutableColor shadowColor(int index){MutableColor color=new MutableColor(index);color.color=new float[]{1,1,1,1};return color;}
    private static final class MutableColor {String format="RGBA8";boolean clear=true,colored;float[] color={0,0,0,0};float x=1,y=1;MutableColor(int ignored){}}
    private static boolean bool(String value,String field){value=value.strip();if(!value.equals("true")&&!value.equals("false"))throw new ShaderPackException(field+": expected a boolean, got "+value);return Boolean.parseBoolean(value);}
    private static int integer(String value,String field){try{return Integer.parseInt(value);}catch(NumberFormatException error){throw new ShaderPackException(field+": invalid integer "+value,error);}}
    private static float decimal(String value,String field){try{return Float.parseFloat(value.replaceFirst("[fF]$",""));}catch(NumberFormatException error){throw new ShaderPackException(field+": invalid number "+value,error);}}
    private static float[] vector(String value,String field){Matcher vector=Pattern.compile("vec4\\s*\\(([^)]+)\\)").matcher(value);if(!vector.matches())throw new ShaderPackException(field+": invalid clear color "+value);String[] parts=vector.group(1).split(",");if(parts.length!=1&&parts.length!=4)throw new ShaderPackException(field+": clear color needs 1 or 4 components");float[] result=new float[4];for(int i=0;i<4;i++)result[i]=decimal(parts[parts.length==1?0:i].strip(),field);return result;}
}
