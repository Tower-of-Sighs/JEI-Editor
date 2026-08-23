package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditBundle;
import cc.sighs.JEIEditor.editor.RecipeEditBundleCodec;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecipeEditorCommands {
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    private RecipeEditorCommands() { }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("jeieditor")
                        .then(Commands.literal("export").then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> exportFile(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal("import").then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> importFile(context.getSource(), StringArgumentType.getString(context, "name")))))));
    }

    private static int exportFile(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        if (server == null || !validName(name) || !RecipeEditorPolicy.load(server).canUseCommands(source)) { source.sendFailure(Component.literal("Invalid export name or permission denied")); return 0; }
        try {
            Path file = filePath(server, name); Files.createDirectories(file.getParent());
            List<RecipePatch> patches = new ArrayList<RecipePatch>(RecipeEditsSavedData.get(server).patches().values());
            Files.write(file, RecipeEditBundleCodec.encode(new RecipeEditBundle(patches)).getBytes(StandardCharsets.UTF_8));
            source.sendSuccess(() -> Component.literal("Exported " + patches.size() + " recipe edits to " + file.getFileName()), true); return 1;
        } catch (IOException | RuntimeException exception) { source.sendFailure(Component.literal("Export failed: " + message(exception))); return 0; }
    }

    private static int importFile(CommandSourceStack source, String name) {
        MinecraftServer server = source.getServer();
        if (server == null || !validName(name) || !RecipeEditorPolicy.load(server).canUseCommands(source)) { source.sendFailure(Component.literal("Invalid import name or permission denied")); return 0; }
        try {
            Path file = filePath(server, name);
            if (Files.notExists(file) || Files.size(file) > MAX_FILE_BYTES) throw new IOException("file is missing or too large");
            RecipeEditBundle bundle = RecipeEditBundleCodec.decode(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            RecipeEditsSavedData data = RecipeEditsSavedData.get(server); RecipeEditorPolicy policy = RecipeEditorPolicy.load(server);
            List<RecipePatch> imported = bundle.patches();
            for (RecipePatch patch : imported) {
                if (!policy.canEdit(source, patch.recipeId())) throw new IOException("permission or namespace policy denied: " + patch.recipeId());
                ResourceLocation id = ResourceLocation.tryParse(patch.recipeId()); Recipe<?> recipe = id == null ? null : server.getRecipeManager().byKey(id).orElse(null);
                if (recipe == null || !RecipeEditorAdapters.createModel(id, recipe, server.registryAccess()).isPresent()) throw new IOException("recipe is missing or read-only: " + patch.recipeId());
                if (!RecipeEditsApplier.canApply(server, data, patch)) throw new IOException("stale or invalid patch: " + patch.recipeId());
            }
            if (imported.isEmpty()) { source.sendSuccess(() -> Component.literal("Import contained no recipe edits"), false); return 1; }
            if (!RecipeEditCoordinator.tryBegin(server)) throw new IOException("another recipe reload is in progress");
            Map<String, RecipePatch> previous = new LinkedHashMap<String, RecipePatch>(); Map<String, EditorModel> previousModels = new HashMap<String, EditorModel>();
            try {
                for (RecipePatch patch : imported) {
                    previous.put(patch.recipeId(), data.patches().get(patch.recipeId())); previousModels.put(patch.recipeId(), data.baseModel(patch.recipeId()));
                    ResourceLocation id = ResourceLocation.tryParse(patch.recipeId()); Recipe<?> recipe = server.getRecipeManager().byKey(id).orElse(null);
                    data.put(patch, RecipeEditorAdapters.createModel(id, recipe, server.registryAccess()).get());
                }
            } catch (RuntimeException exception) {
                for (RecipePatch patch : imported) { RecipePatch oldPatch = previous.get(patch.recipeId()); if (oldPatch == null) data.remove(patch.recipeId()); else data.restore(patch.recipeId(), oldPatch, previousModels.get(patch.recipeId())); }
                RecipeEditCoordinator.finish(server);
                throw exception;
            }
            RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
                if (error == null) { RecipeEditCoordinator.finish(server); for (RecipePatch patch : imported) data.audit(actor(source), "IMPORT", previous.get(patch.recipeId()), patch); source.sendSuccess(() -> Component.literal("Imported " + imported.size() + " recipe edits"), true); return; }
                for (RecipePatch patch : imported) { RecipePatch oldPatch = previous.get(patch.recipeId()); if (oldPatch == null) data.remove(patch.recipeId()); else data.restore(patch.recipeId(), oldPatch, previousModels.get(patch.recipeId())); }
                RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) -> server.execute(() -> { RecipeEditCoordinator.finish(server); source.sendFailure(Component.literal(rollbackError == null ? "Import failed; batch rolled back" : "Import failed; rollback also failed")); }));
            }));
            return 1;
        } catch (IOException | RuntimeException exception) { source.sendFailure(Component.literal("Import failed: " + message(exception))); return 0; }
    }

    private static Path filePath(MinecraftServer server, String name) { return server.getServerDirectory().toPath().resolve("config").resolve("jeieditor").resolve("exports").resolve(name + ".json").normalize(); }
    private static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9._-]{1,64}") && !".".equals(name) && !"..".equals(name); }
    private static String actor(CommandSourceStack source) { return source.getEntity() == null ? "console" : source.getEntity().getUUID().toString() + "/" + source.getTextName(); }
    private static String message(Throwable exception) { return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
}
