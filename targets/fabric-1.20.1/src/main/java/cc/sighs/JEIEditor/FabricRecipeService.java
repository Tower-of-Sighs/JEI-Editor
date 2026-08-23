package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;

import java.util.LinkedHashMap;
import java.util.Map;

final class FabricRecipeService {
    private FabricRecipeService() { }

    static void apply(ServerPlayer player, RecipePatch patch) {
        if (player.getServer() == null) {
            FabricNetwork.reply(player, false, "Server is unavailable");
            return;
        }
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (!RecipeEditorPolicy.load(server).canEdit(player, patch.recipeId())) {
            FabricNetwork.reply(player, false, "Permission or namespace policy denied");
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
        Recipe<?> recipe = id == null ? null : server.getRecipeManager().byKey(id).orElse(null);
        if (recipe == null) {
            FabricNetwork.reply(player, false, "Recipe does not exist");
            return;
        }
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (serializerId == null || !serializerId.toString().equals(patch.serializerId())) {
            FabricNetwork.reply(player, false, "Recipe serializer mismatch");
            return;
        }
        RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
        RecipePatch stored = data.patches().get(patch.recipeId());
        EditorModel storedModel = data.baseModel(patch.recipeId());
        if (stored != null && storedModel != null) {
            Map<String, String> fields = new LinkedHashMap<String, String>(stored.fields());
            fields.putAll(patch.fields());
            patch = new RecipePatch(patch.recipeId(), patch.serializerId(), storedModel.baseFingerprint(), fields);
        }
        if (!RecipeEditsApplier.canApply(server, data, patch)) {
            FabricNetwork.reply(player, false, "Recipe patch is stale or unsupported");
            return;
        }
        EditorModel model = RecipeEditorAdapters.createModel(id, recipe, server.registryAccess()).orElse(null);
        if (model == null || !RecipeEditCoordinator.tryBegin(server)) {
            FabricNetwork.reply(player, false, model == null ? "Recipe is read-only in the MVP" : "Another recipe reload is in progress");
            return;
        }
        RecipePatch accepted = patch;
        RecipePatch previous = data.put(accepted, model);
        RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) {
                data.restore(accepted.recipeId(), previous);
                RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) ->
                        server.execute(() -> {
                            RecipeEditCoordinator.finish(server);
                            if (rollbackError == null) data.audit(actor(player), "SAVE_ROLLBACK", accepted, previous);
                            FabricNetwork.reply(player, false, rollbackError == null
                                    ? "Recipe reload failed; change rolled back"
                                    : "Recipe reload failed; rollback also failed; check server log");
                        }));
            } else {
                RecipeEditCoordinator.finish(server);
                data.audit(actor(player), "SAVE", previous, accepted);
                FabricNetwork.reply(player, true, "Recipe saved");
            }
        }));
    }

    static void remove(ServerPlayer player, String recipeId) {
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server != null && !RecipeEditorPolicy.load(server).canEdit(player, recipeId)) {
            FabricNetwork.reply(player, false, "Permission or namespace policy denied");
            return;
        }
        RecipeEditsSavedData data = server == null ? null : RecipeEditsSavedData.get(server);
        RecipePatch previous = data == null ? null : data.patches().get(recipeId);
        EditorModel baseModel = data == null ? null : data.baseModel(recipeId);
        if (server == null || previous == null || baseModel == null) {
            FabricNetwork.reply(player, false, "No saved edit for recipe");
            return;
        }
        if (!RecipeEditCoordinator.tryBegin(server)) {
            FabricNetwork.reply(player, false, "Another recipe reload is in progress");
            return;
        }
        data.remove(recipeId);
        RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
            if (error != null) {
                data.restore(recipeId, previous, baseModel);
                RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) ->
                        server.execute(() -> {
                            RecipeEditCoordinator.finish(server);
                            if (rollbackError == null) data.audit(actor(player), "RESET_ROLLBACK", null, previous);
                            FabricNetwork.reply(player, false, rollbackError == null
                                    ? "Recipe reset failed; change restored"
                                    : "Recipe reset failed; restore also failed; check server log");
                        }));
            } else {
                RecipeEditCoordinator.finish(server);
                data.audit(actor(player), "RESET", previous, null);
                FabricNetwork.reply(player, true, "Recipe reset to original");
            }
        }));
    }
    private static String actor(ServerPlayer player) { return player.getUUID().toString() + "/" + player.getGameProfile().getName(); }
}
