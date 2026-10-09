package cc.sighs.JEIEditor.recipe;

import cc.sighs.JEIEditor.editor.EditorIngredient;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.IngredientKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fingerprint hashes the kind of every ingredient, so a model can no longer
 * describe two different recipes with the same slug.
 */
class RecipeFingerprintTest {
    @Test
    void hashesKeyRoleKindIdAndAmountInSlotOrder() throws Exception {
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        slots.add(new EditorSlot("input.0", "input",
                new EditorIngredient("minecraft:cobblestone", 1)));
        slots.add(new EditorSlot("input.1", "input",
                new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 1000)));
        slots.add(new EditorSlot("input.2", "input", null));
        Map<String, String> properties = new LinkedHashMap<String, String>();
        properties.put("jei.input.0", "input.0");

        String expected = sha256("create:mixing/lava_from_cobble|create:mixing"
                + "|input.0=input:item:minecraft:cobblestone:1"
                + "|input.1=input:fluid:minecraft:water:1000"
                + "|input.2=input"
                + "|jei.input.0=input.0");

        assertEquals(expected,
                RecipeModelSupport.fingerprint("create:mixing/lava_from_cobble", "create:mixing",
                        slots, properties));
    }

    @Test
    void separatesTheKindsAndTheAmounts() {
        // 8 is a legal amount for all three kinds, so the kind is the only difference.
        String item = fingerprint(new EditorIngredient("create:potion", 8));
        String fluid = fingerprint(new EditorIngredient(IngredientKind.FLUID, "create:potion", 8));
        String chemical =
                fingerprint(new EditorIngredient(IngredientKind.CHEMICAL, "create:potion", 8));
        String largerFluid =
                fingerprint(new EditorIngredient(IngredientKind.FLUID, "create:potion", 9));

        assertNotEquals(item, fluid);
        assertNotEquals(fluid, chemical);
        assertNotEquals(fluid, largerFluid);
        assertEquals(item, fingerprint(new EditorIngredient("create:potion", 8)));
        assertEquals(64, fluid.length());
    }

    @Test
    void aSlotWithoutAnIngredientHashesOnlyItsKeyAndRole() throws Exception {
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        slots.add(new EditorSlot("input.0", "input", null));

        assertEquals(sha256("mekanism:crushing|mekanism:crushing|input.0=input"),
                RecipeModelSupport.fingerprint("mekanism:crushing", "mekanism:crushing", slots, null));
        assertTrue(RecipeModelSupport.fingerprint("mekanism:crushing", "mekanism:crushing", slots, null)
                .matches("[0-9a-f]{64}"));
    }

    private static String fingerprint(EditorIngredient ingredient) {
        List<EditorSlot> slots = new ArrayList<EditorSlot>();
        slots.add(new EditorSlot("input.0", "input", ingredient));
        return RecipeModelSupport.fingerprint("minecraft:test", "create:mixing", slots, null);
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte current : digest) {
            result.append(String.format("%02x", current & 0xff));
        }
        return result.toString();
    }
}
