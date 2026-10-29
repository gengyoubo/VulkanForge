package github.com.gengyoubo.vulkanforge.client.shader;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Standard pack profiles and option values are applied through the same transactional reload. */
final class ShaderPackOptionsScreen extends Screen {
    private final Screen parent;
    private final Map<String,String> values=new HashMap<>(ShaderPackRuntime.values());
    private int page;
    ShaderPackOptionsScreen(Screen parent){super(Component.translatable("vulkanforge.shaders.options"));this.parent=parent;}
    @Override protected void init(){
        var model=ShaderPackRuntime.options();if(model==null){onClose();return;}int left=width/2-155;
        List<String> profiles=new ArrayList<>();profiles.add("Custom");profiles.addAll(model.profiles());
        addRenderableWidget(CycleButton.<String>builder(Component::literal).withValues(profiles).withInitialValue("Custom").create(left,32,310,20,Component.translatable("vulkanforge.shaders.profile"),(button,profile)->{if(!profile.equals("Custom")){values.clear();values.putAll(model.profile(profile));rebuildWidgets();}}));
        var options=model.options().values().stream().sorted(Comparator.comparing(option->option.name())).toList();int perPage=Math.max(2,(height-110)/24*2);
        int start=page*perPage;for(int i=start;i<Math.min(options.size(),start+perPage);i++){
            var option=options.get(i);int index=i-start;addRenderableWidget(CycleButton.<String>builder(Component::literal).withValues(option.values()).withInitialValue(values.getOrDefault(option.name(),option.defaultValue())).create(left+(index%2)*158,58+(index/2)*24,152,20,Component.literal(option.name()),(button,value)->values.put(option.name(),value)));
        }
        if(options.size()>perPage){addRenderableWidget(Button.builder(Component.literal("◀"),button->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(left,height-46,40,20).build());addRenderableWidget(Button.builder(Component.literal("▶"),button->{page=Math.min((options.size()-1)/perPage,page+1);rebuildWidgets();}).bounds(left+270,height-46,40,20).build());}
        addRenderableWidget(Button.builder(Component.translatable("vulkanforge.shaders.apply"),button->{ShaderPackRuntime.request(ShaderPackRuntime.selectedPack(),values);onClose();}).bounds(left,height-24,150,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),button->onClose()).bounds(left+160,height-24,150,20).build());
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,12,0xffffff);super.render(graphics,mouseX,mouseY,partialTick);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
