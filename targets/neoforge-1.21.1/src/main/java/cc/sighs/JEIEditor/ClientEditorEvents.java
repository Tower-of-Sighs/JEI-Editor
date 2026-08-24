package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ClientEditorEvents {
    private static final int RECIPE_ID_INPUT_WIDTH = 110;
    private static final int RECIPE_ID_INPUT_HEIGHT = 14;
    /** ScreenEvent.Init.Post can be delivered more than once for one JEI screen. */
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> editButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> saveButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> reloadButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> resetButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> deleteButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> clearInputButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> newRecipeButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, EditBox> recipeIdInputs =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, EditBox>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Boolean> initializedRecipeScreens =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Boolean>();

    private ClientEditorEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // Controls are created lazily by the context menu.
        if (ClientEditorState.isRecipeScreen(event.getScreen())
                && initializedRecipeScreens.put(event.getScreen(), Boolean.TRUE) == null) {
            // A new JEI page starts with the editor status, not a stale message
            // left over from the previous page instance.
            ClientEditorState.setLastDrop("");
        }
    }

    private static void pressEditButton(net.minecraft.client.gui.screens.Screen screen, Button button) {
        if (ClientEditorState.toggleFromButton()) {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.clearPendingPatch();
                closeRecipeIdInput(screen);
            } else {
                ClientEditorState.setLastDrop("");
            }
        }
        button.setMessage(label());
    }

    /** Re-arm the edit switch after the physical left mouse button is released. */
    @SubscribeEvent
    public static void onMouseButtonReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (event.getButton() == 0) {
            EditBox input = recipeIdInputs.get(event.getScreen());
            if (input != null && input.isMouseOver(event.getMouseX(), event.getMouseY())) {
                event.setCanceled(true);
            }
        }
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui
                && FuelCountInputController.isMouseOver((RecipesGui) event.getScreen(),
                event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            if (event.getButton() == 0
                    && buttonUnderMouse(event.getScreen(), event.getMouseX(), event.getMouseY()) != null) {
                // The editor invokes its button from MouseButtonPressed.Pre.
                // Do not let JEI execute the same physical click again on
                // release, which can also trigger its page navigation/close.
                event.setCanceled(true);
            }
        }
        if (event.getButton() == 0) {
            ClientEditorState.armToggle();
            ClientEditorState.armActionClick();
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            ClientEditorState.clearGhostHighlightAreas();
        }
    }

    /** Handle editor buttons before JEI's input router, then keep other edits mouse-only. */
    @SubscribeEvent
    public static void onMouseButton(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui
                && ClientEditorState.isEditing()
                && FuelCountInputController.mouseClicked((RecipesGui) event.getScreen(),
                event.getMouseX(), event.getMouseY(), event.getButton())) {
            closeMenu(event.getScreen());
            event.setCanceled(true);
            return;
        }
        if (event.getButton() == 0 && event.getScreen() instanceof RecipesGui) {
            EditBox input = recipeIdInputs.get(event.getScreen());
            if (input != null && input.isMouseOver(event.getMouseX(), event.getMouseY())) {
                input.setFocused(true);
                input.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton());
                event.setCanceled(true);
                return;
            }
            if (input != null) {
                input.setFocused(false);
            }
        }
        if (event.getButton() == 0 && ClientEditorState.isRecipeScreen(event.getScreen())) {
            Button button = buttonUnderMouse(event.getScreen(), event.getMouseX(), event.getMouseY());
            if (button != null) {
                if (ClientEditorState.consumeActionClick()) {
                    button.playDownSound(Minecraft.getInstance().getSoundManager());
                    if (button == editButtons.get(event.getScreen())) {
                        pressEditButton(event.getScreen(), button);
                    } else {
                        button.onPress();
                    }
                }
                event.setCanceled(true);
                return;
            }
            closeMenu(event.getScreen());
        }
        if (!(event.getScreen() instanceof RecipesGui gui) || event.getButton() != 1) {
            return;
        }
        EditBox recipeIdInput = recipeIdInputs.get(gui);
        if (recipeIdInput != null && recipeIdInput.isMouseOver(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }
        if (isMenuOpen(gui)) {
            closeMenu(gui);
            event.setCanceled(true);
            return;
        }
        Optional<EditorModel> deletionTarget = JeiRecipeEditorPlugin.recipeDeletionTargetAtMouse(
                gui, event.getMouseX(), event.getMouseY());
        if (deletionTarget.isPresent()) {
            Optional<EditorModel> creationTarget = JeiRecipeEditorPlugin.recipeCreationTargetAtMouse(
                    gui, event.getMouseX(), event.getMouseY());
            boolean clearableInput = !ClientEditorState.isRecipeDeleted(deletionTarget.get().recipeId())
                    && JeiRecipeEditorPlugin.hasEditableInputAtMouse(
                    gui, event.getMouseX(), event.getMouseY());
            openMenu(gui, event.getMouseX(), event.getMouseY(), deletionTarget, clearableInput, creationTarget);
            event.setCanceled(true);
            return;
        }
        if (isBlankJeiArea(gui, event.getMouseX(), event.getMouseY())) {
            openMenu(gui, event.getMouseX(), event.getMouseY(), Optional.empty(), false,
                    JeiRecipeEditorPlugin.recipeCreationTargetOnPage(gui));
            event.setCanceled(true);
        }
    }

    /**
     * The recipe-layout lookup only covers the central recipe panels. JEI's
     * bookmark and ingredient sidebars are outside those layouts, but they
     * still own the mouse when an ingredient is under the pointer. Only a
     * genuinely empty JEI area should open the editor context menu.
     */
    private static boolean isBlankJeiArea(RecipesGui gui, double mouseX, double mouseY) {
        // JEI's left/right ingredient overlays are outside RecipesGui's own
        // area. Do not treat those overlay regions as editor-menu space.
        if (!gui.isMouseOver(mouseX, mouseY)) {
            return false;
        }
        if (JeiRecipeIntrospection.isOverlayAt(gui, mouseX, mouseY)) {
            return false;
        }
        if (gui.getRecipeLayoutUnderMouse(mouseX, mouseY).isPresent()) {
            return false;
        }
        if (gui.getIngredientUnderMouse(mouseX, mouseY).findAny().isPresent()) {
            return false;
        }
        return !gui.getDraggableIngredientUnderMouse(mouseX, mouseY).findAny().isPresent();
    }

    /** The output count is adjusted with the wheel while the pointer is over the output slot. */
    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!ClientEditorState.isEditing() || !(event.getScreen() instanceof RecipesGui gui)) {
            return;
        }
        if (JeiRecipeEditorPlugin.adjustOutputAtMouse(
                gui, event.getMouseX(), event.getMouseY(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        EditBox recipeIdInput = recipeIdInputs.get(event.getScreen());
        if (recipeIdInput != null && recipeIdInput.isFocused()) {
            // Character keys (for example E) are handled later by
            // CharacterTyped.Pre and return false from EditBox.keyPressed().
            // Consume the key event regardless, otherwise JEI sees its own
            // close-screen binding before the character event is delivered.
            recipeIdInput.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
            event.setCanceled(true);
            return;
        }
        if (event.getScreen() instanceof RecipesGui
                && FuelCountInputController.keyPressed((RecipesGui) event.getScreen(),
                event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        EditBox recipeIdInput = recipeIdInputs.get(event.getScreen());
        if (recipeIdInput != null && recipeIdInput.isFocused()
                && recipeIdInput.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
            return;
        }
        if (event.getScreen() instanceof RecipesGui
                && FuelCountInputController.charTyped((RecipesGui) event.getScreen(),
                event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    private static Component label() {
        return Component.literal(ClientEditorState.isEditing() ? "Editing: ON" : "Editing: OFF");
    }

    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientEditorState.clearPreview();
        if (ClientEditorState.isEditing()) {
            ClientEditorState.setLastDrop("Recipes synchronized from server");
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            FuelCountInputController.close((RecipesGui) event.getScreen());
            initializedRecipeScreens.remove(event.getScreen());
            closeMenu(event.getScreen());
            closeRecipeIdInput(event.getScreen());
            ClientEditorState.clearGhostHighlightAreas();
            ClientEditorState.closeRecipeScreen();
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof RecipesGui) {
            FuelCountInputController.prepare((RecipesGui) event.getScreen());
            JeiRecipeEditorPlugin.refreshPendingPreviews((RecipesGui) event.getScreen());
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            JeiRecipeEditorPlugin.drawPendingSlotHighlights((RecipesGui) event.getScreen(), event.getGuiGraphics());
            ClientEditorState.drawGhostHighlights(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            FuelCountInputController.render((RecipesGui) event.getScreen(), event.getGuiGraphics(),
                    event.getMouseX(), event.getMouseY());
            renderRecipeIdInput(event);
            renderMenuAboveJei(event);
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen()) && ClientEditorState.isEditing()) {
            net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
            String status = ClientEditorState.getLastDrop().isEmpty()
                    ? "Editing Mode: ON"
                    : ClientEditorState.getLastDrop();
            status = fitStatus(font, status, Math.max(80, event.getScreen().width - 24));
            Component text = Component.literal(status);
            event.getGuiGraphics().drawString(font, text,
                    (event.getScreen().width - font.width(text)) / 2, 8, 0xFFFFFFFF);
        }
    }

    private static void renderRecipeIdInput(ScreenEvent.Render.Post event) {
        net.minecraft.client.gui.screens.Screen screen = event.getScreen();
        EditBox input = recipeIdInputs.get(screen);
        if (input == null || !(screen instanceof RecipesGui)) {
            return;
        }
        RecipesGui gui = (RecipesGui) screen;
        int width = RECIPE_ID_INPUT_WIDTH;
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String namespace = "jeieditor:";
        int gap = 4;
        int groupWidth = font.width(namespace) + gap + width;
        int groupLeft = (screen.width - groupWidth) / 2;
        java.util.Optional<net.minecraft.client.renderer.Rect2i> pageArea =
                JeiRecipeIntrospection.recipePageNavigationArea(gui);
        if (pageArea.isPresent()) {
            net.minecraft.client.renderer.Rect2i area = pageArea.get();
            groupLeft = area.getX() + (area.getWidth() - groupWidth) / 2;
        }
        int x = groupLeft + font.width(namespace) + gap;
        int y = JeiRecipeEditorPlugin.recipeIdInputY(gui, RECIPE_ID_INPUT_HEIGHT);
        input.setX(x);
        input.setY(y);
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0.0D, 0.0D, 1000.0D);
        int labelY = y + Math.max(0, (RECIPE_ID_INPUT_HEIGHT - font.lineHeight) / 2);
        event.getGuiGraphics().drawString(font, Component.literal(namespace),
                x - gap - font.width(namespace), labelY, 0xFFFFFFFF);
        input.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        event.getGuiGraphics().pose().popPose();
    }

    private static void openRecipeIdInput(RecipesGui screen) {
        EditBox input = recipeIdInputs.get(screen);
        if (input == null) {
            input = new EditBox(Minecraft.getInstance().font, 0, 0,
                    RECIPE_ID_INPUT_WIDTH, RECIPE_ID_INPUT_HEIGHT,
                    Component.literal("Recipe ID"));
            input.setMaxLength(128);
            input.setHint(Component.literal("recipe path"));
            input.setValue("");
            recipeIdInputs.put(screen, input);
        }
        input.setFocused(true);
        input.moveCursorToEnd(false);
    }

    private static void closeRecipeIdInput(net.minecraft.client.gui.screens.Screen screen) {
        EditBox input = recipeIdInputs.remove(screen);
        if (input != null) {
            input.setFocused(false);
        }
    }

    /** Keep status feedback readable on narrow screens and for generated IDs. */
    private static String fitStatus(net.minecraft.client.gui.Font font, String status, int maxWidth) {
        if (status == null || font.width(status) <= maxWidth) {
            return status == null ? "" : status;
        }
        String suffix = "...";
        int end = status.length();
        while (end > 0 && font.width(status.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return status.substring(0, end) + suffix;
    }

    /** JEI draws its recipe page after Screen.render(), so draw the context menu
     * again in the post pass at a high GUI Z level instead of letting the page
     * cover it. */
    private static void renderMenuAboveJei(ScreenEvent.Render.Post event) {
        net.minecraft.client.gui.screens.Screen screen = event.getScreen();
        Button edit = editButtons.get(screen);
        if (edit == null) {
            return;
        }
        event.getGuiGraphics().pose().pushPose();
        event.getGuiGraphics().pose().translate(0.0D, 0.0D, 1000.0D);
        edit.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        Button save = saveButtons.get(screen);
        if (save != null) {
            save.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        Button reload = reloadButtons.get(screen);
        if (reload != null) {
            reload.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        Button reset = resetButtons.get(screen);
        if (reset != null) {
            reset.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        Button delete = deleteButtons.get(screen);
        if (delete != null) {
            delete.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        Button clearInput = clearInputButtons.get(screen);
        if (clearInput != null) {
            clearInput.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        Button newRecipe = newRecipeButtons.get(screen);
        if (newRecipe != null) {
            newRecipe.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        event.getGuiGraphics().pose().popPose();
    }

    private static void openMenu(RecipesGui screen, double mouseX, double mouseY,
                                 Optional<EditorModel> deletionTarget, boolean clearableInput,
                                 Optional<EditorModel> creationTarget) {
        closeMenu(screen);
        int width = 130;
        int height = 80 + (clearableInput ? 20 : 0) + (deletionTarget.isPresent() ? 20 : 0)
                + 20;
        int x = Math.max(2, Math.min(screen.width - width - 2, (int) mouseX));
        int y = Math.max(2, Math.min(screen.height - height - 2, (int) mouseY));
        Button edit = Button.builder(label(), button -> {
            pressEditButton(screen, button);
            closeMenu(screen);
        }).bounds(x, y, width, 20).build();
        Button save = Button.builder(Component.literal("Save"), button -> {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.setLastDrop("Enable edit mode first");
            } else if (ClientEditorState.getPendingPatches().isEmpty()) {
                ClientEditorState.setLastDrop("No pending recipe edit");
            } else {
                if (ClientEditorState.hasCreationDraft(screen)) {
                    EditBox input = recipeIdInputs.get(screen);
                    String error = ClientEditorState.renameCreationDraft(screen,
                            input == null ? "" : input.getValue());
                    if (!error.isEmpty()) {
                        ClientEditorState.setLastDrop(error);
                        return;
                    }
                }
                java.util.List<cc.sighs.JEIEditor.editor.RecipePatch> patches =
                        ClientEditorState.getPendingPatches();
                ClientEditorState.markSaveSubmittedPatches(patches);
                NeoForge121Network.send(patches);
                ClientEditorState.setLastDrop("Recipe edits saved; reload separately to apply");
            }
        }).bounds(x, y + 20, width, 20).build();
        Button reload = Button.builder(Component.literal("Reload"), button -> {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.setLastDrop("Enable edit mode first");
            } else {
                NeoForge121Network.sendReload();
                ClientEditorState.setLastDrop("Saved recipe edits reload submitted");
                screen.onClose();
            }
        }).bounds(x, y + 40, width, 20).build();
        Button reset = Button.builder(Component.literal("Reset Page Changes"), button -> {
                if (screen instanceof RecipesGui) {
                ClientEditorState.clearPendingPatches(screen,
                        JeiRecipeEditorPlugin.currentPageRecipeIds((RecipesGui) screen));
            }
            ClientEditorState.setLastDrop("Current page changes reset");
            closeRecipeIdInput(screen);
            closeMenu(screen);
        }).bounds(x, y + 60, width, 20).build();
        editButtons.put(screen, edit);
        saveButtons.put(screen, save);
        reloadButtons.put(screen, reload);
        resetButtons.put(screen, reset);
        int actionY = y + 80;
        if (clearableInput) {
            Button clearInput = Button.builder(Component.literal("Clear Input Slot"), button -> {
                if (!ClientEditorState.isEditing()) {
                    ClientEditorState.setLastDrop("Enable edit mode first");
                } else {
                    JeiRecipeEditorPlugin.clearInputAtMouse(screen, mouseX, mouseY);
                }
                closeMenu(screen);
            }).bounds(x, actionY, width, 20).build();
            clearInputButtons.put(screen, clearInput);
            actionY += 20;
        }
        if (deletionTarget.isPresent()) {
            EditorModel target = deletionTarget.get();
            boolean deleted = ClientEditorState.isRecipeDeleted(target.recipeId());
            Button delete = Button.builder(Component.literal(
                    deleted ? "Cancel Recipe Delete" : "Delete Recipe"), button -> {
                if (!ClientEditorState.isEditing()) {
                    ClientEditorState.setLastDrop("Enable edit mode first");
                } else if (ClientEditorState.isRecipeDeleted(target.recipeId())) {
                    ClientEditorState.cancelRecipeDeletion(target.recipeId());
                    ClientEditorState.setLastDrop("Recipe deletion canceled: " + target.recipeId());
                } else {
                    ClientEditorState.deleteRecipe(target);
                    ClientEditorState.setLastDrop("Recipe marked for deletion: " + target.recipeId());
                }
                closeMenu(screen);
            }).bounds(x, actionY, width, 20).build();
            deleteButtons.put(screen, delete);
            actionY += 20;
        }
        boolean creationStaged = creationTarget.isPresent()
                && ClientEditorState.isCreationStaged(creationTarget.get().recipeId());
        Button newRecipe = Button.builder(Component.literal(
                creationStaged ? "Cancel New Recipe" : "New Recipe"), button -> {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.setLastDrop("Enable edit mode first");
            } else if (!creationTarget.isPresent()) {
                ClientEditorState.setLastDrop("Select a supported recipe to create a new one");
            } else {
                EditorModel target = creationTarget.get();
                try {
                    if (ClientEditorState.isCreationStaged(target.recipeId())) {
                        if (ClientEditorState.cancelCreationDraft(screen, target.recipeId())) {
                            closeRecipeIdInput(screen);
                            ClientEditorState.setLastDrop("New recipe canceled");
                        }
                    } else {
                        ClientEditorState.createRecipe(screen, target);
                        openRecipeIdInput(screen);
                        ClientEditorState.setLastDrop("New recipe ready. Fill inputs, then Save.");
                    }
                } catch (IllegalArgumentException exception) {
                    ClientEditorState.setLastDrop(exception.getMessage());
                }
            }
            closeMenu(screen);
        }).bounds(x, actionY, width, 20).build();
        newRecipeButtons.put(screen, newRecipe);
        // These controls are rendered and dispatched exclusively by the
        // editor event handlers. Keeping them out of Screen.renderables avoids
        // vanilla's second mouse dispatch and duplicate click sounds.
    }

    private static boolean isMenuOpen(net.minecraft.client.gui.screens.Screen screen) {
        return editButtons.containsKey(screen);
    }

    private static void closeMenu(net.minecraft.client.gui.screens.Screen screen) {
        Button edit = editButtons.remove(screen);
        Button save = saveButtons.remove(screen);
        Button reload = reloadButtons.remove(screen);
        Button reset = resetButtons.remove(screen);
        Button delete = deleteButtons.remove(screen);
        Button clearInput = clearInputButtons.remove(screen);
        Button newRecipe = newRecipeButtons.remove(screen);
        if (edit != null) screen.renderables.remove(edit);
        if (save != null) screen.renderables.remove(save);
        if (reload != null) screen.renderables.remove(reload);
        if (reset != null) screen.renderables.remove(reset);
        if (delete != null) screen.renderables.remove(delete);
        if (clearInput != null) screen.renderables.remove(clearInput);
        if (newRecipe != null) screen.renderables.remove(newRecipe);
    }

    private static Button buttonUnderMouse(net.minecraft.client.gui.screens.Screen screen,
                                           double mouseX, double mouseY) {
        Button editButton = editButtons.get(screen);
        if (editButton != null && editButton.isMouseOver(mouseX, mouseY)) {
            return editButton;
        }
        Button saveButton = saveButtons.get(screen);
        if (saveButton != null && saveButton.isMouseOver(mouseX, mouseY)) {
            return saveButton;
        }
        Button reloadButton = reloadButtons.get(screen);
        if (reloadButton != null && reloadButton.isMouseOver(mouseX, mouseY)) {
            return reloadButton;
        }
        Button resetButton = resetButtons.get(screen);
        if (resetButton != null && resetButton.isMouseOver(mouseX, mouseY)) {
            return resetButton;
        }
        Button deleteButton = deleteButtons.get(screen);
        if (deleteButton != null && deleteButton.isMouseOver(mouseX, mouseY)) {
            return deleteButton;
        }
        Button clearInputButton = clearInputButtons.get(screen);
        if (clearInputButton != null && clearInputButton.isMouseOver(mouseX, mouseY)) {
            return clearInputButton;
        }
        Button newRecipeButton = newRecipeButtons.get(screen);
        if (newRecipeButton != null && newRecipeButton.isMouseOver(mouseX, mouseY)) {
            return newRecipeButton;
        }
        return null;
    }
}
