package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.EditorModel;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.recipe.RecipeIngredientRole;
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
import java.util.IdentityHashMap;
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
        Set<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> seenLayouts =
                Collections.newSetFromMap(new IdentityHashMap<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>, Boolean>());
        Set<String> seenAreas = new HashSet<String>();
        int step = 8;
        for (int x = 0; x < gui.width; x += step) {
            for (int y = 0; y < gui.height; y += step) {
                Optional<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> layout =
                        gui.getRecipeLayoutUnderMouse(x + 0.5D, y + 0.5D);
                if (!layout.isPresent() || !seenLayouts.add(layout.get())) {
                    continue;
                }
                Object displayedRecipe = layout.get().getRecipeLayout().getRecipe();
                RecipePatch patch = findPendingPatch(displayedRecipe, patches);
                if (patch == null || patch.fields().isEmpty()) {
                    continue;
                }
                Optional<EditorModel> model = resolveModel(displayedRecipe);
                if (!model.isPresent()) {
                    continue;
                }
                IRecipeSlotsView slots = layout.get().getRecipeLayout().getRecipeSlotsView();
                Rect2i recipeArea = layout.get().getRecipeLayout().getRect();
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
                    Rect2i slotArea = ((mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view).getRect();
                    Rect2i screenArea = new Rect2i(
                            recipeArea.getX() + slotArea.getX(),
                            recipeArea.getY() + slotArea.getY(),
                            slotArea.getWidth(),
                            slotArea.getHeight());
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
        Set<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> seenLayouts =
                Collections.newSetFromMap(new IdentityHashMap<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>, Boolean>());
        for (int x = 0; x < gui.width; x += 8) {
            for (int y = 0; y < gui.height; y += 8) {
                Optional<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> layout =
                        gui.getRecipeLayoutUnderMouse(x + 0.5D, y + 0.5D);
                if (!layout.isPresent() || !seenLayouts.add(layout.get())) {
                    continue;
                }
                Object displayedRecipe = layout.get().getRecipeLayout().getRecipe();
                RecipePatch patch = findPendingPatch(displayedRecipe, patches);
                if (patch == null) {
                    continue;
                }
                Optional<EditorModel> model = resolveModel(displayedRecipe);
                if (model.isPresent()) {
                    ClientEditorState.applyPreview(
                            layout.get().getRecipeLayout().getRecipeSlotsView(), model.get(), patch,
                            displayedRecipe);
                }
            }
        }
    }

    private static boolean isPatchedSlot(RecipePatch patch, String slotKey) {
        return slotKey != null && (patch.fields().containsKey(slotKey + ".item")
                || patch.fields().containsKey(slotKey + ".count"));
    }

    private static boolean matchesRecipe(Object displayedRecipe, String recipeId) {
        if (displayedRecipe instanceof RecipeHolder<?>) {
            return ((RecipeHolder<?>) displayedRecipe).id().toString().equals(recipeId);
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.level.getRecipeManager().getRecipes().stream()
                .anyMatch(holder -> holder.id().toString().equals(recipeId)
                        && (holder.value() == displayedRecipe || holder.value().equals(displayedRecipe)));
    }

    private static RecipePatch findPendingPatch(Object displayedRecipe, List<RecipePatch> patches) {
        for (RecipePatch patch : patches) {
            if (matchesRecipe(displayedRecipe, patch.recipeId())) {
                return patch;
            }
        }
        return null;
    }

    private static Optional<RecipeTarget> resolveTarget(RecipesGui gui, double mouseX, double mouseY) {
        Optional<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> layout =
                gui.getRecipeLayoutUnderMouse(mouseX, mouseY);
        if (!layout.isPresent()) {
            return Optional.empty();
        }
        Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slot =
                layout.get().getRecipeLayout().getSlotUnderMouse(mouseX, mouseY);
        if (!slot.isPresent()) {
            return Optional.empty();
        }
        Optional<EditorModel> model = resolveModel(layout.get().getRecipeLayout().getRecipe());
        if (!model.isPresent()) {
            return Optional.empty();
        }
        IRecipeSlotsView slots = layout.get().getRecipeLayout().getRecipeSlotsView();
        Object displayedRecipe = layout.get().getRecipeLayout().getRecipe();
        String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), slot.get().slot(), displayedRecipe,
                model.get());
        if (slotKey == null) {
            return Optional.empty();
        }
        return Optional.of(new RecipeTarget(model.get(), slotKey, slots, null, displayedRecipe));
    }

    private static Optional<EditorModel> resolveModel(Object displayedRecipe) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ClientEditorState.setLastDrop("No client level available");
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
    private static List<RecipeTarget> visibleInputTargets(RecipesGui gui) {
        List<RecipeTarget> targets = new ArrayList<RecipeTarget>();
        Set<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> seenLayouts =
                Collections.newSetFromMap(new IdentityHashMap<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>, Boolean>());
        Set<String> seenAreas = new HashSet<String>();
        int step = 8;
        for (int x = 0; x < gui.width; x += step) {
            for (int y = 0; y < gui.height; y += step) {
                Optional<mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?>> layout =
                        gui.getRecipeLayoutUnderMouse(x + 0.5D, y + 0.5D);
                if (!layout.isPresent() || !seenLayouts.add(layout.get())) {
                    continue;
                }
                Optional<EditorModel> model = resolveModel(layout.get().getRecipeLayout().getRecipe());
                if (!model.isPresent()) {
                    continue;
                }
                IRecipeSlotsView slots = layout.get().getRecipeLayout().getRecipeSlotsView();
                for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                    if (view.getRole() != RecipeIngredientRole.INPUT
                            || !(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                        continue;
                    }
                    mezz.jei.api.gui.ingredient.IRecipeSlotDrawable drawable =
                            (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view;
                    Object displayedRecipe = layout.get().getRecipeLayout().getRecipe();
                    String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), drawable, displayedRecipe,
                            model.get());
                    if (slotKey != null && slotKey.startsWith("input.")) {
                        Rect2i slotArea = drawable.getRect();
                        Rect2i recipeArea = layout.get().getRecipeLayout().getRect();
                        Rect2i screenArea = new Rect2i(
                                recipeArea.getX() + slotArea.getX(),
                                recipeArea.getY() + slotArea.getY(),
                                slotArea.getWidth(),
                                slotArea.getHeight());
                        String areaKey = screenArea.getX() + ":" + screenArea.getY() + ":"
                                + screenArea.getWidth() + ":" + screenArea.getHeight();
                        if (seenAreas.add(areaKey)) {
                            targets.add(new RecipeTarget(model.get(), slotKey, slots, screenArea, displayedRecipe));
                        }
                    }
                }
            }
        }
        return targets;
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
            if (!ClientEditorState.isEditing() || !ingredient.getItemStack().isPresent()) {
                return Collections.emptyList();
            }

            List<RecipeTarget> inputTargets = visibleInputTargets(gui);
            if (!inputTargets.isEmpty()) {
                List<Rect2i> highlightAreas = new ArrayList<Rect2i>();
                for (RecipeTarget target : inputTargets) {
                    if (target.area != null) {
                        highlightAreas.add(target.area);
                    }
                }
                ClientEditorState.setGhostHighlightAreas(highlightAreas);
                List<Target<I>> targets = new ArrayList<Target<I>>(inputTargets.size());
                for (RecipeTarget recipeTarget : inputTargets) {
                    targets.add(new Target<I>() {
                        @Override
                        public Rect2i getArea() {
                            return recipeTarget.area;
                        }

                        @Override
                        public void accept(I ignored) {
                            ingredient.getItemStack().ifPresent(stack -> applyAtTarget(recipeTarget, stack));
                        }
                    });
                }
                return targets;
            }
            ClientEditorState.clearGhostHighlightAreas();

            Target<I> fallback = new Target<I>() {
                @Override
                public Rect2i getArea() {
                    int width = Math.min(190, Math.max(1, gui.width - 16));
                    int height = Math.max(40, gui.height - 76);
                    return new Rect2i((gui.width - width) / 2, 38, width, height);
                }

                @Override
                public void accept(I ignored) {
                    ingredient.getItemStack().ifPresent(stack -> acceptAtMouse(gui, stack));
                }
            };
            return Collections.singletonList(fallback);
        }

        private static void applyAtTarget(RecipeTarget target, ItemStack stack) {
            try {
                ClientEditorState.rememberTarget(target.model, target.slotKey, target.slots, target.recipe);
                ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceInput(
                        target.model, target.slotKey, stack));
                ClientEditorState.setLastDrop("Placed " + stack.getHoverName().getString() + " in " + target.slotKey);
            } catch (IllegalArgumentException exception) {
                ClientEditorState.setLastDrop(exception.getMessage());
            }
        }

        private static void acceptAtMouse(RecipesGui gui, ItemStack stack) {
            Minecraft minecraft = Minecraft.getInstance();
            double mouseX;
            double mouseY;
            if (ClientEditorState.hasMousePosition(gui)) {
                mouseX = ClientEditorState.getLastMouseX();
                mouseY = ClientEditorState.getLastMouseY();
            } else {
                mouseX = minecraft.mouseHandler.xpos()
                        * minecraft.getWindow().getGuiScaledWidth() / (double) minecraft.getWindow().getScreenWidth();
                mouseY = minecraft.mouseHandler.ypos()
                        * minecraft.getWindow().getGuiScaledHeight() / (double) minecraft.getWindow().getScreenHeight();
            }

            Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
            if (!target.isPresent()) {
                ClientEditorState.setLastDrop("No editable recipe slot under cursor");
                return;
            }
            if (!target.get().slotKey.startsWith("input.")) {
                ClientEditorState.setLastDrop("The target slot is not an editable input");
                return;
            }
            ClientEditorState.rememberTarget(target.get().model, target.get().slotKey, target.get().slots,
                    target.get().recipe);
            ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceInput(target.get().model, target.get().slotKey, stack));
            ClientEditorState.setLastDrop("Placed " + stack.getHoverName().getString() + " in " + target.get().slotKey);
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
