package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.vanilla.IJeiFuelingRecipe;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.library.plugins.vanilla.cooking.fuel.FurnaceFuelCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** Interactive replacement for the numeric part of JEI's fuel-count text. */
final class FuelCountInputController {
    private static final int INPUT_HEIGHT = 14;
    private static final Map<RecipesGui, Map<String, FuelCountInput>> inputsByScreen =
            new WeakHashMap<RecipesGui, Map<String, FuelCountInput>>();

    private FuelCountInputController() {
    }

    static void prepare(RecipesGui gui) {
        if (gui == null || !ClientEditorState.isEditing()) {
            close(gui);
            return;
        }

        // JEI can replace the layout objects when paging or cycling recipes.
        // Restore any text widgets from the previous layout before hiding the
        // current fuel widgets again.
        JeiRecipeIntrospection.restoreHiddenRecipeText();

        Map<String, FuelCountInput> inputs = inputsByScreen.computeIfAbsent(gui,
                ignored -> new LinkedHashMap<String, FuelCountInput>());
        Set<String> visible = new HashSet<String>();
        for (IRecipeLayoutWithButtons<?> layout : JeiRecipeIntrospection.visibleLayouts(gui)) {
            Object recipe = layout.getRecipeLayout().getRecipe();
            Optional<EditorModel> model = RecipeEditorAdapters.createFuelModel(recipe);
            if (!model.isPresent() || !(recipe instanceof IJeiFuelingRecipe)) {
                continue;
            }
            String recipeId = model.get().recipeId();
            visible.add(recipeId);
            FuelCountInput input = inputs.get(recipeId);
            if (input == null) {
                input = new FuelCountInput(model.get());
                inputs.put(recipeId, input);
            }
            input.update(layout.getRecipeLayout(), model.get(), recipe,
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
        Map<String, FuelCountInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return;
        }
        for (FuelCountInput input : inputs.values()) {
            input.render(graphics, mouseX, mouseY);
        }
    }

    static boolean mouseClicked(RecipesGui gui, double mouseX, double mouseY, int button) {
        Map<String, FuelCountInput> inputs = inputsByScreen.get(gui);
        if (inputs == null || button != 0) {
            return false;
        }
        FuelCountInput clicked = null;
        for (FuelCountInput input : inputs.values()) {
            if (input.isMouseOver(mouseX, mouseY)) {
                clicked = input;
                break;
            }
        }
        for (FuelCountInput input : inputs.values()) {
            if (input != clicked) {
                input.commitAndBlur();
            }
        }
        return clicked != null && clicked.mouseClicked(mouseX, mouseY, button);
    }

    static boolean isMouseOver(RecipesGui gui, double mouseX, double mouseY) {
        Map<String, FuelCountInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return false;
        }
        for (FuelCountInput input : inputs.values()) {
            if (input.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return false;
    }

    static boolean keyPressed(RecipesGui gui, int keyCode, int scanCode, int modifiers) {
        FuelCountInput focused = focusedInput(gui);
        return focused != null && focused.keyPressed(keyCode, scanCode, modifiers);
    }

    static boolean charTyped(RecipesGui gui, char codePoint, int modifiers) {
        FuelCountInput focused = focusedInput(gui);
        return focused != null && focused.charTyped(codePoint, modifiers);
    }

    static void close(RecipesGui gui) {
        if (gui != null) {
            Map<String, FuelCountInput> inputs = inputsByScreen.remove(gui);
            if (inputs != null) {
                for (FuelCountInput input : inputs.values()) {
                    input.commitAndBlur();
                }
            }
        }
        JeiRecipeIntrospection.restoreHiddenRecipeText();
    }

    private static FuelCountInput focusedInput(RecipesGui gui) {
        Map<String, FuelCountInput> inputs = inputsByScreen.get(gui);
        if (inputs == null) {
            return null;
        }
        for (FuelCountInput input : inputs.values()) {
            if (input.isFocused()) {
                return input;
            }
        }
        return null;
    }

    private static int burnTime(EditorModel model) {
        RecipePatch patch = ClientEditorState.getPendingPatch(model.recipeId());
        if (FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return FuelRecipeEditorAdapter.burnTime(patch);
        }
        try {
            return Integer.parseInt(model.properties().get("burn_time"));
        } catch (RuntimeException ignored) {
            return 200;
        }
    }

    private static String exactSmeltCount(int burnTime) {
        return BigDecimal.valueOf(burnTime)
                .divide(BigDecimal.valueOf(200L))
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String displayedSmeltCount(int burnTime) {
        if (burnTime == 200) {
            return "1";
        }
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(2);
        return format.format((double) ((float) burnTime / 200.0F));
    }

    private static Integer parseBurnTime(String value) {
        if (value == null || value.isEmpty() || value.endsWith(".")) {
            return null;
        }
        try {
            int ticks = new BigDecimal(value).multiply(BigDecimal.valueOf(200L)).intValueExact();
            return ticks < 1 || ticks > 2_000_000_000 ? null : Integer.valueOf(ticks);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isPotentialCount(String value) {
        if (value == null || value.length() > 12) {
            return false;
        }
        int decimal = value.indexOf('.');
        if (decimal != value.lastIndexOf('.')) {
            return false;
        }
        String whole = decimal < 0 ? value : value.substring(0, decimal);
        String fraction = decimal < 0 ? "" : value.substring(decimal + 1);
        if (whole.length() > 8 || fraction.length() > 3) {
            return false;
        }
        for (int index = 0; index < whole.length(); index++) {
            if (!Character.isDigit(whole.charAt(index))) {
                return false;
            }
        }
        for (int index = 0; index < fraction.length(); index++) {
            if (!Character.isDigit(fraction.charAt(index))) {
                return false;
            }
        }
        return decimal < 0 || !whole.isEmpty();
    }

    private static final class FuelCountInput {
        private final EditBox input;
        private EditorModel model;
        private Object recipe;
        private IRecipeSlotsView slots;
        private String prefix = "";
        private String suffix = "";
        private int prefixX;
        private int suffixX;
        private int textY;
        private int lastValidBurnTime;

        private FuelCountInput(EditorModel model) {
            Font font = Minecraft.getInstance().font;
            this.model = model;
            this.lastValidBurnTime = burnTime(model);
            this.input = new EditBox(font, 0, 0, 24, INPUT_HEIGHT,
                    Component.literal("Smelting item count"));
            this.input.setMaxLength(12);
            this.input.setFilter(FuelCountInputController::isPotentialCount);
            setValue(exactSmeltCount(lastValidBurnTime));
        }

        private void update(IRecipeLayoutDrawable<?> layout, EditorModel updatedModel,
                            Object updatedRecipe, IRecipeSlotsView updatedSlots) {
            this.model = updatedModel;
            this.recipe = updatedRecipe;
            this.slots = updatedSlots;
            int currentBurnTime = burnTime(updatedModel);
            if (!input.isFocused() && currentBurnTime != lastValidBurnTime) {
                lastValidBurnTime = currentBurnTime;
                setValue(exactSmeltCount(currentBurnTime));
            }

            String originalText = FurnaceFuelCategory.createSmeltCountText(currentBurnTime).getString();
            String displayedNumber = displayedSmeltCount(currentBurnTime);
            int numberStart = originalText.indexOf(displayedNumber);
            if (numberStart >= 0) {
                prefix = originalText.substring(0, numberStart);
                suffix = originalText.substring(numberStart + displayedNumber.length());
            } else {
                prefix = originalText + " ";
                suffix = "";
            }

            Font font = Minecraft.getInstance().font;
            Rect2i area = layout.getRect();
            ScreenPosition textPosition = JeiRecipeIntrospection.firstRecipeTextPosition(layout)
                    .orElse(new ScreenPosition(0, 0));
            int textOffsetX = Math.max(0, textPosition.x());
            int availableX = area.getX() + textOffsetX;
            int availableWidth = Math.max(24, area.getWidth() - textOffsetX);
            int prefixWidth = font.width(prefix);
            int suffixWidth = font.width(suffix);
            int maximumInputWidth = Math.max(16, availableWidth - prefixWidth - suffixWidth - 4);
            int inputWidth = Math.min(maximumInputWidth,
                    Math.max(20, font.width(input.getValue()) + 10));
            int totalWidth = prefixWidth + inputWidth + suffixWidth;
            int startX = availableX + Math.max(0, (availableWidth - totalWidth) / 2);
            int inputY = area.getY() + textPosition.y()
                    + Math.max(0, (area.getHeight() - INPUT_HEIGHT) / 2);
            prefixX = startX;
            // AbstractWidget#setRectangle takes size first, then position.
            input.setRectangle(inputWidth, INPUT_HEIGHT, startX + prefixWidth, inputY);
            suffixX = input.getX() + inputWidth;
            textY = area.getY() + Math.max(0, (area.getHeight() - font.lineHeight) / 2);
        }

        private void render(GuiGraphics graphics, int mouseX, int mouseY) {
            Font font = Minecraft.getInstance().font;
            graphics.drawString(font, prefix, prefixX, textY, 0xFF808080, false);
            input.render(graphics, mouseX, mouseY, 0.0F);
            graphics.drawString(font, suffix, suffixX, textY, 0xFF808080, false);
        }

        private boolean mouseClicked(double mouseX, double mouseY, int button) {
            boolean handled = input.mouseClicked(mouseX, mouseY, button);
            if (handled) {
                // Screen normally assigns focus while dispatching a click to
                // its children. This widget is dispatched by our JEI event
                // bridge, so focus must be assigned explicitly.
                input.setFocused(true);
            }
            return handled;
        }

        private boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 257 || keyCode == 335) {
                commitAndBlur();
                return true;
            }
            if (keyCode == 256) {
                setValue(exactSmeltCount(lastValidBurnTime));
                input.setFocused(false);
                return true;
            }
            return input.keyPressed(keyCode, scanCode, modifiers);
        }

        private boolean charTyped(char codePoint, int modifiers) {
            return input.charTyped(codePoint, modifiers);
        }

        private void commitAndBlur() {
            if (!input.isFocused()) {
                return;
            }
            Integer ticks = parseBurnTime(input.getValue());
            if (ticks == null) {
                setValue(exactSmeltCount(lastValidBurnTime));
                ClientEditorState.setLastDrop("Smelting item count must be greater than 0");
            } else if (ticks.intValue() != lastValidBurnTime) {
                lastValidBurnTime = ticks.intValue();
                ClientEditorState.rememberTarget(model, "input.0", slots, recipe);
                ClientEditorState.setFuelBurnTime(model, ticks.intValue());
                ClientEditorState.setLastDrop("Fuel amount set to " + input.getValue()
                        + " smelting items");
            }
            input.setFocused(false);
        }

        private void setValue(String value) {
            input.setValue(value);
        }

        private boolean isMouseOver(double mouseX, double mouseY) {
            return input.isMouseOver(mouseX, mouseY);
        }

        private boolean isFocused() {
            return input.isFocused();
        }
    }
}
