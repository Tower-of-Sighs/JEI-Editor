package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import mezz.jei.gui.recipes.RecipesGui;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class FabricClientEvents {
    private FabricClientEvents() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof RecipesGui)) return;
            addControls(screen);
            ScreenEvents.remove(screen).register(removed -> FabricClientState.closeRecipeScreen());
            ScreenEvents.afterRender(screen).register(FabricClientEvents::renderMessage);
        });
    }

    private static void addControls(Screen screen) {
        boolean compact = screen.width < 540;
        int primaryY = screen.height - 24;
        int secondaryY = screen.height - 46;
        int clearX = compact ? 4 : 220;
        int clearY = compact ? secondaryY : primaryY;
        int outputDownX = compact ? 60 : 274;
        int outputUpX = compact ? 104 : 318;
        int deleteX = compact ? 148 : 362;
        int undoX = compact ? 212 : 426;
        int redoX = compact ? 266 : 480;
        Button editButton = Button.builder(label(), button -> {
            FabricClientState.toggle();
            button.setMessage(label());
        }).bounds(4, primaryY, 92, 20).build();
        Button saveButton = Button.builder(Component.literal("Save"), button -> {
            RecipePatch patch = FabricClientState.getPendingPatch();
            if (patch == null) FabricClientState.setLastDrop("No pending recipe edit");
            else {
                FabricClientNetwork.send(patch);
                FabricClientState.setLastDrop("Recipe edit submitted to server");
            }
        }).bounds(100, primaryY, 56, 20).build();
        Button resetButton = Button.builder(Component.literal("Reset"), button -> {
            FabricClientState.clearPendingPatch();
            FabricClientState.setLastDrop("Pending changes reset");
        }).bounds(158, primaryY, 58, 20).build();
        Button clearButton = Button.builder(Component.literal("Clear"), button -> {
            if (!FabricClientState.isEditing()
                    || FabricClientState.getLastModel() == null
                    || FabricClientState.getLastSlotKey() == null) {
                FabricClientState.setLastDrop("Drop an item into an input slot first");
                return;
            }
            try {
                FabricClientState.setPendingPatch(RecipeEditorAdapters.clearSlot(
                        FabricClientState.getLastModel(), FabricClientState.getLastSlotKey()));
                FabricClientState.setLastDrop("Pending clear for " + FabricClientState.getLastSlotKey());
            } catch (IllegalArgumentException exception) {
                FabricClientState.setLastDrop(exception.getMessage());
            }
        }).bounds(clearX, clearY, 52, 20).build();
        Button outputDown = Button.builder(Component.literal("Out-"), button -> changeOutput(-1))
                .bounds(outputDownX, compact ? secondaryY : primaryY, 42, 20).build();
        Button outputUp = Button.builder(Component.literal("Out+"), button -> changeOutput(1))
                .bounds(outputUpX, compact ? secondaryY : primaryY, 42, 20).build();
        Button deleteButton = Button.builder(Component.literal("Remove"), button -> {
            if (FabricClientState.getLastModel() == null) {
                FabricClientState.setLastDrop("Select an edited recipe first");
                return;
            }
            FabricClientNetwork.sendDelete(FabricClientState.getLastModel().recipeId());
            FabricClientState.setLastDrop("Recipe reset submitted to server");
        }).bounds(deleteX, compact ? secondaryY : primaryY, 62, 20).build();
        Screens.getButtons(screen).add(editButton);
        Screens.getButtons(screen).add(saveButton);
        Screens.getButtons(screen).add(resetButton);
        Screens.getButtons(screen).add(clearButton);
        Screens.getButtons(screen).add(outputDown);
        Screens.getButtons(screen).add(outputUp);
        Screens.getButtons(screen).add(deleteButton);
        Screens.getButtons(screen).add(Button.builder(Component.literal("Undo"), button -> FabricClientState.undo())
                .bounds(undoX, compact ? secondaryY : primaryY, 52, 20).build());
        Screens.getButtons(screen).add(Button.builder(Component.literal("Redo"), button -> FabricClientState.redo())
                .bounds(redoX, compact ? secondaryY : primaryY, 52, 20).build());
        int numericY = screen.height - 68;
        int numericX = compact ? 4 : 220;
        Screens.getButtons(screen).add(Button.builder(Component.literal("Cancel"), button -> {
            FabricClientState.closeRecipeScreen();
            editButton.setMessage(label());
            FabricClientState.setLastDrop("Editing cancelled");
        }).bounds(compact ? 170 : 4, numericY, 58, 20).build());
        Screens.getButtons(screen).add(Button.builder(Component.literal("XP-"), button -> changeExperience(-0.1F)).bounds(numericX, numericY, 40, 20).build());
        Screens.getButtons(screen).add(Button.builder(Component.literal("XP+"), button -> changeExperience(0.1F)).bounds(numericX + 42, numericY, 40, 20).build());
        Screens.getButtons(screen).add(Button.builder(Component.literal("T-"), button -> changeCookingTime(-10)).bounds(numericX + 84, numericY, 40, 20).build());
        Screens.getButtons(screen).add(Button.builder(Component.literal("T+"), button -> changeCookingTime(10)).bounds(numericX + 126, numericY, 40, 20).build());
    }

    private static void changeOutput(int delta) {
        if (!FabricClientState.isEditing()) return;
        RecipePatch patch = FabricClientState.adjustOutputCount(delta);
        if (patch == null) FabricClientState.setLastDrop("No output count change available");
        else {
            FabricClientState.setPendingPatch(patch);
            FabricClientState.setLastDrop("Pending output count " + (delta < 0 ? "-1" : "+1"));
        }
    }

    private static void changeExperience(float delta) {
        if (!FabricClientState.isEditing()) return;
        RecipePatch patch = FabricClientState.adjustExperience(delta);
        if (patch == null) FabricClientState.setLastDrop("Select a smelting recipe");
        else { FabricClientState.setPendingPatch(patch); FabricClientState.setLastDrop("Pending experience change"); }
    }

    private static void changeCookingTime(int delta) {
        if (!FabricClientState.isEditing()) return;
        RecipePatch patch = FabricClientState.adjustCookingTime(delta);
        if (patch == null) FabricClientState.setLastDrop("Select a smelting recipe");
        else { FabricClientState.setPendingPatch(patch); FabricClientState.setLastDrop("Pending cooking time change"); }
    }

    private static Component label() {
        return Component.literal(FabricClientState.isEditing() ? "Editing: ON" : "Editing: OFF");
    }

    private static void renderMessage(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (FabricClientState.isRecipeScreen(screen) && !FabricClientState.getLastDrop().isEmpty()) {
            graphics.drawString(net.minecraft.client.Minecraft.getInstance().font,
                    FabricClientState.getLastDrop(), 4, screen.height - 42, 0xFFFFFFFF);
        }
    }
}
