package cc.sighs.JEIEditor.editor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-kind ingredient model and the slot patch fields it produces. */
class IngredientKindTest {
    @Test
    void itemAmountRangeStaysOneToSixtyFourAndTheotherKindsUseTheirOwnRange() {
        assertEquals(1, IngredientKind.ITEM.minAmount());
        assertEquals(64, IngredientKind.ITEM.maxAmount());
        assertTrue(IngredientKind.ITEM.acceptsAmount(64));
        assertFalse(IngredientKind.ITEM.acceptsAmount(65));
        assertFalse(IngredientKind.ITEM.acceptsAmount(0));

        // A fluid amount is a volume in millibuckets: the 1000 mB and 125 mB recipes
        // the shipped mods use must be accepted where an item count would not be.
        assertEquals(1000, IngredientKind.FLUID.requireAmount(1000));
        assertEquals(125, IngredientKind.CHEMICAL.requireAmount(125));
        assertFalse(IngredientKind.FLUID.acceptsAmount(0));
        assertFalse(IngredientKind.CHEMICAL.acceptsAmount(1000001));
        assertThrows(IllegalArgumentException.class, () -> IngredientKind.FLUID.requireAmount(0));
        assertThrows(IllegalArgumentException.class, () -> IngredientKind.CHEMICAL.requireAmount(1000001));
    }

    @Test
    void ingredientValidationUsesTheKindsRangeAndFieldNames() {
        EditorIngredient item = new EditorIngredient("minecraft:iron_ingot", 64);
        EditorIngredient fluid = new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 1000);
        EditorIngredient chemical =
                new EditorIngredient(IngredientKind.CHEMICAL, "mekanism:hydrofluoric_acid", 1);

        assertEquals(IngredientKind.ITEM, item.kind());
        assertEquals("minecraft:iron_ingot", item.id());
        assertEquals("minecraft:iron_ingot", item.itemId());
        assertEquals(64, item.amount());
        assertEquals(64, item.count());
        assertEquals(IngredientKind.FLUID, fluid.kind());
        assertEquals("minecraft:water", fluid.id());
        assertEquals("minecraft:water", fluid.itemId());
        assertEquals(1000, fluid.amount());
        assertEquals(IngredientKind.CHEMICAL, chemical.kind());

        // The item bound no longer governs the new kinds, and the new bound still
        // rejects a value outside it.
        assertThrows(IllegalArgumentException.class, () -> new EditorIngredient("minecraft:stone", 65));
        assertThrows(IllegalArgumentException.class, () -> new EditorIngredient("minecraft:stone", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new EditorIngredient(IngredientKind.FLUID, "water", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new EditorIngredient(IngredientKind.CHEMICAL, "mekanism:hydrogen", 1000001));
    }

    @Test
    void kindsAreDistinctIdentities() {
        // 8 is a legal amount for both kinds, so only the kind can separate these two.
        EditorIngredient item = new EditorIngredient("create:potion", 8);
        EditorIngredient fluid = new EditorIngredient(IngredientKind.FLUID, "create:potion", 8);

        assertNotEquals(item, fluid);
        assertNotEquals(item.hashCode(), fluid.hashCode());
        assertTrue(item.isItem());
        assertFalse(fluid.isItem());
        assertEquals(item, new EditorIngredient("create:potion", 8));
    }

    @Test
    void slotPatchFieldsCarryTheKindsOwnNamesAndRoundTrip() {
        Map<String, String> fluidFields = SlotPatchFields.replace("input.0",
                new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 500));
        assertEquals("minecraft:water", fluidFields.get("input.0.fluid"));
        assertEquals("500", fluidFields.get("input.0.fluid_amount"));
        assertEquals(2, fluidFields.size());

        Map<String, String> chemicalFields = SlotPatchFields.replace("output",
                new EditorIngredient(IngredientKind.CHEMICAL, "mekanism:uranium_hexafluoride", 2));
        assertEquals("mekanism:uranium_hexafluoride", chemicalFields.get("output.chemical"));
        assertEquals("2", chemicalFields.get("output.chemical_amount"));

        // The item field names are unchanged, which is what keeps every existing
        // patch, fixture and fingerprint working.
        Map<String, String> itemFields =
                SlotPatchFields.replace("input.1", new EditorIngredient("minecraft:stone", 3));
        assertEquals("minecraft:stone", itemFields.get("input.1.item"));
        assertEquals("3", itemFields.get("input.1.count"));
    }

