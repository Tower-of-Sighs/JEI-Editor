package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import java.util.Optional;

final class RecipeEditorAdapters {
    private RecipeEditorAdapters() { }
    static Optional<EditorModel> createModel(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        Optional<EditorModel> crafting = CraftingRecipeEditorAdapter.createModel(holder, registries);
        return crafting.isPresent() ? crafting : CookingRecipeEditorAdapter.createModel(holder, registries);
    }
    static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        return CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())
                ? CookingRecipeEditorAdapter.replaceInput(model, slotKey, stack)
                : CraftingRecipeEditorAdapter.replaceInput(model, slotKey, stack);
    }
    static RecipePatch clearSlot(EditorModel model, String slotKey) {
        if (CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) {
            throw new IllegalArgumentException("cooking input cannot be cleared; replace it with another item");
        }
        return CraftingRecipeEditorAdapter.clearSlot(model, slotKey);
    }
    static RecipePatch setOutputCount(EditorModel model, int count) {
        return CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())
                ? CookingRecipeEditorAdapter.setOutputCount(model, count)
                : CraftingRecipeEditorAdapter.setOutputCount(model, count);
    }
    static RecipePatch setExperience(EditorModel model, float value) { return CookingRecipeEditorAdapter.setExperience(model, value); }
    static RecipePatch setCookingTime(EditorModel model, int value) { return CookingRecipeEditorAdapter.setCookingTime(model, value); }
}
