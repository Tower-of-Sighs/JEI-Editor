package cc.sighs.JEIEditor.server;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.platform.fuel.FuelOverrideState;
import cc.sighs.JEIEditor.platform.recipe.CookingRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.CraftingSlotMapper;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeAdapterSupport;
import cc.sighs.JEIEditor.platform.recipe.RecipeCreationAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeDeletionAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import cc.sighs.JEIEditor.platform.recipe.VanillaSpecialRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import mezz.jei.library.gui.helpers.CraftingGridHelper;

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

/** Persists accepted recipe patches and performs targeted recipe synchronization. */
public final class RecipeEditsApplier {
    private static final String PACK_DESCRIPTION = "JEI Editor recipe overrides";

    private RecipeEditsApplier() {
    }

    public static boolean canApply(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return canApplyFuel(server, patch);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if ("jei:anvil".equals(patch.serializerId())) {
                return canApplyAnvil(server, patch);
            }
            return JeiVanillaRecipeEditorAdapter.validatePatch(patch);
        }
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return false;
        }
        if (RecipePatchSemantics.isCreation(patch)) {
            return "jeieditor".equals(recipeId.getNamespace())
                    && server.getRecipeManager().byKey(recipeId).isEmpty()
                    && createNewRecipeJson(patch).isPresent();
        }
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return false;
        }
        if (RecipePatchSemantics.isDeletion(patch)) {
            return RecipeDeletionAdapter.matches(holder, patch.serializerId(), patch.baseFingerprint());
        }
        return RecipeEditorAdapters.createModel(holder, server.registryAccess())
                .flatMap(model -> createRecipeJson(holder, patch, server.registryAccess(), model)).isPresent();
    }

    public static CompletableFuture<Void> apply(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return applyFuel(server, patch);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
                return saveComposting(server, patch)
                        .thenCompose(ignored -> reloadComposting(server, patch));
            }
            if ("jei:anvil".equals(patch.serializerId())) {
                return saveAnvil(server, patch)
                        .thenCompose(ignored -> reloadSaved(server, patch));
            }
            return JeiVanillaRecipeEditorAdapter.validatePatch(patch)
                    ? CompletableFuture.completedFuture(null)
                    : failedFuture(new IOException("synthetic JEI patch is invalid: " + patch.recipeId()));
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
            if (holder == null && !RecipePatchSemantics.isCreation(patch)) {
                throw new IOException("recipe does not exist: " + patch.recipeId());
            }
            Optional<JsonObject> json = RecipePatchSemantics.isCreation(patch)
                    ? createNewRecipeJson(patch)
                    : createRecipeJson(holder, patch, server.registryAccess());
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
            if (RecipePatchSemantics.isDeletion(patch)) {
                removeRecipe(server, recipeId);
                return CompletableFuture.completedFuture(null);
            }
            return reloadWithRollback(server, recipeId, recipePath, existed, previous,
                    RecipePatchSemantics.isCreation(patch));
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    /** Saves a patch to the generated datapack without changing live recipes. */
    public static CompletableFuture<Void> save(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return saveFuel(server, patch);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
                return saveComposting(server, patch);
            }
            if ("jei:anvil".equals(patch.serializerId())) {
                return saveAnvil(server, patch);
            }
            return canApply(server, patch)
                    ? CompletableFuture.completedFuture(null)
                    : failedFuture(new IOException("synthetic JEI patch is invalid: " + patch.recipeId()));
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
            if (holder == null && !RecipePatchSemantics.isCreation(patch)) {
                throw new IOException("recipe does not exist: " + patch.recipeId());
            }
            Optional<JsonObject> json = RecipePatchSemantics.isCreation(patch)
                    ? createNewRecipeJson(patch)
                    : createRecipeJson(holder, patch, server.registryAccess());
            if (!json.isPresent()) {
                throw new IOException("recipe patch is stale or unsupported: " + patch.recipeId());
            }
            Path recipePath = recipePath(server, recipeId);
            Files.createDirectories(recipePath.getParent());
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            writeAtomically(recipePath, json.get().toString().getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    /** Applies one already-saved patch to the live recipe manager only. */
    public static CompletableFuture<Void> reloadSaved(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            Optional<ResourceLocation> itemId = FuelRecipeEditorAdapter.itemId(patch);
            int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
            if (!itemId.isPresent() || burnTime < 1) {
                return failedFuture(new IOException("saved fuel patch is invalid: " + patch.recipeId()));
            }
            FuelOverrideState.set(itemId.get(), burnTime);
            return CompletableFuture.completedFuture(null);
        }
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(patch)) {
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
                return reloadComposting(server, patch);
            }
            // Older versions allowed editing generated JEI anvil pages and
            // may have left such patches in SavedData. They are no longer
            // valid under the anvil read-only policy. Drop them during the
            // next reload so one stale entry cannot abort the whole batch and
            // prevent normal crafting output edits from being synchronized.
            if ("jei:anvil".equals(patch.serializerId())
                    && !RecipePatchSemantics.isCreation(patch)) {
                RecipeEditsSavedData.get(server).remove(patch.recipeId());
                return CompletableFuture.completedFuture(null);
            }
            return canApply(server, patch)
                    ? CompletableFuture.completedFuture(null)
                    : failedFuture(new IOException("saved synthetic JEI patch is invalid: " + patch.recipeId()));
        }
        try {
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            if (recipeId == null) {
                throw new IOException("invalid recipe id: " + patch.recipeId());
            }
            Path path = recipePath(server, recipeId);
            if (Files.notExists(path)) {
                throw new IOException("saved recipe file does not exist: " + patch.recipeId());
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                if (readRecipeJson(server, recipeId, path).size() != 0) {
                    throw new IOException("saved deletion override is not empty: " + patch.recipeId());
                }
                removeRecipe(server, recipeId);
                return CompletableFuture.completedFuture(null);
            }
            reloadRecipe(server, recipeId, path, RecipePatchSemantics.isCreation(patch));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    public static CompletableFuture<Void> reset(MinecraftServer server, String recipeIdText) {
        return reset(server, recipeIdText, null);
    }

    public static CompletableFuture<Void> reset(MinecraftServer server, String recipeIdText, EditorModel baseModel) {
        if (FuelRecipeEditorAdapter.itemIdFromRecipeId(recipeIdText).isPresent()) {
            return resetFuel(server, recipeIdText, baseModel);
        }
        RecipePatch savedSynthetic = RecipeEditsSavedData.get(server).patches().get(recipeIdText);
        if (JeiVanillaRecipeEditorAdapter.isSyntheticPatch(savedSynthetic)) {
            return resetSynthetic(server, savedSynthetic);
        }
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
            if (RecipeCreationAdapter.isCreatedModel(baseModel)) {
                removeRecipe(server, recipeId);
                return CompletableFuture.completedFuture(null);
            }
            return reloadWithRollback(server, recipeId, recipePath, existed, previous);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    /** Resolves the current model for either a normal recipe or a synthetic fuel entry. */
    public static EditorModel currentModel(MinecraftServer server, RecipePatch patch) {
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            Optional<ResourceLocation> itemId = FuelRecipeEditorAdapter.itemId(patch);
            if (!itemId.isPresent()) {
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId.get());
            RecipePatch savedPatch = RecipeEditsSavedData.get(server).patches().get(patch.recipeId());
            int burnTime = savedPatch != null ? FuelRecipeEditorAdapter.burnTime(savedPatch)
                    : new ItemStack(item).getBurnTime(null);
            return FuelRecipeEditorAdapter.createModel(itemId.get(), burnTime);
        }
        ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
        if (recipeId == null) {
            return null;
        }
        if (RecipePatchSemantics.isCreation(patch)) {
            return RecipeCreationAdapter.modelFromPatch(patch);
        }
        RecipeHolder<?> holder = server.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null) {
            return null;
        }
        return RecipePatchSemantics.isDeletion(patch)
                ? RecipeDeletionAdapter.createModel(holder).orElse(null)
                : RecipeEditorAdapters.createModel(holder, server.registryAccess()).orElse(null);
    }

    /**
     * Removes persisted synthetic entries whose generated datapack artifact no
     * longer exists. SavedData is a world cache, not an independent source of
     * truth for created anvil recipes; this also handles a user deleting the
     * generated datapack outside the editor.
     */
    public static int pruneMissingSavedArtifacts(MinecraftServer server) {
        if (server == null) {
            return 0;
        }
        RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
        int removed = 0;
        for (RecipePatch patch : new ArrayList<RecipePatch>(data.patches().values())) {
            if (!JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(patch)) {
                continue;
            }
            Path path = anvilDataPath(server, ResourceLocation.tryParse(patch.recipeId()));
            if (path == null || Files.notExists(path)) {
                data.remove(patch.recipeId());
                removed++;
            }
        }
        return removed;
    }

    private static boolean canApplyFuel(MinecraftServer server, RecipePatch patch) {
        if (!FuelRecipeEditorAdapter.itemIdFromRecipeId(patch.recipeId()).isPresent()) {
            return false;
        }
        Optional<ResourceLocation> itemId = FuelRecipeEditorAdapter.itemId(patch);
        if (!itemId.isPresent()) {
            return false;
        }
        ResourceLocation encoded = FuelRecipeEditorAdapter.itemIdFromRecipeId(patch.recipeId()).get();
        if (!encoded.equals(itemId.get())) {
            return false;
        }
        int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
        if (burnTime < 1 || burnTime > 2_000_000_000) {
            return false;
        }
        if (!BuiltInRegistries.ITEM.containsKey(itemId.get())) {
            return false;
        }
        EditorModel model = currentModel(server, patch);
        return model != null && model.baseFingerprint().equals(patch.baseFingerprint());
    }

    /** Existing JEI anvil pages are generated views, not editable recipe
     * records. Only drafts created by this editor may be saved or deleted. */
    private static boolean canApplyAnvil(MinecraftServer server, RecipePatch patch) {
        if (server == null || patch == null || !"jei:anvil".equals(patch.serializerId())) {
            return false;
        }
        if (RecipePatchSemantics.isCreation(patch)) {
            ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
            return id != null && "jeieditor".equals(id.getNamespace())
                    && server.getRecipeManager().byKey(id).isEmpty()
                    && JeiVanillaRecipeEditorAdapter.validatePatch(patch);
        }
        if (!RecipePatchSemantics.isDeletion(patch)) {
            return false;
        }
        return isCreatedAnvilIdentity(server, patch);
    }

    private static boolean isCreatedAnvilIdentity(MinecraftServer server, RecipePatch patch) {
        ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
        if (id == null || !"jeieditor".equals(id.getNamespace())
                || server.getRecipeManager().byKey(id).isPresent()) {
            return false;
        }
        EditorModel baseModel = RecipeEditsSavedData.get(server).baseModel(patch.recipeId());
        if (baseModel != null && RecipeCreationAdapter.isCreatedModel(baseModel)
                && "jei:anvil".equals(baseModel.serializerId())) {
            return true;
        }
        RecipePatch saved = RecipeEditsSavedData.get(server).patches().get(patch.recipeId());
        if (JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(saved)) {
            return true;
        }
        // A draft can be deleted before its first save. Its generated
        // fingerprint and namespace still prove it came from the creation UI.
        return patch.baseFingerprint().startsWith("new:");
    }

    private static CompletableFuture<Void> applyFuel(MinecraftServer server, RecipePatch patch) {
        return saveFuel(server, patch).thenCompose(ignored -> reloadSaved(server, patch));
    }

    private static CompletableFuture<Void> saveFuel(MinecraftServer server, RecipePatch patch) {
        try {
            if (!canApplyFuel(server, patch)) {
                throw new IOException("recipe patch is stale or unsupported: " + patch.recipeId());
            }
            ResourceLocation itemId = FuelRecipeEditorAdapter.itemId(patch).get();
            int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
            Path path = fuelDataMapPath(server);
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            writeFuelValue(path, itemId, burnTime);
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> resetFuel(MinecraftServer server, String recipeIdText,
                                                     EditorModel baseModel) {
        try {
            ResourceLocation itemId = FuelRecipeEditorAdapter.itemIdFromRecipeId(recipeIdText).get();
            removeFuelValue(fuelDataMapPath(server), itemId);
            FuelOverrideState.remove(itemId);
            if (baseModel != null && FuelRecipeEditorAdapter.SERIALIZER.equals(baseModel.serializerId())) {
                int originalBurnTime;
                try {
                    originalBurnTime = Integer.parseInt(baseModel.properties().get("burn_time"));
                } catch (RuntimeException exception) {
                    originalBurnTime = 0;
                }
                // Keep the live server correct even when the generated pack
                // was loaded before the reset and its data map is still cached.
                FuelOverrideState.set(itemId, Math.max(0, originalBurnTime));
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path fuelDataMapPath(MinecraftServer server) {
        return packRoot(server).resolve("data").resolve("neoforge")
                .resolve("data_maps").resolve("item").resolve("furnace_fuels.json");
    }

    private static CompletableFuture<Void> saveComposting(MinecraftServer server, RecipePatch patch) {
        try {
            if (!JeiVanillaRecipeEditorAdapter.validatePatch(patch)) {
                throw new IOException("composting patch is invalid: " + patch.recipeId());
            }
            ResourceLocation original = patchItem(patch, "match.input.0");
            ResourceLocation replacement = patchItem(patch, "input.0");
            if (replacement == null) {
                replacement = original;
            }
            float chance = compostChance(patch);
            if (original == null || replacement == null || chance <= 0.0F) {
                throw new IOException("composting patch has no valid input or chance: " + patch.recipeId());
            }
            Path path = compostDataMapPath(server);
            JsonObject root = readJsonObject(path);
            RecipePatch previous = RecipeEditsSavedData.get(server).patches().get(patch.recipeId());
            if (JeiVanillaRecipeEditorAdapter.isCompostingPatch(previous)) {
                removeCompostPatch(root, previous);
            }
            applyCompostPatch(root, original, replacement, chance);
            Files.createDirectories(path.getParent());
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> reloadComposting(MinecraftServer server, RecipePatch patch) {
        try {
            if (!JeiVanillaRecipeEditorAdapter.validatePatch(patch)) {
                throw new IOException("saved composting patch is invalid: " + patch.recipeId());
            }
            ResourceLocation original = patchItem(patch, "match.input.0");
            ResourceLocation replacement = patchItem(patch, "input.0");
            if (replacement == null) {
                replacement = original;
            }
            float chance = compostChance(patch);
            if (original == null || replacement == null || chance <= 0.0F) {
                throw new IOException("saved composting patch has no valid input or chance: " + patch.recipeId());
            }
            Map<ResourceKey<Item>, Compostable> values =
                    BuiltInRegistries.ITEM.getDataMap(NeoForgeDataMaps.COMPOSTABLES);
            ResourceKey<Item> originalKey = ResourceKey.create(Registries.ITEM, original);
            ResourceKey<Item> replacementKey = ResourceKey.create(Registries.ITEM, replacement);
            Compostable originalValue = values.get(originalKey);
            if (!original.equals(replacement)) {
                values.remove(originalKey);
            }
            values.put(replacementKey, new Compostable(chance,
                    originalValue != null && originalValue.canVillagerCompost()));
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static CompletableFuture<Void> resetSynthetic(MinecraftServer server, RecipePatch patch) {
        if (JeiVanillaRecipeEditorAdapter.isCreatedAnvilPatch(patch)) {
            try {
                Path path = anvilDataPath(server, ResourceLocation.tryParse(patch.recipeId()));
                if (path != null) {
                    Files.deleteIfExists(path);
                    pruneEmptyParents(path.getParent());
                }
                return CompletableFuture.completedFuture(null);
            } catch (IOException | RuntimeException exception) {
                return failedFuture(exception);
            }
        }
        if (!JeiVanillaRecipeEditorAdapter.isCompostingPatch(patch)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Path path = compostDataMapPath(server);
            JsonObject root = readJsonObject(path);
            removeCompostPatch(root, patch);
            if (root.getAsJsonObject("values").size() == 0
                    && (!root.has("remove") || root.getAsJsonArray("remove").size() == 0)) {
                Files.deleteIfExists(path);
                pruneEmptyParents(path.getParent());
            } else {
                writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
            }

            ResourceLocation original = patchItem(patch, "match.input.0");
            ResourceLocation replacement = patchItem(patch, "input.0");
            if (replacement == null) {
                replacement = original;
            }
            Map<ResourceKey<Item>, Compostable> values =
                    BuiltInRegistries.ITEM.getDataMap(NeoForgeDataMaps.COMPOSTABLES);
            if (replacement != null && !replacement.equals(original)) {
                values.remove(ResourceKey.create(Registries.ITEM, replacement));
            }
            float chance = compostChance(patch);
            if (original != null && chance > 0.0F) {
                values.put(ResourceKey.create(Registries.ITEM, original), new Compostable(chance));
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path compostDataMapPath(MinecraftServer server) {
        return packRoot(server).resolve("data").resolve("neoforge")
                .resolve("data_maps").resolve("item").resolve("compostables.json");
    }

    /**
     * Anvil recipes are not vanilla RecipeManager entries, so their datapack
     * representation lives under the mod's data namespace instead of the
     * vanilla recipe directory. SavedData remains the runtime source used by
     * SyntheticRecipeOverrides; this file makes the accepted edit durable and
     * inspectable in the generated datapack as well.
     */
    private static CompletableFuture<Void> saveAnvil(MinecraftServer server, RecipePatch patch) {
        try {
            if (!canApplyAnvil(server, patch)) {
                throw new IOException("anvil patch is stale or unsupported: " + patch.recipeId());
            }
            ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
            Path path = anvilDataPath(server, recipeId);
            if (path == null) {
                throw new IOException("invalid anvil recipe id: " + patch.recipeId());
            }
            ensurePackMetadata(server);
            ensureGeneratedPackSelected(server);
            if (RecipePatchSemantics.isDeletion(patch)) {
                Files.deleteIfExists(path);
                pruneEmptyParents(path.getParent());
            } else {
                Files.createDirectories(path.getParent());
                writeAtomically(path, anvilDatapackJson(patch).toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            return CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            return failedFuture(exception);
        }
    }

    private static Path anvilDataPath(MinecraftServer server, ResourceLocation recipeId) {
        if (server == null || recipeId == null || !"jeieditor".equals(recipeId.getNamespace())) {
            return null;
        }
        return packRoot(server).resolve("data").resolve("jeieditor")
                .resolve("jei_editor").resolve("anvil")
                .resolve(recipeId.getPath() + ".json");
    }

    private static JsonObject anvilDatapackJson(RecipePatch patch) {
        JsonObject root = new JsonObject();
        root.addProperty("type", patch.serializerId());
        root.addProperty("id", patch.recipeId());
        JsonObject fields = new JsonObject();
        for (Map.Entry<String, String> entry : patch.fields().entrySet()) {
            fields.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("fields", fields);
        addAnvilStack(root, "left", patch, "input.0");
        addAnvilStack(root, "right", patch, "input.1");
        addAnvilStack(root, "result", patch, "output");
        return root;
    }

    private static void addAnvilStack(JsonObject root, String name, RecipePatch patch, String prefix) {
        JsonObject stack = new JsonObject();
        String item = patch.fields().get(prefix + ".item");
        String count = patch.fields().get(prefix + ".count");
        stack.addProperty("item", item == null ? "minecraft:air" : item);
        stack.addProperty("count", count == null ? "0" : count);
        String encoded = patch.fields().get(prefix + ".stack");
        if (encoded != null) {
            stack.addProperty("stack", encoded);
        }
        root.add(name, stack);
    }

    private static void applyCompostPatch(JsonObject root, ResourceLocation original,
                                          ResourceLocation replacement, float chance) {
        JsonObject values = root.has("values") && root.get("values").isJsonObject()
                ? root.getAsJsonObject("values") : new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("chance", chance);
        values.add(replacement.toString(), value);
        root.add("values", values);
        if (!original.equals(replacement)) {
            JsonArray removals = root.has("remove") && root.get("remove").isJsonArray()
                    ? root.getAsJsonArray("remove") : new JsonArray();
            if (!jsonArrayContains(removals, original.toString())) {
                removals.add(original.toString());
            }
            root.add("remove", removals);
        }
    }

    private static void removeCompostPatch(JsonObject root, RecipePatch patch) {
        JsonObject values = root.has("values") && root.get("values").isJsonObject()
                ? root.getAsJsonObject("values") : new JsonObject();
        ResourceLocation original = patchItem(patch, "match.input.0");
        ResourceLocation replacement = patchItem(patch, "input.0");
        if (replacement == null) {
            replacement = original;
        }
        if (replacement != null) {
            values.remove(replacement.toString());
        }
        root.add("values", values);
        if (original != null && root.has("remove") && root.get("remove").isJsonArray()) {
            JsonArray removals = root.getAsJsonArray("remove");
            for (int index = removals.size() - 1; index >= 0; index--) {
                if (removals.get(index).isJsonPrimitive()
                        && original.toString().equals(removals.get(index).getAsString())) {
                    removals.remove(index);
                }
            }
        }
    }

    private static boolean jsonArrayContains(JsonArray values, String expected) {
        for (JsonElement value : values) {
            if (value.isJsonPrimitive() && expected.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation patchItem(RecipePatch patch, String prefix) {
        return patch == null ? null : ResourceLocation.tryParse(
                patch.fields().get(prefix + ".item"));
    }

    private static float compostChance(RecipePatch patch) {
        try {
            return Float.parseFloat(patch.fields().get(
                    JeiVanillaRecipeEditorAdapter.COMPOST_CHANCE_PROPERTY));
        } catch (RuntimeException exception) {
            return -1.0F;
        }
    }

    private static void writeFuelValue(Path path, ResourceLocation itemId, int burnTime) throws IOException {
        JsonObject root = readJsonObject(path);
        JsonObject values = root.has("values") && root.get("values").isJsonObject()
                ? root.getAsJsonObject("values") : new JsonObject();
        JsonObject value = new JsonObject();
        value.addProperty("burn_time", burnTime);
        values.add(itemId.toString(), value);
        root.add("values", values);
        Files.createDirectories(path.getParent());
        writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void removeFuelValue(Path path, ResourceLocation itemId) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        JsonObject root = readJsonObject(path);
        if (!root.has("values") || !root.get("values").isJsonObject()) {
            return;
        }
        JsonObject values = root.getAsJsonObject("values");
        values.remove(itemId.toString());
        if (values.size() == 0 && !root.has("remove")) {
            Files.deleteIfExists(path);
            pruneEmptyParents(path.getParent());
        } else {
            root.add("values", values);
            writeAtomically(path, root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        if (Files.notExists(path)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new IOException("fuel data map is not a JSON object");
            }
            return element.getAsJsonObject();
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
        return reloadWithRollback(server, recipeId, recipePath, existed, previous, false);
    }

    private static CompletableFuture<Void> reloadWithRollback(MinecraftServer server, ResourceLocation recipeId,
                                                               Path recipePath, boolean existed, byte[] previous,
                                                               boolean allowAdd) {
        CompletableFuture<Void> result = new CompletableFuture<Void>();
        try {
            reloadRecipe(server, recipeId, recipePath, allowAdd);
            result.complete(null);
        } catch (IOException | RuntimeException error) {
            try {
                restoreRecipeFile(recipePath, existed, previous);
                reloadRecipe(server, recipeId, recipePath, allowAdd);
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
        reloadRecipe(server, recipeId, recipePath, false);
    }

    private static void reloadRecipe(MinecraftServer server, ResourceLocation recipeId,
                                      Path recipePath, boolean allowAdd) throws IOException {
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
            if (!allowAdd) {
                throw new IOException("recipe does not exist: " + recipeId);
            }
            recipes.add(replacement);
        }
        server.getRecipeManager().replaceRecipes(recipes);
        server.getPlayerList().broadcastAll(new ClientboundUpdateRecipesPacket(
                server.getRecipeManager().getOrderedRecipes()));
    }

    /** Removes one live recipe and synchronizes the resulting collection. */
    private static void removeRecipe(MinecraftServer server, ResourceLocation recipeId) {
        List<RecipeHolder<?>> recipes = new ArrayList<RecipeHolder<?>>(
                server.getRecipeManager().getOrderedRecipes());
        recipes.removeIf(holder -> holder.id().equals(recipeId));
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
        if (RecipePatchSemantics.isCreation(patch)) {
            return createNewRecipeJson(patch);
        }
        if (RecipePatchSemantics.isDeletion(patch)) {
            return RecipeDeletionAdapter.matches(holder, patch.serializerId(), patch.baseFingerprint())
                    ? Optional.of(new JsonObject()) : Optional.empty();
        }
        Optional<EditorModel> model = RecipeEditorAdapters.createModel(holder, registries);
        return model.flatMap(value -> createRecipeJson(holder, patch, registries, value));
    }

    private static Optional<JsonObject> createNewRecipeJson(RecipePatch patch) {
        if (!RecipePatchSemantics.isCreation(patch)
                || !RecipeCreationAdapter.isCreatableSerializer(patch.serializerId())) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(patch.recipeId());
        ResourceLocation output = ResourceLocation.tryParse(patch.fields().get("output.item"));
        boolean smithingTrim = "minecraft:smithing_trim".equals(patch.serializerId());
        if (id == null || !"jeieditor".equals(id.getNamespace())
                || (!smithingTrim && (output == null || !BuiltInRegistries.ITEM.containsKey(output)))) {
            return Optional.empty();
        }
        int count;
        try {
            count = Integer.parseInt(patch.fields().get("output.count"));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (smithingTrim ? count != 0 : (count < 1 || count > 64)) {
            return Optional.empty();
        }
        if (!RecipeCreationAdapter.isCreatableSerializer(patch.serializerId())) {
            return Optional.empty();
        }
        boolean crafting = "minecraft:crafting_shaped".equals(patch.serializerId())
                || "minecraft:crafting_shapeless".equals(patch.serializerId());
        boolean special = VanillaSpecialRecipeEditorAdapter.supportsSerializer(patch.serializerId());
        if (!crafting && !special) {
            try {
                float experience = Float.parseFloat(patch.fields().getOrDefault("recipe.experience", "0.0"));
                int cookingTime = Integer.parseInt(patch.fields().getOrDefault("recipe.cooking_time", "200"));
                if (!Float.isFinite(experience) || experience < 0.0F || experience > 1000.0F
                        || cookingTime < 1 || cookingTime > 1000000) {
                    return Optional.empty();
                }
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", patch.serializerId());
        List<EditorIngredient> creationGrid = creationInputGrid(patch);
        if ("minecraft:crafting_shaped".equals(patch.serializerId())
                || "minecraft:crafting_shapeless".equals(patch.serializerId())) {
            if (!hasIngredient(creationGrid)) {
                return Optional.empty();
            }
        }
        if ("minecraft:crafting_shaped".equals(patch.serializerId())) {
            recipe.addProperty("category", "misc");
            recipe.add("pattern", shapedPattern(creationGrid));
            recipe.add("key", shapedKey(creationGrid));
        } else if ("minecraft:crafting_shapeless".equals(patch.serializerId())) {
            recipe.addProperty("category", "misc");
            JsonArray ingredients = new JsonArray();
            for (EditorIngredient ingredient : creationGrid) {
                if (ingredient != null) {
                    ingredients.add(ingredientJson(ingredient));
                }
            }
            recipe.add("ingredients", ingredients);
        } else if ("minecraft:stonecutting".equals(patch.serializerId())) {
            if (creationGrid.isEmpty() || creationGrid.get(0) == null) {
                return Optional.empty();
            }
            recipe.add("ingredient", ingredientJson(creationGrid.get(0)));
        } else if ("minecraft:smithing_transform".equals(patch.serializerId())
                || "minecraft:smithing_trim".equals(patch.serializerId())) {
            if (creationGrid.size() < 3 || creationGrid.get(0) == null
                    || creationGrid.get(1) == null || creationGrid.get(2) == null) {
                return Optional.empty();
            }
            recipe.add("template", ingredientJson(creationGrid.get(0)));
            recipe.add("base", ingredientJson(creationGrid.get(1)));
            recipe.add("addition", ingredientJson(creationGrid.get(2)));
        } else {
            if (creationGrid.isEmpty() || creationGrid.get(0) == null) {
                return Optional.empty();
            }
            recipe.add("ingredient", ingredientJson(creationGrid.get(0)));
            recipe.addProperty("experience", patch.fields().getOrDefault("recipe.experience", "0.0"));
            recipe.addProperty("cookingtime", patch.fields().getOrDefault("recipe.cooking_time", "200"));
        }
        if (!smithingTrim) {
            JsonObject result = new JsonObject();
            result.addProperty("id", output.toString());
            result.addProperty("count", count);
            recipe.add("result", result);
        }
        return Optional.of(recipe);
    }

    private static List<EditorIngredient> creationInputGrid(RecipePatch patch) {
        int size;
        if (CookingRecipeEditorAdapter.supportsSerializer(patch.serializerId())
                || "minecraft:stonecutting".equals(patch.serializerId())) {
            size = 1;
        } else if ("minecraft:smithing_transform".equals(patch.serializerId())
                || "minecraft:smithing_trim".equals(patch.serializerId())) {
            size = 3;
        } else {
            size = 9;
        }
        List<EditorIngredient> grid = new ArrayList<EditorIngredient>(size);
        for (int index = 0; index < size; index++) {
            String prefix = "input." + index;
            String item = patch.fields().get(prefix + ".item");
            String countText = patch.fields().get(prefix + ".count");
            if (item == null && countText == null) {
                grid.add(null);
                continue;
            }
            if (item == null || "minecraft:air".equals(item) || "0".equals(countText)) {
                grid.add(null);
                continue;
            }
            ResourceLocation itemId = ResourceLocation.tryParse(item);
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                return new ArrayList<EditorIngredient>();
            }
            int count;
            try {
                count = countText == null ? 1 : Integer.parseInt(countText);
            } catch (NumberFormatException exception) {
                return new ArrayList<EditorIngredient>();
            }
            if (count < 1 || count > 64) {
                return new ArrayList<EditorIngredient>();
            }
            grid.add(new EditorIngredient(itemId.toString(), count));
        }
        return grid;
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
        if (outputSlot == null) {
            return Optional.empty();
        }
        boolean smithingTrim = "minecraft:smithing_trim".equals(patch.serializerId());
        EditorIngredient output = smithingTrim ? null : patchedIngredient(outputSlot, patch.fields());
        if (!smithingTrim && output == null) {
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
            recipe.add("key", shapedKey(shaped, model, patch, grid));
        } else if (holder.value() instanceof ShapelessRecipe) {
            ShapelessRecipe shapeless = (ShapelessRecipe) holder.value();
            if (!shapeless.getGroup().isEmpty()) {
                recipe.addProperty("group", shapeless.getGroup());
            }
            recipe.addProperty("category", shapeless.category().getSerializedName());
            JsonArray ingredients = shapelessIngredients(holder, model, patch);
            if (ingredients.size() == 0) {
                return Optional.empty();
            }
            recipe.add("ingredients", ingredients);
        } else if (holder.value() instanceof AbstractCookingRecipe) {
            if (inputs.size() != 1 || inputs.get(0) == null) return Optional.empty();
            recipe.add("ingredient", cookingIngredientJson(
                    (AbstractCookingRecipe) holder.value(), patch, inputs.get(0)));
            recipe.addProperty("experience", cookingExperience(model, patch));
            recipe.addProperty("cookingtime", cookingTime(model, patch));
        } else if (holder.value() instanceof StonecutterRecipe) {
            if (inputs.size() != 1 || inputs.get(0) == null) {
                return Optional.empty();
            }
            recipe.add("ingredient", vanillaIngredientJson(
                    ((StonecutterRecipe) holder.value()).getIngredients().get(0),
                    patch, "input.0", inputs.get(0)));
        } else if (holder.value() instanceof SmithingRecipe) {
            if (inputs.size() != 3 || inputs.get(0) == null || inputs.get(1) == null
                    || inputs.get(2) == null) {
                return Optional.empty();
            }
            List<Ingredient> originals = VanillaSpecialRecipeEditorAdapter
                    .ingredientDefinitions(holder.value());
            if (originals.size() != 3) {
                return Optional.empty();
            }
            recipe.add("template", vanillaIngredientJson(originals.get(0), patch, "input.0", inputs.get(0)));
            recipe.add("base", vanillaIngredientJson(originals.get(1), patch, "input.1", inputs.get(1)));
            recipe.add("addition", vanillaIngredientJson(originals.get(2), patch, "input.2", inputs.get(2)));
        } else {
            return Optional.empty();
        }
        if (output != null) {
            recipe.add("result", resultJson(output));
        }
        return Optional.of(recipe);
    }

    private static JsonElement vanillaIngredientJson(Ingredient original, RecipePatch patch,
                                                      String slotKey, EditorIngredient current) {
        if (patch.fields().containsKey(slotKey + ".item")
                || patch.fields().containsKey(slotKey + ".count")) {
            return ingredientJson(current);
        }
        try {
            Optional<JsonElement> encoded = Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, original).result();
            if (encoded.isPresent()) {
                return encoded.get();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the concrete representative when a codec is unavailable.
        }
        return ingredientJson(current);
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
        if (RecipePatchSemantics.isDeletion(patch)) {
            return true;
        }
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
            if (slot == null && isCraftingGridSerializer(model.serializerId())
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

    private static JsonObject shapedKey(ShapedRecipe shaped, EditorModel model,
                                        RecipePatch patch, List<EditorIngredient> grid) {
        JsonObject key = new JsonObject();
        for (int i = 0; i < grid.size(); i++) {
            EditorIngredient current = grid.get(i);
            if (current != null) {
                Ingredient original = shapedIngredientAt(shaped, i);
                String patchKey = inputPatchKeyForGrid(shaped, model, patch, i);
                key.add(String.valueOf((char) ('a' + i)),
                        ingredientJson(original, current, patchKey != null));
            }
        }
        return key;
    }

    private static JsonObject shapedKey(List<EditorIngredient> grid) {
        JsonObject key = new JsonObject();
        for (int i = 0; i < grid.size(); i++) {
            EditorIngredient ingredient = grid.get(i);
            if (ingredient != null) {
                key.add(String.valueOf((char) ('a' + i)), ingredientJson(ingredient));
            }
        }
        return key;
    }

    private static JsonArray shapelessIngredients(RecipeHolder<?> holder, EditorModel model,
                                                   RecipePatch patch) {
        JsonArray result = new JsonArray();
        @SuppressWarnings("unchecked")
        RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe> craftingHolder =
                (RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)
                        (RecipeHolder<?>) holder;
        Map<Integer, Ingredient> originals = CraftingGridHelper
                .getGuiSlotToIngredientMap(craftingHolder, 0, 0);
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            int gridIndex = inputIndex(slot.key());
            if (gridIndex < 0) {
                continue;
            }
            EditorIngredient current = patchedIngredient(slot, patch.fields());
            if (current == null) {
                continue;
            }
            Ingredient original = originals.get(Integer.valueOf(gridIndex));
            result.add(ingredientJson(original, current, hasInputPatch(patch, slot.key())));
        }
        return result;
    }

    private static Ingredient shapedIngredientAt(ShapedRecipe shaped, int gridIndex) {
        List<Ingredient> ingredients = shaped.getIngredients();
        int width = shaped.getWidth();
        int height = shaped.getHeight();
        for (int compactIndex = 0; compactIndex < ingredients.size(); compactIndex++) {
            if (CraftingSlotMapper.craftingGridIndex(compactIndex, width, height) == gridIndex) {
                return ingredients.get(compactIndex);
            }
        }
        return null;
    }

    private static String inputPatchKeyForGrid(ShapedRecipe shaped, EditorModel model,
                                                RecipePatch patch, int gridIndex) {
        String fixedKey = "input." + gridIndex;
        if (hasInputPatch(patch, fixedKey)) {
            return fixedKey;
        }
        boolean fixedGrid = hasInputSlot(model, "input.8");
        int width = shaped.getWidth();
        int height = shaped.getHeight();
        for (EditorSlot slot : model.slots()) {
            if (!"input".equals(slot.role())) {
                continue;
            }
            int compactIndex = inputIndex(slot.key());
            if (compactIndex < 0) {
                continue;
            }
            int mapped = fixedGrid ? compactIndex
                    : CraftingSlotMapper.craftingGridIndex(compactIndex,
                    width, height);
            if (mapped == gridIndex && hasInputPatch(patch, slot.key())) {
                return slot.key();
            }
        }
        return null;
    }

    private static boolean hasInputPatch(RecipePatch patch, String slotKey) {
        return patch.fields().containsKey(slotKey + ".item")
                || patch.fields().containsKey(slotKey + ".count");
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

    private static boolean isCraftingGridSerializer(String serializerId) {
        return "minecraft:crafting_shaped".equals(serializerId)
                || "minecraft:crafting_shapeless".equals(serializerId);
    }

    private static JsonObject ingredientJson(EditorIngredient ingredient) {
        JsonObject json = new JsonObject();
        json.addProperty("item", ingredient.itemId());
        return json;
    }

    /** Keep tag/custom ingredient JSON for untouched slots. Once a slot is
     * edited, the concrete item selected by the user intentionally replaces
     * the original ingredient. */
    private static JsonElement ingredientJson(Ingredient original, EditorIngredient current,
                                              boolean edited) {
        if (!edited && original != null && !original.isEmpty()) {
            try {
                Optional<JsonElement> encoded = Ingredient.CODEC
                        .encodeStart(JsonOps.INSTANCE, original).result();
                if (encoded.isPresent()) {
                    return encoded.get();
                }
            } catch (RuntimeException ignored) {
                // Fall through to the representative item if this ingredient
                // uses a codec that cannot be encoded in the current context.
            }
        }
        return ingredientJson(current);
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
