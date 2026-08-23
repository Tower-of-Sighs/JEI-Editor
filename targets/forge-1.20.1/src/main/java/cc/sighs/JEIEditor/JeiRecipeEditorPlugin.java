package cc.sighs.JEIEditor;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@JeiPlugin
public final class JeiRecipeEditorPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(JEIEditorForge.MOD_ID, "jei_plugin");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipesGui.class, new RecipeGhostHandler());
    }

    private static final class RecipeGhostHandler implements IGhostIngredientHandler<RecipesGui> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(RecipesGui gui, ITypedIngredient<I> ingredient, boolean doStart) {
            if (!ClientEditorState.isEditing() || !ingredient.getItemStack().isPresent()) {
                return Collections.emptyList();
            }

            Target<I> target = new Target<I>() {
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
            return Collections.singletonList(target);
        }

        private static void acceptAtMouse(RecipesGui gui, ItemStack stack) {
            Minecraft minecraft = Minecraft.getInstance();
            double mouseX = minecraft.mouseHandler.xpos()
                    * minecraft.getWindow().getGuiScaledWidth() / (double) minecraft.getWindow().getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos()
                    * minecraft.getWindow().getGuiScaledHeight() / (double) minecraft.getWindow().getScreenHeight();

            Optional<IRecipeLayoutDrawable<?>> layout = findRecipeLayoutUnderMouse(gui, mouseX, mouseY);
            if (!layout.isPresent()) {
                ClientEditorState.setLastDrop("No recipe under cursor");
                return;
            }

            Optional<mezz.jei.api.gui.inputs.RecipeSlotUnderMouse> slot =
                    layout.get().getSlotUnderMouse(mouseX, mouseY);
            if (!slot.isPresent()) {
                ClientEditorState.setLastDrop("Drop outside a recipe slot");
                return;
            }

            Object displayedRecipe = layout.get().getRecipe();
            if (minecraft.level == null) {
                ClientEditorState.setLastDrop("No client level available");
                return;
            }
            Optional<Recipe<?>> recipe = minecraft.level.getRecipeManager().getRecipes().stream()
                    .filter(candidate -> candidate == displayedRecipe || candidate.equals(displayedRecipe))
                    .findFirst();
            if (!recipe.isPresent()) {
                ClientEditorState.setLastDrop("Recipe id could not be resolved");
                return;
            }

            ResourceLocation recipeId = recipe.get().getId();
            Optional<cc.sighs.JEIEditor.editor.EditorModel> model = RecipeEditorAdapters.createModel(
                    recipeId, recipe.get(), minecraft.level.registryAccess());
            if (!model.isPresent()) {
                ClientEditorState.setLastDrop("This recipe is read-only in the MVP");
                return;
            }
            String slotKey = inputSlotKey(layout.get().getRecipeSlotsView().getSlotViews(), slot.get().slot());
            if (slotKey == null) {
                ClientEditorState.setLastDrop("The target slot is not an editable input");
                return;
            }
            if (!slotKey.startsWith("input.")) {
                ClientEditorState.setLastDrop("Only input slots can be replaced");
                return;
            }
            ClientEditorState.rememberTarget(model.get(), slotKey,
                    layout.get().getRecipeSlotsView());
            ClientEditorState.setPendingPatch(RecipeEditorAdapters.replaceInput(model.get(), slotKey, stack));
            ClientEditorState.setLastDrop("Pending " + stack.getHoverName().getString() + " in " + slotKey);
        }

        private static Optional<IRecipeLayoutDrawable<?>> findRecipeLayoutUnderMouse(RecipesGui gui,
                                                                                       double mouseX,
                                                                                       double mouseY) {
            try {
                Object layouts = getField(gui, "layouts");
                Object wrappers = getField(layouts, "recipeLayoutsWithButtons");
                if (!(wrappers instanceof List<?> recipeLayouts)) {
                    return Optional.empty();
                }
                for (Object wrapper : recipeLayouts) {
                    Object layout = wrapper.getClass().getMethod("recipeLayout").invoke(wrapper);
                    if (layout instanceof IRecipeLayoutDrawable<?> drawable
                            && drawable.isMouseOver(mouseX, mouseY)) {
                        return Optional.of(drawable);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // JEI's layout container is internal and may change between minor versions.
            }
            return Optional.empty();
        }

        private static Object getField(Object owner, String name) throws ReflectiveOperationException {
            Class<?> type = owner.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }

        private static String inputSlotKey(List<mezz.jei.api.gui.ingredient.IRecipeSlotView> slots,
                                           mezz.jei.api.gui.ingredient.IRecipeSlotDrawable target) {
            int inputIndex = 0;
            for (mezz.jei.api.gui.ingredient.IRecipeSlotView candidate : slots) {
                if (candidate.getRole() != RecipeIngredientRole.INPUT) {
                    continue;
                }
                if (candidate == target) {
                    return "input." + inputIndex;
                }
                inputIndex++;
            }
            return null;
        }

        @Override
        public void onComplete() {
        }
    }
}
