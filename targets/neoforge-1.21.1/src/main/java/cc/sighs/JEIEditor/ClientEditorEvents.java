package cc.sighs.JEIEditor;

import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Map;
import java.util.WeakHashMap;

@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class ClientEditorEvents {
    /** ScreenEvent.Init.Post can be delivered more than once for one JEI screen. */
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> editButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> saveButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Button> resetButtons =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Button>();
    private static final Map<net.minecraft.client.gui.screens.Screen, Boolean> initializedRecipeScreens =
            new WeakHashMap<net.minecraft.client.gui.screens.Screen, Boolean>();

    private ClientEditorEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        // Controls are created lazily by the blank-area context menu.
        if (ClientEditorState.isRecipeScreen(event.getScreen())
                && initializedRecipeScreens.put(event.getScreen(), Boolean.TRUE) == null) {
            // A new JEI page starts with the editor status, not a stale message
            // left over from the previous page instance.
            ClientEditorState.setLastDrop("");
        }
    }

    private static void pressEditButton(Button button) {
        if (ClientEditorState.toggleFromButton()) {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.clearPendingPatch();
            } else {
                ClientEditorState.setLastDrop("");
            }
        }
        button.setMessage(label());
    }

    /** Re-arm the edit switch after the physical left mouse button is released. */
    @SubscribeEvent
    public static void onMouseButtonReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            ClientEditorState.rememberMousePosition(event.getScreen(), event.getMouseX(), event.getMouseY());
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
        if (event.getButton() == 0 && ClientEditorState.isRecipeScreen(event.getScreen())) {
            Button button = buttonUnderMouse(event.getScreen(), event.getMouseX(), event.getMouseY());
            if (button != null) {
                if (ClientEditorState.consumeActionClick()) {
                    button.playDownSound(Minecraft.getInstance().getSoundManager());
                    if (button == editButtons.get(event.getScreen())) {
                        pressEditButton(button);
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
        if (isMenuOpen(gui)) {
            closeMenu(gui);
            event.setCanceled(true);
            return;
        }
        if (isBlankJeiArea(gui, event.getMouseX(), event.getMouseY())) {
            openMenu(gui, event.getMouseX(), event.getMouseY());
            event.setCanceled(true);
            return;
        }
        if (!ClientEditorState.isEditing()) {
            return;
        }
        if (JeiRecipeEditorPlugin.clearInputAtMouse(gui, event.getMouseX(), event.getMouseY())) {
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

    private static Component label() {
        return Component.literal(ClientEditorState.isEditing() ? "Editing: ON" : "Editing: OFF");
    }

    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        ClientEditorState.clearPreview();
        restoreSavedRecipeScreen();
        if (ClientEditorState.isEditing()) {
            ClientEditorState.setLastDrop("Recipes synchronized from server");
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // A JEI screen replacement may happen just after RecipesUpdatedEvent;
        // keep the same page visible until the save result clears the marker.
        restoreSavedRecipeScreen();
    }

    private static void restoreSavedRecipeScreen() {
        net.minecraft.client.gui.screens.Screen savedScreen = ClientEditorState.getSaveScreen();
        Minecraft minecraft = Minecraft.getInstance();
        if (savedScreen == null || minecraft.screen == savedScreen
                || !ClientEditorState.isRecipeScreen(savedScreen)) {
            return;
        }
        closeMenu(savedScreen);
        minecraft.setScreen(savedScreen);
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            if (ClientEditorState.getSaveScreen() != null) {
                // The close belongs to the recipe synchronization started by
                // Save, including a transient JEI replacement screen. Keep
                // the client edit session alive until the result packet
                // clears the save marker and the page is restored.
                closeMenu(event.getScreen());
                return;
            }
            initializedRecipeScreens.remove(event.getScreen());
            closeMenu(event.getScreen());
            ClientEditorState.clearGhostHighlightAreas();
            ClientEditorState.closeRecipeScreen();
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof RecipesGui) {
            JeiRecipeEditorPlugin.refreshPendingPreviews((RecipesGui) event.getScreen());
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            ClientEditorState.rememberMousePosition(event.getScreen(), event.getMouseX(), event.getMouseY());
            JeiRecipeEditorPlugin.drawPendingSlotHighlights((RecipesGui) event.getScreen(), event.getGuiGraphics());
            ClientEditorState.drawGhostHighlights(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            renderMenuAboveJei(event);
        }
        if (ClientEditorState.isRecipeScreen(event.getScreen()) && ClientEditorState.isEditing()) {
            net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
            String status = ClientEditorState.getLastDrop().isEmpty()
                    ? "Editing Mode: ON"
                    : ClientEditorState.getLastDrop();
            Component text = Component.literal(status);
            event.getGuiGraphics().drawString(font, text,
                    (event.getScreen().width - font.width(text)) / 2, 8, 0xFFFFFFFF);
        }
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
        Button reset = resetButtons.get(screen);
        if (reset != null) {
            reset.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), 0.0F);
        }
        event.getGuiGraphics().pose().popPose();
    }

    private static void openMenu(RecipesGui screen, double mouseX, double mouseY) {
        closeMenu(screen);
        int width = 92;
        int height = 60;
        int x = Math.max(2, Math.min(screen.width - width - 2, (int) mouseX));
        int y = Math.max(2, Math.min(screen.height - height - 2, (int) mouseY));
        Button edit = Button.builder(label(), button -> {
            pressEditButton(button);
            closeMenu(screen);
        }).bounds(x, y, width, 20).build();
        Button save = Button.builder(Component.literal("Save"), button -> {
            if (!ClientEditorState.isEditing()) {
                ClientEditorState.setLastDrop("Enable edit mode first");
            } else if (ClientEditorState.getPendingPatches().isEmpty()) {
                ClientEditorState.setLastDrop("No pending recipe edit");
            } else {
                ClientEditorState.markSaveSubmitted(screen);
                NeoForge121Network.send(ClientEditorState.getPendingPatches());
                ClientEditorState.setLastDrop("Recipe edits submitted");
            }
            // Keep the current recipe page and context menu visible after the
            // request is sent. The result message can arrive asynchronously,
            // and the user may continue inspecting the same recipe meanwhile.
        }).bounds(x, y + 20, width, 20).build();
        Button reset = Button.builder(Component.literal("Reset"), button -> {
            ClientEditorState.clearPendingPatch();
            ClientEditorState.setLastDrop("Pending changes reset");
            closeMenu(screen);
        }).bounds(x, y + 40, width, 20).build();
        editButtons.put(screen, edit);
        saveButtons.put(screen, save);
        resetButtons.put(screen, reset);
        screen.renderables.add(edit);
        screen.renderables.add(save);
        screen.renderables.add(reset);
    }

    private static boolean isMenuOpen(net.minecraft.client.gui.screens.Screen screen) {
        return editButtons.containsKey(screen);
    }

    private static void closeMenu(net.minecraft.client.gui.screens.Screen screen) {
        Button edit = editButtons.remove(screen);
        Button save = saveButtons.remove(screen);
        Button reset = resetButtons.remove(screen);
        if (edit != null) screen.renderables.remove(edit);
        if (save != null) screen.renderables.remove(save);
        if (reset != null) screen.renderables.remove(reset);
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
        Button resetButton = resetButtons.get(screen);
        if (resetButton != null && resetButton.isMouseOver(mouseX, mouseY)) {
            return resetButton;
        }
        return null;
    }
}
