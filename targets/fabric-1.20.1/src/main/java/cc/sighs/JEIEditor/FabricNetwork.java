package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

final class FabricNetwork {
    static final ResourceLocation EDIT = new ResourceLocation(JEIEditorFabric.MOD_ID, "edit_recipe");
    static final ResourceLocation DELETE = new ResourceLocation(JEIEditorFabric.MOD_ID, "delete_recipe_edit");
    static final ResourceLocation RESULT = new ResourceLocation(JEIEditorFabric.MOD_ID, "edit_recipe_result");

    private FabricNetwork() { }

    static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(EDIT, (server, player, handler, buffer, responseSender) -> {
            try {
                String recipeId = buffer.readUtf(256);
                String serializer = buffer.readUtf(256);
                String fingerprint = buffer.readUtf(128);
                int count = buffer.readVarInt();
                RecipeEditPayloadRules.requireFieldCount(count);
                Map<String, String> fields = new LinkedHashMap<String, String>();
                for (int i = 0; i < count; i++) {
                    String key = buffer.readUtf(64);
                    String value = buffer.readUtf(512);
                    RecipeEditPayloadRules.requireField(key, value);
                    fields.put(key, value);
                }
                RecipePatch patch = new RecipePatch(recipeId, serializer, fingerprint, fields);
                server.execute(() -> FabricRecipeService.apply(player, patch));
            } catch (RuntimeException exception) {
                server.execute(() -> reply(player, false, invalidPayloadMessage(exception)));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(DELETE, (server, player, handler, buffer, responseSender) -> {
            try {
                String recipeId = buffer.readUtf(256);
                server.execute(() -> FabricRecipeService.remove(player, recipeId));
            } catch (RuntimeException exception) {
                server.execute(() -> reply(player, false, invalidPayloadMessage(exception)));
            }
        });
    }

    static void reply(net.minecraft.server.level.ServerPlayer player, boolean success, String message) {
        FriendlyByteBuf buffer = PacketByteBufs.create();
        buffer.writeBoolean(success);
        buffer.writeUtf(message, 256);
        ServerPlayNetworking.send(player, RESULT, buffer);
    }

    private static String invalidPayloadMessage(RuntimeException exception) {
        String detail = exception.getMessage();
        return detail == null || detail.isEmpty()
                ? "Invalid recipe edit payload"
                : "Invalid recipe edit payload: " + detail;
    }
}
