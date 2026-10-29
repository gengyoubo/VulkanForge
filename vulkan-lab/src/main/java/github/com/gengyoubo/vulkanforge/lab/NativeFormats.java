package github.com.gengyoubo.vulkanforge.lab;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.opengl.GL30.*;

/** Numeric format translation only. GL constants are inlined; no GL entry point is loaded. */
final class NativeFormats {
    private NativeFormats(){ }
    static int color(int format){return switch(format){
        case 36759,36758->VK_FORMAT_R8G8B8A8_SNORM;
        case 36763,36762->VK_FORMAT_R16G16B16A16_SNORM;
        case 36756->VK_FORMAT_R8_SNORM;case 36757->VK_FORMAT_R8G8_SNORM;
        case 36760->VK_FORMAT_R16_SNORM;case 36761->VK_FORMAT_R16G16_SNORM;
        case GL_RGBA,GL_RGBA8,GL_RGB,GL_RGB8->VK_FORMAT_R8G8B8A8_UNORM;
        case GL_RGBA16->VK_FORMAT_R16G16B16A16_UNORM;
        case GL_RGBA16F,GL_RGB16F->VK_FORMAT_R16G16B16A16_SFLOAT;
        case GL_RGBA32F,GL_RGB32F->VK_FORMAT_R32G32B32A32_SFLOAT;
        case GL_RG8->VK_FORMAT_R8G8_UNORM;case GL_RG16->VK_FORMAT_R16G16_UNORM;
        case GL_RG16F->VK_FORMAT_R16G16_SFLOAT;case GL_RG32F->VK_FORMAT_R32G32_SFLOAT;
        case GL_R8->VK_FORMAT_R8_UNORM;case GL_R16->VK_FORMAT_R16_UNORM;
        case GL_R16F->VK_FORMAT_R16_SFLOAT;case GL_R32F->VK_FORMAT_R32_SFLOAT;
        case GL_R11F_G11F_B10F->VK_FORMAT_B10G11R11_UFLOAT_PACK32;
        case GL_RGB10_A2->VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        case GL_R8UI->VK_FORMAT_R8_UINT;case GL_R16UI->VK_FORMAT_R16_UINT;case GL_R32UI->VK_FORMAT_R32_UINT;
        case GL_RG8UI->VK_FORMAT_R8G8_UINT;case GL_RG16UI->VK_FORMAT_R16G16_UINT;case GL_RG32UI->VK_FORMAT_R32G32_UINT;
        case GL_RGBA8UI->VK_FORMAT_R8G8B8A8_UINT;case GL_RGBA16UI->VK_FORMAT_R16G16B16A16_UINT;case GL_RGBA32UI->VK_FORMAT_R32G32B32A32_UINT;
        case GL_R8I->VK_FORMAT_R8_SINT;case GL_R16I->VK_FORMAT_R16_SINT;case GL_R32I->VK_FORMAT_R32_SINT;
        case GL_RGBA8I->VK_FORMAT_R8G8B8A8_SINT;case GL_RGBA16I->VK_FORMAT_R16G16B16A16_SINT;case GL_RGBA32I->VK_FORMAT_R32G32B32A32_SINT;
        default->throw new UnsupportedOperationException("Native render texture format "+format);
    };}
}
