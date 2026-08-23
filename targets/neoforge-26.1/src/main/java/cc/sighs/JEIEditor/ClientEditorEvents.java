package cc.sighs.JEIEditor;

import mezz.jei.gui.recipes.RecipesGui;
import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;

@EventBusSubscriber(modid = JEIEditorNeoForge261.MOD_ID, value = Dist.CLIENT)
public final class ClientEditorEvents {
    private ClientEditorEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof RecipesGui)) {
            return;
        }
        boolean compact = event.getScreen().width < 540;
        int primaryY = event.getScreen().height - 24;
        int secondaryY = event.getScreen().height - 46;
        int clearX = compact ? 4 : 220;
        int clearY = compact ? secondaryY : primaryY;
        int outputDownX = compact ? 60 : 274;
        int outputUpX = compact ? 104 : 318;
        int deleteX = compact ? 148 : 362;
        int undoX = compact ? 212 : 426;
        int redoX = compact ? 266 : 480;

        Button editButton = Button.builder(label(), button -> {
            ClientEditorState.toggle();
            button.setMessage(label());
        }).bounds(4, primaryY, 92, 20).build();

        event.getScreen().renderables.add(editButton);
        event.addListener(editButton);

        Button saveButton = Button.builder(Component.literal("Save"), button -> {
            if (ClientEditorState.getPendingPatch() != null) {
                NeoForge261Network.send(ClientEditorState.getPendingPatch());
                ClientEditorState.setLastDrop("Recipe edit submitted to server");
            } else {
                ClientEditorState.setLastDrop("No pending recipe edit");
            }
        }).bounds(100, primaryY, 56, 20).build();
        event.getScreen().renderables.add(saveButton);
        event.addListener(saveButton);

        Button resetButton = Button.builder(Component.literal("Reset"), button -> {
            ClientEditorState.clearPendingPatch();
            ClientEditorState.setLastDrop("Pending changes reset");
        }).bounds(158, primaryY, 58, 20).build();
        event.getScreen().renderables.add(resetButton);
        event.addListener(resetButton);

        Button clearButton = Button.builder(Component.literal("Clear"), button -> {
            if (!ClientEditorState.isEditing()
                    || ClientEditorState.getLastModel() == null
                    || ClientEditorState.getLastSlotKey() == null) {
                ClientEditorState.setLastDrop("Drop an item into an input slot first");
                return;
            }
            try {
                ClientEditorState.setPendingPatch(RecipeEditorAdapters.clearSlot(
                        ClientEditorState.getLastModel(), ClientEditorState.getLastSlotKey()));
                ClientEditorState.setLastDrop("Pending clear for " + ClientEditorState.getLastSlotKey());
            } catch (IllegalArgumentException exception) {
                ClientEditorState.setLastDrop(exception.getMessage());
            }
        }).bounds(clearX, clearY, 52, 20).build();
        event.getScreen().renderables.add(clearButton);
        event.addListener(clearButton);

        Button outputDown = Button.builder(Component.literal("Out-"), button -> {
            if (!ClientEditorState.isEditing()) {
                return;
            }
            RecipePatch patch = ClientEditorState.adjustOutputCount(-1);
            if (patch == null) {
                ClientEditorState.setLastDrop("No output count change available");
            } else {
                ClientEditorState.setPendingPatch(patch);
                ClientEditorState.setLastDrop("Pending output count -1");
            }
        }).bounds(outputDownX, compact ? secondaryY : primaryY, 42, 20).build();
        event.getScreen().renderables.add(outputDown);
        event.addListener(outputDown);

        Button outputUp = Button.builder(Component.literal("Out+"), button -> {
            if (!ClientEditorState.isEditing()) {
                return;
            }
            RecipePatch patch = ClientEditorState.adjustOutputCount(1);
            if (patch == null) {
                ClientEditorState.setLastDrop("No output count change available");
            } else {
                ClientEditorState.setPendingPatch(patch);
                ClientEditorState.setLastDrop("Pending output count +1");
            }
        }).bounds(outputUpX, compact ? secondaryY : primaryY, 42, 20).build();
        event.getScreen().renderables.add(outputUp);
        event.addListener(outputUp);

        Button deleteButton = Button.builder(Component.literal("Remove"), button -> {
            if (ClientEditorState.getLastModel() == null) {
                ClientEditorState.setLastDrop("Select an edited recipe first");
                return;
            }
            NeoForge261Network.sendDelete(ClientEditorState.getLastModel().recipeId());
            ClientEditorState.setLastDrop("Recipe reset submitted to server");
        }).bounds(deleteX, compact ? secondaryY : primaryY, 62, 20).build();
        event.getScreen().renderables.add(deleteButton);
        event.addListener(deleteButton);

        Button undoButton = Button.builder(Component.literal("Undo"), button -> ClientEditorState.undo())
                .bounds(undoX, compact ? secondaryY : primaryY, 52, 20).build();
        event.getScreen().renderables.add(undoButton);
        event.addListener(undoButton);
        Button redoButton = Button.builder(Component.literal("Redo"), button -> ClientEditorState.redo())
                .bounds(redoX, compact ? secondaryY : primaryY, 52, 20).build();
        event.getScreen().renderables.add(redoButton);
        event.addListener(redoButton);

        int numericY = event.getScreen().height - 68;
        int numericX = compact ? 4 : 220;
        Button cancelButton = Button.builder(Component.literal("Cancel"), button -> {
            ClientEditorState.closeRecipeScreen();
            editButton.setMessage(label());
            ClientEditorState.setLastDrop("Editing cancelled");
        }).bounds(compact ? 170 : 4, numericY, 58, 20).build();
        event.getScreen().renderables.add(cancelButton);
        event.addListener(cancelButton);
        Button experienceDown = Button.builder(Component.literal("XP-"), button -> changeExperience(-0.1F)).bounds(numericX, numericY, 40, 20).build();
        Button experienceUp = Button.builder(Component.literal("XP+"), button -> changeExperience(0.1F)).bounds(numericX + 42, numericY, 40, 20).build();
        Button cookingTimeDown = Button.builder(Component.literal("T-"), button -> changeCookingTime(-10)).bounds(numericX + 84, numericY, 40, 20).build();
        Button cookingTimeUp = Button.builder(Component.literal("T+"), button -> changeCookingTime(10)).bounds(numericX + 126, numericY, 40, 20).build();
        for (Button button : new Button[] {experienceDown, experienceUp, cookingTimeDown, cookingTimeUp}) { event.getScreen().renderables.add(button); event.addListener(button); }
    }

    private static void changeExperience(float delta) {
        if (!ClientEditorState.isEditing()) return;
        try { RecipePatch patch = ClientEditorState.adjustExperience(delta); if (patch == null) ClientEditorState.setLastDrop("Select a smelting recipe"); else { ClientEditorState.setPendingPatch(patch); ClientEditorState.setLastDrop("Pending experience change"); } } catch (IllegalArgumentException exception) { ClientEditorState.setLastDrop(exception.getMessage()); }
    }
    private static void changeCookingTime(int delta) {
        if (!ClientEditorState.isEditing()) return;
        try { RecipePatch patch = ClientEditorState.adjustCookingTime(delta); if (patch == null) ClientEditorState.setLastDrop("Select a smelting recipe"); else { ClientEditorState.setPendingPatch(patch); ClientEditorState.setLastDrop("Pending cooking time change"); } } catch (IllegalArgumentException exception) { ClientEditorState.setLastDrop(exception.getMessage()); }
    }

    private static Component label() {
        return Component.literal(ClientEditorState.isEditing() ? "Editing: ON" : "Editing: OFF");
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen())) {
            ClientEditorState.closeRecipeScreen();
        }
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (ClientEditorState.isRecipeScreen(event.getScreen()) && !ClientEditorState.getLastDrop().isEmpty()) {
            event.getGuiGraphics().text(net.minecraft.client.Minecraft.getInstance().font,
                    ClientEditorState.getLastDrop(), 4, event.getScreen().height - 42, 0xFFFFFFFF);
        }
    }
}
