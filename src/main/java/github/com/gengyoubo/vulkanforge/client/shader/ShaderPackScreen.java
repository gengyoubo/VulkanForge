package github.com.gengyoubo.vulkanforge.client.shader;

import java.util.*;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** In-world selection and live reload; an unsuccessful request leaves the active pack intact. */
public final class ShaderPackScreen extends Screen {
    private final Screen parent;
    private PackList list;
    private Button options;
    public ShaderPackScreen(Screen parent){super(Component.translatable("vulkanforge.shaders.title"));this.parent=parent;}
    @Override protected void init(){
        list=addRenderableWidget(new PackList(minecraft,width,height,44,height-100));list.add("");for(String pack:ShaderPackRuntime.available())list.add(pack);
        String selected=ShaderPackRuntime.status().active();for(PackEntry entry:list.items())if(entry.name.equals(selected))list.setSelected(entry);
        int left=width/2-155;
        addRenderableWidget(Button.builder(Component.translatable("vulkanforge.shaders.apply"),button->{PackEntry entry=list.getSelected();if(entry!=null)ShaderPackRuntime.select(entry.name);}).bounds(left,height-46,100,20).build());
        addRenderableWidget(Button.builder(Component.translatable("vulkanforge.shaders.reload"),button->ShaderPackRuntime.reload()).bounds(left+105,height-46,100,20).build());
        options=addRenderableWidget(Button.builder(Component.translatable("vulkanforge.shaders.options"),button->minecraft.setScreen(new ShaderPackOptionsScreen(this))).bounds(left+210,height-46,100,20).build());
        addRenderableWidget(Button.builder(Component.translatable("vulkanforge.shaders.folder"),button->Util.getPlatform().openFile(ShaderPackRuntime.directory().toFile())).bounds(left,height-24,150,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),button->onClose()).bounds(left+160,height-24,150,20).build());
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        renderBackground(graphics);super.render(graphics,mouseX,mouseY,partialTick);var status=ShaderPackRuntime.status();options.active=ShaderPackRuntime.options()!=null&&!status.loading();
        graphics.drawCenteredString(font,title,width/2,12,0xffffff);
        graphics.drawCenteredString(font,Component.translatable(status.loading()?"vulkanforge.shaders.loading":"vulkanforge.shaders.active",status.loading()?status.requested():status.active().isEmpty()?Component.translatable("vulkanforge.shaders.off").getString():status.active()),width/2,28,status.loading()?0xffcc66:0xaaffaa);
        if(!status.error().isEmpty()){int y=height-92;for(var line:font.split(Component.literal(status.error()),width-24)){graphics.drawString(font,line,12,y,0xff7777);if((y+=10)>height-57)break;}}
    }
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    private final class PackList extends ObjectSelectionList<PackEntry>{
        PackList(Minecraft minecraft,int width,int height,int top,int bottom){super(minecraft,width,height,top,bottom,24);}
        void add(String name){addEntry(new PackEntry(name));}
        List<PackEntry> items(){return children();}
        @Override public int getRowWidth(){return width-44;}
        @Override protected int getScrollbarPosition(){return width-12;}
    }
    private final class PackEntry extends ObjectSelectionList.Entry<PackEntry>{
        final String name;PackEntry(String name){this.name=name;}
        @Override public Component getNarration(){return name.isEmpty()?Component.translatable("vulkanforge.shaders.off"):Component.literal(name);}
        @Override public void render(GuiGraphics graphics,int index,int top,int left,int width,int height,int mouseX,int mouseY,boolean hovered,float partialTick){graphics.drawString(font,getNarration(),left+4,top+5,0xffffff);}
        @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return false;list.setSelected(this);return true;}
    }
}