    @Test
    void slotPatchFieldsReadBackThePatchedIngredientAndKeepUntouchedFields() {
        EditorIngredient original = new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 1000);
        Map<String, String> amountOnly = java.util.Collections.singletonMap("input.0.fluid_amount", "250");

        assertTrue(SlotPatchFields.touches(amountOnly, "input.0"));
        assertFalse(SlotPatchFields.touches(amountOnly, "input.1"));
        assertEquals(IngredientKind.FLUID, SlotPatchFields.kindOf(amountOnly, "input.0"));
        assertNull(SlotPatchFields.id(amountOnly, "input.0"));
        assertEquals("250", SlotPatchFields.amount(amountOnly, "input.0"));

        EditorIngredient patched = SlotPatchFields.patched(amountOnly, "input.0", original);
        assertEquals("minecraft:water", patched.id());
        assertEquals(250, patched.amount());
        assertEquals(IngredientKind.FLUID, patched.kind());

        // Nothing about the slot keeps the original ingredient.
        assertNull(SlotPatchFields.kindOf(amountOnly, "input.1"));
        assertEquals(original, SlotPatchFields.patched(amountOnly, "input.1", original));
    }

    @Test
    void slotPatchFieldsClearOnlyAnItemSlot() {
        Map<String, String> cleared = SlotPatchFields.clearItem("input.0");
        assertEquals("minecraft:air", cleared.get("input.0.item"));
        assertEquals("0", cleared.get("input.0.count"));
        assertNull(SlotPatchFields.patched(cleared, "input.0",
                new EditorIngredient("minecraft:stone", 1)));

        // A fluid patch that names the item clear sentinel is a different kind, so it
        // is an amount outside the fluid range rather than a clear.
        Map<String, String> fluid = java.util.Collections.singletonMap("input.0.fluid", "minecraft:air");
        assertNull(SlotPatchFields.patched(fluid, "input.0", null));
    }

    @Test
    void slotPatchFieldsRefuseAMixedOrUnresolvableSlot() {
        java.util.Map<String, String> mixed = new java.util.LinkedHashMap<String, String>();
        mixed.put("input.0.item", "minecraft:stone");
        mixed.put("input.0.fluid", "minecraft:water");
        assertNull(SlotPatchFields.kindOf(mixed, "input.0"));
        assertNull(SlotPatchFields.patched(mixed, "input.0", null));

        Map<String, String> tooLarge = java.util.Collections.singletonMap("input.0.fluid_amount", "1000001");
        assertNull(SlotPatchFields.patched(tooLarge, "input.0",
                new EditorIngredient(IngredientKind.FLUID, "minecraft:water", 1000)));
        Map<String, String> notANumber = java.util.Collections.singletonMap("input.0.chemical_amount", "x");
        assertNull(SlotPatchFields.patched(notANumber, "input.0", null));
    }

    @Test
    void propertiesResolveBackToTheirKind() {
        assertEquals(IngredientKind.ITEM, IngredientKind.forProperty("item"));
        assertEquals(IngredientKind.ITEM, IngredientKind.forProperty("count"));
        assertEquals(IngredientKind.FLUID, IngredientKind.forProperty("fluid"));
        assertEquals(IngredientKind.FLUID, IngredientKind.forProperty("fluid_amount"));
        assertEquals(IngredientKind.CHEMICAL, IngredientKind.forProperty("chemical_amount"));
        assertNull(IngredientKind.forProperty("stack"));
        assertNull(IngredientKind.forProperty(null));
        assertEquals(IngredientKind.CHEMICAL, IngredientKind.forId("chemical"));
        assertNull(IngredientKind.forId("energy"));
    }
}
