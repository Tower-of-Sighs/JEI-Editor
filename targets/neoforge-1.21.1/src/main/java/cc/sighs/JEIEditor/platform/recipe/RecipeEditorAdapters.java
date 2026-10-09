package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.recipe.RecipeFieldMapping;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import mezz.jei.api.recipe.vanilla.IJeiFuelingRecipe;
import net.minecraft.resources.ResourceLocation;
import java.util.Optional;

public final class RecipeEditorAdapters {
    private RecipeEditorAdapters() { }
    public static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Optional<EditorModel> crafting = CraftingRecipeEditorAdapter.createModel(holder, registries);
        if (crafting.isPresent()) {
            return crafting;
        }
        Optional<EditorModel> cooking = CookingRecipeEditorAdapter.createModel(holder, registries);
        if (cooking.isPresent()) {
            return cooking;
        }
        return VanillaSpecialRecipeEditorAdapter.createModel(holder, registries);
    }

    /**
     * Whether this editor really implements the serializer, i.e. it is one of the
     * vanilla serializers with a dedicated adapter.
     *
     * <p>Recipe classes are not a reliable identity: a mod can ship a serializer whose
     * recipe merely extends a vanilla class, and then these adapters would build a
     * model for it while the written JSON - rebuilt in the vanilla serializer's own
     * shape - is rejected by that mod's codec. Every vanilla adapter therefore gates on
     * the serializer id it implements, and the write path asks this method before it
     * rebuilds a vanilla-shaped recipe. A modded serializer that this editor does
     * support is a declared mod recipe type ({@code ModdedRecipeAdapters}), which is
     * patched in place instead of being rebuilt.
     */
    public static boolean isImplementedVanillaSerializer(String serializerId) {
        return CraftingRecipeEditorAdapter.supportsSerializer(serializerId)
                || CookingRecipeEditorAdapter.supportsSerializer(serializerId)
                || VanillaSpecialRecipeEditorAdapter.supportsSerializer(serializerId);
    }
    public static Optional<EditorModel> createFuelModel(Object recipe) {
        return recipe instanceof IJeiFuelingRecipe
                ? FuelRecipeEditorAdapter.createModel((IJeiFuelingRecipe) recipe)
                : Optional.<EditorModel>empty();
    }

    public static Optional<EditorModel> createJeiModel(Object recipe) {
        return JeiVanillaRecipeEditorAdapter.createModel(recipe);
    }

    public static Optional<EditorModel> createJeiModel(Object recipe, ResourceLocation fallbackUid) {
        return JeiVanillaRecipeEditorAdapter.createModel(recipe, fallbackUid);
    }
    public static Optional<EditorModel> createJeiModel(Object recipe, ResourceLocation fallbackUid,
                                                        HolderLookup.Provider registries) {
        return JeiVanillaRecipeEditorAdapter.createModel(recipe, fallbackUid, registries);
    }
    public static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        // A furnace fuel entry is identified by its item: the generated recipe id encodes it
        // (jeieditor:fuel/<namespace>/<path>), so swapping the item would leave the id
        // describing a different item and the server refuses such a patch
        // (canApplyFuel requires itemIdFromRecipeId == itemId). The client already does not
        // offer the drag (visibleEditableTargets skips fuel models); refusing here keeps the
        // adapter from building a patch the server would reject.
        if (FuelRecipeEditorAdapter.SERIALIZER.equals(model.serializerId())) {
            throw new IllegalArgumentException(
                    "a furnace fuel entry is identified by its item; change its burn time instead");
        }
        if (ModdedRecipeAdapters.supports(model.serializerId())) {
            return ModdedRecipeAdapters.replaceInput(model, slotKey, stack);
        }
        if (CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return CookingRecipeEditorAdapter.replaceInput(model, slotKey, stack);
        }
        if (VanillaSpecialRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return VanillaSpecialRecipeEditorAdapter.replaceInput(model, slotKey, stack);
        }
        if (JeiVanillaRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return JeiVanillaRecipeEditorAdapter.replaceInput(model, slotKey, stack);
        }
        return CraftingRecipeEditorAdapter.replaceInput(model, slotKey, stack);
    }
    public static RecipePatch replaceSlot(EditorModel model, String slotKey, ItemStack stack) {
        return replaceSlot(model, slotKey, stack, null);
    }

    /**
     * Replaces one slot with an ingredient of any kind the page declares.
     *
     * <p>A declared page routes to its declaration, which checks the kind against
     * the declared field. Every other page - the vanilla serializers, the JEI
     * generated pages, a cooking or stonecutting recipe - only has item fields, so
     * a fluid or a chemical is refused here rather than being written into an item
     * node.
     */
    public static RecipePatch replaceSlot(EditorModel model, String slotKey, EditorIngredient ingredient) {
        if (model == null || ingredient == null) {
            throw new IllegalArgumentException("recipe slot and ingredient are required");
        }
        if (ModdedRecipeAdapters.supports(model.serializerId())) {
            return ModdedRecipeAdapters.replaceSlot(model, slotKey, ingredient);
        }
        if (!ingredient.isItem()) {
            throw new IllegalArgumentException(
                    "this page has only item slots; a " + ingredient.kind().id() + " cannot be placed here");
        }
        return replaceSlot(model, slotKey, itemStack(ingredient), null);
    }

    /** An {@code ItemStack} for an item ingredient, refusing an unknown item. */
    private static ItemStack itemStack(EditorIngredient ingredient) {
        ResourceLocation id = ResourceLocation.tryParse(ingredient.id());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalArgumentException("unknown item " + ingredient.id());
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id), ingredient.amount());
    }
    public static RecipePatch replaceSlot(EditorModel model, String slotKey, ItemStack stack,
                                          HolderLookup.Provider registries) {
        if (ModdedRecipeAdapters.supports(model.serializerId())) {
            if (RecipeFieldMapping.outputIndex(slotKey) >= 0) {
                return ModdedRecipeAdapters.replaceOutput(model, slotKey, stack);
            }
            return ModdedRecipeAdapters.replaceInput(model, slotKey, stack);
        }
        if (RecipeFieldMapping.outputIndex(slotKey) >= 0) {
            if (CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                return CookingRecipeEditorAdapter.replaceOutput(model, stack);
            }
            if (VanillaSpecialRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                return VanillaSpecialRecipeEditorAdapter.replaceOutput(model, stack);
            }
            if (JeiVanillaRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
                return JeiVanillaRecipeEditorAdapter.replaceOutput(model, stack, registries);
            }
            return CraftingRecipeEditorAdapter.replaceOutput(model, stack);
        }
        if (JeiVanillaRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return JeiVanillaRecipeEditorAdapter.replaceInput(model, slotKey, stack, registries);
        }
        return replaceInput(model, slotKey, stack);
    }
    public static RecipePatch clearSlot(EditorModel model, String slotKey) {
        if (ModdedRecipeAdapters.supports(model.serializerId())) {
            return ModdedRecipeAdapters.clearSlot(model, slotKey);
        }
        if (CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            throw new IllegalArgumentException("cooking input cannot be cleared; replace it with another item");
        }
        if (VanillaSpecialRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return VanillaSpecialRecipeEditorAdapter.clearSlot(model, slotKey);
        }
        if (JeiVanillaRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return JeiVanillaRecipeEditorAdapter.clearSlot(model, slotKey);
        }
        return CraftingRecipeEditorAdapter.clearSlot(model, slotKey);
    }
    /**
     * Resizes one output slot. {@code slotKey} is {@code output} for a
     * single-output page and {@code output.<n>} for one of several; the vanilla
     * adapters only ever expose a single {@code output} slot.
     */
    public static RecipePatch setOutputCount(EditorModel model, String slotKey, int count) {
        if (ModdedRecipeAdapters.supports(model.serializerId())) {
            return ModdedRecipeAdapters.setOutputCount(model, slotKey, count);
        }
        if (CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return CookingRecipeEditorAdapter.setOutputCount(model, count);
        }
        if (VanillaSpecialRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return VanillaSpecialRecipeEditorAdapter.setOutputCount(model, count);
        }
        if (JeiVanillaRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            return JeiVanillaRecipeEditorAdapter.setOutputCount(model, count);
        }
        return CraftingRecipeEditorAdapter.setOutputCount(model, count);
    }
    public static RecipePatch setExperience(EditorModel model, float value) { return CookingRecipeEditorAdapter.setExperience(model, value); }
    public static RecipePatch setCookingTime(EditorModel model, int value) { return CookingRecipeEditorAdapter.setCookingTime(model, value); }
    public static RecipePatch setFuelBurnTime(EditorModel model, int value) { return FuelRecipeEditorAdapter.setBurnTime(model, value); }
}
