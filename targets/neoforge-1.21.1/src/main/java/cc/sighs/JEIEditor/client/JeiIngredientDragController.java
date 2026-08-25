package cc.sighs.JEIEditor.client;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IBookmark;
import mezz.jei.gui.input.IDraggableIngredientInternal;
import mezz.jei.gui.overlay.IngredientListOverlay;
import mezz.jei.gui.overlay.bookmarks.BookmarkOverlay;
import mezz.jei.gui.overlay.ingredients.IIngredientListOverlayContents;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Bridges recipe-slot items to JEI's bookmark drag model.
 *
 * JEI intentionally keeps the bookmark list and the ingredient-grid layout
 * behind its runtime facade. The concrete overlays still expose the same
 * drag source/target abstractions used by JEI itself, so this controller only
 * reads those objects and delegates add/remove operations to BookmarkList.
 */
final class JeiIngredientDragController {
    private static final Field RECIPES_BOOKMARKS = findField(RecipesGui.class, "bookmarks");
    private static final Field INGREDIENT_CONTENTS = findField(IngredientListOverlay.class, "contents");

    private static Drag drag;

    private JeiIngredientDragController() {
    }

    /**
     * Starts tracking a drag. Recipe-slot drags are consumed immediately;
     * JEI's own side-overlay drags are only observed so normal ghost dragging
     * into recipe slots remains untouched.
     */
    static boolean start(RecipesGui gui, double mouseX, double mouseY) {
        drag = null;
        if (ClientEditorState.isEditing()) {
            Optional<ItemStack> recipeItem = JeiRecipeEditorPlugin.editorItemAtMouse(gui, mouseX, mouseY);
            if (recipeItem.isPresent() && !recipeItem.get().isEmpty()) {
                drag = Drag.recipe(gui, recipeItem.get(), mouseX, mouseY);
                return false;
            }
        }

        Optional<BookmarkOverlay> bookmarkOverlay = bookmarkOverlay();
        if (bookmarkOverlay.isPresent()) {
            Optional<IDraggableIngredientInternal<?>> source = bookmarkOverlay.get()
                    .getDraggableIngredientUnderMouse(mouseX, mouseY).findFirst();
            if (source.isPresent()) {
                Optional<IBookmark> bookmark = source.get().getElement().getBookmark();
                if (bookmark.isPresent()) {
                    drag = Drag.bookmark(gui, source.get().getTypedIngredient(), bookmark.get(), mouseX, mouseY);
                    return false;
                }
            }
        }

        Optional<IngredientListOverlay> ingredientOverlay = ingredientOverlay();
        if (ingredientOverlay.isPresent()) {
            Optional<IDraggableIngredientInternal<?>> source = ingredientOverlay.get()
                    .getDraggableIngredientUnderMouse(mouseX, mouseY).findFirst();
            if (source.isPresent()) {
                drag = Drag.ingredient(gui, source.get().getTypedIngredient(), mouseX, mouseY);
            }
        }
        return false;
    }

    static boolean drag(RecipesGui gui) {
        return drag != null && drag.gui == gui && drag.origin == Origin.RECIPE && drag.moved;
    }

    static void update(RecipesGui gui, double mouseX, double mouseY) {
        if (drag != null && drag.gui == gui && drag.origin == Origin.RECIPE) {
            if (!drag.moved && Math.hypot(mouseX - drag.startX, mouseY - drag.startY) >= 3.0D) {
                drag.moved = true;
                // Do not clear on mouse-down: a normal click must remain a
                // JEI click. Once the drag threshold is crossed, the source
                // slot becomes empty immediately and the item follows the
                // cursor as a move operation.
                drag.sourceCleared = JeiRecipeEditorPlugin.clearInputAtMouse(
                        gui, drag.startX, drag.startY);
            }
        }
    }

    /**
     * Completes a tracked cross-overlay drag. A recipe-slot source always
     * consumes the release, even when it was dropped somewhere unrelated, so
     * a normal click cannot accidentally open or transfer a recipe.
     */
    static boolean complete(RecipesGui gui, double mouseX, double mouseY) {
        Drag current = drag;
        drag = null;
        if (current == null || current.gui != gui) {
            return false;
        }

        boolean right = isIngredientArea(mouseX, mouseY);
        boolean left = !right && isBookmarkArea(mouseX, mouseY);
        if (current.origin == Origin.RECIPE) {
            if (!current.moved) {
                // This was an ordinary click on a JEI recipe ingredient.
                return false;
            }
            if (left) {
                addBookmark(gui, current.typedIngredient);
                restoreRecipeSource(gui, current);
            } else if (right) {
                // The source was cleared as soon as dragging began. Keep it
                // empty when the move is released over the right overlay.
            } else if (!JeiRecipeEditorPlugin.replaceItemAtMouse(
                    gui, mouseX, mouseY, current.stack())) {
                // Dropping outside an editable slot cancels the move and
                // restores the source item instead of losing it.
                restoreRecipeSource(gui, current);
            }
            return true;
        }

        // These two paths fill the gaps in JEI's native handlers: the normal
        // ghost-drag path still runs for every other destination.
        if (current.origin == Origin.INGREDIENT && left) {
            addBookmark(gui, current.typedIngredient);
            // Let JEI receive the release as well so its own DragRouter can
            // finish and clear the native side-overlay drag state.
            return false;
        }
        if (current.origin == Origin.BOOKMARK && right) {
            removeBookmark(gui, current.bookmark);
            // The native bookmark drag must still see the release and stop
            // its visual drag state, even though the removal is ours.
            return false;
        }
        return false;
    }

    private static void restoreRecipeSource(RecipesGui gui, Drag source) {
        if (source.sourceCleared) {
            JeiRecipeEditorPlugin.replaceItemAtMouse(gui, source.startX, source.startY, source.stack());
        }
    }

