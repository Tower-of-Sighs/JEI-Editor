package cc.sighs.JEIEditor.recipe;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeFieldMappingTest {
    private static final List<String> COMBINING_INPUTS = Arrays.asList("main_input", "extra_input");
    /** The single output field every one-output declaration names. */
    private static final List<String> SINGLE_OUTPUT = Collections.singletonList("output");
    private static final List<String> NO_OUTPUT = Collections.emptyList();

    private static String field(List<String> inputs, List<String> outputs, String slotKey) {
        return RecipeFieldMapping.field(inputs, outputs, slotKey);
    }

    private static List<String> keys(int inputCount, int outputCount,
                                     List<String> inputFields, List<String> outputFields) {
        return RecipeFieldMapping.declaredSlotKeys(inputCount, outputCount, inputFields, outputFields)
                .orElse(null);
    }

    @Test
    void declaredFieldsFollowTheJeiInputOrder() {
        assertEquals("main_input", field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.0"));
        assertEquals("extra_input", field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.1"));
        assertEquals("output", field(COMBINING_INPUTS, SINGLE_OUTPUT, "output"));
    }

    @Test
    void slotsOutsideTheDeclarationAreRejected() {
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.2"));
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.-1"));
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.x"));
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, "input"));
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, "slot.0"));
        assertNull(field(COMBINING_INPUTS, null, "output"));
        assertNull(field(COMBINING_INPUTS, NO_OUTPUT, "output"));
        assertNull(field(COMBINING_INPUTS, Arrays.asList(""), "output"));
        assertNull(field(null, SINGLE_OUTPUT, "input.0"));
        assertNull(field(Collections.singletonList(""), SINGLE_OUTPUT, "input.0"));
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, null));
    }

    @Test
    void ordinalsOnlyParseTheInputPrefix() {
        assertEquals(0, RecipeFieldMapping.inputOrdinal("input.0"));
        assertEquals(12, RecipeFieldMapping.inputOrdinal("input.12"));
        assertEquals(-1, RecipeFieldMapping.inputOrdinal("output"));
        assertEquals(-1, RecipeFieldMapping.inputOrdinal("input."));
        assertEquals(-1, RecipeFieldMapping.inputOrdinal("input.0.item"));
        assertEquals(-1, RecipeFieldMapping.inputOrdinal(null));
    }

    @Test
    void outputIndexAcceptsTheSingleOutputKeyAndNumberedOutputs() {
        // "output" is the only output of a single-output page, so ordinal 0.
        assertEquals(0, RecipeFieldMapping.outputIndex("output"));
        assertEquals(0, RecipeFieldMapping.outputIndex("output.0"));
        assertEquals(3, RecipeFieldMapping.outputIndex("output.3"));
        assertEquals(-1, RecipeFieldMapping.outputIndex("output."));
        assertEquals(-1, RecipeFieldMapping.outputIndex("output.-1"));
        assertEquals(-1, RecipeFieldMapping.outputIndex("output.0.item"));
        assertEquals(-1, RecipeFieldMapping.outputIndex("input.0"));
        assertEquals(-1, RecipeFieldMapping.outputIndex(null));
    }

    @Test
    void declaredSlotKeysFollowThePageSlotCounts() {
        assertEquals(Arrays.asList("input.0", "input.1", "output"),
                keys(2, 1, COMBINING_INPUTS, SINGLE_OUTPUT));
        assertEquals(Collections.singletonList("output"),
                keys(0, 1, Collections.<String>emptyList(), SINGLE_OUTPUT));
        // Two outputs keep the single-output key free: output.0 .. output.M-1.
        assertEquals(Arrays.asList("input.0", "output.0", "output.1"),
                keys(1, 2, Collections.singletonList("ingredients.0"),
                        Arrays.asList("results.%d")));
    }

    @Test
    void declaredSlotKeysRejectPagesThatDoNotMatchTheDeclaration() {
        // The immersiveengineering:alloy page has two input slots; the dump lists
        // 26 candidate stacks because one slot holds a 24-member tag. Only the
        // slot count is compared, never the flattened candidate count.
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                26, 1, COMBINING_INPUTS, SINGLE_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                1, 1, COMBINING_INPUTS, SINGLE_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, 0, COMBINING_INPUTS, SINGLE_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, 2, COMBINING_INPUTS, SINGLE_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, 1, COMBINING_INPUTS, null).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, 1, COMBINING_INPUTS, NO_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, 1, null, SINGLE_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, -1, COMBINING_INPUTS, SINGLE_OUTPUT).isPresent());
    }

    // --- change 1: repeating (variable arity) field patterns -----------------

    @Test
    void fixedListKeepsItsExactCountBehaviour() {
        // Unchanged: a fixed declaration names exactly the page's slot count.
        assertEquals("main_input", field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.0"));
        assertNull(field(COMBINING_INPUTS, SINGLE_OUTPUT, "input.2"));
        assertEquals(Arrays.asList("input.0", "input.1", "output"),
                keys(2, 1, COMBINING_INPUTS, SINGLE_OUTPUT));
        assertNull(keys(3, 1, COMBINING_INPUTS, SINGLE_OUTPUT));
        assertNull(keys(1, 1, COMBINING_INPUTS, SINGLE_OUTPUT));
    }

    @Test
    void prefixedRepeatEntryCoversEveryAdditionalInput() {
        // immersiveengineering:arc_furnace: the fixed "input" field plus one
        // "additives.<n>" per extra JEI INPUT slot.
        List<String> inputs = Arrays.asList("input", "additives.%d");
        assertEquals(Collections.singletonList("input.0"), firstInputs(keys(1, 1, inputs, SINGLE_OUTPUT)));
        assertEquals(Arrays.asList("input.0", "input.1"), firstInputs(keys(2, 1, inputs, SINGLE_OUTPUT)));
        assertEquals(Arrays.asList("input.0", "input.1", "input.2"), firstInputs(keys(3, 1, inputs, SINGLE_OUTPUT)));
        assertEquals(Arrays.asList("input.0", "input.1", "input.2", "input.3"),
                firstInputs(keys(4, 1, inputs, SINGLE_OUTPUT)));
        assertEquals("input", field(inputs, SINGLE_OUTPUT, "input.0"));
        assertEquals("additives.0", field(inputs, SINGLE_OUTPUT, "input.1"));
        assertEquals("additives.1", field(inputs, SINGLE_OUTPUT, "input.2"));
        assertEquals("additives.2", field(inputs, SINGLE_OUTPUT, "input.3"));
        // The repeat entry consumes every remaining ordinal, so the fields run
        // out exactly where the page's slots do.
        assertEquals("additives.9", field(inputs, SINGLE_OUTPUT, "input.10"));
    }

    @Test
    void leadingRepeatEntryCoversEveryInput() {
        // immersiveengineering:blueprint: one "inputs.<n>" per JEI INPUT slot.
        List<String> inputs = Collections.singletonList("inputs.%d");
        for (int count = 1; count <= 6; count++) {
            List<String> expected = new java.util.ArrayList<String>(count);
            for (int index = 0; index < count; index++) {
                expected.add("input." + index);
            }
            assertEquals(expected, firstInputs(keys(count, 1, inputs, SINGLE_OUTPUT)),
                    "input slot count " + count);
        }
        assertEquals("inputs.0", field(inputs, SINGLE_OUTPUT, "input.0"));
        assertEquals("inputs.5", field(inputs, SINGLE_OUTPUT, "input.5"));
        assertEquals("inputs.41", field(inputs, SINGLE_OUTPUT, "input.41"));
        // A page with no INPUT slot at all is still expressible: the repeat entry
        // simply covers no ordinal.
        assertEquals(Collections.singletonList("output"), keys(0, 1, inputs, SINGLE_OUTPUT));
    }

    @Test
    void repeatEntryIsTheOnlyEntryOfItsList() {
        List<String> inputs = Collections.singletonList("%d");
        assertEquals(Arrays.asList("input.0", "input.1", "input.2"),
                firstInputs(keys(3, 1, inputs, SINGLE_OUTPUT)));
        assertEquals("0", field(inputs, SINGLE_OUTPUT, "input.0"));
        assertEquals("2", field(inputs, SINGLE_OUTPUT, "input.2"));
    }

    @Test
    void repeatEntryMayFollowAFixedPrefixButNeverAPrecedesAFixedEntry() {
        // A %d entry that is not the first one: the prefix keeps its own ordinals.
        List<String> inputs = Arrays.asList("main_input", "extra.%d");
        assertEquals("main_input", field(inputs, SINGLE_OUTPUT, "input.0"));
        assertEquals("extra.0", field(inputs, SINGLE_OUTPUT, "input.1"));
        assertEquals("extra.1", field(inputs, SINGLE_OUTPUT, "input.2"));
        // A fixed entry after the %d entry can never be addressed, so the
        // declaration is refused instead of silently ignoring it.
        List<String> unreachable = Arrays.asList("main_input", "extra.%d", "tail");
        assertNull(keys(3, 1, unreachable, SINGLE_OUTPUT));
        assertNull(field(unreachable, SINGLE_OUTPUT, "input.0"));
    }

    @Test
    void repeatEntriesAreRejectedWhenTheDeclarationCannotNameTheSlot() {
        List<String> inputs = Arrays.asList("input", "additives.%d");
        // Fewer slots than the fixed prefix: the page cannot be this declaration.
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                0, 1, inputs, SINGLE_OUTPUT).isPresent());
        // A repeatable entry names every later ordinal, so none of them is
        // refused - that is what makes the variable arity work.
        assertNotNull(field(inputs, SINGLE_OUTPUT, "input.4"));
        // A fixed list larger than the page is refused: the page cannot expose
        // the surplus fields.
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                2, 1, Arrays.asList("a", "b", "c"), SINGLE_OUTPUT).isPresent());
        assertNull(field(Arrays.asList("a", "b"), SINGLE_OUTPUT, "input.2"));
        // An empty or null pattern names nothing.
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                1, 1, Arrays.asList("a", null), SINGLE_OUTPUT).isPresent());
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                1, 1, Arrays.asList("a", ""), SINGLE_OUTPUT).isPresent());
        assertNull(field(Arrays.asList("a", null), SINGLE_OUTPUT, "input.0"));
    }

    // --- change 2: multiple output slots ------------------------------------

    @Test
    void singleOutputDeclarationKeepsTheOutputKey() {
        assertEquals("output", field(Collections.singletonList("input"), SINGLE_OUTPUT, "output"));
        // "output.0" names the same slot, so a patch written with the numbered
        // key still reaches the single output field.
        assertEquals("output", field(Collections.singletonList("input"), SINGLE_OUTPUT, "output.0"));
        assertNull(field(Collections.singletonList("input"), SINGLE_OUTPUT, "output.1"));
    }

    @Test
    void repeatOutputEntryNamesEveryOutput() {
        // create:splashing / create:haunting: results[0], results[1], ...
        List<String> outputs = Collections.singletonList("results.%d");
        assertEquals(Collections.singletonList("output"),
                tailOutputs(keys(1, 1, Collections.singletonList("ingredients.0"), outputs)));
        assertEquals(Arrays.asList("output.0", "output.1"),
                tailOutputs(keys(1, 2, Collections.singletonList("ingredients.0"), outputs)));
        assertEquals(Arrays.asList("output.0", "output.1", "output.2"),
                tailOutputs(keys(1, 3, Collections.singletonList("ingredients.0"), outputs)));
        assertEquals("results.0", field(Collections.singletonList("ingredients.0"), outputs, "output"));
        assertEquals("results.0", field(Collections.singletonList("ingredients.0"), outputs, "output.0"));
        assertEquals("results.1", field(Collections.singletonList("ingredients.0"), outputs, "output.1"));
    }

    @Test
    void outputListMayEndInAnOptionalExtraSlot() {
        // horsepowered:grinding: "secondary" only exists on some recipes, so the
        // declaration must cover a page with one output and a page with two.
        List<String> outputs = Arrays.asList("result", "secondary");
        assertEquals(Collections.singletonList("output"),
                tailOutputs(keys(1, 1, Collections.singletonList("ingredient"), outputs)));
        assertEquals(Arrays.asList("output.0", "output.1"),
                tailOutputs(keys(1, 2, Collections.singletonList("ingredient"), outputs)));
        assertEquals("result", field(Collections.singletonList("ingredient"), outputs, "output"));
        assertEquals("secondary", field(Collections.singletonList("ingredient"), outputs, "output.1"));
        // A third output cannot be named, so the whole page is refused rather
        // than patched with a partial output list.
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                1, 3, Collections.singletonList("ingredient"), outputs).isPresent());
        assertNull(field(Collections.singletonList("ingredient"), outputs, "output.2"));
    }

    @Test
    void fixedOutputListStillRequiresItsExactCountForInputs() {
        // The optional-trailing rule is for outputs only: an input list that
        // names more fields than the page has is still refused.
        assertFalse(RecipeFieldMapping.declaredSlotKeys(
                1, 1, Arrays.asList("input", "additives.0"),
                Collections.singletonList("results.0")).isPresent());
    }

    @Test
    void prefixedRepeatOutputEntryCoversEverySecondaryOutput() {
        // immersiveengineering:crusher: result, secondaries[0].output, ...
        List<String> outputs = Arrays.asList("result", "secondaries.%d.output");
        assertEquals(Collections.singletonList("output"),
                tailOutputs(keys(1, 1, Collections.singletonList("input"), outputs)));
        assertEquals(Arrays.asList("output.0", "output.1"),
                tailOutputs(keys(1, 2, Collections.singletonList("input"), outputs)));
        assertEquals(Arrays.asList("output.0", "output.1", "output.2"),
                tailOutputs(keys(1, 3, Collections.singletonList("input"), outputs)));
        assertEquals("result", field(Collections.singletonList("input"), outputs, "output.0"));
        assertEquals("secondaries.0.output", field(Collections.singletonList("input"), outputs, "output.1"));
        assertEquals("secondaries.1.output", field(Collections.singletonList("input"), outputs, "output.2"));
    }

    // --- change 3: ordered segment lists (recipe-JSON concatenation) ---------

    /**
     * A fixed recipe content for the expansion: an entry whose value is a
     * {@link List} is an array of that length, any other non-null value is a
     * plain path the recipe carries, and a missing key is a path or array it
     * does not.
     */
    private static RecipeFieldMapping.SegmentSource recipe(Object... entries) {
        final Map<String, Object> content = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            content.put((String) entries[index], entries[index + 1]);
        }
        return new RecipeFieldMapping.SegmentSource() {
            @Override
            public boolean hasPath(String path) {
                Object value = content.get(path);
                if (value == null) {
                    return false;
                }
                return !(value instanceof List) || !((List<?>) value).isEmpty();
            }

            @Override
            public int arrayLength(String path) {
                Object value = content.get(path);
                if (value == null) {
                    return 0;
                }
                return value instanceof List ? ((List<?>) value).size() : -1;
            }
        };
    }

    @Test
    void segmentListConcatenatesInJeiSlotOrder() {
        // immersiveengineering:arc_furnace: results[*], then secondaries[*].output,
        // then slag - the order ArcFurnaceRecipeCategory adds its OUTPUT slots in.
        List<String> segments = Arrays.asList("results.%d", "secondaries.%d.output", "slag");
        RecipeFieldMapping.SegmentSource source = recipe(
                "results", Arrays.asList("r0", "r1"),
                "secondaries", Arrays.asList("s0"),
                "slag", "present");
        assertEquals(Arrays.asList("results.0", "results.1", "secondaries.0.output", "slag"),
                RecipeFieldMapping.expandSegments(segments, source).orElse(null));
        assertEquals("results.0", RecipeFieldMapping.segmentPath(segments, 0, source));
        assertEquals("results.1", RecipeFieldMapping.segmentPath(segments, 1, source));
        assertEquals("secondaries.0.output", RecipeFieldMapping.segmentPath(segments, 2, source));
        assertEquals("slag", RecipeFieldMapping.segmentPath(segments, 3, source));
    }

    @Test
    void anAbsentPlainSegmentShiftsTheFollowingOrdinals() {
        // immersiveengineering:sawmill: the same declaration names a different
        // field at the same ordinal once "stripped" is carried or not.
        List<String> segments = Arrays.asList("stripped", "result",
                "strippingSecondaries.%d", "secondaryOutputs.%d");
        RecipeFieldMapping.SegmentSource withStripped = recipe(
                "stripped", "present",
                "result", "present",
                "strippingSecondaries", Arrays.asList("ss0"),
                "secondaryOutputs", Arrays.asList("so0"));
        assertEquals(Arrays.asList("stripped", "result", "strippingSecondaries.0", "secondaryOutputs.0"),
                RecipeFieldMapping.expandSegments(segments, withStripped).orElse(null));
        assertEquals("stripped", RecipeFieldMapping.segmentPath(segments, 0, withStripped));
        assertEquals("result", RecipeFieldMapping.segmentPath(segments, 1, withStripped));
        RecipeFieldMapping.SegmentSource withoutStripped = recipe(
                "result", "present",
                "strippingSecondaries", Arrays.asList("ss0"),
                "secondaryOutputs", Collections.emptyList());
        assertEquals(Arrays.asList("result", "strippingSecondaries.0"),
                RecipeFieldMapping.expandSegments(segments, withoutStripped).orElse(null));
        assertEquals("result", RecipeFieldMapping.segmentPath(segments, 0, withoutStripped));
        assertEquals("strippingSecondaries.0", RecipeFieldMapping.segmentPath(segments, 1, withoutStripped));
    }

    @Test
    void anEmptyOrMissingArrayContributesNoSlot() {
        List<String> segments = Arrays.asList("results.%d", "secondaries.%d.output", "slag");
        // An empty array carries no element, exactly like the JEI category.
        assertEquals(Collections.singletonList("results.0"),
                RecipeFieldMapping.expandSegments(segments,
                        recipe("results", Arrays.asList("r0"), "secondaries", Collections.emptyList()))
                        .orElse(null));
        // A missing array (the key is not carried at all) behaves like an empty one.
        assertEquals(Collections.singletonList("results.0"),
                RecipeFieldMapping.expandSegments(segments, recipe("results", Arrays.asList("r0")))
                        .orElse(null));
    }

    @Test
    void aMissingTrailingSegmentRefusesTheOrdinalThatWouldNameIt() {
        List<String> segments = Arrays.asList("results.%d", "secondaries.%d.output", "slag");
        RecipeFieldMapping.SegmentSource source = recipe("results", Arrays.asList("r0", "r1"));
        assertEquals(Arrays.asList("results.0", "results.1"),
                RecipeFieldMapping.expandSegments(segments, source).orElse(null));
        // The concatenation stops at results[1]; ordinal 2 would be the missing slag,
        // so the patch that addressed it has to be refused rather than written.
        assertEquals("results.1", RecipeFieldMapping.segmentPath(segments, 1, source));
        assertNull(RecipeFieldMapping.segmentPath(segments, 2, source));
        assertNull(RecipeFieldMapping.segmentPath(segments, -1, source));
    }

    @Test
    void aSegmentListRefusesWhatItCannotResolve() {
        // A value where the repeating segment expects an array is an error, not an
        // empty list: the declaration cannot be trusted for this recipe.
        assertFalse(RecipeFieldMapping.expandSegments(Collections.singletonList("results.%d"),
                recipe("results", "not-an-array")).isPresent());
        assertNull(RecipeFieldMapping.segmentPath(Collections.singletonList("results.%d"), 0,
                recipe("results", "not-an-array")));
        // A %d that is not a whole path component names no array element.
        for (String malformed : Arrays.asList("a%db", "results.%x", "")) {
            assertFalse(RecipeFieldMapping.expandSegments(Collections.singletonList(malformed), recipe())
                    .isPresent(), malformed);
            assertFalse(RecipeFieldMapping.segmentCountFits(Collections.singletonList(malformed), 1), malformed);
        }
        assertNull(RecipeFieldMapping.segmentPath(Arrays.asList("results.%d", null), 0, recipe()));
        assertFalse(RecipeFieldMapping.expandSegments(null, recipe()).isPresent());
        assertFalse(RecipeFieldMapping.expandSegments(Collections.<String>emptyList(), recipe()).isPresent());
        assertFalse(RecipeFieldMapping.expandSegments(Collections.singletonList("results.%d"), null).isPresent());
    }

    @Test
    void segmentCountBoundsComeFromTheDeclarationAlone() {
        // A repeating segment leaves the output count unbounded; the page just has to
        // draw at least one slot.
        List<String> sawmill = Arrays.asList("stripped", "result",
                "strippingSecondaries.%d", "secondaryOutputs.%d");
        for (int count = 1; count <= 4; count++) {
            assertTrue(RecipeFieldMapping.segmentCountFits(sawmill, count), "sawmill output count " + count);
            assertTrue(RecipeFieldMapping.declaredSlotKeys(1, count,
                    Collections.singletonList("input"), sawmill, true).isPresent());
        }
        assertFalse(RecipeFieldMapping.segmentCountFits(sawmill, 0));
        // A list of singles bounds the count at their number, the surplus staying unused.
        List<String> singles = Arrays.asList("stripped", "result");
        assertTrue(RecipeFieldMapping.segmentCountFits(singles, 1));
        assertTrue(RecipeFieldMapping.segmentCountFits(singles, 2));
        assertFalse(RecipeFieldMapping.segmentCountFits(singles, 3));
        assertFalse(RecipeFieldMapping.segmentCountFits(singles, 0));
        assertFalse(RecipeFieldMapping.segmentCountFits(Arrays.asList("a", null), 1));
        // The client keys are still output.0 .. output.M-1 for several outputs.
        assertEquals(Arrays.asList("input.0", "output"), RecipeFieldMapping.declaredSlotKeys(
                1, 1, Collections.singletonList("input"), sawmill, true).orElse(null));
        assertEquals(Arrays.asList("input.0", "output.0", "output.1"), RecipeFieldMapping.declaredSlotKeys(
                1, 2, Collections.singletonList("input"), sawmill, true).orElse(null));
    }

    private static List<String> firstInputs(List<String> keys) {
        if (keys == null) {
            return null;
        }
        List<String> inputs = new java.util.ArrayList<String>(keys);
        inputs.remove("output");
        return inputs;
    }

    private static List<String> tailOutputs(List<String> keys) {
        if (keys == null) {
            return null;
        }
        List<String> outputs = new java.util.ArrayList<String>();
        for (String key : keys) {
            if (key.startsWith("output")) {
                outputs.add(key);
            }
        }
        return outputs;
    }
}
