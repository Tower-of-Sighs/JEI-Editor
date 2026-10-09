package cc.sighs.JEIEditor.client;

import cc.sighs.JEIEditor.JEIEditorNeoForge121;
import net.minecraft.client.Minecraft;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.common.Internal;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Development diagnostic template - not compiled into the mod.
 *
 * Copy this file into
 * targets/neoforge-1.21.1/src/main/java/cc/sighs/JEIEditor/client/ before a
 * diagnostic run and delete it again afterwards. While it is compiled in, the
 * client automatically creates a throwaway world on the title screen (JEI only
 * registers its categories after a world join) and writes
 * run/jei-category-dump.txt with one line per registered recipe category:
 *
 *   uid  visible/hidden  recipe count  category class  recipe class
 *   source jar  title  ingredient shape of a sample recipe  sample recipe id
 *
 * scripts/jei-category-dump/dump-categories.ps1 builds and runs that client and
 * stops it once the dump file appears.
 */
@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID, value = Dist.CLIENT)
public final class JeiCategoryDiagnostic {
    private static final String OUTPUT_FILE = "jei-category-dump.txt";
    private static final String INGREDIENT_FILE = "jei-ingredient-types.txt";
    private static final String HEARTBEAT_FILE = "jei-category-dump.heartbeat.txt";
    private static int ticks;
    private static boolean done;
    private static boolean joinAttempted;

