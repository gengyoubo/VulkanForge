package github.com.gengyoubo.vulkanforge.shaderpack;

/** A pack diagnostic suitable for the selection screen and the detailed loading report. */
public final class ShaderPackException extends RuntimeException {
    public ShaderPackException(String message){super(message);}
    public ShaderPackException(String message,Throwable cause){super(message,cause);}
}
