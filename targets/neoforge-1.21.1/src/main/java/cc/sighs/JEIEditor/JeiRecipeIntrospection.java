package cc.sighs.JEIEditor;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.widgets.ITextWidget;
import mezz.jei.api.gui.widgets.IRecipeWidget;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.recipes.IRecipeGuiLogic;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Compatibility boundary for JEI 19.44's internal recipe GUI state.
 *
 * JEI exposes the layout and slot abstractions publicly, but it does not
 * expose the complete list of layouts currently visible on a page. All
 * private access is intentionally isolated here so the editor does not grow
 * version-specific reflection throughout its input and rendering code.
 */
final class JeiRecipeIntrospection {
    private static Field recipesGuiLayoutsField;
    private static Field recipeLayoutsListField;
    private static Field recipesGuiLogicField;
    private static Field recipesGuiBookmarksField;
    private static Field borderPaddingField;
    private static Field recipeLayoutWidgetsField;
    private static Field recipesGuiPreviousPageField;
    private static Field recipesGuiNextPageField;
    private static Method iconButtonAreaMethod;
    private static Method recipeLayoutsAreaMethod;
    private static Method ensureRecipeExtrasMethod;
    private static final Map<ITextWidget, ScreenPosition> hiddenRecipeTextPositions =
            new WeakHashMap<ITextWidget, ScreenPosition>();

    private JeiRecipeIntrospection() {
    }

