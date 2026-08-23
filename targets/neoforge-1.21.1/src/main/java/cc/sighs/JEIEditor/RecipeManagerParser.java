package cc.sighs.JEIEditor;

import com.google.gson.JsonObject;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

/** Exposes Minecraft's protected recipe JSON parser without reflection. */
final class RecipeManagerParser extends RecipeManager {
    private RecipeManagerParser(HolderLookup.Provider registries) {
        super(registries);
    }

    static RecipeHolder<?> parse(ResourceLocation id, JsonObject json, HolderLookup.Provider registries) {
        return fromJson(id, json, registries);
    }
}
