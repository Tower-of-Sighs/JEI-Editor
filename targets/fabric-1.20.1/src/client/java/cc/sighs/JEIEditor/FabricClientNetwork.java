package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Map;

final class FabricClientNetwork {
    private FabricClientNetwork() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(FabricNetwork.RESULT, (client, handler, buffer, responseSender) -> {
            boolean success = buffer.readBoolean();
            String message = buffer.readUtf(256);
            client.execute(() -> FabricClientState.result(success, message));
        });
    }

    static void send(RecipePatch patch) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        buffer.writeUtf(patch.recipeId(), 256);
        buffer.writeUtf(patch.serializerId(), 256);
        buffer.writeUtf(patch.baseFingerprint(), 128);
        RecipeEditPayloadRules.requireFieldCount(patch.fields().size());
        buffer.writeVarInt(patch.fields().size());
        for (Map.Entry<String, String> field : patch.fields().entrySet()) {
            RecipeEditPayloadRules.requireField(field.getKey(), field.getValue());
            buffer.writeUtf(field.getKey(), 64);
            buffer.writeUtf(field.getValue(), 512);
        }
        ClientPlayNetworking.send(FabricNetwork.EDIT, buffer);
    }

    static void sendDelete(String recipeId) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        buffer.writeUtf(recipeId, 256);
        ClientPlayNetworking.send(FabricNetwork.DELETE, buffer);
    }
}
