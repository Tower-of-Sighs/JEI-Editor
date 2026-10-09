package cc.sighs.JEIEditor.server;

import cc.sighs.JEIEditor.JEIEditorNeoForge121;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * TEMPORARY diagnostic used only to establish the real provenance of the
 * recipe families that have no datapack JSON. It is not part of the shipped
 * feature and must be deleted again.
 */
@EventBusSubscriber(modid = JEIEditorNeoForge121.MOD_ID)
public final class RecipeProvenanceProbe {
    private static final int PER_TOKEN_CAP = 30;
    private static final int DETAIL_LIMIT = 5;

    private RecipeProvenanceProbe() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("jeiprovenance")
                .then(Commands.argument("name", StringArgumentType.word())
                        .then(Commands.argument("tokens", StringArgumentType.greedyString())
                                .executes(context -> dump(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        StringArgumentType.getString(context, "tokens"))))));
        event.getDispatcher().register(Commands.literal("jeiprobeforce")
                .then(Commands.argument("token", StringArgumentType.greedyString())
                        .executes(context -> force(context.getSource(),
                                StringArgumentType.getString(context, "token")))));
    }

    private static ResourceLocation serializerOf(RecipeHolder<?> holder) {
        return BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
    }

    private static boolean resourceReachable(MinecraftServer server, ResourceLocation id) {
        ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                "recipe/" + id.getPath() + ".json");
        return server.getResourceManager().getResource(resourceId).isPresent();
    }

    private static List<RecipeHolder<?>> matching(MinecraftServer server, String token) {
        List<RecipeHolder<?>> matches = new ArrayList<RecipeHolder<?>>();
        if (token.startsWith("exact:")) {
            ResourceLocation id = ResourceLocation.tryParse(token.substring("exact:".length()));
            server.getRecipeManager().byKey(id).ifPresent(matches::add);
            return matches;
        }
        if (token.startsWith("serializer:")) {
            String serializer = token.substring("serializer:".length());
            for (RecipeHolder<?> holder : server.getRecipeManager().getOrderedRecipes()) {
                ResourceLocation key = serializerOf(holder);
                if (key != null && key.toString().equals(serializer)) {
                    matches.add(holder);
                }
            }
            return matches;
        }
        if (token.startsWith("all:")) {
            // Every recipe the resource manager cannot back: the whole set of
            // "in the RecipeManager, no writable datapack JSON" candidates.
            for (RecipeHolder<?> holder : server.getRecipeManager().getOrderedRecipes()) {
                if (!resourceReachable(server, holder.id())) {
                    matches.add(holder);
                }
            }
            return matches;
        }
        String needle = token.substring(token.indexOf(':') + 1);
        for (RecipeHolder<?> holder : server.getRecipeManager().getOrderedRecipes()) {
            boolean hit = token.startsWith("prefix:") ? holder.id().toString().startsWith(needle)
                    : holder.id().toString().contains(needle);
            if (hit) {
                matches.add(holder);
            }
        }
        return matches;
    }

    private static int dump(CommandSourceStack source, String name, String tokens) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        StringBuilder report = new StringBuilder();
        report.append("tokens: ").append(tokens).append('\n');
        report.append("manager recipes total: ")
                .append(server.getRecipeManager().getOrderedRecipes().size()).append('\n');
        int detail = 0;
        for (String rawToken : tokens.split("[\\s,]+")) {
            if (rawToken.isEmpty()) {
                continue;
            }
            List<RecipeHolder<?>> matches = matching(server, rawToken);
            int reachable = 0;
            for (RecipeHolder<?> holder : matches) {
                if (resourceReachable(server, holder.id())) {
                    reachable++;
                }
            }
            report.append("=== ").append(rawToken).append(" matches=").append(matches.size())
                    .append(" withResource=").append(reachable)
                    .append(" withoutResource=").append(matches.size() - reachable).append('\n');
            boolean full = rawToken.startsWith("serializer:");
            int shown = 0;
            for (RecipeHolder<?> holder : matches) {
                if (!full && shown >= PER_TOKEN_CAP) {
                    report.append("  ... ").append(matches.size() - shown).append(" more\n");
                    break;
                }
                shown++;
                ResourceLocation id = holder.id();
                report.append("--- ").append(id)
                        .append(" resource=").append(resourceReachable(server, id) ? "present" : "ABSENT");
                if (detail < DETAIL_LIMIT || full) {
                    report.append(" class=").append(holder.value().getClass().getSimpleName());
                    try {
                        var result = Recipe.CODEC.encodeStart(ops, holder.value());
                        Optional<JsonElement> encoded = result.result();
                        report.append(" codec=").append(encoded.isPresent() ? "ok" : "failed");
                        if (encoded.isPresent() && (detail < DETAIL_LIMIT)) {
                            String text = encoded.get().toString();
                            report.append("\n  canonical=")
                                    .append(text.length() > 900 ? text.substring(0, 900) + "..." : text);
                        }
                    } catch (RuntimeException exception) {
                        report.append(" codec=threw ").append(exception.getClass().getSimpleName());
                    }
                }
                report.append('\n');
                if (detail < DETAIL_LIMIT) {
                    detail++;
                }
            }
        }
        write(server, report.toString(), name + ".txt");
        source.sendSuccess(() -> Component.literal("provenance report written: " + name), false);
        return 1;
    }

    /**
     * Writes the canonical JSON of one existing recipe into the editor's
     * generated datapack with one field changed, reloads, and reports what the
     * live recipe then encodes to. A change that survives proves the datapack
     * file reaches that recipe; the original value proves the mod's own
     * generation wins.
     *
     * <p>A {@code serializer:} token prefers a recipe the resource manager does
     * NOT back - exactly the "in the manager, no writable JSON" case - and falls
     * back to the first match.
     */
    private static int force(CommandSourceStack source, String token) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        List<RecipeHolder<?>> matches = matching(server, token);
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("no manager recipe matches " + token));
            return 0;
        }
        RecipeHolder<?> chosen = matches.get(0);
        boolean fellBack = true;
        for (RecipeHolder<?> holder : matches) {
            if (!resourceReachable(server, holder.id())) {
                chosen = holder;
                fellBack = false;
                break;
            }
        }
        return forceOne(source, chosen.id(), fellBack, matches.size());
    }

    private static int forceOne(CommandSourceStack source, ResourceLocation id, boolean fellBack, int candidates) {
        MinecraftServer server = source.getServer();
        RecipeHolder<?> holder = server.getRecipeManager().byKey(id).orElseThrow();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Optional<JsonElement> encoded = Recipe.CODEC.encodeStart(ops, holder.value()).result();
        if (encoded.isEmpty() || !encoded.get().isJsonObject()) {
            source.sendFailure(Component.literal("codec encode failed for " + id));
            return 0;
        }
        JsonObject json = encoded.get().getAsJsonObject();
        String mutation = mutate(json);
        if (mutation == null) {
            source.sendFailure(Component.literal("no generic mutation available for " + id));
            return 0;
        }
        try {
            Path root = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("jeieditor-generated");
            Files.createDirectories(root.resolve("data"));
            Path metadata = root.resolve("pack.mcmeta");
            if (!Files.exists(metadata)) {
                Files.write(metadata, ("{\"pack\":{\"pack_format\":48,\"description\":\"probe\"}}")
                        .getBytes(StandardCharsets.UTF_8));
            }
            Path target = root.resolve("data").resolve(id.getNamespace())
                    .resolve("recipe").resolve(id.getPath() + ".json");
            Files.createDirectories(target.getParent());
            Files.write(target, json.toString().getBytes(StandardCharsets.UTF_8));
            PackRepository repository = server.getPackRepository();
            repository.reload();
            Optional<String> packId = repository.getAvailableIds().stream()
                    .filter(candidate -> candidate.endsWith("/jeieditor-generated")).findFirst();
            if (packId.isPresent()) {
                List<String> selected = new ArrayList<String>(repository.getSelectedIds());
                if (!selected.contains(packId.get())) {
                    selected.add(packId.get());
                    repository.setSelected(selected);
                }
            }
            List<String> selectedIds = new ArrayList<String>(repository.getSelectedIds());
            server.reloadResources(selectedIds).whenComplete((ignored, error) -> server.execute(() -> {
                StringBuilder report = new StringBuilder();
                report.append("forced id: ").append(id)
                        .append(fellBack ? " (fallback: every match had a resource)" : " (no resource)")
                        .append(" candidates=").append(candidates).append('\n');
                report.append("mutation: ").append(mutation).append('\n');
                if (error != null) {
                    report.append("reload error: ").append(error).append('\n');
                }
                int holderCount = 0;
                for (RecipeHolder<?> candidate : server.getRecipeManager().getOrderedRecipes()) {
                    if (candidate.id().equals(id)) {
                        holderCount++;
                    }
                }
                report.append("after reload holders=").append(holderCount).append('\n');
                server.getRecipeManager().byKey(id).ifPresent(after -> {
                    RegistryOps<JsonElement> reloadedOps =
                            RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
                    Optional<JsonElement> reEncoded = Recipe.CODEC
                            .encodeStart(reloadedOps, after.value()).result();
                    report.append("live json: ")
                            .append(reEncoded.map(JsonElement::toString).orElse("?")).append('\n');
                });
                append(server, report.toString());
                source.sendSuccess(() -> Component.literal("forced override for " + id + " reloaded"), false);
            }));
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("probe failed: " + exception));
            return 0;
        }
    }

    /** One field edit that most recipe shapes admit, so the probe is generic. */
    private static String mutate(JsonObject json) {
        if (json.has("energy")) {
            json.addProperty("energy", 4242);
            return "energy=4242";
        }
        if (json.has("processing_time")) {
            json.addProperty("processing_time", 4242);
            return "processing_time=4242";
        }
        if (json.has("time")) {
            json.addProperty("time", 4242);
            return "time=4242";
        }
        if (json.has("results") && json.get("results").isJsonArray()) {
            JsonArray results = json.getAsJsonArray("results");
            if (results.size() > 0 && results.get(0).isJsonObject()) {
                results.get(0).getAsJsonObject().addProperty("id", "minecraft:bedrock");
                return "results.0.id=minecraft:bedrock";
            }
        }
        if (json.has("result") && json.get("result").isJsonObject()) {
            json.getAsJsonObject("result").addProperty("id", "minecraft:bedrock");
            return "result.id=minecraft:bedrock";
        }
        return null;
    }

    private static void append(MinecraftServer server, String text) {
        try {
            Path path = server.getServerDirectory().resolve("config").resolve("jeieditor")
                    .resolve("provenance-reload.txt");
            Files.createDirectories(path.getParent());
            String previous = Files.exists(path) ? Files.readString(path) : "";
            Files.write(path, (previous + text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void write(MinecraftServer server, String text, String fileName) {
        try {
            Path path = server.getServerDirectory().resolve("config").resolve("jeieditor").resolve(fileName);
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(temporary, text.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
