package cc.sighs.JEIEditor.editor;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client/server patch field-shape contract, table-driven over every
 * {@link IngredientKind}.
 *
 * <p>A slot patch crosses the boundary in exactly one shape per kind: the client
 * emits it with {@link SlotPatchFields#replace} (or {@link SlotPatchFields#clearItem}
 * for an item clear, or the bare amount field for a count edit) and the server reads
 * it back with {@link SlotPatchFields#kindOf} and {@link IngredientKind#forProperty},
 * then requires the kind to be one the page's declaration accepts for that slot
 * ({@link SlotPatchFields#acceptsProperty}, the predicate the server gate itself
 * calls). This test drives those pieces together for every kind, so a new kind or a
 * renamed field cannot land on one side only: a declaration-scoped fluid field was
 * exactly the shape that drifted once and left the client offering a slot the server
 * refused.
 */
class SlotPatchShapeContractTest {
    private static final String SLOT = "input.0";

    /** The id a test ingredient of each kind carries. */
    private static String sampleId(IngredientKind kind) {
        if (kind == IngredientKind.FLUID) {
            return "minecraft:water";
        }
        if (kind == IngredientKind.CHEMICAL) {
            return "mekanism:hydrogen";
        }
        return "minecraft:stone";
    }

    private static EditorIngredient ingredient(IngredientKind kind, int amount) {
        return new EditorIngredient(kind, sampleId(kind), amount);
    }

    /** The whole-slot shape the client emits for one kind. */
    private static Map<String, String> emitted(IngredientKind kind) {
        return SlotPatchFields.replace(SLOT, ingredient(kind, Math.max(1, kind.minAmount())));
    }

    /** The last dotted component of a patch key, which is what the server dispatches on. */
    private static String property(String key) {
        return key.substring(key.lastIndexOf('.') + 1);
    }

    @Test
    void everyKindEmitsTheTwoFieldsItsOwnPropertiesName() {
        for (IngredientKind kind : IngredientKind.values()) {
            Map<String, String> fields = emitted(kind);
            assertEquals(2, fields.size(), kind + " must emit exactly one id and one amount field");
            Set<String> keys = fields.keySet();
            assertTrue(keys.contains(SLOT + "." + kind.idField()),
                    kind + " must emit " + kind.idField());
            assertTrue(keys.contains(SLOT + "." + kind.amountField()),
                    kind + " must emit " + kind.amountField());

            // The server's property dispatch has to map both names back to this kind, or a
            // patch the client produced cannot be attributed to a kind at all.
            assertEquals(kind, IngredientKind.forProperty(kind.idField()));
            assertEquals(kind, IngredientKind.forProperty(kind.amountField()));
            assertEquals(kind, SlotPatchFields.kindOf(fields, SLOT));
        }
    }

    @Test
    void theServerAcceptsEveryShapeTheClientEmitsWhenTheDeclarationNamesTheKind() {
        for (IngredientKind kind : IngredientKind.values()) {
            Set<IngredientKind> declared = Collections.singleton(kind);
            Map<String, String> full = emitted(kind);
            for (String key : full.keySet()) {
                assertTrue(SlotPatchFields.acceptsProperty(full, SLOT, property(key), declared),
                        kind + " full shape must be accepted for a declaration naming it");
            }

            // The partial shapes the client can send for the same slot: an id-only drop
            // and an amount-only count edit.
            Map<String, String> idOnly = new LinkedHashMap<String, String>();
            idOnly.put(SLOT + "." + kind.idField(), sampleId(kind));
            Map<String, String> amountOnly = new LinkedHashMap<String, String>();
            amountOnly.put(SLOT + "." + kind.amountField(), "2");
            for (int index = 0; index < 2; index++) {
                Map<String, String> partial = index == 0 ? idOnly : amountOnly;
                assertEquals(kind, SlotPatchFields.kindOf(partial, SLOT));
                for (String key : partial.keySet()) {
                    assertTrue(SlotPatchFields.acceptsProperty(partial, SLOT, property(key), declared),
                            kind + " partial shape must be accepted for a declaration naming it");
                }
            }

            // A whole-slot shape and both partials must also hold a declaration that
            // accepts the kind alongside others, as an ordered segment list does.
            Set<IngredientKind> all = EnumSet.allOf(IngredientKind.class);
            for (String key : full.keySet()) {
                assertTrue(SlotPatchFields.acceptsProperty(full, SLOT, property(key), all));
            }
        }
    }

    @Test
    void theServerRefusesAShapeTheDeclarationDoesNotNameForTheSlot() {
        for (IngredientKind emittedKind : IngredientKind.values()) {
            Map<String, String> fields = emitted(emittedKind);
            for (IngredientKind declaredKind : IngredientKind.values()) {
                if (declaredKind == emittedKind) {
                    continue;
                }
                Set<IngredientKind> declared = Collections.singleton(declaredKind);
                for (String key : fields.keySet()) {
                    assertFalse(
                            SlotPatchFields.acceptsProperty(fields, SLOT, property(key), declared),
                            "a " + emittedKind + " shape must be refused when the declaration says "
                                    + declaredKind);
                }
            }
            // No declaration at all accepts nothing.
            for (String key : fields.keySet()) {
                assertFalse(SlotPatchFields.acceptsProperty(fields, SLOT, property(key),
                        Collections.<IngredientKind>emptySet()));
            }
            // A null declaration set is malformed and refused rather than trusted.
            assertFalse(SlotPatchFields.acceptsProperty(fields, SLOT, emittedKind.idField(), null));
        }
    }

    @Test
    void theServerRefusesAMixedShapeForEveryDeclaration() {
        IngredientKind[] kinds = IngredientKind.values();
        for (int first = 0; first < kinds.length; first++) {
            for (int second = first + 1; second < kinds.length; second++) {
                Map<String, String> mixed = new LinkedHashMap<String, String>();
                mixed.putAll(SlotPatchFields.replace(SLOT, ingredient(kinds[first], 1)));
                mixed.putAll(SlotPatchFields.replace(SLOT, ingredient(kinds[second], 1)));
                assertNull(SlotPatchFields.kindOf(mixed, SLOT),
                        kinds[first] + "+" + kinds[second] + " names two kinds for one slot");
                for (String key : mixed.keySet()) {
                    assertFalse(SlotPatchFields.acceptsProperty(mixed, SLOT, property(key),
                            EnumSet.allOf(IngredientKind.class)));
                }
            }
        }
    }

    @Test
    void theItemClearFormIsAcceptedOnlyByAnItemDeclaration() {
        Map<String, String> cleared = SlotPatchFields.clearItem(SLOT);
        assertEquals("minecraft:air", cleared.get(SLOT + "." + IngredientKind.ITEM.idField()));
        assertEquals("0", cleared.get(SLOT + "." + IngredientKind.ITEM.amountField()));
        assertTrue(SlotPatchFields.acceptsProperty(cleared, SLOT, "item",
                Collections.singleton(IngredientKind.ITEM)));
        assertFalse(SlotPatchFields.acceptsProperty(cleared, SLOT, "item",
                Collections.singleton(IngredientKind.FLUID)));
        assertNull(SlotPatchFields.patched(cleared, SLOT, ingredient(IngredientKind.ITEM, 1)),
                "the clear form reads back as an empty slot");
    }

    @Test
    void everyEmittedAmountIsInsideItsKindsRangeAndRoundTrips() {
        for (IngredientKind kind : IngredientKind.values()) {
            int amount = kind.maxAmount();
            EditorIngredient emitted = ingredient(kind, amount);
            Map<String, String> fields = SlotPatchFields.replace(SLOT, emitted);
            assertEquals(Integer.toString(amount), fields.get(SLOT + "." + kind.amountField()));
            assertTrue(kind.acceptsAmount(amount));
            assertEquals(emitted, SlotPatchFields.patched(fields, SLOT, null),
                    kind + " shape must read back as the ingredient that produced it");

            // One past the documented ceiling is outside the range, which is what the
            // server's amount check consults before it writes the value.
            int tooLarge = kind.maxAmount() + 1;
            assertFalse(kind.acceptsAmount(tooLarge));
            Map<String, String> overflow = Collections.singletonMap(
                    SLOT + "." + kind.amountField(), Integer.toString(tooLarge));
            assertNull(SlotPatchFields.patched(overflow, SLOT, null));
        }
    }
}
