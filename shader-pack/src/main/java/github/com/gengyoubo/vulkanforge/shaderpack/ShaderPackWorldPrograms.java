package github.com.gengyoubo.vulkanforge.shaderpack;

import java.util.*;

/** Which gbuffers program replaces a vanilla core shader, by the world render phase in which it is drawn. */
public final class ShaderPackWorldPrograms {
    /** Phases follow LevelRenderer order; deferred passes run between BLOCK_ENTITIES and TRANSLUCENT. SHADOW is the shadow-map pass. */
    public enum Phase {SKY,TERRAIN,ENTITIES,BLOCK_ENTITIES,TRANSLUCENT,PARTICLES,WEATHER,HAND,SHADOW;public boolean translucent(){return this!=SHADOW&&ordinal()>=TRANSLUCENT.ordinal();}}
    private static final Set<String> TERRAIN_TRANSLUCENT=Set.of("rendertype_translucent","rendertype_translucent_moving_block","rendertype_translucent_no_crumbling","rendertype_tripwire");
    private static final Set<String> GLINT=Set.of("rendertype_armor_glint","rendertype_armor_entity_glint","rendertype_glint","rendertype_glint_translucent","rendertype_glint_direct","rendertype_entity_glint","rendertype_entity_glint_direct");
    private ShaderPackWorldPrograms(){ }
    /** The first program asked for; empty keeps the vanilla shader (GUI, outlines, portals, water mask). */
    public static Optional<String> requested(String shader,Phase phase){
        if(phase==Phase.SHADOW)return Optional.ofNullable(switch(shader){
            case "rendertype_solid"->"shadow_solid";case "rendertype_cutout","rendertype_cutout_mipped"->"shadow_cutout";
            default->TERRAIN_TRANSLUCENT.contains(shader)?"shadow_water":shader.startsWith("rendertype_entity_")?"shadow_entities":null;
        });
        String program=switch(shader){
            case "position","position_color"->phase==Phase.SKY?"gbuffers_skybasic":"gbuffers_basic";
            case "position_tex","position_tex_color"->phase==Phase.SKY?"gbuffers_skytextured":"gbuffers_textured";
            case "position_color_tex","position_color_lightmap","position_color_tex_lightmap","position_tex_lightmap_color"->"gbuffers_textured_lit";
            case "position_tex_color_normal"->"gbuffers_clouds";
            case "particle"->phase==Phase.WEATHER?"gbuffers_weather":phase.translucent()?"gbuffers_particles_translucent":"gbuffers_particles";
            case "rendertype_solid"->"gbuffers_terrain_solid";
            case "rendertype_cutout","rendertype_cutout_mipped"->"gbuffers_terrain_cutout";
            case "rendertype_lines"->"gbuffers_line";
            case "rendertype_crumbling"->"gbuffers_damagedblock";
            case "rendertype_beacon_beam"->"gbuffers_beaconbeam";
            case "rendertype_lightning"->"gbuffers_lightning";
            case "rendertype_eyes"->"gbuffers_spidereyes";
            case "rendertype_energy_swirl"->"gbuffers_entities_glowing";
            default->null;
        };
        if(program!=null)return Optional.of(program);
        if(TERRAIN_TRANSLUCENT.contains(shader))return Optional.of(phase==Phase.HAND?"gbuffers_hand_water":"gbuffers_water");
        if(GLINT.contains(shader))return Optional.of("gbuffers_armor_glint");
        if(shader.startsWith("rendertype_entity_")||shader.startsWith("rendertype_text")&&!shader.contains("background")||shader.equals("rendertype_armor_cutout_no_cull")||shader.equals("rendertype_item_entity_translucent_cull")){
            boolean translucent=shader.contains("translucent");
            return Optional.of(switch(phase){case HAND->translucent?"gbuffers_hand_water":"gbuffers_hand";case BLOCK_ENTITIES->translucent?"gbuffers_block_translucent":"gbuffers_block";default->translucent?"gbuffers_entities_translucent":"gbuffers_entities";});
        }
        return Optional.empty();
    }
    /** Walks the fallback hierarchy until an enabled program exists. */
    public static Optional<String> resolve(String shader,Phase phase,Set<String> enabled){
        for(String name=requested(shader,phase).orElse(null);name!=null;name=ShaderPack.fallback(name).orElse(null))if(enabled.contains(name))return Optional.of(name);
        return Optional.empty();
    }
}
