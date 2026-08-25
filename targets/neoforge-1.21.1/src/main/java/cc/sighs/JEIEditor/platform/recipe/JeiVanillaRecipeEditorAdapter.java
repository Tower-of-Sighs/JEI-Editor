package cc.sighs.JEIEditor.platform.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipePatchSemantics;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.api.recipe.vanilla.IJeiBrewingRecipe;
import mezz.jei.api.recipe.vanilla.IJeiCompostingRecipe;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.api.recipe.vanilla.IJeiIngredientInfoRecipe;
import mezz.jei.library.plugins.vanilla.anvil.AnvilHelper;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Models JEI's vanilla-generated pages that have no RecipeManager JSON entry. */
public final class JeiVanillaRecipeEditorAdapter {
    public static final String STACK_PROPERTY_PREFIX = "stack.";
    public static final String VISUAL_INPUT_PROPERTY_PREFIX = "jei.input.";
    public static final String NAMED_SLOT_PROPERTY_PREFIX = "jei.name.";
    public static final String COMPOST_CHANCE_PROPERTY = "compost.chance";
    public static final String CANDIDATES_PROPERTY_PREFIX = "candidates.";
    public static final String ANVIL_COST_PROPERTY = "anvil.cost";

    private JeiVanillaRecipeEditorAdapter() {
    }

    public static Optional<EditorModel> createModel(Object recipe) {
        return createModel(recipe, null, null);
    }

    public static Optional<EditorModel> createModel(Object recipe, ResourceLocation fallbackUid) {
        return createModel(recipe, fallbackUid, null);
    }

    public static Optional<EditorModel> createModel(Object recipe, ResourceLocation fallbackUid,
                                                     HolderLookup.Provider registries) {
        if (recipe instanceof IJeiBrewingRecipe) {
            IJeiBrewingRecipe brewing = (IJeiBrewingRecipe) recipe;
            List<ItemStack> inputs = new ArrayList<ItemStack>();
            inputs.add(first(brewing.getPotionInputs()));
            inputs.add(first(brewing.getIngredients()));
            Map<String, String> layout = new LinkedHashMap<String, String>();
            // JEI draws the same potion ingredient in three bottle positions.
            layout.put(VISUAL_INPUT_PROPERTY_PREFIX + "0", "input.0");
            layout.put(VISUAL_INPUT_PROPERTY_PREFIX + "1", "input.0");
            layout.put(VISUAL_INPUT_PROPERTY_PREFIX + "2", "input.0");
            layout.put(VISUAL_INPUT_PROPERTY_PREFIX + "3", "input.1");
            addCandidates(layout, "input.0", brewing.getPotionInputs(), registries);
            addCandidates(layout, "input.1", brewing.getIngredients(), registries);
            return create("jei:brewing", brewing.getUid(), inputs, brewing.getPotionOutput(),
                    layout, registries);
        }
        if (recipe instanceof IJeiAnvilRecipe) {
            IJeiAnvilRecipe anvil = (IJeiAnvilRecipe) recipe;
            List<ItemStack> inputs = new ArrayList<ItemStack>();
            inputs.add(first(anvil.getLeftInputs()));
            inputs.add(first(anvil.getRightInputs()));
            Map<String, String> layout = sequentialLayout(2);
            layout.put(NAMED_SLOT_PROPERTY_PREFIX + "leftSlot", "input.0");
            layout.put(NAMED_SLOT_PROPERTY_PREFIX + "rightSlot", "input.1");
            addCandidates(layout, "input.0", anvil.getLeftInputs(), registries);
            addCandidates(layout, "input.1", anvil.getRightInputs(), registries);
            Optional<EditorModel> model = create("jei:anvil", anvil.getUid(), inputs,
                    first(anvil.getOutputs()), layout, registries);
            if (model.isPresent()) {
                int cost = AnvilHelper.findLevelsCost(inputs.get(0), inputs.get(1));
                Map<String, String> properties = new LinkedHashMap<String, String>(model.get().properties());
                properties.put(ANVIL_COST_PROPERTY, Integer.toString(Math.max(1, cost)));
                model = Optional.of(new EditorModel(model.get().recipeId(), model.get().serializerId(),
                        model.get().baseFingerprint(), model.get().slots(), properties));
            }
            return model;
        }
        if (recipe instanceof IJeiGrindstoneRecipe) {
            IJeiGrindstoneRecipe grindstone = (IJeiGrindstoneRecipe) recipe;
            List<ItemStack> inputs = new ArrayList<ItemStack>();
            inputs.add(first(grindstone.getTopInputs()));
            inputs.add(first(grindstone.getBottomInputs()));
            Map<String, String> layout = sequentialLayout(2);
            layout.put(NAMED_SLOT_PROPERTY_PREFIX + "topSlot", "input.0");
            layout.put(NAMED_SLOT_PROPERTY_PREFIX + "bottomSlot", "input.1");
            addCandidates(layout, "input.0", grindstone.getTopInputs(), registries);
            addCandidates(layout, "input.1", grindstone.getBottomInputs(), registries);
            return create("jei:grindstone", grindstone.getUid(), inputs,
                    first(grindstone.getOutputs()), layout, registries);
        }
        if (recipe instanceof IJeiCompostingRecipe) {
            IJeiCompostingRecipe composting = (IJeiCompostingRecipe) recipe;
            Map<String, String> properties = sequentialLayout(1);
            properties.put(COMPOST_CHANCE_PROPERTY, Float.toString(composting.getChance()));
            addCandidates(properties, "input.0", composting.getInputs(), registries);
            return create("jei:composting", composting.getUid(),
                    Collections.singletonList(first(composting.getInputs())), ItemStack.EMPTY,
                    properties, registries);
        }
        if (recipe instanceof IJeiIngredientInfoRecipe) {
            IJeiIngredientInfoRecipe info = (IJeiIngredientInfoRecipe) recipe;
            List<ItemStack> inputs = new ArrayList<ItemStack>();
            info.getIngredients().forEach(value -> {
                if (value != null && value.getIngredient() instanceof ItemStack) {
                    inputs.add(((ItemStack) value.getIngredient()).copy());
                }
            });
            return create("jei:information", fallbackUid, inputs, ItemStack.EMPTY,
                    sequentialLayout(inputs.size()), registries);
        }
        return Optional.empty();
    }