    private JeiCategoryDiagnostic() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        ticks++;
        try {
            heartbeat();
            Optional<IJeiRuntime> runtime = Internal.getOptionalJeiRuntime();
            if (!runtime.isPresent()) {
                startDiagnosticWorld();
                return;
            }
            List<String> lines = dump(runtime.get());
            if (lines.isEmpty()) {
                return;
            }
            write(lines);
            writeIngredients(ingredientDump(runtime.get()));
            done = true;
        } catch (Throwable throwable) {
            try {
                write(java.util.Collections.singletonList("ERROR " + throwable));
            } catch (Throwable ignored) {
                // Nothing more we can do while the game is running.
            }
            done = true;
        }
    }

    /**
     * JEI only creates its runtime after the client joins a world. An existing
     * save may require backup confirmation, which breaks a programmatic load,
     * so the diagnostic always creates a fresh throwaway world instead.
     */
    private static void startDiagnosticWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (joinAttempted || !(minecraft.screen instanceof net.minecraft.client.gui.screens.TitleScreen)) {
            return;
        }
        joinAttempted = true;
        try {
            String levelId = "jei-diag-" + Long.toHexString(System.currentTimeMillis());
            net.minecraft.world.level.LevelSettings settings = new net.minecraft.world.level.LevelSettings(
                    levelId, net.minecraft.world.level.GameType.CREATIVE, false,
                    net.minecraft.world.Difficulty.NORMAL, true,
                    new net.minecraft.world.level.GameRules(),
                    net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
            System.out.println("[JEI-DIAG] creating world " + levelId);
            minecraft.createWorldOpenFlows().createFreshLevel(levelId, settings,
                    net.minecraft.world.level.levelgen.WorldOptions.defaultWithRandomSeed(),
                    net.minecraft.world.level.levelgen.presets.WorldPresets::createNormalWorldDimensions,
                    minecraft.screen);
        } catch (Throwable throwable) {
            System.out.println("[JEI-DIAG] world creation failed: " + throwable);
        }
    }

    private static List<String> dump(IJeiRuntime runtime) {
        IRecipeManager manager = runtime.getRecipeManager();
        Set<String> visible = manager.createRecipeCategoryLookup().get()
                .map(category -> category.getRecipeType().getUid().toString())
                .collect(Collectors.toSet());
        List<IRecipeCategory<?>> categories = manager.createRecipeCategoryLookup().includeHidden().get()
                .sorted((left, right) -> left.getRecipeType().getUid().toString()
                        .compareTo(right.getRecipeType().getUid().toString()))
                .collect(Collectors.toList());
        if (categories.isEmpty()) {
            return new ArrayList<String>();
        }
        List<String> lines = new ArrayList<String>();
        lines.add("# uid\tvisible\trecipes\tcategoryClass\trecipeClass\tsourceJar\ttitle\tshape\tsampleRecipe");
        for (IRecipeCategory<?> category : categories) {
            lines.add(describe(manager, category, visible));
        }
        return lines;
    }

    /**
     * Every ingredient type JEI knows about, with how many ingredients it holds
     * and how many of those the ingredient index (the right hand list) shows.
     */
    private static List<String> ingredientDump(IJeiRuntime runtime) {
        List<String> lines = new ArrayList<String>();
        lines.add("# typeUid\tregistered\ttotal\tvisible\tsample");
        for (mezz.jei.api.ingredients.IIngredientType<?> type : runtime.getIngredientManager()
                .getRegisteredIngredientTypes()) {
            lines.add(describeIngredientType(runtime, type));
        }
        return lines;
    }

    private static <V> String describeIngredientType(IJeiRuntime runtime,
                                                     mezz.jei.api.ingredients.IIngredientType<V> type) {
        Collection<mezz.jei.api.ingredients.ITypedIngredient<V>> all =
                runtime.getIngredientManager().getAllTypedIngredients(type);
        int visible = 0;
        String sample = "";
        for (mezz.jei.api.ingredients.ITypedIngredient<V> typed : all) {
            boolean shown;
            try {
                shown = runtime.getIngredientVisibility().isIngredientVisible(typed);
            } catch (RuntimeException exception) {
                shown = false;
            }
            if (shown) {
                visible++;
                if (sample.isEmpty()) {
                    sample = String.valueOf(typed.getIngredient());
                }
            }
        }
        return String.join("\t", type.getUid(), "yes", Integer.toString(all.size()),
                Integer.toString(visible), sample.replace('\t', ' ').replace('\n', ' '));
    }

    private static void writeIngredients(List<String> lines) {
        try {
            Path path = Paths.get(INGREDIENT_FILE);
            Files.write(path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            System.out.println("[JEI-DIAG] wrote " + lines.size() + " ingredient types to " + path.toAbsolutePath());
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
    }

    private static <T> String describe(IRecipeManager manager, IRecipeCategory<T> category, Set<String> visible) {
        RecipeType<T> type = category.getRecipeType();        List<T> recipes = manager.createRecipeLookup(type).includeHidden().get().collect(Collectors.toList());
        String title;
        try {
            title = category.getTitle().getString();
        } catch (RuntimeException exception) {
            title = "";
        }
        String shape = "";
        String sample = "";
        if (!recipes.isEmpty()) {
            T recipe = recipes.get(0);
            sample = recipe.getClass().getName();
            if (recipe instanceof RecipeHolder<?>) {
                ResourceLocation uid = ((RecipeHolder<?>) recipe).id();
                sample = sample + " id=" + uid
                        + " type=" + net.minecraft.core.registries.BuiltInRegistries.RECIPE_SERIALIZER.getKey(
                        ((RecipeHolder<?>) recipe).value().getSerializer());
            }
            try {
                shape = shape(manager, category, recipe);
            } catch (Throwable throwable) {
                shape = "shape-error:" + throwable.getClass().getSimpleName();
            }
        }
        return String.join("\t",
                type.getUid().toString(),
                visible.contains(type.getUid().toString()) ? "visible" : "hidden",
                Integer.toString(recipes.size()),
                category.getClass().getName(),
                type.getRecipeClass().getName(),
                sourceOf(category.getClass()),
                title,
                shape.replace('\t', ' '),
                sample.replace('\t', ' '));
    }

    private static <T> String shape(IRecipeManager manager, IRecipeCategory<T> category, T recipe) {
        mezz.jei.api.ingredients.IIngredientSupplier supplier = manager.getRecipeIngredients(category, recipe);
        List<String> parts = new ArrayList<String>();
        for (RecipeIngredientRole role : RecipeIngredientRole.values()) {
            List<ITypedIngredient<?>> ingredients = supplier.getIngredients(role);
            for (int index = 0; index < ingredients.size(); index++) {
                ITypedIngredient<?> typed = ingredients.get(index);
                String ingredientType = typed.getType().getUid();
                String value = "";
                Optional<ItemStack> stack = typed.getItemStack();
                if (stack.isPresent()) {
                    value = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                            stack.get().getItem()));
                } else {
                    value = String.valueOf(typed.getIngredient());
                }
                parts.add(role.name().toLowerCase() + "[" + index + "]=" + ingredientType + ":" + value);
            }
        }
        return String.join(",", parts);
    }

    private static String sourceOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return source.getLocation().toString();
            }
        } catch (Throwable ignored) {
            // Fall through to the class resource lookup.
        }
        try {
            java.net.URL resource = type.getResource("/" + type.getName().replace('.', '/') + ".class");
            return resource == null ? "" : resource.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void heartbeat() {
        try {
            Files.write(Paths.get(HEARTBEAT_FILE),
                    ("ticks=" + ticks + " runtime=" + Internal.getOptionalJeiRuntime().isPresent()
                            + " join=" + joinAttempted
                            + " screen=" + (Minecraft.getInstance().screen == null
                            ? "none" : Minecraft.getInstance().screen.getClass().getName()))
                            .getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            // Diagnostics only.
        }
    }

    private static void write(List<String> lines) throws java.io.IOException {
        Path path = Paths.get(OUTPUT_FILE);
        Files.write(path, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        System.out.println("[JEI-DIAG] wrote " + lines.size() + " lines to " + path.toAbsolutePath());
    }
}
