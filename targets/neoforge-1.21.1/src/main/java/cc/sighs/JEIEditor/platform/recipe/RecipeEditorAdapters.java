package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.HolderLookup;
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
    public static RecipePatch replaceSlot(EditorModel model, String slotKey, ItemStack stack,
                                          HolderLookup.Provider registries) {
        if ("output".equals(slotKey)) {
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
    public static RecipePatch setOutputCount(EditorModel model, int count) {
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