    static void cancel(RecipesGui gui) {
        if (drag != null && drag.gui == gui) {
            drag = null;
        }
    }

    static void render(GuiGraphics graphics, RecipesGui gui, int mouseX, int mouseY) {
        if (drag == null || drag.gui != gui || drag.origin != Origin.RECIPE || !drag.moved) {
            return;
        }
        ItemStack stack = drag.stack();
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, mouseX - 8, mouseY - 8);
        }
    }

    private static void addBookmark(RecipesGui gui, ITypedIngredient<?> source) {
        if (source == null) {
            return;
        }
        ItemStack stack = source.getItemStack().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            return;
        }
        // JEI bookmarks represent an ingredient, not a recipe quantity.
        stack.setCount(1);
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        IIngredientManager manager = runtime.get().getIngredientManager();
        Optional<ITypedIngredient<ItemStack>> typed = manager.createTypedIngredient(
                VanillaTypes.ITEM_STACK, stack, false);
        if (!typed.isPresent()) {
            return;
        }
        BookmarkList bookmarks = bookmarkList(gui).orElse(null);
        if (bookmarks != null && bookmarks.addIngredientBookmark(typed.get())) {
            ClientEditorState.setLastDrop("Added " + stack.getHoverName().getString() + " to bookmarks");
        }
    }

    private static void removeBookmark(RecipesGui gui, IBookmark bookmark) {
        BookmarkList bookmarks = bookmarkList(gui).orElse(null);
        if (bookmarks != null && bookmarks.remove(bookmark)) {
            ClientEditorState.setLastDrop("Removed item from bookmarks");
        }
    }

    private static boolean isBookmarkArea(double mouseX, double mouseY) {
        Optional<BookmarkOverlay> overlay = bookmarkOverlay();
        return overlay.isPresent() && overlay.get().isListDisplayed()
                && overlay.get().isMouseOver(mouseX, mouseY);
    }

    private static boolean isIngredientArea(double mouseX, double mouseY) {
        Optional<IngredientListOverlay> overlay = ingredientOverlay();
        if (!overlay.isPresent() || !overlay.get().isListDisplayed()) {
            return false;
        }
        // The item itself is the most reliable signal. The background area
        // can be stale for one frame while JEI is changing pages.
        if (overlay.get().getDraggableIngredientUnderMouse(mouseX, mouseY).findAny().isPresent()
                || overlay.get().getIngredientUnderMouse(mouseX, mouseY).findAny().isPresent()) {
            return true;
        }
        try {
            Object contents = INGREDIENT_CONTENTS.get(overlay.get());
            if (contents instanceof IIngredientListOverlayContents) {
                ImmutableRect2i area = ((IIngredientListOverlayContents) contents).getBackgroundArea();
                return area != null && !area.isEmpty() && area.contains(mouseX, mouseY);
            }
        } catch (ReflectiveOperationException ignored) {
            // A JEI implementation change should disable this optional path,
            // not prevent the recipe screen from opening.
        }
        return false;
    }

    private static Optional<BookmarkList> bookmarkList(RecipesGui gui) {
        try {
            Object value = RECIPES_BOOKMARKS.get(gui);
            return value instanceof BookmarkList
                    ? Optional.of((BookmarkList) value) : Optional.empty();
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<BookmarkOverlay> bookmarkOverlay() {
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent() || !(runtime.get().getBookmarkOverlay() instanceof BookmarkOverlay)) {
            return Optional.empty();
        }
        return Optional.of((BookmarkOverlay) runtime.get().getBookmarkOverlay());
    }

    private static Optional<IngredientListOverlay> ingredientOverlay() {
        Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent() || !(runtime.get().getIngredientListOverlay() instanceof IngredientListOverlay)) {
            return Optional.empty();
        }
        return Optional.of((IngredientListOverlay) runtime.get().getIngredientListOverlay());
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private enum Origin {
        RECIPE,
        INGREDIENT,
        BOOKMARK
    }

    private static final class Drag {
        private final RecipesGui gui;
        private final Origin origin;
        private final ITypedIngredient<?> typedIngredient;
        private final IBookmark bookmark;
        private final double startX;
        private final double startY;
        private boolean moved;
        private boolean sourceCleared;

        private Drag(RecipesGui gui, Origin origin, ITypedIngredient<?> typedIngredient, IBookmark bookmark,
                     double startX, double startY) {
            this.gui = gui;
            this.origin = origin;
            this.typedIngredient = typedIngredient;
            this.bookmark = bookmark;
            this.startX = startX;
            this.startY = startY;
        }

        static Drag recipe(RecipesGui gui, ItemStack stack, double startX, double startY) {
            Optional<ITypedIngredient<ItemStack>> typed = Internal.getOptionalJeiRuntime()
                    .flatMap(runtime -> runtime.getIngredientManager().createTypedIngredient(
                            VanillaTypes.ITEM_STACK, stack.copy(), false));
            return new Drag(gui, Origin.RECIPE, typed.orElse(null), null, startX, startY);
        }

        static Drag ingredient(RecipesGui gui, ITypedIngredient<?> typed, double startX, double startY) {
            return new Drag(gui, Origin.INGREDIENT, typed, null, startX, startY);
        }

        static Drag bookmark(RecipesGui gui, ITypedIngredient<?> typed, IBookmark bookmark,
                             double startX, double startY) {
            return new Drag(gui, Origin.BOOKMARK, typed, bookmark, startX, startY);
        }

        ItemStack stack() {
            return typedIngredient == null
                    ? ItemStack.EMPTY
                    : typedIngredient.getItemStack().map(ItemStack::copy).orElse(ItemStack.EMPTY);
        }
    }
}
