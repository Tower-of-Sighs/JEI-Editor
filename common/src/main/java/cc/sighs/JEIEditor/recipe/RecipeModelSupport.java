package cc.sighs.JEIEditor.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.SlotPatchFields;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/** Pure model operations shared by every loader adapter. */
public final class RecipeModelSupport {
    private RecipeModelSupport() {
    }

    /**
     * Replaces one whole slot with {@code ingredient}, in the field names of the
     * ingredient's kind: {@code <slotKey>.item}/{@code .count} for an item,
     * {@code <slotKey>.fluid}/{@code .fluid_amount} for a fluid and
     * {@code <slotKey>.chemical}/{@code .chemical_amount} for a chemical.
     */
    public static RecipePatch slotPatch(EditorModel model, String slotKey, EditorIngredient ingredient) {
        if (model == null) {
            throw new IllegalArgumentException("recipe slot and ingredient are required");
        }
        return new RecipePatch(model.recipeId(), model.serializerId(), model.baseFingerprint(),
                SlotPatchFields.replace(slotKey, ingredient));
    }

    /**
     * The fingerprint of a model, hashed with SHA-256.
     *
     * <p>The hashed string is
     * {@code <recipeId>|<serializerId>|[<key>=<role>[:<kind>:<id>:<amount>]]*[|<property>=<value>]*}
     * over the model's slots in order; a slot without an ingredient contributes
     * only its key and role. The ingredient's kind is part of the hashed value,
     * so a slot holding {@code minecraft:water} as a fluid never hashes like one
     * holding it as an item, and the stored slug is a true identity of the model
     * the patch was built from.
     */
    public static String fingerprint(String recipeId, String serializerId,
                                     List<EditorSlot> slots, Map<String, String> properties) {
        StringBuilder value = new StringBuilder(recipeId).append('|').append(serializerId);
        for (EditorSlot slot : slots) {
            value.append('|').append(slot.key()).append('=').append(slot.role());
            if (slot.ingredient() != null) {
                IngredientKind kind = slot.ingredient().kind();
                value.append(':').append(kind.id())
                        .append(':').append(slot.ingredient().id())
                        .append(':').append(slot.ingredient().amount());
            }
        }
        if (properties != null) {
            for (Map.Entry<String, String> property : properties.entrySet()) {
                value.append('|').append(property.getKey()).append('=').append(property.getValue());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                result.append(String.format("%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
