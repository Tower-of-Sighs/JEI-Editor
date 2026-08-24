package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory fuel values used immediately after a save, before the next data reload. */
@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID)
final class FuelOverrideState {
    private static final Map<ResourceLocation, Integer> OVERRIDES =
            new LinkedHashMap<ResourceLocation, Integer>();

    private FuelOverrideState() {
    }

    static synchronized void set(ResourceLocation itemId, int burnTime) {
        if (itemId != null && burnTime >= 0) {
            OVERRIDES.put(itemId, Integer.valueOf(burnTime));
        }
    }

    static synchronized void remove(ResourceLocation itemId) {
        if (itemId != null) {
            OVERRIDES.remove(itemId);
        }
    }

    static synchronized Optional<Integer> get(ResourceLocation itemId) {
        return Optional.ofNullable(OVERRIDES.get(itemId));
    }

    static synchronized Map<ResourceLocation, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<ResourceLocation, Integer>(OVERRIDES));
    }

    static void applyPatch(RecipePatch patch) {
        FuelRecipeEditorAdapter.itemId(patch).ifPresent(itemId -> set(itemId,
                FuelRecipeEditorAdapter.burnTime(patch)));
    }

    @SubscribeEvent
    public static void onFuelBurnTime(FurnaceFuelBurnTimeEvent event) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        Integer burnTime;
        synchronized (FuelOverrideState.class) {
            burnTime = OVERRIDES.get(itemId);
        }
        if (burnTime != null) {
            event.setBurnTime(burnTime.intValue());
        }
    }
}
