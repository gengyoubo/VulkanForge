package github.com.gengyoubo.vulkanforge.experiment;

import net.minecraftforge.fml.common.Mod;

/** Bootstrap only for the separately built, property-controlled experiment jar. */
@Mod("vf_ae2_controller_probe")
public final class Ae2ControllerExperimentMod {
    public Ae2ControllerExperimentMod() {
        System.out.println("VF_AE2_CONTROLLER_EXPERIMENT enabled=" + Boolean.getBoolean("vf.ae2.controllerFast"));
    }
}
