package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditSession;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorSlot;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-only state for the first editor interaction slice. */
final class ClientEditorState {
    private static String lastDrop = "";
    // Keep the UI switch independent from the pending-edit session. JEI may reset
    // or recreate recipe state while the recipe screen remains open.
    private static boolean editing;
    // A button press can be delivered more than once while JEI rebuilds its
    // widget tree. Re-arm only after the physical mouse button is released.
    private static boolean toggleArmed = true;
    // Guard all editor buttons against duplicate press routing for one click.
    private static boolean actionClickArmed = true;
    // A recipe sync can make JEI rebuild or temporarily replace its screen.
    // Keep the screen identity until the save result arrives so that only the
    // page affected by this save is restored.
    private static Screen saveScreen;
    private static boolean saveRestorePending;
    private static final RecipeEditSession session = new RecipeEditSession();
    private static final Map<String, RecipePatch> submittedSavePatches =
            new LinkedHashMap<String, RecipePatch>();
    private static EditorModel lastModel;
    private static String lastSlotKey;
    private static IRecipeSlotsView lastRecipeSlots;
    private static Object lastRecipe;
    private static final List<IRecipeSlotDrawable> previewSlots = new ArrayList<IRecipeSlotDrawable>();
    private static final List<Rect2i> ghostHighlightAreas = new ArrayList<Rect2i>();

    private ClientEditorState() {
    }

    static boolean isEditing() {
        return editing;
    }

    static void toggle() {
        editing = !editing;
    }

    /** Accept at most one toggle callback for each physical mouse press. */
    static boolean toggleFromButton() {
        if (!toggleArmed) {
            return false;
        }
        toggleArmed = false;
        toggle();
        return true;
    }

    static void armToggle() {
        toggleArmed = true;
    }

    static boolean consumeActionClick() {
        if (!actionClickArmed) {
            return false;
        }
        actionClickArmed = false;
        return true;
    }

    static void armActionClick() {
        actionClickArmed = true;
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

    static List<RecipePatch> getPendingPatches() {
        return session.pendingPatches();
    }

    static RecipePatch getPendingPatch(String recipeId) {
        for (RecipePatch patch : session.pendingPatches()) {
            if (patch.recipeId().equals(recipeId)) {
                return patch;
            }
        }
        return null;
    }

    static void setPendingPatch(RecipePatch patch) {
        session.apply(patch);
        refreshPreview();
    }

    static void clearPendingPatch() {
        session.reset();
        clearPreview();
        lastModel = null;
        lastSlotKey = null;
        lastRecipeSlots = null;
        lastRecipe = null;
    }

    static void clearPendingPatches(java.util.Collection<String> recipeIds) {
        session.remove(recipeIds);
        clearPreview();
        lastModel = null;
        lastSlotKey = null;
        lastRecipeSlots = null;
        lastRecipe = null;
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
    }

    static void markSaveSubmitted(Screen screen) {
        saveScreen = screen;
        saveRestorePending = true;
    }

    static void markSaveSubmittedPatches(List<RecipePatch> patches) {
        if (patches != null) {
            for (RecipePatch patch : patches) {
                submittedSavePatches.put(patch.recipeId(), patch);
            }
        }
    }

    static Screen getSaveScreen() {
        return saveRestorePending ? saveScreen : null;
    }

    static void clearSaveRestore() {
        saveScreen = null;
        saveRestorePending = false;
    }

    static void rememberTarget(EditorModel model, String slotKey, IRecipeSlotsView slots, Object recipe) {
        lastModel = model;
        lastSlotKey = slotKey;
        lastRecipeSlots = slots;
        lastRecipe = recipe;
    }

    static IRecipeSlotsView getLastRecipeSlots() {
        return lastRecipeSlots;
    }

    /** Reapply the local preview after JEI rebuilds or cycles its slot displays. */
    static void refreshPreview() {
        clearPreview();
        if (lastRecipeSlots != null && lastModel != null && session.pending() != null) {
            applyPreview(lastRecipeSlots, lastModel, session.pending(), lastRecipe);
        }
    }

    static void beginPreviewRefresh() {
        clearPreview();
    }

    static void applyPreview(IRecipeSlotsView slots, EditorModel model, RecipePatch patch,
                             Object displayedRecipe) {
        if (slots == null || model == null || patch == null) {
            return;
        }
        for (IRecipeSlotView view : slots.getSlotViews()) {
            if (!(view instanceof IRecipeSlotDrawable)) {
                continue;
            }
            IRecipeSlotDrawable drawable = (IRecipeSlotDrawable) view;
            String slotKey = CraftingSlotMapper.slotKey(model, displayedRecipe, slots.getSlotViews(), view);
            if (view.getRole() != RecipeIngredientRole.INPUT
                    && view.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            if (slotKey == null || (!patch.fields().containsKey(slotKey + ".item")
                    && !patch.fields().containsKey(slotKey + ".count"))) {
                // Leave untouched slots under JEI's own display pipeline. An
                // empty override would hide their original ingredient.
                continue;
            }
            previewSlots.add(drawable);
            EditorIngredient ingredient = patchedIngredient(model, slotKey, patch);
            if (ingredient != null) {
                ResourceLocation id = ResourceLocation.tryParse(ingredient.itemId());
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    drawable.createDisplayOverrides().addItemStack(new ItemStack(
                            BuiltInRegistries.ITEM.get(id), ingredient.count()));
                }
            } else {
                // An empty override is different from no override: without it
                // JEI falls back to the recipe's original ingredient and the
                // item remains visible until the server reloads the recipe.
                drawable.createDisplayOverrides();
            }
        }
    }

    static void clearPreview() {
        for (IRecipeSlotDrawable slot : previewSlots) {
            slot.clearDisplayOverrides();
        }
        previewSlots.clear();
    }

    static void setGhostHighlightAreas(List<Rect2i> areas) {
        ghostHighlightAreas.clear();
        if (areas != null) {
            ghostHighlightAreas.addAll(areas);
        }
    }

    static void clearGhostHighlightAreas() {
        ghostHighlightAreas.clear();
    }

    static void drawGhostHighlights(GuiGraphics graphics, double mouseX, double mouseY) {
        for (Rect2i area : ghostHighlightAreas) {
            boolean hovered = mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                    && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
            graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(),
                    area.getY() + area.getHeight(), hovered ? 0x4849c978 : 0x3039b86b);
        }
    }

