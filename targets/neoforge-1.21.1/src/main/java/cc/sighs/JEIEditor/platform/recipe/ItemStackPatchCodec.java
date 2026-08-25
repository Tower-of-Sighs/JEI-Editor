package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Encodes the platform-specific ItemStack details kept outside the common model. */
public final class ItemStackPatchCodec {
    private ItemStackPatchCodec() {
    }

    public static Optional<EditorIngredient> identity(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getCount() < 1 || stack.getCount() > 64) {
            return Optional.empty();
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? Optional.<EditorIngredient>empty()
                : Optional.of(new EditorIngredient(id.toString(), stack.getCount()));
    }

    public static Optional<String> encode(ItemStack stack, HolderLookup.Provider registries) {
        if (registries == null || !identity(stack).isPresent()) {
            return Optional.empty();
        }
        try {
            Tag encoded = stack.save(registries);
            String value = encoded.toString();
            return value.length() <= RecipeEditPayloadRules.MAX_FIELD_VALUE_LENGTH
                    ? Optional.of(value) : Optional.<String>empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static Optional<ItemStack> decode(String encoded, HolderLookup.Provider registries) {
        if (encoded == null || registries == null) {
            return Optional.empty();
        }
        try {
            CompoundTag tag = TagParser.parseTag(encoded);
            return ItemStack.parse(registries, tag).filter(stack -> !stack.isEmpty());
        } catch (Exception exception) {
            return Optional.empty();
        }
    }
}
