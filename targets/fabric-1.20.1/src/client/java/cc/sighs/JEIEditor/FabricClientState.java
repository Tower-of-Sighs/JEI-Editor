package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditSession;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class FabricClientState {
    private static String lastMessage = "";
    private static final RecipeEditSession session = new RecipeEditSession();
    private static EditorModel model;
    private static String lastSlotKey;
    private static IRecipeSlotsView recipeSlots;
    private static final List<IRecipeSlotDrawable> previewSlots = new ArrayList<IRecipeSlotDrawable>();

    private FabricClientState() { }

    static boolean isEditing() { return session.isEditing(); }
    static void toggle() { session.toggle(); }
    static String getLastDrop() { return lastMessage; }
    static void setLastDrop(String value) { lastMessage = value; }
    static RecipePatch getPendingPatch() { return session.pending(); }
    static EditorModel getLastModel() { return model; }
    static String getLastSlotKey() { return lastSlotKey; }

    static void rememberTarget(EditorModel value, String slotKey, IRecipeSlotsView slots) {
        model = value;
        lastSlotKey = slotKey;
        recipeSlots = slots;
    }

    static void setPendingPatch(RecipePatch patch) {
        session.apply(patch);
        refreshPreview();
    }

    static void clearPendingPatch() {
        session.reset();
        clearPreview();
    }

    static void undo() {
        session.undo();
        refreshPreview();
    }

    static void redo() {
        session.redo();
        refreshPreview();
    }

    static void closeRecipeScreen() {
        clearPendingPatch();
        model = null;
        lastSlotKey = null;
        recipeSlots = null;
        session.cancel();
    }

    private static void refreshPreview() {
        if (recipeSlots != null && model != null && session.pending() != null) {
            applyPreview(recipeSlots, model, session.pending());
        } else {
            clearPreview();
        }
    }

    static void applyPreview(IRecipeSlotsView slots, EditorModel editorModel, RecipePatch patch) {
        clearPreview();
        int inputIndex = 0;
        for (IRecipeSlotView view : slots.getSlotViews()) {
            if (!(view instanceof IRecipeSlotDrawable)) continue;
            IRecipeSlotDrawable drawable = (IRecipeSlotDrawable) view;
            String slotKey;
            if (view.getRole() == RecipeIngredientRole.INPUT) slotKey = "input." + inputIndex++;
            else if (view.getRole() == RecipeIngredientRole.OUTPUT) slotKey = "output";
            else continue;
            if (!patch.fields().containsKey(slotKey + ".item") && !patch.fields().containsKey(slotKey + ".count")) continue;
            previewSlots.add(drawable);
            EditorIngredient ingredient = patchedIngredient(editorModel, slotKey, patch);
            if (ingredient == null) {
                drawable.clearDisplayOverrides();
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(ingredient.itemId());
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                drawable.createDisplayOverrides().addItemStack(new ItemStack(
                        BuiltInRegistries.ITEM.get(id), ingredient.count()));
            }
        }
    }

    static void clearPreview() {
        for (IRecipeSlotDrawable slot : previewSlots) slot.clearDisplayOverrides();
        previewSlots.clear();
    }

    private static EditorIngredient patchedIngredient(EditorModel editorModel, String slotKey, RecipePatch patch) {
        EditorIngredient original = editorModel.slots().stream()
                .filter(slot -> slot.key().equals(slotKey))
                .map(cc.sighs.JEIEditor.editor.EditorSlot::ingredient)
                .findFirst().orElse(null);
        String item = patch.fields().get(slotKey + ".item");
        String count = patch.fields().get(slotKey + ".count");
        if ("minecraft:air".equals(item) || "0".equals(count)) return null;
        if (original == null && item == null) return null;
        String itemId = item == null ? original.itemId() : item;
        try {
            return new EditorIngredient(itemId, count == null ? original.count() : Integer.parseInt(count));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static RecipePatch adjustOutputCount(int delta) {
        if (model == null || CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) return null;
        int current = model.slots().stream()
                .filter(slot -> "output".equals(slot.role()) && slot.ingredient() != null)
                .map(slot -> slot.ingredient().count()).findFirst().orElse(0);
        if (session.pending() != null && session.pending().recipeId().equals(model.recipeId())) {
            String value = session.pending().fields().get("output.count");
            if (value != null) {
                try { current = Integer.parseInt(value); }
                catch (NumberFormatException exception) { return null; }
            }
        }
        int next = current + delta;
        return next < 1 || next > 64 ? null : RecipeEditorAdapters.setOutputCount(model, next);
    }

    static RecipePatch adjustExperience(float delta) {
        if (model == null || !CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) return null;
        float current = propertyFloat("experience", 0.0F);
        float next = Math.round((current + delta) * 10.0F) / 10.0F;
        return next < 0.0F || next > 1000.0F ? null : RecipeEditorAdapters.setExperience(model, next);
    }
    static RecipePatch adjustCookingTime(int delta) {
        if (model == null || !CookingRecipeEditorAdapter.supportsSerializer(model.serializerId())) return null;
        int next = propertyInt("cooking_time", 1) + delta;
        return next < 1 || next > 1000000 ? null : RecipeEditorAdapters.setCookingTime(model, next);
    }
    private static float propertyFloat(String key, float fallback) {
        String value = session.pending() == null ? null : session.pending().fields().get("recipe." + key); if (value == null) value = model.properties().get(key);
        try { return value == null ? fallback : Float.parseFloat(value); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static int propertyInt(String key, int fallback) {
        String value = session.pending() == null ? null : session.pending().fields().get("recipe." + key); if (value == null) value = model.properties().get(key);
        try { return value == null ? fallback : Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    static void result(boolean success, String message) {
        lastMessage = message;
        if (success) {
            session.reset();
            clearPreview();
            model = null;
            lastSlotKey = null;
            recipeSlots = null;
        }
    }

    static boolean isRecipeScreen(Screen screen) {
        return screen instanceof mezz.jei.gui.recipes.RecipesGui;
    }
}
