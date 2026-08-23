package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Converts accepted simple crafting patches into a world datapack and reloads recipes. */
final class RecipeEditsApplier {
    private static final String PACK_ID_SUFFIX = "/jeieditor-generated";
    private static final String PACK_DESCRIPTION = "JEI Editor recipe overrides";

    private RecipeEditsApplier() {
    }

    static CompletableFuture<Void> apply(MinecraftServer server, RecipeEditsSavedData data) {
        try {
            Path packRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                    .resolve("jeieditor-generated");
            writePack(server, data, packRoot);

            PackRepository repository = server.getPackRepository();
            repository.reload();
            Optional<String> packId = repository.getAvailableIds().stream()
                    .filter(id -> id.endsWith(PACK_ID_SUFFIX))
                    .findFirst();
            if (!packId.isPresent()) {
                throw new IOException("generated datapack was not discovered by the server");
            }

            List<String> selected = new ArrayList<String>(repository.getSelectedIds());
            if (!selected.contains(packId.get())) {
                selected.add(packId.get());
            }
            repository.setSelected(selected);
            return server.reloadResources(selected);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    static boolean canApply(MinecraftServer server, RecipePatch patch) {
        return canApply(server, null, patch);
    }

    static boolean canApply(MinecraftServer server, RecipeEditsSavedData data, RecipePatch patch) {
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return false;
        }
        Recipe<?> recipe = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (recipe == null) {
            return false;
        }
        EditorModel baseModel = data == null ? null : data.baseModel(patch.recipeId());
        if (baseModel != null) {
            RecipePatch currentPatch = data.patches().get(patch.recipeId());
            return createRecipeJson(recipeId, recipe, patch, server.registryAccess(), baseModel, currentPatch).isPresent();
        }
        return RecipeEditorAdapters.createModel(recipeId, recipe, server.registryAccess())
                .flatMap(model -> createRecipeJson(recipeId, recipe, patch, server.registryAccess(), model)).isPresent();
    }

    private static void writePack(MinecraftServer server, RecipeEditsSavedData data, Path packRoot) throws IOException {
        Path stagingRoot = packRoot.resolveSibling(packRoot.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        try {
            writePackContents(server, data, stagingRoot);
            replaceGeneratedPack(stagingRoot, packRoot);
        } catch (IOException | RuntimeException exception) {
            try {
                clearGeneratedPack(stagingRoot);
            } catch (IOException | RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    private static void writePackContents(MinecraftServer server, RecipeEditsSavedData data, Path packRoot) throws IOException {
        Files.createDirectories(packRoot.resolve("data"));
        String metadata = "{\"pack\":{\"pack_format\":15,\"description\":\"" + PACK_DESCRIPTION + "\"}}";
        Files.write(packRoot.resolve("pack.mcmeta"), metadata.getBytes(StandardCharsets.UTF_8));

        for (RecipePatch patch : data.patches().values()) {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid stored recipe id: " + patch.recipeId());
            }
            Recipe<?> recipe = server.getRecipeManager().byKey(recipeId).orElse(null);
            if (recipe == null) {
                throw new IOException("stored recipe no longer exists: " + patch.recipeId());
            }
            EditorModel baseModel = data.baseModel(patch.recipeId());
            Optional<JsonObject> json = baseModel == null
                    ? createRecipeJson(recipeId, recipe, patch, server.registryAccess())
                    : createRecipeJson(recipeId, recipe, patch, server.registryAccess(), baseModel);
            if (!json.isPresent()) {
                throw new IOException("stored recipe is no longer supported: " + patch.recipeId());
            }

            Path recipePath = packRoot.resolve("data").resolve(recipeId.getNamespace())
                    .resolve("recipes").resolve(recipeId.getPath() + ".json");
            Files.createDirectories(recipePath.getParent());
            Files.write(recipePath, json.get().toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void replaceGeneratedPack(Path stagingRoot, Path packRoot) throws IOException {
        Path backupRoot = packRoot.resolveSibling(packRoot.getFileName().toString() + ".backup-" + UUID.randomUUID());
        boolean movedOldPack = false;
        try {
            if (Files.exists(packRoot)) {
                Files.move(packRoot, backupRoot);
                movedOldPack = true;
            }
            Files.move(stagingRoot, packRoot);
            if (movedOldPack) {
                clearGeneratedPack(backupRoot);
            }
        } catch (IOException | RuntimeException exception) {
            if (movedOldPack && !Files.exists(packRoot) && Files.exists(backupRoot)) {
                try {
                    Files.move(backupRoot, packRoot);
                } catch (IOException | RuntimeException restoreException) {
                    exception.addSuppressed(restoreException);
                }
            }
            throw exception;
        }
    }

    private static void clearGeneratedPack(Path packRoot) throws IOException {
        if (!Files.exists(packRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(packRoot)) {
            paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new PackCleanupException(exception);
                }
            });
        } catch (PackCleanupException exception) {
            throw exception.cause;
        }
    }

    private static final class PackCleanupException extends RuntimeException {
        private final IOException cause;

        private PackCleanupException(IOException cause) {
            this.cause = cause;
        }
    }

    private static Optional<JsonObject> createRecipeJson(ResourceLocation recipeId, Recipe<?> recipe, RecipePatch patch,
                                                          net.minecraft.core.RegistryAccess registries) {
        Optional<EditorModel> model = RecipeEditorAdapters.createModel(recipeId, recipe, registries);
        return model.flatMap(value -> createRecipeJson(recipeId, recipe, patch, registries, value));
    }

    private static Optional<JsonObject> createRecipeJson(ResourceLocation recipeId, Recipe<?> recipe, RecipePatch patch,
                                                          net.minecraft.core.RegistryAccess registries, EditorModel model) {
        if (model == null || !model.baseFingerprint().equals(patch.baseFingerprint())
                || !model.serializerId().equals(patch.serializerId())
                || !validatePatch(model, patch)) {
            return Optional.empty();
        }

        return createRecipeJsonContent(recipe, patch, model);
    }

    private static Optional<JsonObject> createRecipeJson(ResourceLocation recipeId, Recipe<?> recipe, RecipePatch patch,
                                                          net.minecraft.core.RegistryAccess registries, EditorModel model,
                                                          RecipePatch currentPatch) {
        if (model == null || !model.baseFingerprint().equals(patch.baseFingerprint())
                || !model.serializerId().equals(patch.serializerId())
                || !validatePatch(model, patch)
                || !currentRecipeMatchesExpected(recipeId, recipe, registries, model, currentPatch)) {
            return Optional.empty();
        }

        return createRecipeJsonContent(recipe, patch, model);
    }

    private static Optional<JsonObject> createRecipeJsonContent(Recipe<?> recipe, RecipePatch patch,
                                                                 EditorModel model) {

        List<EditorIngredient> inputs = new ArrayList<EditorIngredient>();
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            EditorIngredient ingredient = patchedIngredient(slot, patch.fields());
            inputs.add(ingredient);
        }
        EditorSlot outputSlot = model.slots().stream()
                .filter(slot -> "output".equals(slot.role()))
                .findFirst()
                .orElse(null);
        if (outputSlot == null || outputSlot.ingredient() == null) {
            return Optional.empty();
        }
        EditorIngredient output = patchedIngredient(outputSlot, patch.fields());
        if (output == null) {
            return Optional.empty();
        }

        JsonObject json = new JsonObject();
        ResourceLocation serializerId = ResourceLocation.tryParse(patch.serializerId());
        if (serializerId == null) {
            return Optional.empty();
        }
        json.addProperty("type", serializerId.toString());
        if (recipe instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) recipe;
            if (!shaped.getGroup().isEmpty()) {
                json.addProperty("group", shaped.getGroup());
            }
            json.addProperty("category", shaped.category().getSerializedName());
            if (!shaped.showNotification()) {
                json.addProperty("show_notification", false);
            }
            json.add("pattern", shapedPattern(shaped.getWidth(), shaped.getHeight(), inputs));
            json.add("key", shapedKey(inputs));
        } else if (recipe instanceof ShapelessRecipe) {
            ShapelessRecipe shapeless = (ShapelessRecipe) recipe;
            if (!shapeless.getGroup().isEmpty()) {
                json.addProperty("group", shapeless.getGroup());
            }
            json.addProperty("category", shapeless.category().getSerializedName());
            JsonArray ingredients = new JsonArray();
            for (EditorIngredient ingredient : inputs) {
                if (ingredient == null) {
                    return Optional.empty();
                }
                ingredients.add(ingredientJson(ingredient));
            }
            json.add("ingredients", ingredients);
        } else if (recipe instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe) {
            if (inputs.size() != 1 || inputs.get(0) == null) return Optional.empty();
            json.add("ingredient", ingredientJson(inputs.get(0)));
            json.addProperty("experience", cookingExperience(model, patch));
            json.addProperty("cookingtime", cookingTime(model, patch));
            if (output.count() != 1) return Optional.empty();
            json.addProperty("result", output.itemId());
        } else {
            return Optional.empty();
        }
        if (!(recipe instanceof net.minecraft.world.item.crafting.AbstractCookingRecipe)) {
            json.add("result", resultJson(output));
        }
        return Optional.of(json);
    }

    private static boolean currentRecipeMatchesExpected(ResourceLocation recipeId, Recipe<?> recipe, net.minecraft.core.RegistryAccess registries,
                                                         EditorModel baseModel, RecipePatch currentPatch) {
        Optional<EditorModel> current = RecipeEditorAdapters.createModel(recipeId, recipe, registries);
        if (!current.isPresent()) {
            return false;
        }
        if (current.get().baseFingerprint().equals(baseModel.baseFingerprint())) {
            return true;
        }
        if (currentPatch == null) {
            return false;
        }
        if (current.get().slots().size() != baseModel.slots().size()) {
            return false;
        }
        Map<String, EditorSlot> currentSlots = new HashMap<String, EditorSlot>();
        for (EditorSlot slot : current.get().slots()) {
            currentSlots.put(slot.key(), slot);
        }
        for (EditorSlot baseSlot : baseModel.slots()) {
            EditorSlot currentSlot = currentSlots.get(baseSlot.key());
            if (currentSlot == null || !currentSlot.role().equals(baseSlot.role())) {
                return false;
            }
            EditorIngredient expected = patchedIngredient(baseSlot, currentPatch.fields());
            if (!java.util.Objects.equals(currentSlot.ingredient(), expected)) {
                return false;
            }
        }
        for (Map.Entry<String, String> property : baseModel.properties().entrySet()) {
            String expected = currentPatch.fields().get("recipe." + property.getKey());
            if (expected == null) expected = property.getValue();
            if (!java.util.Objects.equals(current.get().properties().get(property.getKey()), expected)) {
                return false;
            }
        }
        return current.get().serializerId().equals(baseModel.serializerId());
    }

    private static boolean validatePatch(EditorModel model, RecipePatch patch) {
        if (patch.fields().isEmpty()) {
            return false;
        }
        Map<String, EditorSlot> slots = new HashMap<String, EditorSlot>();
        for (EditorSlot slot : model.slots()) {
            slots.put(slot.key(), slot);
        }
        for (Map.Entry<String, String> field : patch.fields().entrySet()) {
            String key = field.getKey();
            int separator = key.lastIndexOf('.');
            if (separator <= 0 || separator == key.length() - 1) {
                return false;
            }
            String property = key.substring(separator + 1);
            if (key.startsWith("recipe.") && CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                if ("experience".equals(property)) {
                    try { float value = Float.parseFloat(field.getValue()); if (!Float.isFinite(value) || value < 0.0F || value > 1000.0F) return false; }
                    catch (NumberFormatException exception) { return false; }
                } else if ("cooking_time".equals(property)) {
                    try { int value = Integer.parseInt(field.getValue()); if (value < 1 || value > 1000000) return false; }
                    catch (NumberFormatException exception) { return false; }
                } else return false;
                continue;
            }
            EditorSlot slot = slots.get(key.substring(0, separator));
            if (slot == null || (!"item".equals(property) && !"count".equals(property))) {
                return false;
            }
            if ("output".equals(slot.role()) && "item".equals(property)) {
                return false;
            }
            if ("output".equals(slot.role()) && "count".equals(property)
                    && CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())
                    && !"1".equals(field.getValue())) {
                return false;
            }
            if ("item".equals(property)) {
                ResourceLocation itemId = ResourceLocation.tryParse(field.getValue());
                if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                    return false;
                }
            } else {
                int count;
                try {
                    count = Integer.parseInt(field.getValue());
                } catch (NumberFormatException exception) {
                    return false;
                }
                if (count < 1 || count > 64) {
                    if (count != 0 || !"input".equals(slot.role())
                            || !"minecraft:air".equals(patch.fields().get(slot.key() + ".item"))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static float cookingExperience(EditorModel model, RecipePatch patch) { String value = patch.fields().get("recipe.experience"); if (value == null) value = model.properties().get("experience"); return Float.parseFloat(value); }
    private static int cookingTime(EditorModel model, RecipePatch patch) { String value = patch.fields().get("recipe.cooking_time"); if (value == null) value = model.properties().get("cooking_time"); return Integer.parseInt(value); }

    private static EditorIngredient patchedIngredient(EditorSlot slot, Map<String, String> fields) {
        EditorIngredient original = slot.ingredient();
        String itemId = fields.get(slot.key() + ".item");
        String count = fields.get(slot.key() + ".count");
        if (itemId == null && count == null) {
            return original;
        }
        if ("minecraft:air".equals(itemId) || "0".equals(count)) {
            return null;
        }
        if (itemId == null && original == null) {
            return null;
        }
        if (itemId == null) {
            itemId = original.itemId();
        }
        int parsedCount;
        try {
            parsedCount = count == null ? original.count() : Integer.parseInt(count);
            return new EditorIngredient(itemId, parsedCount);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static JsonArray shapedPattern(int width, int height, List<EditorIngredient> inputs) {
        JsonArray pattern = new JsonArray();
        for (int y = 0; y < height; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                row.append(index < inputs.size() && inputs.get(index) != null ? (char) ('a' + index) : ' ');
            }
            pattern.add(row.toString());
        }
        return pattern;
    }

    private static JsonObject shapedKey(List<EditorIngredient> inputs) {
        JsonObject key = new JsonObject();
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i) != null) {
                key.add(String.valueOf((char) ('a' + i)), ingredientJson(inputs.get(i)));
            }
        }
        return key;
    }

    private static JsonObject ingredientJson(EditorIngredient ingredient) {
        JsonObject json = new JsonObject();
        json.addProperty("item", ingredient.itemId());
        return json;
    }

    private static JsonObject resultJson(EditorIngredient result) {
        JsonObject json = new JsonObject();
        json.addProperty("item", result.itemId());
        json.addProperty("count", result.count());
        return json;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }
}
