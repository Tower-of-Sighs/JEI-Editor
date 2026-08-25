package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.client.JeiRecipeIntrospection;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;

/** Maps JEI's fixed 3x3 crafting grid positions to editor input keys. */
public final class CraftingSlotMapper {
    private CraftingSlotMapper() {
    }

    public static String slotKey(EditorModel model, Object displayedRecipe,
                          List<IRecipeSlotView> slots, IRecipeSlotView target) {
        if (target == null) {
            return null;
        }
        if (target.getRole() == RecipeIngredientRole.OUTPUT) {
            return "output";
        }
        if (target.getRole() != RecipeIngredientRole.INPUT) {
            return null;
        }

        // Named slots are the strongest JEI-level contract and allow custom
        // recipe categories to provide their own semantic mapping without
        // any layout assumptions.
        Optional<String> namedSlot = target.getSlotName();
        if (namedSlot.isPresent() && hasInputSlot(model, namedSlot.get())) {
            return namedSlot.get();
        }

        int inputCount = inputCount(model);
        if (inputCount == 0) {
            return null;
        }
        if (!isCrafting(model)) {
            return inputOrdinal(slots, target) == 0 ? "input.0" : null;
        }

        // New shaped models always expose the complete workbench grid. Keep
        // the JEI position as the semantic key so empty cells in the third
        // column/row remain editable. Older saved models may still contain a
        // compact list; those continue through the compatibility path below.
        if (isCrafting(model) && hasInputSlot(model, "input.8")) {
            int gridIndex = visualGridIndex(slots, target, 3, 3);
            return gridIndex >= 0 && gridIndex < 9 && hasInputSlot(model, "input." + gridIndex)
                    ? "input." + gridIndex
                    : null;
        }

        int[] dimensions = dimensions(model, displayedRecipe, inputCount);
        // JEI's crafting grid helper exposes its slots as a stable row-major
        // list. Use that abstraction instead of inferring coordinates from
        // pixel rectangles, which vary between layouts and versions.
        int gridIndex = visualGridIndex(slots, target, dimensions[0], dimensions[1]);
        int compactIndex = compactIndex(gridIndex, dimensions[0], dimensions[1], inputCount);
        if (compactIndex < 0 || compactIndex >= inputCount) {
            return null;
        }
        return "input." + compactIndex;
    }

    private static boolean isCrafting(EditorModel model) {
        return "minecraft:crafting_shaped".equals(model.serializerId())
                || "minecraft:crafting_shapeless".equals(model.serializerId());
    }