    public static boolean supportsSerializer(String serializerId) {
        return "jei:brewing".equals(serializerId) || "jei:anvil".equals(serializerId)
                || "jei:grindstone".equals(serializerId) || "jei:composting".equals(serializerId)
                || "jei:information".equals(serializerId);
    }

    public static boolean isSyntheticPatch(RecipePatch patch) {
        return patch != null && supportsSerializer(patch.serializerId());
    }

    public static boolean isCompostingPatch(RecipePatch patch) {
        return patch != null && "jei:composting".equals(patch.serializerId());
    }

    public static boolean isCreatedAnvilPatch(RecipePatch patch) {
        return patch != null && "jei:anvil".equals(patch.serializerId())
                && RecipePatchSemantics.isCreation(patch);
    }

    public static boolean validatePatch(RecipePatch patch) {
        if (!isSyntheticPatch(patch) || RecipePatchSemantics.isDeletion(patch)) {
            return false;
        }
        boolean creation = RecipePatchSemantics.isCreation(patch);
        if (creation && !"jei:anvil".equals(patch.serializerId())) {
            return false;
        }
        boolean editFound = false;
        for (Map.Entry<String, String> field : patch.fields().entrySet()) {
            String key = field.getKey();
            String value = field.getValue();
            if (RecipePatchSemantics.CREATED_FIELD.equals(key)
                    && RecipePatchSemantics.CREATED_VALUE.equals(value)) {
                continue;
            }
            if (COMPOST_CHANCE_PROPERTY.equals(key)) {
                if (!validChance(value)) {
                    return false;
                }
                continue;
            }
            if (ANVIL_COST_PROPERTY.equals(key)) {
                if (!"jei:anvil".equals(patch.serializerId()) || !validAnvilCost(value)) {
                    return false;
                }
                editFound = true;
                continue;
            }
            String slotField = stripMetadataPrefix(key);
            if (!validSlotField(slotField, value)) {
                return false;
            }
            if (key.startsWith("input.") || key.startsWith("output.")) {
                editFound = true;
            }
        }
        if (!editFound) {
            return false;
        }
        return !creation || validCreatedAnvilFields(patch);
    }

