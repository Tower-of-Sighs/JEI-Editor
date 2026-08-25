package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.platform.recipe.JeiVanillaRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeCreationAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** Replaces JEI's generated anvil-cost text with an editable level input. */
final class AnvilExperienceInputController {
    private static final int INPUT_HEIGHT = 14;
    private static final int MAX_COST = 1_000_000;
    private static final Map<RecipesGui, Map<String, AnvilCostInput>> inputsByScreen =
            new WeakHashMap<RecipesGui, Map<String, AnvilCostInput>>();

    private AnvilExperienceInputController() {
    }

    static void prepare(RecipesGui gui) {
        if (gui == null || Minecraft.getInstance().level == null) {
            close(gui);
            return;
        }

        // Layout instances are replaced while paging and cycling recipes.
        // Put any previously hidden JEI text back before selecting the
        // current page's layouts.
        JeiRecipeIntrospection.restoreHiddenRecipeText();

        Map<String, AnvilCostInput> inputs = inputsByScreen.computeIfAbsent(gui,
                ignored -> new LinkedHashMap<String, AnvilCostInput>());
        Set<String> visible = new HashSet<String>();
        for (IRecipeLayoutWithButtons<?> layout : JeiRecipeIntrospection.visibleLayouts(gui)) {
            Object recipe = layout.getRecipeLayout().getRecipe();
            Optional<EditorModel> source = RecipeEditorAdapters.createJeiModel(
                    recipe,
                    JeiRecipeIntrospection.recipeId(layout.getRecipeLayout().getRecipeCategory(), recipe)
                            .orElse(null),
                    Minecraft.getInstance().level.registryAccess());
            if (!source.isPresent() || !"jei:anvil".equals(source.get().serializerId())) {
                continue;
            }

            EditableAnvil editable = editableAnvil(gui, source.get());
            RecipePatch patch = editable == null
                    ? JeiRecipeEditorPlugin.createdAnvilPatch(source.get().recipeId())
                    : editable.patch;
            if (patch == null || !RecipePatchSemantics.isCreation(patch)) {
                continue;
            }
            EditorModel model = editable == null ? source.get() : editable.model;
            String recipeId = model.recipeId();
            visible.add(recipeId);
            AnvilCostInput input = inputs.get(recipeId);
            if (input == null || input.editable != ClientEditorState.isEditing()) {
                input = new AnvilCostInput(model, patch, ClientEditorState.isEditing());
                inputs.put(recipeId, input);
            }
            input.update(layout.getRecipeLayout(), model, patch, recipe,
                    layout.getRecipeLayout().getRecipeSlotsView());
            JeiRecipeIntrospection.setRecipeTextVisible(layout.getRecipeLayout(), false);
        }

        List<String> removed = new ArrayList<String>();
        for (String recipeId : inputs.keySet()) {
            if (!visible.contains(recipeId)) {
                removed.add(recipeId);
            }
        }
        for (String recipeId : removed) {
            inputs.remove(recipeId);
        }
        if (inputs.isEmpty()) {
            inputsByScreen.remove(gui);
        }
    }

