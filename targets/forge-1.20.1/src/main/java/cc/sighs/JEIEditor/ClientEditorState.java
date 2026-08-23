package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditSession;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorIngredient;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Client-only state for the first editor interaction slice. */
final class ClientEditorState {
    private static String lastDrop = "";
    private static final RecipeEditSession session = new RecipeEditSession();
    private static EditorModel lastModel;
    private static String lastSlotKey;
    private static IRecipeSlotsView lastRecipeSlots;
    private static final List<IRecipeSlotDrawable> previewSlots = new ArrayList<IRecipeSlotDrawable>();

    private ClientEditorState() {
    }

    static boolean isEditing() {
        return session.isEditing();
    }

    static void toggle() {
        session.toggle();
    }

    static void setLastDrop(String value) {
        lastDrop = value;
    }

    static String getLastDrop() {
        return lastDrop;
    }

    static RecipePatch getPendingPatch() {
        return session.pending();
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
        lastModel = null;
        lastSlotKey = null;
        lastRecipeSlots = null;
        session.cancel();
    }

    static void rememberTarget(EditorModel model, String slotKey, IRecipeSlotsView slots) {
        lastModel = model;
        lastSlotKey = slotKey;
        lastRecipeSlots = slots;
    }

    static IRecipeSlotsView getLastRecipeSlots() {
        return lastRecipeSlots;
    }

    private static void refreshPreview() {
        if (lastRecipeSlots != null && lastModel != null && session.pending() != null) {
            applyPreview(lastRecipeSlots, lastModel, session.pending());
        } else {
            clearPreview();
        }
    }

    static void applyPreview(IRecipeSlotsView slots, EditorModel model, RecipePatch patch) {
        if (slots == null || model == null || patch == null) {
            return;
        }
        clearPreview();
        int inputIndex = 0;
        for (IRecipeSlotView view : slots.getSlotViews()) {
            if (!(view instanceof IRecipeSlotDrawable)) {
                continue;
            }
            IRecipeSlotDrawable drawable = (IRecipeSlotDrawable) view;
            String slotKey;
            if (view.getRole() == RecipeIngredientRole.INPUT) {
                slotKey = "input." + inputIndex++;
            } else if (view.getRole() == RecipeIngredientRole.OUTPUT) {
                slotKey = "output";
            } else {
                continue;
            }
            if (patch.fields().containsKey(slotKey + ".item")
                    || patch.fields().containsKey(slotKey + ".count")) {
                previewSlots.add(drawable);
                EditorIngredient ingredient = patchedIngredient(model, slotKey, patch);
                if (ingredient == null) {
                    drawable.clearDisplayOverrides();
                } else {
                    ResourceLocation id = ResourceLocation.tryParse(ingredient.itemId());
                    if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                        drawable.createDisplayOverrides().addItemStack(new ItemStack(
                                BuiltInRegistries.ITEM.get(id), ingredient.count()));
                    }
                }
            }
        }
    }

    static void clearPreview() {
        for (IRecipeSlotDrawable slot : previewSlots) {
            slot.clearDisplayOverrides();
        }
        previewSlots.clear();
    }

    private static EditorIngredient patchedIngredient(EditorModel model, String slotKey, RecipePatch patch) {
        EditorIngredient original = model.slots().stream()
                .filter(slot -> slot.key().equals(slotKey))
                .map(cc.sighs.JEIEditor.editor.EditorSlot::ingredient)
                .findFirst()
                .orElse(null);
        String item = patch.fields().get(slotKey + ".item");
        String count = patch.fields().get(slotKey + ".count");
        if ("minecraft:air".equals(item) || "0".equals(count)) {
            return null;
        }
        if (original == null && item == null) {
            return null;
        }
        String itemId = item == null ? original.itemId() : item;
        int amount;
        try {
            amount = count == null ? original.count() : Integer.parseInt(count);
        } catch (NumberFormatException exception) {
            return null;
        }
        try {
            return new EditorIngredient(itemId, amount);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static EditorModel getLastModel() {
        return lastModel;
    }

    static String getLastSlotKey() {
        return lastSlotKey;
    }

    static RecipePatch adjustOutputCount(int delta) {
        if (lastModel == null || CookingRecipeEditorAdapter.supportsSerializer(lastModel.serializerId())) {
            return null;
        }
        int current = lastModel.slots().stream()
                .filter(slot -> "output".equals(slot.role()) && slot.ingredient() != null)
                .map(slot -> slot.ingredient().count())
                .findFirst()
                .orElse(0);
        if (session.pending() != null && session.pending().recipeId().equals(lastModel.recipeId())) {
            String value = session.pending().fields().get("output.count");
            if (value != null) {
                try {
                    current = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        int next = current + delta;
        if (next < 1 || next > 64) {
            return null;
        }
        return RecipeEditorAdapters.setOutputCount(lastModel, next);
    }

    static RecipePatch adjustExperience(float delta) {
        if (lastModel == null || !CookingRecipeEditorAdapter.supportsSerializer(lastModel.serializerId())) return null;
        float current = propertyFloat("experience", 0.0F);
        float next = Math.round((current + delta) * 10.0F) / 10.0F;
        return next < 0.0F || next > 1000.0F ? null : RecipeEditorAdapters.setExperience(lastModel, next);
    }
    static RecipePatch adjustCookingTime(int delta) {
        if (lastModel == null || !CookingRecipeEditorAdapter.supportsSerializer(lastModel.serializerId())) return null;
        int next = propertyInt("cooking_time", 1) + delta;
        return next < 1 || next > 1000000 ? null : RecipeEditorAdapters.setCookingTime(lastModel, next);
    }
    private static float propertyFloat(String key, float fallback) {
        String value = session.pending() == null ? null : session.pending().fields().get("recipe." + key); if (value == null) value = lastModel.properties().get(key);
        try { return value == null ? fallback : Float.parseFloat(value); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static int propertyInt(String key, int fallback) {
        String value = session.pending() == null ? null : session.pending().fields().get("recipe." + key); if (value == null) value = lastModel.properties().get(key);
        try { return value == null ? fallback : Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    static void applyResult(boolean success, String message, String recipeId) {
        lastDrop = message + (recipeId.isEmpty() ? "" : ": " + recipeId);
        if (success) {
            session.reset();
            clearPreview();
            lastModel = null;
            lastSlotKey = null;
            lastRecipeSlots = null;
        }
    }

    static boolean isRecipeScreen(Screen screen) {
        return screen instanceof mezz.jei.gui.recipes.RecipesGui;
    }
}
