package cc.sighs.JEIEditor;

import net.minecraftforge.fml.common.Mod;

@Mod(JEIEditorForge.MOD_ID)
public final class JEIEditorForge {
    public static final String MOD_ID = "jeieditor";

    public JEIEditorForge() {
        ForgeNetwork.register();
        RecipeEditorCommands.register();
    }
}
