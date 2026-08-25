package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.JEIEditorNeoForge121;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.platform.fuel.FuelOverrideState;
import cc.sighs.JEIEditor.platform.recipe.CraftingSlotMapper;
import cc.sighs.JEIEditor.platform.recipe.FuelRecipeEditorAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeCreationAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeDeletionAdapter;
import cc.sighs.JEIEditor.platform.recipe.RecipeEditorAdapters;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.vanilla.IJeiFuelingRecipe;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.common.Internal;
import mezz.jei.library.plugins.vanilla.cooking.fuel.FuelingRecipe;
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
import java.util.HashMap;
import java.util.Map;

@JeiPlugin
public final class JeiRecipeEditorPlugin implements IModPlugin {
    private static final Map<String, Integer> fuelPreviewTimes = new HashMap<String, Integer>();
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

    static boolean hasEditableInputAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        return target.isPresent() && target.get().slotKey.startsWith("input.");
    }

    static boolean adjustOutputAtMouse(RecipesGui gui, double mouseX, double mouseY, double scrollDelta) {
        if (scrollDelta == 0.0D) {
            return false;
        }
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || FuelRecipeEditorAdapter.SERIALIZER.equals(target.get().model.serializerId())
                || ClientEditorState.isRecipeDeleted(target.get().model.recipeId())
                || !"output".equals(target.get().slotKey)) {
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
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> sourceModel = resolveModel(displayedRecipe, category);
            EditorModel model = sourceModel.orElse(null);
            RecipePatch patch = findPendingPatch(displayedRecipe, category, patches);
            if (sourceModel.isPresent()) {
                Optional<EditorModel> creationModel = ClientEditorState.creationModelFor(
                        gui, sourceModel.get().recipeId());
                if (creationModel.isPresent()) {
                    model = creationModel.get();
                    patch = ClientEditorState.getPendingPatch(model.recipeId());
                }
            }
            if (patch == null || patch.fields().isEmpty()) {
                continue;
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                Rect2i screenArea = layout.getRecipeLayout().getRectWithBorder();
                String areaKey = "deleted:" + screenArea.getX() + ":" + screenArea.getY() + ":"
                        + screenArea.getWidth() + ":" + screenArea.getHeight();
                if (seenAreas.add(areaKey)) {
                    graphics.fill(screenArea.getX(), screenArea.getY(),
                            screenArea.getX() + screenArea.getWidth(),
                            screenArea.getY() + screenArea.getHeight(), 0x40f05a5a);
                }
                continue;
            }
            if (model == null) {
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
                        displayedRecipe, model);
                if (!isPatchedSlot(patch, model, slotKey)) {
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
            fuelPreviewTimes.clear();
            return;
        }
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            IRecipeCategory<?> category = layout.getRecipeLayout().getRecipeCategory();
            Optional<EditorModel> sourceModel = resolveModel(displayedRecipe, category);
            EditorModel model = sourceModel.orElse(null);
            RecipePatch patch = findPendingPatch(displayedRecipe, category, patches);
            if (sourceModel.isPresent()) {
                Optional<EditorModel> creationModel = ClientEditorState.creationModelFor(
                        gui, sourceModel.get().recipeId());
                if (creationModel.isPresent()) {
                    model = creationModel.get();
                    patch = ClientEditorState.getPendingPatch(model.recipeId());
                }
            }
            if (patch == null) {
                continue;
            }
            if (RecipePatchSemantics.isDeletion(patch)) {
                continue;
            }
            ensureFuelPreview(displayedRecipe, patch);
            if (model != null) {
                ClientEditorState.applyPreview(
                        layout.getRecipeLayout().getRecipeSlotsView(), model, patch,
                        displayedRecipe);
            }
        }
    }

    private static boolean isPatchedSlot(RecipePatch patch, EditorModel model, String slotKey) {
        if (patch == null || model == null || slotKey == null) {
            return false;
        }
        if (FuelRecipeEditorAdapter.isFuelPatch(patch) && "input.0".equals(slotKey)) {
            String burnTime = patch.fields().get("fuel.burn_time");
            return burnTime != null && !burnTime.equals(model.properties().get("burn_time"));
        }
        cc.sighs.JEIEditor.editor.EditorSlot original = model.slots().stream()
                .filter(slot -> slotKey.equals(slot.key()))
                .findFirst()
                .orElse(null);
        if (original == null) {
            return false;
        }
        cc.sighs.JEIEditor.editor.EditorIngredient ingredient = original.ingredient();
        String item = patch.fields().get(slotKey + ".item");
        String count = patch.fields().get(slotKey + ".count");
        boolean itemChanged = item != null && (ingredient == null
                ? !"minecraft:air".equals(item)
                : !item.equals(ingredient.itemId()));
        boolean countChanged = count != null && (ingredient == null
                ? !"0".equals(count)
                : !count.equals(Integer.toString(ingredient.count())));
        return itemChanged || countChanged;
    }

    private static boolean matchesRecipe(Object displayedRecipe, IRecipeCategory<?> category, String recipeId) {
        if (FuelRecipeEditorAdapter.isFuelRecipe(displayedRecipe)) {
            Optional<EditorModel> model = RecipeEditorAdapters.createFuelModel(displayedRecipe);
            return model.isPresent() && model.get().recipeId().equals(recipeId);
        }
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
            EditorModel editableModel = ClientEditorState.creationModelFor(gui, model.get().recipeId())
                    .orElse(model.get());
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            String slotKey = RecipeGhostHandler.slotKey(slots.getSlotViews(), slot.get().slot(), displayedRecipe,
                    editableModel);
            if (slotKey != null) {
                return Optional.of(new RecipeTarget(editableModel, slotKey, slots, null, displayedRecipe));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the currently displayed item in the recipe slot under the
     * pointer. Display overrides are already reflected by JEI's slot view, so
     * dragging a locally edited item uses exactly what the player sees.
     */
    static Optional<ItemStack> editorItemAtMouse(RecipesGui gui, double mouseX, double mouseY) {
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || ClientEditorState.isRecipeDeleted(target.get().model.recipeId())) {
            return Optional.empty();
        }
        for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : target.get().slots.getSlotViews()) {
            if (view.getRole() != RecipeIngredientRole.INPUT
                    && view.getRole() != RecipeIngredientRole.OUTPUT) {
                continue;
            }
            if (!(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                continue;
            }
            String slotKey = RecipeGhostHandler.slotKey(target.get().slots.getSlotViews(),
                    (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view,
                    target.get().recipe, target.get().model);
            if (target.get().slotKey.equals(slotKey)) {
                return view.getDisplayedItemStack().map(ItemStack::copy);
            }
        }
        return Optional.empty();
    }

    /** Applies a dragged editor item to the editable slot under the pointer. */
    static boolean replaceItemAtMouse(RecipesGui gui, double mouseX, double mouseY, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Optional<RecipeTarget> target = resolveTarget(gui, mouseX, mouseY);
        if (!target.isPresent() || ClientEditorState.isRecipeDeleted(target.get().model.recipeId())) {
            return false;
        }
        try {
            ClientEditorState.rememberTarget(target.get().model, target.get().slotKey,
                    target.get().slots, target.get().recipe);
            ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceSlot(
                    target.get().model, target.get().slotKey, stack));
            ClientEditorState.setLastDrop("Moved " + stack.getHoverName().getString()
                    + " to " + target.get().slotKey);
            return true;
        } catch (IllegalArgumentException exception) {
            ClientEditorState.setLastDrop(exception.getMessage());
            return false;
        }
    }

    static Optional<EditorModel> recipeDeletionTargetAtMouse(
            RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Object displayedRecipe = layout.getRecipeLayout().getRecipe();
            Optional<RecipeHolder<?>> holder = resolveRecipeHolder(displayedRecipe,
                    layout.getRecipeLayout().getRecipeCategory());
            Optional<EditorModel> model = holder.flatMap(RecipeDeletionAdapter::createModel);
            if (model.isPresent()) {
                return model;
            }
        }
        return Optional.empty();
    }

    static Optional<EditorModel> recipeCreationTargetAtMouse(
            RecipesGui gui, double mouseX, double mouseY) {
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            if (!layout.getRecipeLayout().isMouseOver(mouseX, mouseY)) {
                continue;
            }
            Optional<EditorModel> source = resolveModel(layout.getRecipeLayout().getRecipe(),
                    layout.getRecipeLayout().getRecipeCategory());
            Optional<EditorModel> creation = source.flatMap(value ->
                    creationModelForSource(gui, value));
            if (creation.isPresent()) {
                return creation;
            }
        }
        // All layouts visible in one JEI tab belong to the same recipe
        // category. If the hovered layout cannot provide a source model,
        // use another supported layout from that same page.
        return recipeCreationTargetOnPage(gui);
    }

    static Optional<EditorModel> recipeCreationTargetOnPage(RecipesGui gui) {
        if (Minecraft.getInstance().level == null) {
            return Optional.empty();
        }
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Optional<EditorModel> source = resolveModel(layout.getRecipeLayout().getRecipe(),
                    layout.getRecipeLayout().getRecipeCategory());
            Optional<EditorModel> creation = source.flatMap(value ->
                    creationModelForSource(gui, value));
            if (creation.isPresent()) {
                // A page can show several recipes from the same JEI category;
                // use the first supported layout as the creation template.
                return creation;
            }
        }
        return Optional.empty();
    }

    private static Optional<EditorModel> creationModelForSource(RecipesGui gui, EditorModel source) {
        Optional<EditorModel> existing = ClientEditorState.creationModelFor(gui, source.recipeId());
        return existing.isPresent() ? existing : RecipeCreationAdapter.createModel(source);
    }

    private static Optional<EditorModel> resolveModel(Object displayedRecipe, IRecipeCategory<?> category) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ClientEditorState.setLastDrop("No client level available");
            return Optional.empty();
        }
        Optional<EditorModel> fuelModel = RecipeEditorAdapters.createFuelModel(displayedRecipe);
        if (fuelModel.isPresent()) {
            return fuelModel;
        }
        if (category != null && !JeiRecipeIntrospection.isHandled(category, displayedRecipe)) {
            return Optional.empty();
        }
        Optional<RecipeHolder<?>> holder = resolveRecipeHolder(displayedRecipe, category);
        if (!holder.isPresent()) {
            ClientEditorState.setLastDrop("Recipe id could not be resolved");
            return Optional.empty();
        }
        Optional<EditorModel> model = RecipeEditorAdapters.createModel(
                holder.get(), minecraft.level.registryAccess());
        if (!model.isPresent()) {
            ClientEditorState.setLastDrop("This recipe format is not supported by the editor adapter");
        }
        return model;
    }

    private static Optional<RecipeHolder<?>> resolveRecipeHolder(
            Object displayedRecipe, IRecipeCategory<?> category) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return Optional.empty();
        }
        Optional<ResourceLocation> recipeId = JeiRecipeIntrospection.recipeId(category, displayedRecipe);
        if (recipeId.isPresent()) {
            Optional<RecipeHolder<?>> holder = minecraft.level.getRecipeManager().byKey(recipeId.get());
            if (holder.isPresent()) {
                return holder;
            }
        }
        return minecraft.level.getRecipeManager().getRecipes().stream()
                .filter(candidate -> candidate.value() == displayedRecipe
                        || candidate.value().equals(displayedRecipe))
                .findFirst();
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
            EditorModel source = model.get();
            if (ClientEditorState.isRecipeDeleted(source.recipeId())) {
                continue;
            }
            EditorModel editableModel = ClientEditorState.creationModelFor(gui, source.recipeId())
                    .orElse(source);
            // Fuel entries are generated from the item registry rather than
            // recipe JSON. Their editable operation is burn-time scrolling;
            // dragging a different item would require changing the synthetic
            // identity and is intentionally not offered as a ghost target.
            if (FuelRecipeEditorAdapter.SERIALIZER.equals(editableModel.serializerId())) {
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
                        editableModel);
                if (slotKey != null && (slotKey.startsWith("input.") || "output".equals(slotKey))) {
                    Rect2i screenArea = JeiRecipeIntrospection.screenTargetArea(layout.getRecipeLayout(), drawable);
                    String areaKey = screenArea.getX() + ":" + screenArea.getY() + ":"
                            + screenArea.getWidth() + ":" + screenArea.getHeight();
                    if (seenAreas.add(areaKey)) {
                        targets.add(new RecipeTarget(editableModel, slotKey, slots, screenArea, displayedRecipe));
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

    /** Places the creation ID field in JEI's recipe pagination strip. */
    static int recipeIdInputY(RecipesGui gui, int inputHeight) {
        Optional<Rect2i> pageArea = JeiRecipeIntrospection.recipePageNavigationArea(gui);
        if (pageArea.isPresent()) {
            Rect2i area = pageArea.get();
            return area.getY() + Math.max(0, (area.getHeight() - inputHeight) / 2);
        }
        int firstSlotY = Integer.MAX_VALUE;
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            IRecipeSlotsView slots = layout.getRecipeLayout().getRecipeSlotsView();
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView view : slots.getSlotViews()) {
                if (!(view instanceof mezz.jei.api.gui.ingredient.IRecipeSlotDrawable)) {
                    continue;
                }
                Rect2i area = JeiRecipeIntrospection.screenTargetArea(
                        layout.getRecipeLayout(),
                        (mezz.jei.api.gui.ingredient.IRecipeSlotDrawable) view);
                firstSlotY = Math.min(firstSlotY, area.getY());
            }
        }
        if (firstSlotY != Integer.MAX_VALUE) {
            // The first slot starts below JEI's category header. The page
            // counter strip is roughly one compact widget row above it.
            return Math.max(22, firstSlotY - 20 + (20 - inputHeight) / 2);
        }
        return Math.max(22, gui.height / 2 - 230);
    }

    static Set<String> currentPageRecipeIds(RecipesGui gui) {
        Set<String> result = new HashSet<String>();
        for (mezz.jei.gui.recipes.IRecipeLayoutWithButtons<?> layout : visibleRecipeLayouts(gui)) {
            Optional<RecipeHolder<?>> holder = resolveRecipeHolder(layout.getRecipeLayout().getRecipe(),
                    layout.getRecipeLayout().getRecipeCategory());
            holder.ifPresent(value -> result.add(value.id().toString()));
        }
        return result;
    }

    /** Replace a generated JEI fuel entry with the locally edited burn time. */
    private static void ensureFuelPreview(Object displayedRecipe, RecipePatch patch) {
        if (!(displayedRecipe instanceof IJeiFuelingRecipe) || !FuelRecipeEditorAdapter.isFuelPatch(patch)) {
            return;
        }
        int burnTime = FuelRecipeEditorAdapter.burnTime(patch);
        if (burnTime < 1) {
            return;
        }
        IJeiFuelingRecipe current = (IJeiFuelingRecipe) displayedRecipe;
        if (current.getBurnTime() == burnTime) {
            fuelPreviewTimes.put(patch.recipeId(), Integer.valueOf(burnTime));
            return;
        }
        Integer applied = fuelPreviewTimes.get(patch.recipeId());
        if (applied != null && applied.intValue() == burnTime) {
            return;
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        ResourceLocation itemId = FuelRecipeEditorAdapter.itemId(patch).orElse(null);
        if (itemId == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemId)) {
            return;
        }
        ItemStack item = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId));
        runtime.get().getRecipeManager().hideRecipes(RecipeTypes.FUELING,
                java.util.Collections.singletonList(current));
        runtime.get().getRecipeManager().addRecipes(RecipeTypes.FUELING,
                java.util.Collections.<IJeiFuelingRecipe>singletonList(
                        new FuelingRecipe(java.util.Collections.singletonList(item), burnTime)));
        fuelPreviewTimes.put(patch.recipeId(), Integer.valueOf(burnTime));
    }

    /** Applies a saved/reset fuel value to JEI's generated fuel list. */
    static void applyFuelResult(List<RecipePatch> submitted) {
        if (submitted == null) {
            return;
        }
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent()) {
            return;
        }
        for (RecipePatch patch : submitted) {
            if (!FuelRecipeEditorAdapter.isFuelPatch(patch)) {
                continue;
            }
            FuelOverrideState.get(FuelRecipeEditorAdapter.itemId(patch).orElse(null)).ifPresent(value ->
                    replaceFuelRecipes(runtime.get(), FuelRecipeEditorAdapter.itemId(patch).get(), value.intValue()));
        }
    }

    static void applyFuelReset(ResourceLocation itemId) {
        Optional<mezz.jei.api.runtime.IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
        if (!runtime.isPresent() || itemId == null
                || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemId)) {
            return;
        }
        ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId));
        int burnTime = stack.getBurnTime(null);
        if (burnTime > 0) {
            replaceFuelRecipes(runtime.get(), itemId, burnTime);
        } else {
            hideFuelRecipes(runtime.get(), itemId);
        }
    }

    private static void hideFuelRecipes(mezz.jei.api.runtime.IJeiRuntime runtime, ResourceLocation itemId) {
        List<IJeiFuelingRecipe> matches = runtime.getRecipeManager()
                .createRecipeLookup(RecipeTypes.FUELING).includeHidden().get()
                .filter(recipe -> recipe.getInputs().size() == 1
                        && itemId.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        recipe.getInputs().get(0).getItem())))
                .collect(java.util.stream.Collectors.toList());
        if (!matches.isEmpty()) {
            runtime.getRecipeManager().hideRecipes(RecipeTypes.FUELING, matches);
        }
    }

    private static void replaceFuelRecipes(mezz.jei.api.runtime.IJeiRuntime runtime,
                                           ResourceLocation itemId, int burnTime) {
        List<IJeiFuelingRecipe> matches = runtime.getRecipeManager()
                .createRecipeLookup(RecipeTypes.FUELING).includeHidden().get()
                .filter(recipe -> recipe.getInputs().size() == 1
                        && itemId.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                        recipe.getInputs().get(0).getItem())))
                .collect(java.util.stream.Collectors.toList());
        if (!matches.isEmpty()) {
            runtime.getRecipeManager().hideRecipes(RecipeTypes.FUELING, matches);
            runtime.getRecipeManager().addRecipes(RecipeTypes.FUELING,
                    java.util.Collections.<IJeiFuelingRecipe>singletonList(new FuelingRecipe(
                            java.util.Collections.singletonList(new ItemStack(
                                    net.minecraft.core.registries.BuiltInRegistries.ITEM.get(itemId))), burnTime)));
        }
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
