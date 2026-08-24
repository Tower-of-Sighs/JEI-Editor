package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.EditorModel;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@JeiPlugin
public final class JeiRecipeEditorPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(JEIEditorNeoForge121.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipesGui.class, new RecipeGhostHandler());
    }

    static boolean clearInputAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent()) {
            return false;
        }
        if (!target.get().slotKey.startsWith("input.")) {
            ClientEditorState.setLastDrop("Right-click only clears input slots");
            return false;
        }
        try {
            ClientEditorState.rememberTarget(target.get().model, target.get().slotKey, target.get().slots,
                    target.get().recipe);
            ClientEditorState.setPendingPatch(RecipeEditorAdapters.clearSlot(
                    target.get().model, target.get().slotKey));
            ClientEditorState.setLastDrop("Cleared " + target.get().slotKey);
            return true;
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
            return false;
        }
    }

    static boolean adjustOutputAtMouse(RecipesGui gui, double mouseX, double mouseY, double scrollDelta) {
        if (scrollDelta == 0.0D) {
            return false;
        }
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || !"output".equals(target.get().slotKey)) {
            return false;
        }
        ClientEditorState.rememberTarget(target.get().model, target.get().slotKey, target.get().slots,
                target.get().recipe);
        RecipePatch patch = ClientEditorState.adjustOutputCount(scrollDelta > 0.0D ? 1 : -1);
        if (patch == null) {
            ClientEditorState.setLastDrop("Output count must be between 1 and 64");
        } else {
            ClientEditorState.setPendingPatch(patch);
            ClientEditorState.setLastDrop("Output count adjusted");
        }
        return true;
    }

    /** Draws a translucent blue background over every visible slot changed by
     * the pending patch. JEI does not expose a slot background override, so the
     * actual JEI slot rectangles are used and the item remains visible above it.
     */
    @SuppressWarnings("removal")
    static void drawPendingSlotHighlights(RecipesGui gui, GuiGraphics graphics) {
        List<RecipePatch> patches = ClientEditorState.getPendingPatches();
        if (patches.isEmpty()) {
            return;
        }
        Set<String> seenAreas = new HashSet<String>();
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            RecipePatch patch = findPendingPatch(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory(), patches);
            if (patch == null || patch.fields().isEmpty()) {
                continue;
            }
            Optional<EditorModel> model = resolveModel(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory());
            if (!model.isPresent()) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                if (!(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)
                        || (view.getRole() != RecipeIngredientRole.INPUT
                        && view.getRole() != RecipeIngredientRole.OUTPUT)) {
                    continue;
                }
                String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(),
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view,
                        displayedRecipe, model.get());
                if (!isPatchedSlot(patch, slotKey)) {
                    continue;
                }
                Rect2i screenArea = JeiRecipeIntrospection.screenTargetArea(
                        layout.getRecipeLayout(),
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view);
                String areaKey = screenArea.getX() + ":" + screenArea.getY() + ":"
                        + screenArea.getWidth() + ":" + screenArea.getHeight();
                if (seenAreas.add(areaKey)) {
                    graphics.fill(screenArea.getX(), screenArea.getY(),
                            screenArea.getX() + screenArea.getWidth(),
                            screenArea.getY() + screenArea.getHeight(), 0x662b6cff);
                }
            }
        }
    }

    /** Rebuilds local JEI display overrides for every visible recipe with a
     * pending patch, not just the last slot the user touched. */
    @SuppressWarnings("removal")
    static void refreshPendingPreviews(RecipesGui gui) {
        ClientEditorState.beginPreviewRefresh();
        List<RecipePatch> patches = ClientEditorState.getPendingPatches();
        if (patches.isEmpty()) {
            return;
        }
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            RecipePatch patch = findPendingPatch(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory(), patches);
            if (patch == null) {
                continue;
            }
            Optional<EditorModel> model = resolveModel(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory());
            if (model.isPresent()) {
                ClientEditorState.applyPreview(
                        layout.getRecipeLayout().getRecipeSlotsView(), model.get(), patch,
                        displayedRecipe);
            }
        }
    }

    private static boolean isPatchedSlot(RecipePatch patch, String slotKey) {
        return slotKey != null && (patch.fields().containsKey(slotKey + ".item")
                || patch.fields().containsKey(slotKey + ".count"));
    }

    private static boolean matchesRecipe(Object displayedRecipe, IRecipeCategory<?> category, String recipeId) {
        Optional<ResourceLocation> categoryId = JeiRecipeIntrospection.recipeId(category, displayedRecipe);
        if (categoryId.isPresent()) {
            return categoryId.get().toString().equals(recipeId);
        }
        if (displayedRecipe instanceof RecipeHolder<?>) {
            return ((RecipeHolder<?>) displayedRecipe).id().toString().equals(recipeId);
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.getRecipeManager().getRecipes().stream()
                .anyMatch(holder -> holder.id().toString().equals(recipeId)
                        && (holder.value() == displayedRecipe || holder.value().equals(displayedRecipe)));
    }

    private static RecipePatch findPendingPatch(Object displayedRecipe, IRecipeCategory<?> category,
                                                List<RecipePatch> patches) {
        for (RecipePatch patch : patches) {
            if (matchesRecipe(displayedRecipe, category, patch.recipeId())) {
                return patch;
            }
        }
        return null;
    }

    private static Optional<RecipeTarget> resolveTarget(RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slot =
                    layout.getRecipeLayout().getSlotUnderMouse(mouseX, mouseY);
            if (!slot.isPresent()) {
                continue;
            }
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            Optional<EditorModel> model = resolveModel(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory());
            if (!model.isPresent()) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), slot.get().slot(), displayedRecipe,
                    model.get());
            if (slotKey != null) {
                return Optional.of(new RecipeTarget(model.get(), slotKey, slots, null, displayedRecipe));
            }
        }
        return Optional.empty();
    }

    private static Optional<EditorModel> resolveModel(Object displayedRecipe, IRecipeCategory<?> category) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ClientEditorState.setLastDrop("No client level available");
            return Optional.empty();
        }
        if (category != null && !JeiRecipeIntrospection.isHandled(category, displayedRecipe)) {
            return Optional.empty();
        }
        Optional<RecipeHolder<?>> holder;
        if (displayedRecipe instanceof RecipeHolder<?>) {
            RecipeHolder<?> displayedHolder = (RecipeHolder<?>) displayedRecipe;
            holder = minecraft.level.getRecipeManager().getRecipes().stream()
                    .filter(candidate -> candidate.id().equals(displayedHolder.id()))
                    .findFirst();
        } else {
            holder = minecraft.level.getRecipeManager().getRecipes().stream()
                    .filter(candidate -> candidate.value() == displayedRecipe
                            || candidate.value().equals(displayedRecipe))
                    .findFirst();
        }
        if (!holder.isPresent()) {
            ClientEditorState.setLastDrop("Recipe id could not be resolved");
            return Optional.empty();
        }
        Optional<EditorModel> model = RecipeEditorAdapters.createModel(
                holder.get(), minecraft.level.registryAccess());
        if (!model.isPresent()) {
            ClientEditorState.setLastDrop("This recipe is read-only in this version");
        }
        return model;
    }

    @SuppressWarnings("removal")
    private static List<RecipeTarget> visibleEditableTargets(RecipesGui gui) {
        List<RecipeTarget> targets = new ArrayList<RecipeTarget>();
        Set<String> seenAreas = new HashSet<String>();
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> model = resolveModel(displayedRecipe, category);
            if (!model.isPresent()) {
                continue;
            }
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                if ((view.getRole() != RecipeIngredientRole.INPUT
                        && view.getRole() != RecipeIngredientRole.OUTPUT)
                        || !(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                    continue;
                }
                mezz.jei.api.gui.ingredient.IRecipeSlotDrawable drawable =
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view;
                String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), drawable, displayedRecipe,
                        model.get());
                if (slotKey != null && (slotKey.startsWith("input.") || "output".equals(slotKey))) {
                    Rect2i screenArea = JeiRecipeIntrospection.screenTargetArea(layout.getRecipeLayout(), drawable);
                    String areaKey = screenArea.getX() + ":" + screenArea.getY() + ":"
                            + screenArea.getWidth() + ":" + screenArea.getHeight();
                    if (seenAreas.add(areaKey)) {
                        targets.add(new RecipeTarget(model.get(), slotKey, slots, screenArea, displayedRecipe));
                    }
                }
            }
        }
        return targets;
    }

    /** Returns every layout currently shown by JEI through the compatibility boundary. */
    private static List<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> visibleRecipeLayouts(RecipesGui gui) {
        return JeiRecipeIntrospection.visibleLayouts(gui);
    }

    private static final class RecipeTarget {
        private final cc.sighs.JEIEditor.editor.EditorModel model;
        private final String slotKey;
        private final IRecipeSlotsView slots;
        private final Rect2i area;
        private final Object recipe;

        private RecipeTarget(cc.sighs.JEIEditor.editor.EditorModel model, String slotKey, IRecipeSlotsView slots,
                             Rect2i area, Object recipe) {
            this.model = model;
            this.slotKey = slotKey;
            this.slots = slots;
            this.area = area;
            this.recipe = recipe;
        }
    }

    private static final class RecipeGhostHandler implements IGhostIngredientHandler<RecipesGui> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(RecipesGui gui, ITypedIngredient<I> ingredient, boolean doStart) {
            Optional<ItemStack> stack = JeiRecipeIntrospection.itemStack(ingredient)
                    .filter(value -> !value.isEmpty());
            if (!doStart || !ClientEditorState.isEditing() || !stack.isPresent()) {
                return Collections.emptyList();
            }

            List<RecipeTarget> editableTargets = visibleEditableTargets(gui);
            if (!editableTargets.isEmpty()) {
                List<Rect2i> highlightAreas = new ArrayList<Rect2i>();
                for (RecipeTarget target : editableTargets) {
                    if (target.area != null) {
                        highlightAreas.add(target.area);
                    }
                }
                ClientEditorState.setGhostHighlightAreas(highlightAreas);
                List<Target<I>> targets = new ArrayList<Target<I>>(editableTargets.size());
                for (RecipeTarget recipeTarget : editableTargets) {
                    targets.add(new Target<I>() {
                        @Override
                        public Rect2i getArea() {
                            return recipeTarget.area;
                        }

                        @Override
                        public void accept(I ignored) {
                            stack.ifPresent(value -> applyAtTarget(recipeTarget, value));
                        }
                    });
                }
                return targets;
            }
            ClientEditorState.clearGhostHighlightAreas();
            return Collections.emptyList();
        }

        private static void applyAtTarget(RecipeTarget target, ItemStack stack) {
            try {
                ClientEditorState.rememberTarget(target.model, target.slotKey, target.slots, target.recipe);
                ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceSlot(
                        target.model, target.slotKey, stack));
                ClientEditorState.setLastDrop("Placed " + stack.getHoverName().getString() + " in " + target.slotKey);
            } catch (IllegalArgumentException exception) {
                ClientEditorState.setLastDrop(exception.getMessage());
            }
        }

        private static String slotKey(List<mezz.jei.api.gui.ingredient.IRecipeSlotView> slots,
                                      mezz.jei.api.gui.ingredient.IRecipeSlotDrawable target,
                                      Object displayedRecipe, EditorModel model) {
            return CraftingSlotMapper.slotKey(model, displayedRecipe, slots, target);
        }

        @Override
        public void onComplete() {
            ClientEditorState.clearGhostHighlightAreas();
        }

        @Override
        public boolean shouldHighlightTargets() {
            // JEI's built-in overlay is opaque green and has no color setting.
            // ClientEditorState draws a softer overlay for these targets.
            return false;
        }
    }
}
