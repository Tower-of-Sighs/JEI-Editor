package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;

import java.io.IOException;
import java.io.Reader;
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
import com.google.gson.JsonParser;

/** Converts accepted simple crafting patches into a world datapack and reloads recipes. */
final class RecipeEditsApplier {
    private static final String PACK_DESCRIPTION = "JEI Editor recipe overrides";

    private RecipeEditsApplier() {
    }

    static boolean canApply(MinecraftServer server, RecipePatch patch) {
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return false;
        }
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return false;
        }
        return RecipeEditorAdapters.createModel(holder, server.registryAccess())
                .flatMap(model -> createRecipeJson(holder, patch, server.registryAccess(), model)).isPresent();
    }

    static CompletableFuture<Void> apply(MinecraftServer server, RecipePatch patch) {
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
            if (holder == null) {
                throw new IOException("recipe does not exist: " + patch.recipeId());
            }
            Optional<EditorModel> model = RecipeEditorAdapters.createModel(holder, server.registryAccess());
            Optional<JsonObject> json = model.flatMap(value -> createRecipeJson(holder, patch, server.registryAccess(), value));
            if (!json.isPresent()) {
                throw new IOException("recipe patch is stale or unsupported: " + patch.recipeId());
            }
            Path recipePath = recipePath(server, recipeId);
            Files.createDirectories(recipePath.getParent());
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            boolean existed = Files.exists(recipePath);
            byte[] previous = existed ? Files.readAllBytes(recipePath) : null;
            writeAtomically(recipePath, json.get().toString().getBytes(StandardCharsets.UTF_8));
            return reloadWithRollback(server, recipeId, recipePath, existed, previous);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    static CompletableFuture<Void> reset(MinecraftServer server, String recipeIdText) {
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(recipeIdText);
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + recipeIdText);
            }
            Path recipePath = recipePath(server, recipeId);
            boolean existed = Files.exists(recipePath);
            byte[] previous = existed ? Files.readAllBytes(recipePath) : null;
            if (existed) {
                Files.delete(recipePath);
                pruneEmptyParents(recipePath.getParent());
            }
            return reloadWithRollback(server, recipeId, recipePath, existed, previous);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path packRoot(MinecraftServer server) {
        return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.DATAPACK_DIR)
                .resolve("jeieditor-generated");
    }

    private static Path recipePath(MinecraftServer server, ResourceLocation recipeId) {
        return packRoot(server).resolve("data").resolve(recipeId.getNamespace())
                .resolve("recipe").resolve(recipeId.getPath() + ".json");
    }

    private static void ensurePackMetadata(MinecraftServer server) throws IOException {
        Path root = packRoot(server);
        Files.createDirectories(root.resolve("data"));
        Path metadata = root.resolve("pack.mcmeta");
        if (!Files.exists(metadata)) {
            String value = "{\"pack\":{\"pack_format\":48,\"description\":\"" + PACK_DESCRIPTION + "\"}}";
            writeAtomically(metadata, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Refreshes only pack discovery/selection; it does not reload resources. */
    private static void ensureGeneratedPackSelected(MinecraftServer server) throws IOException {
        PackRepository repository = server.getPackRepository();
        repository.reload();
        Optional<String> packId = repository.getAvailableIds().stream()
                .filter(id -> id.endsWith("/jeieditor-generated"))
                .findFirst();
        if (!packId.isPresent()) {
            throw new IOException("generated datapack was not discovered by the server");
        }
        List<String> selected = new ArrayList<String>(repository.getSelectedIds());
        if (!selected.contains(packId.get())) {
            selected.add(packId.get());
            repository.setSelected(selected);
        }
    }

    private static CompletableFuture<Void> reloadWithRollback(MinecraftServer server, ResourceLocation recipeId,
                                                               Path recipePath, boolean existed, byte[] previous) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        try {
            reloadRecipe(server, recipeId, recipePath);
            result.complete(null);
        } catch (IOException | RuntimeException error) {
            try {
                restoreRecipeFile(recipePath, existed, previous);
                reloadRecipe(server, recipeId, recipePath);
            } catch (IOException | RuntimeException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            result.completeExceptionally(error);
        }
        return result;
    }

    /** Replaces one recipe in the live manager and syncs the recipe collection,
     * without rebuilding unrelated server resources. */
    private static void reloadRecipe(MinecraftServer server, ResourceLocation recipeId,
                                     Path recipePath) throws IOException {
        JsonObject json = readRecipeJson(server, recipeId, recipePath);
        RecipeHolder<?> replacement = RecipeManagerParser.parse(recipeId, json, server.registryAccess());
        List<RecipeHolder<?>> recipes = new ArrayList<RecipeHolder<?>>(server.getRecipeManager().getOrderedRecipes());
        boolean found = false;
        for (int index = 0; index < recipes.size(); index++) {
            if (recipes.get(index).id().equals(recipeId)) {
                recipes.set(index, replacement);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IOException("recipe does not exist: " + recipeId);
        }
        server.getRecipeManager().replaceRecipes(recipes);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(
                server.getRecipeManager().getOrderedRecipes()));
    }

    private static JsonObject readRecipeJson(MinecraftServer server, ResourceLocation recipeId,
                                             Path recipePath) throws IOException {
        if (Files.exists(recipePath)) {
            try (Reader reader = Files.newBufferedReader(recipePath, StandardCharsets.UTF_8)) {
                return parseObject(reader, recipeId.toString());
            }
        }
        ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(recipeId.getNamespace(),
                "recipe/" + recipeId.getPath() + ".json");
        Optional<Resource> resource = server.getResourceManager().getResource(resourceId);
        if (!resource.isPresent()) {
            throw new IOException("recipe resource does not exist: " + recipeId);
        }
        try (Reader reader = resource.get().openAsReader()) {
            return parseObject(reader, resourceId.toString());
        }
    }

    private static JsonObject parseObject(Reader reader, String resourceId) throws IOException {
        com.google.gson.JsonElement element = JsonParser.parseReader(reader);
        if (!element.isJsonObject()) {
            throw new IOException("recipe resource is not an object: " + resourceId);
        }
        return element.getAsJsonObject();
    }

    private static void restoreRecipeFile(Path recipePath, boolean existed, byte[] previous) throws IOException {
        if (existed) {
            Files.createDirectories(recipePath.getParent());
            writeAtomically(recipePath, previous);
        } else {
            Files.deleteIfExists(recipePath);
            pruneEmptyParents(recipePath.getParent());
        }
    }

    private static void writeAtomically(Path path, byte[] bytes) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName().toString() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void pruneEmptyParents(Path directory) throws IOException {
        Path root = directory;
        while (root != null && Files.isDirectory(root) && !root.getFileName().toString().equals("jeieditor-generated")) {
            try (Stream<Path> entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
            }
            Files.delete(root);
            root = root.getParent();
        }
    }

    private static Optional<JsonObject> createRecipeJson(RecipeHolder<?> holder, RecipePatch patch,
                                                          HolderLookup.Provider registries) {
        Optional<EditorModel> model = RecipeEditorAdapters.createModel(holder, registries);
        return model.flatMap(value -> createRecipeJson(holder, patch, registries, value));
    }

    private static Optional<JsonObject> createRecipeJson(RecipeHolder<?> holder, RecipePatch patch,
                                                          HolderLookup.Provider registries, EditorModel model) {
        if (model == null || !model.baseFingerprint().equals(patch.baseFingerprint())
                || !model.serializerId().equals(patch.serializerId())
                || !validatePatch(model, patch)) {
            return Optional.empty();
        }

        return createRecipeJsonContent(holder, patch, model);
    }

    private static Optional<JsonObject> createRecipeJsonContent(RecipeHolder<?> holder, RecipePatch patch,
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

        JsonObject recipe = new JsonObject();
        ResourceLocation serializerId = ResourceLocation.tryParse(patch.serializerId());
        if (serializerId == null) {
            return Optional.empty();
        }
        recipe.addProperty("type", serializerId.toString());
        if (holder.value() instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) holder.value();
            if (!shaped.getGroup().isEmpty()) {
                recipe.addProperty("group", shaped.getGroup());
            }
            recipe.addProperty("category", shaped.category().getSerializedName());
            if (!shaped.showNotification()) {
                recipe.addProperty("show_notification", false);
            }
            List<EditorIngredient> grid = shapedInputGrid(shaped, model, patch);
            if (!hasIngredient(grid)) {
                return Optional.empty();
            }
            recipe.add("pattern", shapedPattern(grid));
            recipe.add("key", shapedKey(grid));
        } else if (holder.value() instanceof ShapelessRecipe) {
            ShapelessRecipe shapeless = (ShapelessRecipe) holder.value();
            if (!shapeless.getGroup().isEmpty()) {
                recipe.addProperty("group", shapeless.getGroup());
            }
            recipe.addProperty("category", shapeless.category().getSerializedName());
            JsonArray ingredients = new JsonArray();
            for (EditorIngredient ingredient : inputs) {
                if (ingredient == null) {
                    return Optional.empty();
                }
                ingredients.add(ingredientJson(ingredient));
            }
            recipe.add("ingredients", ingredients);
        } else if (holder.value() instanceof AbstractCookingRecipe) {
            if (inputs.size() != 1 || inputs.get(0) == null) return Optional.empty();
            recipe.add("ingredient", cookingIngredientJson(
                    (AbstractCookingRecipe) holder.value(), patch, inputs.get(0)));
            recipe.addProperty("experience", cookingExperience(model, patch));
            recipe.addProperty("cookingtime", cookingTime(model, patch));
        } else {
            return Optional.empty();
        }
        recipe.add("result", resultJson(output));
        return Optional.of(recipe);
    }

    /**
     * Keep a tag/multi-item cooking ingredient when the input was untouched.
     * Once the user edits that slot, the patch intentionally narrows it to the
     * selected concrete item.
     */
    private static JsonElement cookingIngredientJson(AbstractCookingRecipe recipe,
                                                      RecipePatch patch,
                                                      EditorIngredient editedInput) {
        if (patch.fields().containsKey("input.0.item")
                || patch.fields().containsKey("input.0.count")) {
            return ingredientJson(editedInput);
        }
        try {
            Optional<JsonElement> encoded = Ingredient.CODEC
                    .encodeStart(JsonOps.INSTANCE, recipe.getIngredients().get(0)).result();
            if (encoded.isPresent()) {
                return encoded.get();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the representative item if a custom codec cannot
            // be encoded in this context.
        }
        return ingredientJson(editedInput);
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
            if (slot == null && "minecraft:crafting_shaped".equals(model.serializerId())
                    && isGridInputKey(key.substring(0, separator))) {
                slot = new EditorSlot(key.substring(0, separator), "input", null);
            }
            if (slot == null || (!"item".equals(property) && !"count".equals(property))) {
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

    private static float cookingExperience(EditorModel model, RecipePatch patch) {
        String value = patch.fields().get("recipe.experience");
        if (value == null) value = model.properties().get("experience");
        return Float.parseFloat(value);
    }

    private static int cookingTime(EditorModel model, RecipePatch patch) {
        String value = patch.fields().get("recipe.cooking_time");
        if (value == null) value = model.properties().get("cooking_time");
        return Integer.parseInt(value);
    }

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
        if (original == null && count == null) {
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

    private static List<EditorIngredient> shapedInputGrid(ShapedRecipe shaped, EditorModel model,
                                                          RecipePatch patch) {
        List<EditorIngredient> grid = new ArrayList<EditorIngredient>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(null);
        }
        boolean fixedGrid = hasInputSlot(model, "input.8");
        int[] dimensions = new int[] {shaped.getWidth(), shaped.getHeight()};
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            int slotIndex = inputIndex(slot.key());
            if (slotIndex < 0) {
                continue;
            }
            int gridIndex = fixedGrid
                    ? slotIndex
                    : CraftingSlotMapper.craftingGridIndex(slotIndex, dimensions[0], dimensions[1]);
            if (gridIndex < 0 || gridIndex >= grid.size()) {
                continue;
            }
            grid.set(gridIndex, patchedIngredient(slot, patch.fields()));
        }
        // A patch can contain fixed-grid fields that did not exist in an old
        // compact model (for example input.5 on a 2x3 recipe). Apply these
        // semantic coordinates after compact-slot translation so new edits can
        // add cells in the third column or row without ambiguity.
        for (int gridIndex = 0; gridIndex < grid.size(); gridIndex++) {
            String prefix = "input." + gridIndex;
            String item = patch.fields().get(prefix + ".item");
            String count = patch.fields().get(prefix + ".count");
            if (item != null || count != null) {
                grid.set(gridIndex, patchedIngredient(
                        new EditorSlot(prefix, "input", null), patch.fields()));
            }
        }
        return grid;
    }

    private static JsonArray shapedPattern(List<EditorIngredient> grid) {
        int minX = 3;
        int minY = 3;
        int maxX = -1;
        int maxY = -1;
        for (int index = 0; index < grid.size(); index++) {
            if (grid.get(index) == null) {
                continue;
            }
            int x = index % 3;
            int y = index / 3;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        JsonArray pattern = new JsonArray();
        for (int y = minY; y <= maxY; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                int index = y * 3 + x;
                row.append(grid.get(index) != null ? (char) ('a' + index) : ' ');
            }
            pattern.add(row.toString());
        }
        return pattern;
    }

    private static JsonObject shapedKey(List<EditorIngredient> grid) {
        JsonObject key = new JsonObject();
        for (int i = 0; i < grid.size(); i++) {
            if (grid.get(i) != null) {
                key.add(String.valueOf((char) ('a' + i)), ingredientJson(grid.get(i)));
            }
        }
        return key;
    }

    private static boolean hasIngredient(List<EditorIngredient> grid) {
        for (EditorIngredient ingredient : grid) {
            if (ingredient != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInputSlot(EditorModel model, String key) {
        for (EditorSlot slot : model.slots()) {
            if (key.equals(slot.key()) && "input".equals(slot.role())) {
                return true;
            }
        }
        return false;
    }

    private static int inputIndex(String key) {
        if (!key.startsWith("input.")) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring("input.".length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static boolean isGridInputKey(String key) {
        int index = inputIndex(key);
        return index >= 0 && index < 9;
    }

    private static JsonObject ingredientJson(EditorIngredient ingredient) {
        JsonObject json = new JsonObject();
        json.addProperty("item", ingredient.itemId());
        return json;
    }

    private static JsonObject resultJson(EditorIngredient result) {
        JsonObject json = new JsonObject();
        json.addProperty("id", result.itemId());
        json.addProperty("count", result.count());
        return json;
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }
}
