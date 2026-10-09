package cc.sighs.JEIEditor.platform.recipe;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pages whose JEI category shows recipes the manager also holds, but under a
 * rewritten id.
 *
 * <p>Mekanism's electric smelter page ({@code mekanism:smelting}) is the case:
 * {@code MekanismRecipeType} builds its smelting list from the vanilla
 * {@code minecraft:smelting} recipes and renames each one through
 * {@code RecipeViewerUtils.synthetic(id, "mekanism_generated")}, which inserts
 * {@code /mekanism_generated/} in front of the path. JEI therefore draws a
 * {@code RecipeHolder} whose id resolves nowhere, while the recipe the game
 * really runs - and the one an edit has to write - is the original
 * {@code minecraft:smelting} recipe, whose JSON is an ordinary datapack file.
 *
 * <p>That is deliberately not a {@link ModdedRecipeAdapter}: a declaration names
 * the JSON fields of a mod's own serializer and its patches carry that
 * serializer, while a patch made on an aliased page carries the underlying
 * recipe's serializer - one the editor already implements. This table only says
 * how to find the recipe the page is showing; the edit itself then travels the
 * ordinary path for that recipe's own serializer.
 *
 * <p>Only the id is translated, and only when the real recipe exists in the
 * manager, so a page that is not declared here keeps resolving exactly as
 * before.
 */
public final class RecipeViewerAliases {
    /** Page uid -> the path prefix that page puts in front of the real path. */
    private static final Map<String, String> PATH_PREFIXES;

    static {
        Map<String, String> prefixes = new LinkedHashMap<String, String>();
        prefixes.put("mekanism:smelting", "/mekanism_generated/");
        PATH_PREFIXES = Collections.unmodifiableMap(prefixes);
    }

    private RecipeViewerAliases() {
    }

    /** The path prefix {@code pageUid} rewrites its recipe ids with, or null. */
    public static String pathPrefixOf(String pageUid) {
        return pageUid == null ? null : PATH_PREFIXES.get(pageUid);
    }

    /**
     * The id of the recipe {@code displayedId} is an alias of, or empty when the
     * page has no alias rule or the id does not carry the page's prefix.
     */
    public static Optional<ResourceLocation> underlyingRecipeId(String pageUid, ResourceLocation displayedId) {
        String prefix = pathPrefixOf(pageUid);
        if (prefix == null || displayedId == null || !displayedId.getPath().startsWith(prefix)) {
            return Optional.empty();
        }
        String path = displayedId.getPath().substring(prefix.length());
        if (path.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ResourceLocation.fromNamespaceAndPath(displayedId.getNamespace(), path));
    }
}
