package cc.sighs.JEIEditor;

import net.fabricmc.api.ClientModInitializer;

public final class JEIEditorFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricClientNetwork.register();
        FabricClientEvents.register();
    }
}