    static void render(RecipesGui gui, GuiGraphics graphics, int mouseX, int mouseY) {
        Map<String, AnvilCostInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return;
        }
        for (AnvilCostInput input : inputs.values()) {
            input.render(graphics, mouseX, mouseY);
        }
    }

    static boolean mouseClicked(RecipesGui gui, double mouseX, double mouseY, int button) {
        Map<String, AnvilCostInput> inputs = inputsByScreen.get(gui);
        if (inputs == null || button != 0) {
            return false;
        }
        AnvilCostInput clicked = null;
        for (AnvilCostInput input : inputs.values()) {
            if (input.isMouseOver(mouseX, mouseY)) {
                clicked = input;
                break;
            }
        }
        for (AnvilCostInput input : inputs.values()) {
            if (input != clicked) {
                input.commitAndBlur();
            }
        }
        return clicked != null && clicked.mouseClicked(mouseX, mouseY, button);
    }

    static boolean isMouseOver(RecipesGui gui, double mouseX, double mouseY) {
        Map<String, AnvilCostInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return false;
        }
        for (AnvilCostInput input : inputs.values()) {
            if (input.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    static boolean keyPressed(RecipesGui gui, int keyCode, int scanCode, int modifiers) {
        AnvilCostInput focused = focusedInput(gui);
        return focused != null && focused.keyPressed(keyCode, scanCode, modifiers);
    }

    static boolean charTyped(RecipesGui gui, char codePoint, int modifiers) {
        AnvilCostInput focused = focusedInput(gui);
        return focused != null && focused.charTyped(codePoint, modifiers);
    }

    /** Commit an active field before an editor action reads the pending patch. */
    static void commitAll(RecipesGui gui) {
        Map<String, AnvilCostInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return;
        }
        for (AnvilCostInput input : inputs.values()) {
            input.commitAndBlur();
        }
    }

    static void close(RecipesGui gui) {
        if (gui == null) {
            return;
        }
        Map<String, AnvilCostInput> inputs = inputsByScreen.remove(gui);
        if (inputs != null) {
            for (AnvilCostInput input : inputs.values()) {
                input.commitAndBlur();
            }
        }
        JeiRecipeIntrospection.restoreHiddenRecipeText();
    }

    private static AnvilCostInput focusedInput(RecipesGui gui) {
        Map<String, AnvilCostInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return null;
        }
        for (AnvilCostInput input : inputs.values()) {
            if (input.isFocused()) {
                return input;
            }
        }
        return null;
    }

    private static EditableAnvil editableAnvil(RecipesGui gui, EditorModel source) {
        Optional<EditorModel> draft = ClientEditorState.creationModelFor(gui, source.recipeId());
        if (draft.isPresent()) {
            return new EditableAnvil(draft.get(), ClientEditorState.getPendingPatch(draft.get().recipeId()));
        }
        RecipePatch patch = ClientEditorState.getPendingPatch(source.recipeId());
        if (!RecipePatchSemantics.isCreation(patch)) {
            patch = JeiRecipeEditorPlugin.createdAnvilPatch(source.recipeId());
        }
        if (!RecipePatchSemantics.isCreation(patch)) {
            return null;
        }
        try {
            return new EditableAnvil(RecipeCreationAdapter.modelFromPatch(patch), patch);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isPotentialCost(String value) {
        if (value == null || value.length() > 7) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseCost(String value) {
        try {
            int cost = Integer.parseInt(value);
            return cost >= 1 && cost <= MAX_COST ? Integer.valueOf(cost) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class EditableAnvil {
        private final EditorModel model;
        private final RecipePatch patch;

        private EditableAnvil(EditorModel model, RecipePatch patch) {
            this.model = model;
            this.patch = patch;
        }
    }

    private static final class AnvilCostInput {
        private final EditBox input;
        private final boolean editable;
        private EditorModel model;
        private RecipePatch baselinePatch;
        private Object recipe;
        private IRecipeSlotsView slots;
        private String prefix = "";
        private String suffix = "";
        private int prefixX;
        private int suffixX;
        private int textY;
        private int lastValidCost;
        private boolean visible;

        private AnvilCostInput(EditorModel model, RecipePatch patch, boolean editable) {
            this.editable = editable;
            this.model = model;
            this.baselinePatch = patch;
            this.lastValidCost = JeiVanillaRecipeEditorAdapter.anvilCost(model, patch);
            if (editable) {
                this.input = new EditBox(Minecraft.getInstance().font, 0, 0, 24, INPUT_HEIGHT,
                        Component.literal("Anvil experience cost"));
                this.input.setMaxLength(7);
                this.input.setFilter(AnvilExperienceInputController::isPotentialCost);
                this.input.setValue(Integer.toString(lastValidCost));
            } else {
                this.input = null;
            }
        }

        private void update(IRecipeLayoutDrawable<?> layout, EditorModel updatedModel,
                            RecipePatch updatedPatch, Object updatedRecipe,
                            IRecipeSlotsView updatedSlots) {
            this.model = updatedModel;
            this.baselinePatch = updatedPatch;
            this.recipe = updatedRecipe;
            this.slots = updatedSlots;
            int currentCost = JeiVanillaRecipeEditorAdapter.anvilCost(updatedModel,
                    ClientEditorState.getPendingPatch(updatedModel.recipeId()) == null
                            ? updatedPatch : ClientEditorState.getPendingPatch(updatedModel.recipeId()));
            if ((input == null || !input.isFocused()) && currentCost != lastValidCost) {
                lastValidCost = currentCost;
                if (input != null) {
                    input.setValue(Integer.toString(currentCost));
                }
            }

            String number = Integer.toString(currentCost);
            String originalText = Component.translatable("container.repair.cost", number).getString();
            int numberStart = originalText.lastIndexOf(number);
            if (numberStart >= 0) {
                prefix = originalText.substring(0, numberStart);
                suffix = originalText.substring(numberStart + number.length());
            } else {
                prefix = originalText + " ";
                suffix = "";
            }

            Font font = Minecraft.getInstance().font;
            Rect2i area = layout.getRect();
            Optional<JeiRecipeIntrospection.RecipeTextGeometry> textGeometry =
                    JeiRecipeIntrospection.anvilCostTextGeometry(layout);
            if (!textGeometry.isPresent()) {
                visible = false;
                return;
            }
            visible = true;
            JeiRecipeIntrospection.RecipeTextGeometry geometry = textGeometry.get();
            ScreenPosition position = geometry.position();
            int inputWidth = editable
                    ? Math.max(20, font.width(input.getValue()) + 10)
                    : font.width(number);
            int totalWidth = font.width(prefix) + inputWidth + font.width(suffix);
            int startX = area.getX() + position.x()
                    + geometry.horizontalAlignment().getXPos(geometry.width(), totalWidth);
            textY = area.getY() + position.y();
            prefixX = startX;
            int inputY = textY + (geometry.height() - INPUT_HEIGHT) / 2;
            if (editable) {
                input.setRectangle(inputWidth, INPUT_HEIGHT, startX + font.width(prefix), inputY);
                suffixX = input.getX() + inputWidth;
            } else {
                suffixX = startX + font.width(prefix) + inputWidth;
            }
        }

        private void render(GuiGraphics graphics, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            int color = displayColor();
            graphics.drawString(font, prefix, prefixX, textY, color, true);
            if (editable) {
                input.render(graphics, mouseX, mouseY, 0.0F);
            } else {
                graphics.drawString(font, Integer.toString(lastValidCost),
                        prefixX + font.width(prefix), textY, color, true);
            }
            graphics.drawString(font, suffix, suffixX, textY, color, true);
        }

        private int displayColor() {
            net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
            boolean enough = player == null || player.isCreative()
                    || (lastValidCost < 40 && lastValidCost <= player.experienceLevel);
            return enough ? 0xFF80FF20 : 0xFFFF6060;
        }

        private boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (!editable || input == null) {
                return false;
            }
            boolean handled = input.mouseClicked(mouseX, mouseY, button);
            if (handled) {
                input.setFocused(true);
            }
            return handled;
        }

        private boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (!editable || input == null) {
                return false;
            }
            if (keyCode == 257 || keyCode == 335) {
                commitAndBlur();
                return true;
            }
            if (keyCode == 256) {
                input.setValue(Integer.toString(lastValidCost));
                input.setFocused(false);
                return true;
            }
            return input.keyPressed(keyCode, scanCode, modifiers);
        }

        private boolean charTyped(char codePoint, int modifiers) {
            return editable && input != null && input.charTyped(codePoint, modifiers);
        }

        private void commitAndBlur() {
            if (!editable || input == null || !input.isFocused()) {
                return;
            }
            Integer cost = parseCost(input.getValue());
            if (cost == null) {
                input.setValue(Integer.toString(lastValidCost));
                ClientEditorState.setLastDrop("Anvil experience cost must be between 1 and 1000000");
            } else if (cost.intValue() != lastValidCost) {
                RecipePatch costPatch = JeiVanillaRecipeEditorAdapter.setAnvilCost(model, cost.intValue());
                if (ClientEditorState.getPendingPatch(model.recipeId()) == null
                        && RecipePatchSemantics.isCreation(baselinePatch)) {
                    Map<String, String> fields = new LinkedHashMap<String, String>(baselinePatch.fields());
                    fields.putAll(costPatch.fields());
                    costPatch = new RecipePatch(model.recipeId(), model.serializerId(),
                            model.baseFingerprint(), fields);
                }
                lastValidCost = cost.intValue();
                ClientEditorState.rememberTarget(model,
                        JeiVanillaRecipeEditorAdapter.ANVIL_COST_PROPERTY, slots, recipe);
                ClientEditorState.setPendingPatch(costPatch);
                ClientEditorState.setLastDrop("Anvil experience cost set to " + cost);
            }
            input.setFocused(false);
        }

        private boolean isMouseOver(double mouseX, double mouseY) {
            return editable && visible && input != null && input.isMouseOver(mouseX, mouseY);
        }

        private boolean isFocused() {
            return editable && input != null && input.isFocused();
        }
    }
}
