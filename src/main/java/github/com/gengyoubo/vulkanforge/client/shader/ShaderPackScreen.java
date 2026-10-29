package github.com.gengyoubo.vulkanforge.client.shader;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/** Selection is a draft until Apply/Done; only successful compilation changes the running renderer. */
public final class ShaderPackScreen extends Screen {
    private enum Kind { TOGGLE,DOWNLOAD,PACK,HINT }
    private final Screen parent;
    private final ShaderMenuBackground background=new ShaderMenuBackground();
    private PackList list;
    private Button options,apply,done;
    private List<String> packs=List.of();
    private String selected="",pending="",notice="";
    private boolean initialized,enabled,waiting,closeAfterApply,settingsAfterApply,importing;
    private int refreshTicks;
    public ShaderPackScreen(Screen parent){super(Component.translatable("vulkanforge.shaders.title"));this.parent=parent;}
    @Override protected void init(){
        if(!initialized){selected=ShaderPackRuntime.selectedPack();enabled=ShaderPackRuntime.configuredEnabled();initialized=true;}
        packs=ShaderPackRuntime.available();
        list=addRenderableWidget(new PackList(minecraft,width,height,34,height-58));refreshEntries();
        int span=Math.min(308,width-24),left=(width-span)/2,gap=4,half=(span-gap)/2,third=(span-2*gap)/3;
        addRenderableWidget(new ShaderMenuButton(left,height-51,half,Component.translatable("vulkanforge.shaders.folder"),button->CompletableFuture.runAsync(()->Util.getPlatform().openFile(ShaderPackRuntime.directory().toFile()))));
        options=addRenderableWidget(new ShaderMenuButton(left+half+gap,height-51,span-half-gap,Component.translatable("vulkanforge.shaders.options"),button->applyChanges(false,true)));
        addRenderableWidget(new ShaderMenuButton(left,height-27,third,Component.translatable("gui.cancel"),button->minecraft.setScreen(parent)));
        apply=addRenderableWidget(new ShaderMenuButton(left+third+gap,height-27,third,Component.translatable("vulkanforge.shaders.apply"),button->applyChanges(false,false)));
        done=addRenderableWidget(new ShaderMenuButton(left+2*(third+gap),height-27,span-2*(third+gap),Component.translatable("gui.done"),button->onClose()));
        updateButtons();
    }
    private void refreshEntries(){
        double scroll=list.getScrollAmount();list.reset();list.add(Kind.TOGGLE,"");
        if(packs.isEmpty())list.add(Kind.DOWNLOAD,"");
        for(String name:packs)list.add(Kind.PACK,name);
        list.add(Kind.HINT,"");list.setScrollAmount(scroll);
    }
    private void applyChanges(boolean close,boolean settings){
        if(importing||waiting||ShaderPackRuntime.status().loading()&&!ShaderPackRuntime.deferred())return;
        if(enabled&&!packs.contains(selected)){notice=Component.translatable("vulkanforge.shaders.choose").getString();return;}
        String desired=enabled?selected:"";
        closeAfterApply=close;settingsAfterApply=settings;
        var status=ShaderPackRuntime.status();
        if(desired.equals(ShaderPackRuntime.displayedPack())&&status.error().isEmpty()&&(!settings||ShaderPackRuntime.options()!=null)){finishApply();return;}
        pending=desired;waiting=true;notice="";ShaderPackRuntime.select(desired);updateButtons();
    }
    private void finishApply(){
        waiting=false;
        if(settingsAfterApply&&ShaderPackRuntime.options()!=null){settingsAfterApply=false;minecraft.setScreen(new ShaderPackOptionsScreen(this));}
        else if(closeAfterApply){closeAfterApply=false;minecraft.setScreen(parent);}
    }
    private void updateButtons(){
        boolean busy=waiting||importing||ShaderPackRuntime.status().loading()&&!ShaderPackRuntime.deferred();
        if(options!=null)options.active=!busy&&enabled&&packs.contains(selected);
        if(apply!=null)apply.active=!busy;if(done!=null)done.active=!busy;
    }
    @Override public void tick(){
        var status=ShaderPackRuntime.status();
        if(waiting&&(ShaderPackRuntime.deferred()||!status.loading())){
            if(status.error().isEmpty()&&(status.active().equals(pending)||ShaderPackRuntime.deferred()&&status.requested().equals(pending))){if(!settingsAfterApply||ShaderPackRuntime.options()!=null)finishApply();}
            else{waiting=false;closeAfterApply=settingsAfterApply=false;}
        }
        if(!importing&&++refreshTicks>=20){refreshTicks=0;List<String> current=ShaderPackRuntime.available();if(!current.equals(packs)){packs=current;refreshEntries();}}
        updateButtons();
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        graphics.flush();background.render(partialTick);
        graphics.fill(0,0,width,height,0x70000000);
        graphics.fill(0,34,width,height-58,0x30000000);
        graphics.fill(0,34,width,35,0xff777777);graphics.fill(0,height-59,width,height-58,0xff777777);
        super.render(graphics,mouseX,mouseY,partialTick);
        graphics.drawCenteredString(font,title,width/2,8,0xffffff);
        var status=ShaderPackRuntime.status();
        Component subtitle=Component.translatable("vulkanforge.shaders.select").withStyle(ChatFormatting.GRAY,ChatFormatting.ITALIC);
        if(ShaderPackRuntime.deferred())subtitle=Component.translatable("vulkanforge.shaders.deferred",status.requested()).withStyle(ChatFormatting.GRAY);
        else if(status.loading())subtitle=Component.translatable("vulkanforge.shaders.loading",status.requested()).withStyle(ChatFormatting.YELLOW);
        else if(importing)subtitle=Component.translatable("vulkanforge.shaders.importing");
        else if(!notice.isEmpty())subtitle=Component.literal(notice).withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(font,shorten(subtitle,width-20),width/2,21,0xffffff);
        if(!status.error().isEmpty()){
            int panelWidth=Math.min(308,width-24),x=(width-panelWidth)/2,y=height-99;
            graphics.fill(x-3,y-3,x+panelWidth+3,height-60,0xe0000000);
            int lines=0;for(var line:font.split(Component.literal(status.error()),panelWidth)){graphics.drawString(font,line,x,y+10*lines++,0xff7777);if(lines==3)break;}
        }
        graphics.drawString(font,"VulkanForge",2,height-10,0xaaaaaa);
    }
    private Component shorten(Component text,int width){return font.width(text)<=width?text:Component.literal(font.plainSubstrByWidth(text.getString(),Math.max(0,width-font.width("…")))+"…").setStyle(text.getStyle());}
    @Override public void onClose(){applyChanges(true,false);}
    @Override public void removed(){background.close();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_R&&hasControlDown()){ShaderPackRuntime.reload();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void onFilesDrop(List<Path> files){
        if(importing||waiting)return;importing=true;notice="";updateButtons();
        ShaderPackRuntime.importPacks(files).whenComplete((names,error)->minecraft.execute(()->{
            importing=false;packs=ShaderPackRuntime.available();refreshEntries();
            if(error!=null){Throwable cause=error.getCause()==null?error:error.getCause();notice=Objects.requireNonNullElse(cause.getMessage(),cause.toString());}
            else{if(!names.isEmpty()){selected=names.get(0);enabled=true;}notice=Component.translatable("vulkanforge.shaders.imported",names.size()).getString();}
            updateButtons();
        }));
    }
    private final class PackList extends ObjectSelectionList<PackEntry>{
        PackList(Minecraft mc,int width,int height,int top,int bottom){super(mc,width,height,top,bottom,20);setRenderBackground(false);setRenderTopAndBottom(false);setRenderSelection(false);}
        void reset(){clearEntries();}
        void add(Kind kind,String name){addEntry(new PackEntry(kind,name));}
        @Override public int getRowWidth(){return Math.min(308,width-50);}
        @Override protected int getScrollbarPosition(){return width/2+getRowWidth()/2+6;}
        @Override public boolean keyPressed(int key,int scan,int modifiers){if((key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_SPACE)&&getSelected()!=null)return getSelected().activate();return super.keyPressed(key,scan,modifiers);}
    }
    private final class PackEntry extends ObjectSelectionList.Entry<PackEntry>{
        final Kind kind;final String name;
        PackEntry(Kind kind,String name){this.kind=kind;this.name=name;}
        @Override public Component getNarration(){return label();}
        private Component label(){return switch(kind){
            case TOGGLE->Component.translatable(packs.isEmpty()?"vulkanforge.shaders.none":enabled?"vulkanforge.shaders.enabled":"vulkanforge.shaders.disabled");
            case DOWNLOAD->Component.translatable("vulkanforge.shaders.download");
            case HINT->Component.translatable("vulkanforge.shaders.drop").withStyle(ChatFormatting.GRAY,ChatFormatting.ITALIC);
            case PACK->Component.literal(name);
        };}
        @Override public void render(GuiGraphics graphics,int index,int top,int left,int width,int height,int mouseX,int mouseY,boolean hovered,float partialTick){
            boolean button=kind==Kind.TOGGLE||kind==Kind.DOWNLOAD;
            boolean chosen=kind==Kind.PACK&&name.equals(selected);
            if(button||hovered&&kind==Kind.PACK||chosen)ShaderMenuButton.panel(graphics,left-2,top-2,width,height+2,hovered||chosen,kind!=Kind.TOGGLE||!packs.isEmpty());
            int color=kind==Kind.TOGGLE&&packs.isEmpty()?0x999999:kind==Kind.PACK&&!enabled?0xaaaaaa:kind==Kind.PACK&&name.equals(ShaderPackRuntime.displayedPack())?0xffef75:0xffffff;
            graphics.drawCenteredString(font,shorten(label(),width-8),left-2+width/2,top+(height-9)/2,color);
        }
        private boolean activate(){
            if(kind==Kind.HINT||waiting||importing)return false;
            if(kind==Kind.TOGGLE){if(packs.isEmpty())return false;enabled=!enabled;if(enabled&&!packs.contains(selected))selected=packs.get(0);}
            else if(kind==Kind.DOWNLOAD)minecraft.setScreen(new ConfirmLinkScreen(accepted->{minecraft.setScreen(ShaderPackScreen.this);if(accepted)Util.getPlatform().openUri("https://modrinth.com/shaders");},"https://modrinth.com/shaders",true));
            else{selected=name;enabled=true;}
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK,1));updateButtons();return true;
        }
        @Override public boolean mouseClicked(double x,double y,int button){if(button!=0)return false;list.setSelected(this);return activate();}
    }
}
