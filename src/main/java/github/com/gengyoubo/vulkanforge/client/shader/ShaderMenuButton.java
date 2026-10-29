package github.com.gengyoubo.vulkanforge.client.shader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Translucent controls drawn without another mod's GUI textures. */
final class ShaderMenuButton extends Button {
    ShaderMenuButton(int x,int y,int width,Component text,OnPress action){super(x,y,width,20,text,action,DEFAULT_NARRATION);}
    @Override public void renderWidget(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        panel(graphics,getX(),getY(),width,height,isHoveredOrFocused(),active);
        var font=Minecraft.getInstance().font;String text=getMessage().getString();
        if(font.width(text)>width-8)text=font.plainSubstrByWidth(text,width-8-font.width("…"))+"…";
        graphics.drawCenteredString(font,text,getX()+width/2,getY()+(height-8)/2,active?0xffffff:0x999999);
    }
    static void panel(GuiGraphics graphics,int x,int y,int width,int height,boolean focused,boolean enabled){
        int border=focused&&enabled?0xffd0d0d0:0xff777777;
        graphics.fill(x,y,x+width,y+1,border);graphics.fill(x,y+height-1,x+width,y+height,border);
        graphics.fill(x,y+1,x+1,y+height-1,border);graphics.fill(x+width-1,y+1,x+width,y+height-1,border);
        graphics.fill(x+1,y+1,x+width-1,y+height-1,focused&&enabled?0xa0303030:0x90000000);
    }
}