    private static EditorIngredient patchedIngredient(EditorModel model, String slotKey, RecipePatch patch) {
        EditorSlot originalSlot = model.slots().stream()
                .filter(slot -> slot.key().equals(slotKey))
                .findFirst()
                .orElse(null);
        EditorIngredient original = originalSlot == null ? null : originalSlot.ingredient();
        String item = patch.fields().get(slotKey + ".item");
        String count = patch.fields().get(slotKey + ".count");
        if ("minecraft:air".equals(item) || "0".equals(count)) {
            return null;
        }
        if (original == null && item == null) {
            return null;
        }
        if (original == null && count == null) {
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
        if (lastModel == null) {
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

    static void setFuelBurnTime(EditorModel model, int burnTime) {
        if (model == null || !FuelRecipeEditorAdapter.SERIALIZER.equals(model.serializerId())) {
            return;
        }
        RecipePatch pending = getPendingPatch(model.recipeId());
        String baseFingerprint = pending == null
                ? model.baseFingerprint() : pending.baseFingerprint();
        setPendingPatch(FuelRecipeEditorAdapter.setBurnTime(model, burnTime, baseFingerprint));
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
        // The response is the end of this save attempt, whether it succeeded
        // or failed. A failed request must not restore a page on a later,
        // unrelated recipe synchronization.
        clearSaveRestore();
        if (!success) {
            submittedSavePatches.clear();
        }
        if (success && message.startsWith("Saved ")) {
            // Save persists the patch but deliberately leaves it pending so
            // the current page still shows the blue modified state until the
            // separate Reload action is chosen.
            return;
        }
        if (success) {
            java.util.Optional<net.minecraft.resources.ResourceLocation> resetFuel =
                    FuelRecipeEditorAdapter.itemIdFromRecipeId(recipeId);
            if (resetFuel.isPresent()) {
                FuelOverrideState.remove(resetFuel.get());
                JeiRecipeEditorPlugin.applyFuelReset(resetFuel.get());
            }
            java.util.LinkedHashMap<String, RecipePatch> allKnown =
                    new java.util.LinkedHashMap<String, RecipePatch>();
            allKnown.putAll(submittedSavePatches);
            for (RecipePatch patch : session.pendingPatches()) {
                allKnown.put(patch.recipeId(), patch);
            }
            java.util.List<RecipePatch> submitted =
                    new java.util.ArrayList<RecipePatch>(allKnown.values());
            for (RecipePatch patch : submitted) {
                if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
                    FuelOverrideState.applyPatch(patch);
                }
            }
            JeiRecipeEditorPlugin.applyFuelResult(submitted);
            if (message.startsWith("Reloaded ")) {
                java.util.List<String> reloaded = new java.util.ArrayList<String>();
                for (Map.Entry<String, RecipePatch> entry : submittedSavePatches.entrySet()) {
                    RecipePatch current = getPendingPatch(entry.getKey());
                    if (samePatch(current, entry.getValue())) {
                        reloaded.add(entry.getKey());
                    }
                }
                session.remove(reloaded);
                submittedSavePatches.clear();
                clearPreview();
            } else {
                session.reset();
                clearPreview();
            }
            lastModel = null;
            lastSlotKey = null;
            lastRecipeSlots = null;
            lastRecipe = null;
        }
    }

    private static boolean samePatch(RecipePatch left, RecipePatch right) {
        return left != null && right != null
                && left.recipeId().equals(right.recipeId())
                && left.serializerId().equals(right.serializerId())
                && left.baseFingerprint().equals(right.baseFingerprint())
                && left.fields().equals(right.fields());
    }

    static boolean isRecipeScreen(Screen screen) {
        return screen instanceof mezz.jei.gui.recipes.RecipesGui;
    }
}
