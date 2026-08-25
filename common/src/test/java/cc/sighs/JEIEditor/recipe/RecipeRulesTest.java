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
}