    static List<IRecipeLayoutWithButtons<?>> visibleLayouts(RecipesGui gui) {
        if (gui == null) {
            return Collections.emptyList();
        }

        try {
            Object layouts = getRecipesGuiLayouts(gui);
            if (layouts != null) {
                List<IRecipeLayoutWithButtons<?>> current = copyLayoutList(getRecipeLayoutsList(layouts));
                if (current != null) {
                    // An empty list is a valid page state. Do not rediscover
                    // layouts by scanning arbitrary screen coordinates.
                    return current;
                }
            }

            List<IRecipeLayoutWithButtons<?>> generated = invokeVisibleLayoutGenerator(gui);
            return generated == null ? Collections.<IRecipeLayoutWithButtons<?>>emptyList() : generated;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("removal")
    static Rect2i screenArea(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot) {
        return screenArea(layout, slot, true);
    }

    /**
     * Returns the screen-space slot rectangle. Background-inclusive bounds are
     * useful for painting a visual marker, while drag targets must use the
     * actual slot rect so a category background cannot become a huge target.
     */
    static Rect2i screenTargetArea(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot) {
        return screenArea(layout, slot, false);
    }

    @SuppressWarnings("removal")
    private static Rect2i screenArea(IRecipeLayoutDrawable<?> layout, IRecipeSlotDrawable slot,
                                     boolean includeBackground) {
        Rect2i recipeArea = layout.getRect();
        Rect2i slotArea = includeBackground ? slot.getAreaIncludingBackground() : slot.getRect();
        return new Rect2i(recipeArea.getX() + slotArea.getX(),
                recipeArea.getY() + slotArea.getY(),
                slotArea.getWidth(), slotArea.getHeight());
    }

    static Optional<ItemStack> itemStack(ITypedIngredient<?> ingredient) {
        return ingredient == null ? Optional.<ItemStack>empty() : ingredient.getItemStack();
    }

    /** Temporarily moves JEI text widgets out of a recipe layout while a
     * client-side editor draws an interactive replacement in the same area. */
    static void setRecipeTextVisible(IRecipeLayoutDrawable<?> layout, boolean visible) {
        if (layout == null) {
            return;
        }
        try {
            Method ensureExtras = ensureRecipeExtrasMethod;
            if (ensureExtras == null || !ensureExtras.getDeclaringClass().isInstance(layout)) {
                ensureExtras = layout.getClass().getMethod("ensureRecipeExtrasAreCreated");
                ensureExtras.setAccessible(true);
                ensureRecipeExtrasMethod = ensureExtras;
            }
            ensureExtras.invoke(layout);

            Field widgetsField = recipeLayoutWidgetsField;
            if (widgetsField == null || !widgetsField.getDeclaringClass().isInstance(layout)) {
                widgetsField = findNamedField(layout.getClass(), "allWidgets", List.class);
                recipeLayoutWidgetsField = widgetsField;
            }
            if (widgetsField == null) {
                return;
            }
            Object widgets = widgetsField.get(layout);
            if (!(widgets instanceof List<?>)) {
                return;
            }
            for (Object widget : (List<?>) widgets) {
                if (!(widget instanceof ITextWidget) || !(widget instanceof IRecipeWidget)) {
                    continue;
                }
                ITextWidget text = (ITextWidget) widget;
                if (visible) {
                    ScreenPosition original = hiddenRecipeTextPositions.remove(text);
                    if (original != null) {
                        text.setPosition(original.x(), original.y());
                    }
                } else if (!hiddenRecipeTextPositions.containsKey(text)) {
                    hiddenRecipeTextPositions.put(text, ((IRecipeWidget) text).getPosition());
                    text.setPosition(-10000, -10000);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // JEI internals are optional here; failing closed leaves its
            // original text visible instead of breaking the recipe page.
        }
    }

    static Optional<ScreenPosition> firstRecipeTextPosition(IRecipeLayoutDrawable<?> layout) {
        if (layout == null) {
            return Optional.empty();
        }
        try {
            Method ensureExtras = ensureRecipeExtrasMethod;
            if (ensureExtras == null || !ensureExtras.getDeclaringClass().isInstance(layout)) {
                ensureExtras = layout.getClass().getMethod("ensureRecipeExtrasAreCreated");
                ensureExtras.setAccessible(true);
                ensureRecipeExtrasMethod = ensureExtras;
            }
            ensureExtras.invoke(layout);
            Field widgetsField = recipeLayoutWidgetsField;
            if (widgetsField == null || !widgetsField.getDeclaringClass().isInstance(layout)) {
                widgetsField = findNamedField(layout.getClass(), "allWidgets", List.class);
                recipeLayoutWidgetsField = widgetsField;
            }
            if (widgetsField == null) {
                return Optional.empty();
            }
            Object widgets = widgetsField.get(layout);
            if (!(widgets instanceof List<?>)) {
                return Optional.empty();
            }
            for (Object widget : (List<?>) widgets) {
                if (widget instanceof ITextWidget && widget instanceof IRecipeWidget) {
                    return Optional.of(((IRecipeWidget) widget).getPosition());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Keep the editor usable if JEI changes its private widget list.
        }
        return Optional.empty();
    }

    static void restoreHiddenRecipeText() {
        for (Map.Entry<ITextWidget, ScreenPosition> entry :
                new ArrayList<Map.Entry<ITextWidget, ScreenPosition>>(hiddenRecipeTextPositions.entrySet())) {
            ITextWidget widget = entry.getKey();
            ScreenPosition position = entry.getValue();
            if (widget != null && position != null) {
                widget.setPosition(position.x(), position.y());
            }
        }
        hiddenRecipeTextPositions.clear();
    }

    /** Returns true when JEI owns this point through a sidebar or exclusion area. */
    static boolean isOverlayAt(Screen screen, double mouseX, double mouseY) {
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent() || screen == null) {
            return false;
        }
        try {
            if (runtime.get().getScreenHelper().getGuiExclusionAreas(screen)
                    .anyMatch(area -> contains(area, mouseX, mouseY))) {
                return true;
            }
            return runtime.get().getScreenHelper()
                    .getClickableIngredientUnderMouse(screen, mouseX, mouseY)
                    .findAny().isPresent();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Returns the exact strip JEI uses to draw its page counter. In JEI
     * 19.44, RecipesGui.render() unions previousPage.getArea() and
     * nextPage.getArea(), then centers pageString in that union. Keeping the
     * editor field on the same geometry avoids guesses based on recipe slots.
     */
    static Optional<Rect2i> recipePageNavigationArea(RecipesGui gui) {
        if (gui == null) {
            return Optional.empty();
        }
        try {
            Field previousField = recipesGuiPreviousPageField;
            if (previousField == null || !previousField.getDeclaringClass().isInstance(gui)) {
                previousField = findNamedField(RecipesGui.class, "previousPage", Object.class);
                recipesGuiPreviousPageField = previousField;
            }
            Field nextField = recipesGuiNextPageField;
            if (nextField == null || !nextField.getDeclaringClass().isInstance(gui)) {
                nextField = findNamedField(RecipesGui.class, "nextPage", Object.class);
                recipesGuiNextPageField = nextField;
            }
            if (previousField == null || nextField == null) {
                return Optional.empty();
            }
            Object previous = previousField.get(gui);
            Object next = nextField.get(gui);
            Rect2i previousArea = iconButtonArea(previous);
            Rect2i nextArea = iconButtonArea(next);
            if (previousArea == null || nextArea == null) {
                return Optional.empty();
            }
            int left = Math.min(previousArea.getX(), nextArea.getX());
            int top = Math.min(previousArea.getY(), nextArea.getY());
            int right = Math.max(previousArea.getX() + previousArea.getWidth(),
                    nextArea.getX() + nextArea.getWidth());
            int bottom = Math.max(previousArea.getY() + previousArea.getHeight(),
                    nextArea.getY() + nextArea.getHeight());
            return Optional.of(new Rect2i(left, top, right - left, bottom - top));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return Optional.empty();
        }
    }

    private static Rect2i iconButtonArea(Object button) throws ReflectiveOperationException {
        if (button == null) {
            return null;
        }
        Method method = iconButtonAreaMethod;
        if (method == null || !method.getDeclaringClass().isInstance(button)) {
            method = button.getClass().getMethod("getArea");
            method.setAccessible(true);
            iconButtonAreaMethod = method;
        }
        Object area = method.invoke(button);
        if (!(area instanceof ImmutableRect2i)) {
            return null;
        }
        ImmutableRect2i rect = (ImmutableRect2i) area;
        return new Rect2i(rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());
    }

    private static boolean contains(Rect2i area, double mouseX, double mouseY) {
        return area != null && mouseX >= area.getX() && mouseX < area.getX() + area.getWidth()
                && mouseY >= area.getY() && mouseY < area.getY() + area.getHeight();
    }

    static Optional<ResourceLocation> recipeId(IRecipeCategory<?> category, Object recipe) {
        if (recipe instanceof RecipeHolder<?>) {
            return Optional.of(((RecipeHolder<?>) recipe).id());
        }
        if (category == null || recipe == null) {
            return Optional.empty();
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            ResourceLocation id = ((IRecipeCategory) category).getRegistryName(recipe);
            return Optional.ofNullable(id);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    static boolean isHandled(IRecipeCategory<?> category, Object recipe) {
        if (category == null || recipe == null) {
            return false;
        }
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            boolean handled = ((IRecipeCategory) category).isHandled(recipe);
            return handled;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Replays a category through JEI's own ingredient supplier builder. This
     * is intentionally exposed for adapters that need all role candidates,
     * rather than only the one item currently displayed in a cycling slot.
     */
    static Optional<mezz.jei.api.ingredients.IIngredientSupplier> recipeIngredients(
            IRecipeCategory<?> category, Object recipe) {
        if (category == null || recipe == null) {
            return Optional.empty();
        }
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return Optional.empty();
        }
        try {
            IRecipeManager manager = runtime.get().getRecipeManager();
            @SuppressWarnings({"rawtypes", "unchecked"})
            mezz.jei.api.ingredients.IIngredientSupplier supplier =
                    manager.getRecipeIngredients((IRecipeCategory) category, recipe);
            return Optional.ofNullable(supplier);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Object getRecipesGuiLayouts(RecipesGui gui) throws IllegalAccessException {
        Field field = recipesGuiLayoutsField;
        if (field == null) {
            field = findField(RecipesGui.class, RecipeGuiLayouts.class, false);
            recipesGuiLayoutsField = field;
        }
        return field == null ? null : field.get(gui);
    }

    private static Object getRecipeLayoutsList(Object layouts) throws IllegalAccessException {
        Field field = recipeLayoutsListField;
        if (field == null || !field.getDeclaringClass().isInstance(layouts)) {
            field = findLayoutListField(layouts.getClass());
            recipeLayoutsListField = field;
        }
        return field == null ? null : field.get(layouts);
    }

    private static Field findLayoutListField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())
                    && field.getName().equals("recipeLayoutsWithButtons")) {
                field.setAccessible(true);
                return field;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static List<IRecipeLayoutWithButtons<?>> copyLayoutList(Object value) {
        if (!(value instanceof List<?>)) {
            return null;
        }
        List<IRecipeLayoutWithButtons<?>> result = new ArrayList<IRecipeLayoutWithButtons<?>>();
        for (Object entry : (List<?>) value) {
            if (entry instanceof IRecipeLayoutWithButtons<?>) {
                result.add((IRecipeLayoutWithButtons<?>) entry);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<IRecipeLayoutWithButtons<?>> invokeVisibleLayoutGenerator(RecipesGui gui)
            throws ReflectiveOperationException {
        Field logicField = recipesGuiLogicField;
        if (logicField == null) {
            logicField = findField(RecipesGui.class, IRecipeGuiLogic.class, false);
            recipesGuiLogicField = logicField;
        }
        Field bookmarksField = recipesGuiBookmarksField;
        if (bookmarksField == null) {
            bookmarksField = findField(RecipesGui.class, BookmarkList.class, false);
            recipesGuiBookmarksField = bookmarksField;
        }
        Method areaMethod = recipeLayoutsAreaMethod;
        if (areaMethod == null) {
            areaMethod = RecipesGui.class.getDeclaredMethod("getRecipeLayoutsArea");
            areaMethod.setAccessible(true);
            recipeLayoutsAreaMethod = areaMethod;
        }
        Field paddingField = borderPaddingField;
        if (paddingField == null) {
            paddingField = RecipesGui.class.getDeclaredField("borderPadding");
            paddingField.setAccessible(true);
            borderPaddingField = paddingField;
        }
        if (logicField == null || bookmarksField == null) {
            return Collections.emptyList();
        }
        Object logic = logicField.get(gui);
        if (!(logic instanceof IRecipeGuiLogic)) {
            return Collections.emptyList();
        }
        Object area = areaMethod.invoke(gui);
        if (!(area instanceof ImmutableRect2i)) {
            return Collections.emptyList();
        }
        int height = ((ImmutableRect2i) area).getHeight();
        int padding = paddingField.getInt(null);
        Object bookmarks = bookmarksField.get(gui);
        Object result = ((IRecipeGuiLogic) logic).getVisibleRecipeLayoutsWithButtons(
                height, padding, gui.getParentContainerMenu(), (BookmarkList) bookmarks, gui);
        List<IRecipeLayoutWithButtons<?>> copied = copyLayoutList(result);
        return copied == null ? Collections.<IRecipeLayoutWithButtons<?>>emptyList() : copied;
    }

    private static Field findField(Class<?> type, Class<?> fieldType, boolean staticOnly) {
        for (Field field : type.getDeclaredFields()) {
            if (fieldType.isAssignableFrom(field.getType())
                    && (!staticOnly || Modifier.isStatic(field.getModifiers()))) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static Field findNamedField(Class<?> type, String name, Class<?> fieldType) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                if (fieldType.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Continue through the implementation hierarchy.
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