    public static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack,
                                           HolderLookup.Provider registries) {
        if (!ItemStackPatchCodec.identity(stack).isPresent() || !isInputSlot(model, slotKey)) {
            throw new IllegalArgumentException("this JEI input slot cannot be replaced");
        }
        return withBaseMetadata(model, slotFields(slotKey, stack, registries));
    }

    public static RecipePatch replaceInput(EditorModel model, String slotKey, ItemStack stack) {
        return replaceInput(model, slotKey, stack, null);
    }

    public static RecipePatch replaceOutput(EditorModel model, ItemStack stack,
                                            HolderLookup.Provider registries) {
        // A newly created JEI recipe deliberately starts with an empty
        // output slot. It is still an editable target; validation only
        // requires a non-empty output when the draft is saved.
        boolean emptyOutputDraft = RecipeCreationAdapter.isCreatedModel(model);
        if (!ItemStackPatchCodec.identity(stack).isPresent()
                || (!hasOutput(model) && !emptyOutputDraft)) {
            throw new IllegalArgumentException("this JEI page has no editable output");
        }
        return withBaseMetadata(model, slotFields("output", stack, registries));
    }

    public static RecipePatch replaceOutput(EditorModel model, ItemStack stack) {
        return replaceOutput(model, stack, null);
    }

    public static RecipePatch clearSlot(EditorModel model, String slotKey) {
        if (model == null || !supportsSerializer(model.serializerId()) || !isInputSlot(model, slotKey)) {
            throw new IllegalArgumentException("this JEI input slot cannot be cleared");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", "minecraft:air");
        fields.put(slotKey + ".count", "0");
        return withBaseMetadata(model, fields);
    }

    public static RecipePatch setOutputCount(EditorModel model, int count) {
        if (!hasOutput(model) || count < 1 || count > 64) {
            throw new IllegalArgumentException("output count must be between 1 and 64");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("output.count", Integer.toString(count));
        return withBaseMetadata(model, fields);
    }

    public static RecipePatch setAnvilCost(EditorModel model, int cost) {
        if (model == null || !"jei:anvil".equals(model.serializerId())) {
            throw new IllegalArgumentException("this page has no editable anvil cost");
        }
        if (!validAnvilCost(Integer.toString(cost))) {
            throw new IllegalArgumentException("anvil experience cost must be between 1 and 1000000");
        }
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(ANVIL_COST_PROPERTY, Integer.toString(cost));
        return withBaseMetadata(model, fields);
    }

    public static int anvilCost(EditorModel model, RecipePatch patch) {
        String value = patch == null ? null : patch.fields().get(ANVIL_COST_PROPERTY);
        if (value == null && model != null) {
            value = model.properties().get(ANVIL_COST_PROPERTY);
        }
        return validAnvilCost(value) ? Integer.parseInt(value) : 1;
    }

    /** Reconstructs a component-bearing preview stack from a model and its patch. */
    public static Optional<ItemStack> patchedStack(EditorModel model, String slotKey, RecipePatch patch,
                                                   HolderLookup.Provider registries) {
        if (model == null || slotKey == null || patch == null) {
            return Optional.empty();
        }
        String item = patch.fields().get(slotKey + ".item");
        String count = patch.fields().get(slotKey + ".count");
        if ("minecraft:air".equals(item) || "0".equals(count)) {
            return Optional.empty();
        }
        Optional<ItemStack> encoded = ItemStackPatchCodec.decode(
                patch.fields().get(slotKey + ".stack"), registries);
        if (!encoded.isPresent()) {
            encoded = modelStack(model, slotKey, registries);
        }
        ItemStack result = encoded.orElseGet(() -> basicStack(model, slotKey, patch));
        if (result.isEmpty()) {
            return Optional.empty();
        }
        if (item != null) {
            ResourceLocation id = ResourceLocation.tryParse(item);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                return Optional.empty();
            }
            if (!BuiltInRegistries.ITEM.getKey(result.getItem()).equals(id)) {
                result = new ItemStack(BuiltInRegistries.ITEM.get(id));
            }
        }
        if (count != null) {
            try {
                result.setCount(Integer.parseInt(count));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
        return result.isEmpty() ? Optional.<ItemStack>empty() : Optional.of(result);
    }

    public static Optional<ItemStack> modelStack(EditorModel model, String slotKey,
                                                 HolderLookup.Provider registries) {
        return model == null ? Optional.<ItemStack>empty() : ItemStackPatchCodec.decode(
                model.properties().get(STACK_PROPERTY_PREFIX + slotKey), registries);
    }

    /** Decodes one desired/base/match stack prefix stored in a synthetic patch. */
    public static Optional<ItemStack> stackFromFields(RecipePatch patch, String prefix,
                                                      HolderLookup.Provider registries) {
        if (patch == null || prefix == null) {
            return Optional.empty();
        }
        String itemText = patch.fields().get(prefix + ".item");
        String countText = patch.fields().get(prefix + ".count");
        if ("minecraft:air".equals(itemText) || "0".equals(countText)) {
            return Optional.empty();
        }
        ItemStack stack = ItemStackPatchCodec.decode(
                patch.fields().get(prefix + ".stack"), registries).orElse(ItemStack.EMPTY);
        ResourceLocation itemId = ResourceLocation.tryParse(itemText);
        if (stack.isEmpty() && itemId != null && BuiltInRegistries.ITEM.containsKey(itemId)) {
            stack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        } else if (!stack.isEmpty() && itemId != null
                && !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)) {
            stack = new ItemStack(BuiltInRegistries.ITEM.get(itemId));
        }
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (countText != null) {
            try {
                stack.setCount(Integer.parseInt(countText));
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
        return stack.isEmpty() ? Optional.<ItemStack>empty() : Optional.of(stack);
    }

    public static List<ItemStack> candidatesFromFields(RecipePatch patch, String prefix,
                                                       HolderLookup.Provider registries) {
        if (patch == null || prefix == null) {
            return Collections.emptyList();
        }
        String encoded = patch.fields().get(prefix + ".candidates");
        if (encoded == null) {
            return Collections.emptyList();
        }
        try {
            JsonElement parsed = JsonParser.parseString(encoded);
            if (!parsed.isJsonArray()) {
                return Collections.emptyList();
            }
            List<ItemStack> result = new ArrayList<ItemStack>();
            for (JsonElement value : parsed.getAsJsonArray()) {
                if (value.isJsonPrimitive()) {
                    ItemStackPatchCodec.decode(value.getAsString(), registries)
                            .ifPresent(result::add);
                }
            }
            return result;
        } catch (RuntimeException exception) {
            return Collections.emptyList();
        }
    }

    private static Optional<EditorModel> create(String serializer, ResourceLocation uid,
                                                 List<ItemStack> inputStacks, ItemStack outputStack,
                                                 Map<String, String> initialProperties,
                                                 HolderLookup.Provider registries) {
        if (inputStacks == null) {
            return Optional.empty();
        }
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        Map<String, String> properties = new LinkedHashMap<String, String>(initialProperties);
        for (int index = 0; index < inputStacks.size(); index++) {
            String key = "input." + index;
            ItemStack stack = inputStacks.get(index);
            slots.add(new EditorSlot(key, "input", ItemStackPatchCodec.identity(stack).orElse(null)));
            ItemStackPatchCodec.encode(stack, registries).ifPresent(value ->
                    properties.put(STACK_PROPERTY_PREFIX + key, value));
        }
        slots.add(new EditorSlot("output", "output",
                ItemStackPatchCodec.identity(outputStack).orElse(null)));
        ItemStackPatchCodec.encode(outputStack, registries).ifPresent(value ->
                properties.put(STACK_PROPERTY_PREFIX + "output", value));
        String recipeId = uid == null
                ? generatedUid(serializer, inputStacks, outputStack, registries)
                : uid.toString();
        String fingerprint = RecipeAdapterSupport.fingerprint(recipeId, serializer, slots, properties);
        return Optional.of(new EditorModel(recipeId, serializer, fingerprint, slots, properties));
    }

    /**
     * JEI's generated anvil enchantment pages do not always provide a UID.
     * Derive a stable page identity from the same displayed stacks instead of
     * silently making those pages non-editable.
     */
    private static String generatedUid(String serializer, List<ItemStack> inputs,
                                       ItemStack output, HolderLookup.Provider registries) {
        StringBuilder source = new StringBuilder(serializer);
        for (ItemStack stack : inputs) {
            source.append('|').append(stackIdentity(stack, registries));
        }
        source.append('|').append(stackIdentity(output, registries));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int index = 0; index < 12; index++) {
                hex.append(String.format("%02x", digest[index] & 0xff));
            }
            return ResourceLocation.fromNamespaceAndPath("jei", serializer.substring("jei:".length())
                    + "_" + hex).toString();
        } catch (Exception exception) {
            return ResourceLocation.fromNamespaceAndPath("jei", "synthetic_" +
                    Integer.toHexString(source.toString().hashCode())).toString();
        }
    }

    private static String stackIdentity(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        Optional<String> encoded = ItemStackPatchCodec.encode(stack, registries);
        return encoded.orElseGet(() -> BuiltInRegistries.ITEM.getKey(stack.getItem()) + "#"
                + stack.getCount() + "#" + stack.getComponents());
    }

    private static Map<String, String> sequentialLayout(int inputCount) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int index = 0; index < inputCount; index++) {
            result.put(VISUAL_INPUT_PROPERTY_PREFIX + index, "input." + index);
        }
        return result;
    }

    private static void addCandidates(Map<String, String> properties, String slotKey,
                                      List<ItemStack> candidates,
                                      HolderLookup.Provider registries) {
        if (properties == null || candidates == null || registries == null) {
            return;
        }
        JsonArray encoded = new JsonArray();
        for (ItemStack candidate : candidates) {
            ItemStackPatchCodec.encode(candidate, registries).ifPresent(value -> encoded.add(value));
        }
        if (encoded.size() > 0) {
            String value = encoded.toString();
            if (value.length() <= RecipeEditPayloadRules.MAX_FIELD_VALUE_LENGTH) {
                properties.put(CANDIDATES_PROPERTY_PREFIX + slotKey, value);
            }
        }
    }

    private static LinkedHashMap<String, String> slotFields(String slotKey, ItemStack stack,
                                                             HolderLookup.Provider registries) {
        EditorIngredient ingredient = ItemStackPatchCodec.identity(stack).orElseThrow(() ->
                new IllegalArgumentException("item stack is empty or has an invalid count"));
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>();
        fields.put(slotKey + ".item", ingredient.itemId());
        fields.put(slotKey + ".count", Integer.toString(ingredient.count()));
        ItemStackPatchCodec.encode(stack, registries).ifPresent(value ->
                fields.put(slotKey + ".stack", value));
        return fields;
    }

    private static RecipePatch withBaseMetadata(EditorModel model, Map<String, String> edits) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<String, String>(edits);
        for (EditorSlot slot : model.slots()) {
            if ("input".equals(slot.role()) && slot.ingredient() != null) {
                fields.put("match." + slot.key() + ".item", slot.ingredient().itemId());
                String stack = model.properties().get(STACK_PROPERTY_PREFIX + slot.key());
                if (stack != null) {
                    fields.put("match." + slot.key() + ".stack", stack);
                }
                String candidates = model.properties().get(CANDIDATES_PROPERTY_PREFIX + slot.key());
                if (candidates != null) {
                    fields.put("match." + slot.key() + ".candidates", candidates);
                }
            } else if ("output".equals(slot.role()) && slot.ingredient() != null) {
                fields.put("base.output.item", slot.ingredient().itemId());
                fields.put("base.output.count", Integer.toString(slot.ingredient().count()));
                String stack = model.properties().get(STACK_PROPERTY_PREFIX + "output");
                if (stack != null) {
                    fields.put("base.output.stack", stack);
                }
            }
        }
        String chance = model.properties().get(COMPOST_CHANCE_PROPERTY);
        if (chance != null) {
            fields.put(COMPOST_CHANCE_PROPERTY, chance);
        }
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(), fields);
    }

    private static ItemStack basicStack(EditorModel model, String slotKey, RecipePatch patch) {
        EditorIngredient original = null;
        for (EditorSlot slot : model.slots()) {
            if (slotKey.equals(slot.key())) {
                original = slot.ingredient();
                break;
            }
        }
        String itemText = patch.fields().get(slotKey + ".item");
        String countText = patch.fields().get(slotKey + ".count");
        ResourceLocation itemId = ResourceLocation.tryParse(itemText == null && original != null
                ? original.itemId() : itemText);
        int count = original == null ? 1 : original.count();
        try {
            if (countText != null) {
                count = Integer.parseInt(countText);
            }
        } catch (NumberFormatException exception) {
            return ItemStack.EMPTY;
        }
        return itemId == null || count < 1 || !BuiltInRegistries.ITEM.containsKey(itemId)
                ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.get(itemId), count);
    }

    private static String stripMetadataPrefix(String key) {
        if (key.startsWith("match.")) {
            return key.substring("match.".length());
        }
        if (key.startsWith("base.")) {
            return key.substring("base.".length());
        }
        return key;
    }

    private static boolean validSlotField(String key, String value) {
        boolean input = key.startsWith("input.") && key.indexOf('.', "input.".length()) > 0;
        boolean output = key.startsWith("output.");
        if (!input && !output) {
            return false;
        }
        if (key.endsWith(".item")) {
            ResourceLocation id = ResourceLocation.tryParse(value);
            return id != null && BuiltInRegistries.ITEM.containsKey(id);
        }
        if (key.endsWith(".count")) {
            try {
                int count = Integer.parseInt(value);
                return count >= 0 && count <= 64;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        if (key.endsWith(".stack")) {
            try {
                TagParser.parseTag(value);
                return true;
            } catch (Exception exception) {
                return false;
            }
        }
        if (key.endsWith(".candidates")) {
            try {
                JsonElement parsed = JsonParser.parseString(value);
                if (!parsed.isJsonArray()) {
                    return false;
                }
                for (JsonElement candidate : parsed.getAsJsonArray()) {
                    if (!candidate.isJsonPrimitive()) {
                        return false;
                    }
                    TagParser.parseTag(candidate.getAsString());
                }
                return true;
            } catch (Exception exception) {
                return false;
            }
        }
        return false;
    }

    private static boolean validChance(String value) {
        try {
            float chance = Float.parseFloat(value);
            return Float.isFinite(chance) && chance > 0.0F && chance <= 1.0F;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean validAnvilCost(String value) {
        try {
            int cost = Integer.parseInt(value);
            return cost >= 1 && cost <= 1000000;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean validCreatedAnvilFields(RecipePatch patch) {
        for (String slotKey : new String[] {"input.0", "input.1", "output"}) {
            String itemText = patch.fields().get(slotKey + ".item");
            String countText = patch.fields().get(slotKey + ".count");
            ResourceLocation itemId = ResourceLocation.tryParse(itemText);
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)
                    || "minecraft:air".equals(itemId.toString())) {
                return false;
            }
            try {
                int count = Integer.parseInt(countText);
                if (count < 1 || count > 64) {
                    return false;
                }
            } catch (RuntimeException exception) {
                return false;
            }
        }
        String cost = patch.fields().get(ANVIL_COST_PROPERTY);
        return cost == null || validAnvilCost(cost);
    }

    private static ItemStack first(List<ItemStack> stacks) {
        return stacks == null || stacks.isEmpty() || stacks.get(0) == null
                ? ItemStack.EMPTY : stacks.get(0).copy();
    }

    private static boolean hasOutput(EditorModel model) {
        if (model == null) {
            return false;
        }
        for (EditorSlot slot : model.slots()) {
            if ("output".equals(slot.role()) && slot.ingredient() != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInputSlot(EditorModel model, String slotKey) {
        if (model == null || slotKey == null) {
            return false;
        }
        for (EditorSlot slot : model.slots()) {
            if (slotKey.equals(slot.key()) && "input".equals(slot.role())) {
                return true;
            }
        }
        return false;
    }
}
