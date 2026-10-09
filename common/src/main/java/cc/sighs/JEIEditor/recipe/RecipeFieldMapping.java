package cc.sighs.JEIEditor.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure mapping between editor slot keys and the recipe JSON fields a mod recipe
 * type declares. A declaration is the only place that knows how a mod names its
 * slots, so both the client model and the server writer go through this mapping
 * instead of guessing from the JSON layout.
 *
 * <p>A declaration is a list of field patterns in JEI display order. A pattern
 * without {@code %d} names exactly one slot. A pattern containing {@code %d} is
 * repeatable and names every remaining slot, with {@code %d} replaced by the
 * ordinal relative to that pattern: {@code ["input", "additives.%d"]} names
 * ordinal 0 as {@code input}, ordinal 1 as {@code additives.0} and ordinal 2 as
 * {@code additives.1}, so one declaration covers every arity the page can show.
 * A repeatable pattern consumes every remaining ordinal, so it has to be the
 * last entry - an entry after it could never be addressed and makes the whole
 * declaration invalid.
 *
 * <p>Inputs and outputs differ in one point. An input list must describe the
 * page exactly: a page with fewer input slots would leave a declared field
 * unaddressed, and one with more would have a slot no field names. An output
 * list may end in a slot that only some recipes carry - Horse Powered's
 * {@code secondary} exists on 6 of the 28 grinding recipes, and
 * {@code immersiveengineering:crusher} only draws its extra OUTPUT slots when a
 * recipe has {@code secondaries}. A page may therefore expose fewer outputs than
 * the declaration lists, but never more than the declaration can name.
 *
 * <p>A positional output list cannot describe a page whose ordinal-to-field
 * mapping depends on the recipe: Immersive Engineering's sawmill draws
 * {@code OUTPUT(stripped)} only when the recipe carries a {@code stripped}, so
 * its first OUTPUT slot is {@code stripped} on 21 of the 72 shipped recipes and
 * {@code result} on the other 51. Such a page is declared as an <em>ordered
 * segment list</em> instead (the {@code concatenatedOutputs} flag of
 * {@link cc.sighs.JEIEditor.platform.recipe.ModdedRecipeAdapter}): the segments
 * are concatenated - exactly the order the JEI category adds its slots in - to
 * form the effective output sequence, and the k-th OUTPUT slot is the k-th
 * element of that concatenation. A segment is either a plain path
 * ({@code result}, {@code slag}) or a repeating array pattern
 * ({@code results.%d}, {@code secondaries.%d.output}); a plain path contributes
 * one slot when the recipe carries it and nothing when it does not, a repeating
 * pattern contributes one slot per array element (none for a missing or empty
 * array). Because the concatenation depends on the recipe JSON, only the server
 * can resolve it ({@link #expandSegments}) - the client has no JSON and only
 * checks the slot count ({@link #segmentCountFits}).
 *
 * <p>Editor slot keys are {@code input.0 .. input.N-1} for the inputs, followed
 * by {@code output} for a single-output page and {@code output.0 .. output.M-1}
 * for a page with M &gt; 1 outputs.
 */
public final class RecipeFieldMapping {
    /** Marker of a repeatable field pattern. */
    public static final String REPEAT = "%d";
    private static final String INPUT_PREFIX = "input.";
    private static final String OUTPUT_PREFIX = "output.";
    private static final String OUTPUT_SLOT = "output";

    private RecipeFieldMapping() {
    }

    /**
     * The editor slot keys a declared page exposes for one page shape, in the
     * order the editor numbers them, or empty when the page does not match the
     * declaration.
     *
     * <p>The counts are those of the real slots on the page, never the number of
     * candidate stacks JEI flattens into them.
     */
    public static Optional<List<String>> declaredSlotKeys(int inputCount, int outputCount,
                                                          List<String> inputFields, List<String> outputFields) {
        return declaredSlotKeys(inputCount, outputCount, inputFields, outputFields, false);
    }

    /**
     * The same keys, for a declaration whose output list is a positional list
     * ({@code concatenatedOutputs} false) or an ordered segment list concatenated
     * against the recipe JSON (true, see {@link #expandSegments}).
     *
     * <p>A segment-list declaration cannot be resolved here - the client has the
     * slot views but not the recipe JSON - so its output count is only checked
     * against the bounds the declaration itself implies
     * ({@link #segmentCountFits}); whether the k-th element really exists is
     * decided by the server while it writes the recipe, which refuses the whole
     * patch rather than writing a guessed path.
     */
    public static Optional<List<String>> declaredSlotKeys(int inputCount, int outputCount,
                                                          List<String> inputFields, List<String> outputFields,
                                                          boolean concatenatedOutputs) {
        if (inputFields == null || outputFields == null || outputFields.isEmpty()
                || inputCount < 0 || outputCount < 0) {
            return Optional.empty();
        }
        Optional<List<String>> inputs = expand(inputFields, inputCount, false);
        if (!inputs.isPresent()) {
            return Optional.empty();
        }
        if (concatenatedOutputs) {
            if (!segmentCountFits(outputFields, outputCount)) {
                return Optional.empty();
            }
        } else if (!expand(outputFields, outputCount, true).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(slotKeys(inputCount, outputCount));
    }

    /**
     * The editor slot keys of a page with {@code inputCount} inputs and
     * {@code outputCount} outputs, in the order the editor numbers them:
     * {@code input.0 .. input.N-1} first, then {@code output} for a single output
     * and {@code output.0 .. output.M-1} for several.
     *
     * <p>It names the keys of a page shape without judging it. The field-backed
     * declaration checks a shape against its declaration before calling this
     * ({@link #declaredSlotKeys}); a recipe-derived slot sequence cannot check the
     * shape at all on the client, so it applies its own count bound and then builds
     * the keys here (see {@link cc.sighs.JEIEditor.platform.recipe.DerivedSlotSequence}).
     */
    public static List<String> slotKeys(int inputCount, int outputCount) {
        List<String> keys = new ArrayList<String>(inputCount + outputCount);
        for (int ordinal = 0; ordinal < inputCount; ordinal++) {
            keys.add(INPUT_PREFIX + ordinal);
        }
        if (outputCount == 1) {
            keys.add(OUTPUT_SLOT);
        } else {
            for (int ordinal = 0; ordinal < outputCount; ordinal++) {
                keys.add(OUTPUT_PREFIX + ordinal);
            }
        }
        return keys;
    }

    /** Declared field for a slot key, or null when the declaration does not cover it. */
    public static String field(List<String> inputFields, List<String> outputFields, String slotKey) {
        int output = outputIndex(slotKey);
        if (output >= 0) {
            return patternAt(outputFields, output);
        }
        int ordinal = inputOrdinal(slotKey);
        return ordinal < 0 ? null : patternAt(inputFields, ordinal);
    }

    // --- ordered segment lists (recipe-JSON driven concatenation) --------------

    /**
     * The two facts a segment has to ask of the recipe content it addresses:
     * whether a plain path is carried, and how many elements the array a
     * repeating segment iterates holds. The server implements this over the
     * recipe JSON; the unit tests implement it over a fixed map.
     */
    public interface SegmentSource {
        /** Whether {@code path} is carried by the recipe (and is not empty). */
        boolean hasPath(String path);

        /**
         * The element count of the array at {@code path}, 0 when the recipe does
         * not carry it at all (a missing optional array, exactly like an empty
         * one), or -1 when it is present but not an array.
         */
        int arrayLength(String path);
    }

    /**
     * The concrete path list of a segment declaration, in the order the JEI
     * category concatenates its slots - or empty when the declaration itself is
     * malformed or names an array that is not an array.
     *
     * <p>A plain path contributes one path when the recipe carries it, nothing
     * when it does not. A repeating segment ({@code results.%d},
     * {@code secondaries.%d.output}) contributes one path per element of the
     * array before its {@code %d} component, in order. The result is the whole
     * effective output sequence, so the k-th OUTPUT slot is element k.
     */
    public static Optional<List<String>> expandSegments(List<String> segments, SegmentSource source) {
        if (segments == null || segments.isEmpty() || source == null) {
            return Optional.empty();
        }
        List<String> paths = new ArrayList<String>();
        for (String segment : segments) {
            if (!isSegment(segment)) {
                return Optional.empty();
            }
            int repeatAt = repeatComponent(segment);
            if (repeatAt < 0) {
                if (source.hasPath(segment)) {
                    paths.add(segment);
                }
                continue;
            }
            String[] parts = segment.split("\\.", -1);
            String prefix = join(parts, 0, repeatAt);
            String suffix = join(parts, repeatAt + 1, parts.length);
            int length = source.arrayLength(prefix);
            if (length < 0) {
                return Optional.empty();
            }
            for (int index = 0; index < length; index++) {
                String path = prefix.isEmpty() ? Integer.toString(index) : prefix + "." + index;
                paths.add(suffix.isEmpty() ? path : path + "." + suffix);
            }
        }
        return Optional.of(paths);
    }

    /**
     * The path of output {@code ordinal} under a segment declaration, or null
     * when the concatenation does not reach that far. A null refuses the patch;
     * no guessed or partial path is ever returned.
     */
    public static String segmentPath(List<String> segments, int ordinal, SegmentSource source) {
        if (ordinal < 0) {
            return null;
        }
        Optional<List<String>> paths = expandSegments(segments, source);
        if (!paths.isPresent() || ordinal >= paths.get().size()) {
            return null;
        }
        return paths.get().get(ordinal);
    }

    /**
     * Whether a segment declaration can name a page with {@code count} output
     * slots, from the declaration alone. The client has no recipe JSON, so this
     * is a count bound: a page must draw at least one output slot, a repeating
     * segment leaves the count unbounded, and a list of singles bounds it at
     * their number (the surplus ones stay unused, as for a positional output
     * list). A malformed declaration names nothing.
     */
    public static boolean segmentCountFits(List<String> segments, int count) {
        if (segments == null || segments.isEmpty() || count < 1) {
            return false;
        }
        boolean repeat = false;
        for (String segment : segments) {
            if (!isSegment(segment)) {
                return false;
            }
            if (isRepeatSegment(segment)) {
                repeat = true;
            }
        }
        return repeat || count <= segments.size();
    }

    /**
     * True when {@code segment} names every element of an array: it carries
     * {@link #REPEAT} as one whole path component ({@code results.%d},
     * {@code secondaries.%d.output}).
     */
    public static boolean isRepeatSegment(String segment) {
        return repeatComponent(segment) >= 0;
    }

    /**
     * Index of the {@code %d} path component of a segment, or -1. A {@code %d}
     * that is not a whole component (a path such as {@code a%db}) names no slot
     * and is refused.
     */
    private static int repeatComponent(String segment) {
        if (segment == null || segment.isEmpty()) {
            return -1;
        }
        String[] parts = segment.split("\\.", -1);
        for (int index = 0; index < parts.length; index++) {
            if (REPEAT.equals(parts[index])) {
                return index;
            }
        }
        return -1;
    }

    /** A segment that names something: no marker, or a whole-component one. */
    private static boolean isSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        return segment.indexOf('%') < 0 || isRepeatSegment(segment);
    }

    /** The dotted path of the components in {@code [from, to)}, empty when none. */
    private static String join(String[] parts, int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int index = from; index < to; index++) {
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(parts[index]);
        }
        return builder.toString();
    }

    /** {@code input.<ordinal>} in JEI display order, or -1 for any other slot key. */
    public static int inputOrdinal(String slotKey) {
        if (slotKey == null || !slotKey.startsWith(INPUT_PREFIX)) {
            return -1;
        }
        try {
            int ordinal = Integer.parseInt(slotKey.substring(INPUT_PREFIX.length()));
            return ordinal < 0 ? -1 : ordinal;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * Output ordinal of {@code output} (the only output, so ordinal 0) or
     * {@code output.<n>}, or -1 for any other slot key.
     */
    public static int outputIndex(String slotKey) {
        if (OUTPUT_SLOT.equals(slotKey)) {
            return 0;
        }
        if (slotKey == null || !slotKey.startsWith(OUTPUT_PREFIX)) {
            return -1;
        }
        try {
            int ordinal = Integer.parseInt(slotKey.substring(OUTPUT_PREFIX.length()));
            return ordinal < 0 ? -1 : ordinal;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /** The pattern of one slot, or null when the declaration does not name it. */
    private static String patternAt(List<String> fields, int ordinal) {
        Optional<Integer> repeatAt = repeatIndex(fields);
        if (!repeatAt.isPresent() || ordinal < 0) {
            return null;
        }
        int first = repeatAt.get().intValue();
        if (first >= 0 && ordinal >= first) {
            String declared = fields.get(first).replace(REPEAT, Integer.toString(ordinal - first));
            return declared.isEmpty() ? null : declared;
        }
        if (ordinal >= fields.size()) {
            return null;
        }
        String declared = fields.get(ordinal);
        return declared == null || declared.isEmpty() ? null : declared;
    }

    /**
     * Index of the first repeatable pattern, -1 when the declaration is a fixed
     * list, or empty when the declaration itself is invalid: an empty pattern
     * names no slot, and a pattern after the repeatable one could never be
     * addressed because the repeatable one covers every remaining ordinal.
     */
    private static Optional<Integer> repeatIndex(List<String> fields) {
        if (fields == null) {
            return Optional.empty();
        }
        int repeatAt = -1;
        for (int index = 0; index < fields.size(); index++) {
            String field = fields.get(index);
            if (field == null || field.isEmpty()) {
                return Optional.empty();
            }
            if (repeatAt < 0 && field.contains(REPEAT)) {
                repeatAt = index;
            }
        }
        if (repeatAt >= 0 && repeatAt != fields.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(Integer.valueOf(repeatAt));
    }

    /**
     * The field of every one of {@code count} slots, or empty when the
     * declaration cannot name them all.
     *
     * <p>{@code allowUnusedTrailing} permits a page with fewer slots than the
     * declaration lists; the surplus patterns stay unused. It is set for outputs
     * (whose extra slots are optional per recipe) and never for inputs.
     */
    private static Optional<List<String>> expand(List<String> fields, int count, boolean allowUnusedTrailing) {
        if (count < 0) {
            return Optional.empty();
        }
        Optional<Integer> repeat = repeatIndex(fields);
        if (!repeat.isPresent()) {
            return Optional.empty();
        }
        int repeatAt = repeat.get().intValue();
        if (repeatAt < 0) {
            if (count == fields.size()) {
                return Optional.of(fields);
            }
            return allowUnusedTrailing && count >= 1 && count < fields.size()
                    ? Optional.of(new ArrayList<String>(fields.subList(0, count)))
                    : Optional.empty();
        }
        int minimum = allowUnusedTrailing ? Math.max(1, repeatAt) : repeatAt;
        if (count < minimum) {
            return Optional.empty();
        }
        List<String> expanded = new ArrayList<String>(count);
        for (int ordinal = 0; ordinal < repeatAt; ordinal++) {
            expanded.add(fields.get(ordinal));
        }
        String repeatField = fields.get(repeatAt);
        for (int ordinal = repeatAt; ordinal < count; ordinal++) {
            expanded.add(repeatField.replace(REPEAT, Integer.toString(ordinal - repeatAt)));
        }
        return Optional.of(expanded);
    }
}