    private static int inputCount(EditorModel model) {
        int count = 0;
        for (cc.sighs.JEIEditor.editor.EditorSlot slot : model.slots()) {
            if ("input".equals(slot.role())) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasInputSlot(EditorModel model, String key) {
        for (cc.sighs.JEIEditor.editor.EditorSlot slot : model.slots()) {
            if (key.equals(slot.key()) && "input".equals(slot.role())) {
                return true;
            }
        }
        return false;
    }

    private static int[] dimensions(EditorModel model, Object displayedRecipe, int inputCount) {
        Optional<int[]> jeiDimensions = JeiRecipeIntrospection.craftingGridDimensions(displayedRecipe);
        if (jeiDimensions.isPresent()) {
            return jeiDimensions.get();
        }
        Object recipe = displayedRecipe instanceof RecipeHolder<?>
                ? ((RecipeHolder<?>) displayedRecipe).value()
                : displayedRecipe;
        if ("minecraft:crafting_shaped".equals(model.serializerId()) && recipe instanceof ShapedRecipe) {
            ShapedRecipe shaped = (ShapedRecipe) recipe;
            return new int[] {shaped.getWidth(), shaped.getHeight()};
        }
        // JEI's CraftingGridHelper uses a 1x1, 2x2, or 3x3 area for shapeless
        // recipes based on the number of ingredients.
        int size = inputCount <= 1 ? 1 : inputCount <= 4 ? 2 : 3;
        return new int[] {size, size};
    }

    private static int compactIndex(int gridIndex, int width, int height, int inputCount) {
        if (gridIndex < 0) {
            return -1;
        }
        for (int index = 0; index < inputCount; index++) {
            if (craftingGridIndex(index, width, height) == gridIndex) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Returns the actual row-major 3x3 position for a recipe input. JEI's
     * 19.44 helper has a 2x3 edge case that indexes the first six slot
     * builders instead of the two-column positions in all three rows. The
     * editor must use the recipe's real grid geometry so the third row stays
     * editable and the saved pattern keeps its intended shape.
     */
    public static int craftingGridIndex(int index, int width, int height) {
        // This mirrors JEI's CraftingGridHelper semantic placement. It is
        // deliberately expressed in grid coordinates, never screen pixels.
        if (width == 1) {
            return height == 1 ? 4 : index * 3 + 1;
        }
        if (height == 1) {
            return index + 3;
        }
        if (width == 2) {
            int gridIndex = index;
            if (index > 1) {
                gridIndex++;
            }
            if (index > 3) {
                gridIndex++;
            }
            return gridIndex;
        }
        if (height == 2) {
            return index + 3;
        }
        return index;
    }

    private static int inputOrdinal(List<IRecipeSlotView> slots, IRecipeSlotView target) {
        int ordinal = 0;
        for (IRecipeSlotView slot : slots) {
            if (slot.getRole() != RecipeIngredientRole.INPUT) {
                continue;
            }
            if (slot == target) {
                return ordinal;
            }
            ordinal++;
        }
        return -1;
    }

    /**
     * Returns the row-major position exposed by JEI's actual slot rectangles.
     * This uses the layout abstraction, not fixed offsets or pixel spacing, so
     * custom recipe categories and the vanilla 2x3 edge case remain aligned.
     */
    @SuppressWarnings("removal")
    private static int visualGridIndex(List<IRecipeSlotView> slots, IRecipeSlotView target,
                                       int width, int height) {
        if (!(target instanceof IRecipeSlotDrawable)) {
            return inputOrdinal(slots, target);
        }
        List<IRecipeSlotDrawable> inputs = new ArrayList<IRecipeSlotDrawable>();
        for (IRecipeSlotView slot : slots) {
            if (slot.getRole() == RecipeIngredientRole.INPUT && slot instanceof IRecipeSlotDrawable) {
                inputs.add((IRecipeSlotDrawable) slot);
            }
        }
        inputs.sort(new Comparator<IRecipeSlotDrawable>() {
            @Override
            public int compare(IRecipeSlotDrawable left, IRecipeSlotDrawable right) {
                Rect2i a = left.getRect();
                Rect2i b = right.getRect();
                int y = Integer.compare(a.getY(), b.getY());
                return y != 0 ? y : Integer.compare(a.getX(), b.getX());
            }
        });
        List<Integer> actualX = new ArrayList<Integer>();
        List<Integer> actualY = new ArrayList<Integer>();
        for (IRecipeSlotDrawable input : inputs) {
            Rect2i rect = input.getRect();
            if (!actualX.contains(rect.getX())) actualX.add(rect.getX());
            if (!actualY.contains(rect.getY())) actualY.add(rect.getY());
        }
        actualX.sort(Integer::compareTo);
        actualY.sort(Integer::compareTo);
        List<Integer> expectedX = new ArrayList<Integer>();
        List<Integer> expectedY = new ArrayList<Integer>();
        int ingredientCount = Math.max(1, width * height);
        for (int index = 0; index < ingredientCount; index++) {
            int grid = craftingGridIndex(index, width, height);
            int x = grid % 3;
            int y = grid / 3;
            if (!expectedX.contains(x)) expectedX.add(x);
            if (!expectedY.contains(y)) expectedY.add(y);
        }
        expectedX.sort(Integer::compareTo);
        expectedY.sort(Integer::compareTo);
        IRecipeSlotDrawable drawable = (IRecipeSlotDrawable) target;
        Rect2i targetRect = drawable.getRect();
        int xRank = actualX.indexOf(targetRect.getX());
        int yRank = actualY.indexOf(targetRect.getY());
        if (xRank < 0 || yRank < 0 || xRank >= expectedX.size() || yRank >= expectedY.size()) {
            return -1;
        }
        return expectedY.get(yRank).intValue() * 3 + expectedX.get(xRank).intValue();
    }
}
