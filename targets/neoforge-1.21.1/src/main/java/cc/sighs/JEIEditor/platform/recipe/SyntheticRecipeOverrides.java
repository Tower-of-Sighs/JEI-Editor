package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.JEIEditorNeoForge121;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.server.RecipeEditsSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.GrindstoneEvent;
import net.neoforged.neoforge.event.brewing.PotionBrewEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Applies saved JEI-generated recipe edits to NeoForge's runtime events. */
@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID)
public final class SyntheticRecipeOverrides {
    private SyntheticRecipeOverrides() {
    }

    @SubscribeEvent
    public static void onAnvilUpdate(AnvilUpdateEvent event) {
        MinecraftServer server = event.getPlayer() == null ? null : event.getPlayer().getServer();
        if (server == null) {
            return;
        }
        List<ItemStack> inputs = new ArrayList<ItemStack>();
        inputs.add(event.getLeft());
        inputs.add(event.getRight());
        find(server, "jei:anvil", inputs).ifPresent(patch -> {
            ItemStack output = output(server, patch);
            if (output.isEmpty()) {
                event.setCanceled(true);
            } else {
                event.setOutput(output);
                // AnvilMenu refuses to take an output when the event leaves
                // the maximum cost at zero. Generated JEI anvil recipes do
                // not have vanilla's calculated repair cost, so charge the
                // minimum one level while preserving any larger cost that
                // vanilla or another integration already supplied.
                int configuredCost = JeiVanillaRecipeEditorAdapter.anvilCost(null, patch);
                event.setCost(Math.max((long) configuredCost, Math.max(1L, event.getCost())));
            }
        });
    }

    @SubscribeEvent
    public static void onGrindstone(GrindstoneEvent.OnPlaceItem event) {
        if (event.getTopItem().isEmpty() && event.getBottomItem().isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        List<ItemStack> inputs = new ArrayList<ItemStack>();
        inputs.add(event.getTopItem());
        inputs.add(event.getBottomItem());
        find(server, "jei:grindstone", inputs).ifPresent(patch -> {
            ItemStack output = output(server, patch);
            if (output.isEmpty()) {
                event.setCanceled(true);
            } else {
                event.setOutput(output);
            }
        });
    }

    @SubscribeEvent
    public static void onPotionBrew(PotionBrewEvent.Pre event) {
        // PotionBrewEvent intentionally carries no server field. Brewing is
        // server-side, so recover the active server from the global instance.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        List<ItemStack> inputs = new ArrayList<ItemStack>();
        for (int index = 0; index < Math.min(4, event.getLength()); index++) {
            inputs.add(event.getItem(index));
        }
        find(server, "jei:brewing", inputs).ifPresent(patch -> {
            ItemStack output = output(server, patch);
            if (output.isEmpty()) {
                event.setCanceled(true);
                return;
            }
            for (int index = 0; index < Math.min(3, event.getLength()); index++) {
                if (!event.getItem(index).isEmpty()) {
                    event.setItem(index, output.copy());
                }
            }
            event.setCanceled(true);
        });
    }

    private static Optional<RecipePatch> find(MinecraftServer server, String serializer,
                                               List<ItemStack> inputs) {
        if (server == null) {
            return Optional.empty();
        }
        for (RecipePatch patch : RecipeEditsSavedData.get(server).patches().values()) {
            if (!serializer.equals(patch.serializerId()) || RecipePatchSemantics.isDeletion(patch)
                    || ("jei:anvil".equals(patch.serializerId())
                    && !RecipePatchSemantics.isCreation(patch))
                    || !matches(server, patch, inputs)) {
                continue;
            }
            return Optional.of(patch);
        }
        return Optional.empty();
    }

    private static boolean matches(MinecraftServer server, RecipePatch patch, List<ItemStack> inputs) {
        if ("jei:brewing".equals(patch.serializerId())) {
            boolean potion = matchesAnyExpected(server, patch, "input.0",
                    inputs.subList(0, Math.min(3, inputs.size())));
            ItemStack ingredient = inputs.size() > 3 ? inputs.get(3) : ItemStack.EMPTY;
            return potion && matchesExpected(server, patch, "input.1", ingredient);
        }
        boolean found = false;
        for (int index = 0; index < inputs.size(); index++) {
            String prefix = "input." + index;
            if (!hasExpected(patch, prefix)) {
                continue;
            }
            found = true;
            boolean ignoreComponents = "jei:anvil".equals(patch.serializerId()) && index == 0;
            if (!matchesExpected(server, patch, prefix, inputs.get(index), ignoreComponents)) {
                return false;
            }
        }
        return found;
    }

    private static boolean matchesAnyExpected(MinecraftServer server, RecipePatch patch,
                                              String prefix, List<ItemStack> actual) {
        if (!hasExpected(patch, prefix)) {
            return false;
        }
        for (ItemStack stack : actual) {
            if (matchesExpected(server, patch, prefix, stack, false)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesExpected(MinecraftServer server, RecipePatch patch,
                                           String prefix, ItemStack actual) {
        return matchesExpected(server, patch, prefix, actual, false);
    }

    private static boolean matchesExpected(MinecraftServer server, RecipePatch patch,
                                           String prefix, ItemStack actual,
                                           boolean ignoreComponents) {
        String desiredPrefix = hasFields(patch, prefix) ? prefix : "match." + prefix;
        Optional<ItemStack> expected = JeiVanillaRecipeEditorAdapter.stackFromFields(
                patch, desiredPrefix, server.registryAccess());
        List<ItemStack> candidates = JeiVanillaRecipeEditorAdapter.candidatesFromFields(
                patch, desiredPrefix, server.registryAccess());
        if (!candidates.isEmpty()) {
            for (ItemStack candidate : candidates) {
                if (actual != null && !actual.isEmpty()
                        && (ignoreComponents
                        ? ItemStack.isSameItem(candidate, actual)
                        : ItemStack.isSameItemSameComponents(candidate, actual))) {
                    return true;
                }
            }
            return false;
        }
        if (!expected.isPresent()) {
            return actual == null || actual.isEmpty();
        }
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        // The left anvil item is a family of stacks: damage, repair cost and
        // other mutable components change during normal gameplay. The right
        // item (for example an enchanted book) retains meaningful components.
        return ignoreComponents
                ? ItemStack.isSameItem(expected.get(), actual)
                : ItemStack.isSameItemSameComponents(expected.get(), actual);
    }

    private static boolean hasExpected(RecipePatch patch, String prefix) {
        return hasFields(patch, prefix) || hasFields(patch, "match." + prefix);
    }

    private static boolean hasFields(RecipePatch patch, String prefix) {
        return patch.fields().containsKey(prefix + ".item")
                || patch.fields().containsKey(prefix + ".stack")
                || patch.fields().containsKey(prefix + ".count");
    }

    private static ItemStack output(MinecraftServer server, RecipePatch patch) {
        String prefix = hasFields(patch, "output") ? "output" : "base.output";
        return JeiVanillaRecipeEditorAdapter.stackFromFields(
                patch, prefix, server.registryAccess()).orElse(ItemStack.EMPTY);
    }
}
