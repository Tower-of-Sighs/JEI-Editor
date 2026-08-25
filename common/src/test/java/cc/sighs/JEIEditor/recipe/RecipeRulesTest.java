package cc.sighs.JEIEditor.recipe;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.EditorSlot;
import cc.sighs.JEIEditor.editor.RecipePatch;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRulesTest {
    @Test
    void creationRulesBuildACompleteBlankCraftingDraft() {
        EditorModel source = new EditorModel("minecraft:source", "minecraft:crafting_shaped", "fingerprint",
                Arrays.asList(new EditorSlot("input.0", "input", null),
                        new EditorSlot("output", "output", null)));

        EditorModel draft = RecipeCreationRules.createModel(source).orElse(null);

        assertNotNull(draft);
        assertEquals(10, draft.slots().size());
        assertEquals("minecraft:crafting_shaped", draft.serializerId());
        assertTrue(RecipeCreationRules.isCreatedModel(new EditorModel(draft.recipeId(), draft.serializerId(),
                draft.baseFingerprint(), draft.slots(), withCreatedFlag(draft.properties()))));
    }

    @Test
    void creationPatchClearsInputsAndKeepsOutputExplicitlyEmpty() {
        EditorModel source = new EditorModel("minecraft:source", "minecraft:smelting", "fingerprint",
                Arrays.asList(new EditorSlot("input.0", "input", null),
                        new EditorSlot("output", "output", null)),
                Collections.singletonMap("cooking_time", "200"));

        RecipePatch patch = RecipeCreationRules.createPatch(source);

        assertEquals("minecraft:air", patch.fields().get("input.0.item"));
        assertEquals("0", patch.fields().get("input.0.count"));
        assertEquals("minecraft:air", patch.fields().get("output.item"));
        assertEquals("200", patch.fields().get("recipe.cooking_time"));
    }

    @Test
    void creationRulesUseTheVanillaSlotShapeForStonecuttingAndSmithing() {
        EditorModel stone = new EditorModel("minecraft:source", "minecraft:stonecutting", "fingerprint",
                Collections.singletonList(new EditorSlot("input.0", "input", null)));
        EditorModel smithing = new EditorModel("minecraft:source", "minecraft:smithing_trim", "fingerprint",
                Collections.singletonList(new EditorSlot("input.0", "input", null)));

        assertEquals(2, RecipeCreationRules.createModel(withOutput(stone)).get().slots().size());
        assertEquals(4, RecipeCreationRules.createModel(withOutput(smithing)).get().slots().size());
    }

    @Test
    void creationRulesUseTwoInputsForJeiAnvilDrafts() {
        EditorModel anvil = new EditorModel("jei:source", "jei:anvil", "fingerprint",
                Arrays.asList(new EditorSlot("input.0", "input", null),
                        new EditorSlot("input.1", "input", null),
                        new EditorSlot("output", "output", null)));

        EditorModel draft = RecipeCreationRules.createModel(anvil).orElse(null);

        assertNotNull(draft);
        assertEquals(3, draft.slots().size());
        assertEquals("input.0", draft.slots().get(0).key());
        assertEquals("input.1", draft.slots().get(1).key());
        assertEquals("output", draft.slots().get(2).key());
    }

    @Test
    void policyRulesNormalizeNamespacesAndClampPermission() {
        Properties properties = new Properties();
        properties.setProperty("min_permission_level", "99");
        properties.setProperty("allow_namespaces", "minecraft, JEIEDITOR");
        properties.setProperty("deny_namespaces", "minecraft:invalid, jeieditor.blocked");

        RecipePolicyRules rules = RecipePolicyRules.fromProperties(properties);

        assertEquals(4, rules.minPermissionLevel());
        assertTrue(rules.isAllowed("minecraft:stone"));
        assertTrue(rules.isAllowed("jeieditor:test"));
        assertFalse(rules.isAllowed("example:test"));
        assertFalse(rules.isAllowed("minecraft"));
        assertFalse(rules.isAllowed("minecraft:"));
    }

    @Test
    void operationCoordinatorRejectsOverlappingOperations() {
        EditOperationCoordinator<Object> coordinator = new EditOperationCoordinator<Object>(0L);
        Object key = new Object();

        assertTrue(coordinator.tryBegin(key));
        assertFalse(coordinator.tryBegin(key));
        coordinator.finish(key);
        assertTrue(coordinator.tryBegin(key));
    }

    private static java.util.Map<String, String> withCreatedFlag(java.util.Map<String, String> source) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<String, String>(source);
        copy.put("recipe.new", "true");
        return copy;
    }

    private static EditorModel withOutput(EditorModel model) {
        java.util.List<EditorSlot> slots = new java.util.ArrayList<EditorSlot>(model.slots());
        slots.add(new EditorSlot("output", "output", null));
        return new EditorModel(model.recipeId(), model.serializerId(), model.baseFingerprint(), slots);
    }
}
