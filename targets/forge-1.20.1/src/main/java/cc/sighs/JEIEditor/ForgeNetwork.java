package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.EditorModel;
import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

final class ForgeNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(JEIEditorForge.MOD_ID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals);

    private ForgeNetwork() {
    }

    static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, RecipeEditMessage.class, RecipeEditMessage::encode,
                RecipeEditMessage::decode, ForgeNetwork::handleEdit);
        CHANNEL.registerMessage(id++, RecipeDeleteMessage.class, RecipeDeleteMessage::encode,
                RecipeDeleteMessage::decode, ForgeNetwork::handleDelete);
        CHANNEL.registerMessage(id, RecipeResultMessage.class, RecipeResultMessage::encode,
                RecipeResultMessage::decode, ForgeNetwork::handleResult);
    }

    static void send(RecipePatch patch) {
        CHANNEL.sendToServer(RecipeEditMessage.fromPatch(patch));
    }

    static void sendDelete(String recipeId) {
        CHANNEL.sendToServer(new RecipeDeleteMessage(recipeId));
    }

    private static void handleEdit(RecipeEditMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                reply(player, RecipeResultMessage.failure("Permission denied", ""));
                return;
            }
            try {
                RecipePatch patch = message.toPatch();
                MinecraftServer server = player.getServer();
                ResourceLocation recipeId = ResourceLocation.tryParse(patch.recipeId());
                if (server == null || recipeId == null) {
                    reply(player, RecipeResultMessage.failure("Unknown recipe id", patch.recipeId()));
                    return;
                }
                if (!RecipeEditorPolicy.load(server).canEdit(player, patch.recipeId())) {
                    reply(player, RecipeResultMessage.failure("Permission or namespace policy denied", patch.recipeId()));
                    return;
                }
                Recipe<?> recipe = server.getRecipeManager().byKey(recipeId).orElse(null);
                if (recipe == null) {
                    reply(player, RecipeResultMessage.failure("Recipe does not exist", patch.recipeId()));
                    return;
                }
                ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
                if (serializerId == null || !serializerId.toString().equals(patch.serializerId())) {
                    reply(player, RecipeResultMessage.failure("Recipe serializer mismatch", patch.recipeId()));
                    return;
                }
                RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
                RecipePatch stored = data.patches().get(patch.recipeId());
                if (stored != null && data.baseModel(patch.recipeId()) != null) {
                    Map<String, String> fields = new LinkedHashMap<String, String>(stored.fields());
                    fields.putAll(patch.fields());
                    patch = new RecipePatch(patch.recipeId(), patch.serializerId(),
                            data.baseModel(patch.recipeId()).baseFingerprint(), fields);
                }
                if (!RecipeEditsApplier.canApply(server, data, patch)) {
                    reply(player, RecipeResultMessage.failure("Recipe patch is stale or unsupported", patch.recipeId()));
                    return;
                }
                java.util.Optional<EditorModel> baseModel = RecipeEditorAdapters.createModel(
                        recipeId, recipe, server.registryAccess());
                if (!baseModel.isPresent()) {
                    reply(player, RecipeResultMessage.failure("Recipe is read-only in the MVP", patch.recipeId()));
                    return;
                }
                if (!RecipeEditCoordinator.tryBegin(server)) {
                    reply(player, RecipeResultMessage.failure("Another recipe reload is in progress", patch.recipeId()));
                    return;
                }
                RecipePatch acceptedPatch = patch;
                RecipePatch previous = data.put(acceptedPatch, baseModel.get());
                RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
                    if (error != null) {
                        data.restore(acceptedPatch.recipeId(), previous);
                        RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) ->
                                server.execute(() -> {
                                    RecipeEditCoordinator.finish(server);
                                    if (rollbackError == null) data.audit(actor(player), "SAVE_ROLLBACK", acceptedPatch, previous);
                                    reply(player, RecipeResultMessage.failure(rollbackError == null
                                            ? "Recipe reload failed; change rolled back"
                                            : "Recipe reload failed; rollback also failed; check server log", acceptedPatch.recipeId()));
                                }));
                    } else {
                        RecipeEditCoordinator.finish(server);
                        data.audit(actor(player), "SAVE", previous, acceptedPatch);
                        reply(player, RecipeResultMessage.success("Recipe saved", acceptedPatch.recipeId()));
                    }
                }));
            } catch (IllegalArgumentException exception) {
                reply(player, RecipeResultMessage.failure("Invalid recipe edit payload", message.recipeId));
            }
        });
        context.setPacketHandled(true);
    }

    private static void handleDelete(RecipeDeleteMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                reply(player, RecipeResultMessage.failure("Permission denied", message.recipeId));
                return;
            }
            MinecraftServer server = player.getServer();
            if (server != null && !RecipeEditorPolicy.load(server).canEdit(player, message.recipeId)) {
                reply(player, RecipeResultMessage.failure("Permission or namespace policy denied", message.recipeId));
                return;
            }
            RecipeEditsSavedData data = server == null ? null : RecipeEditsSavedData.get(server);
            RecipePatch previous = data == null ? null : data.patches().get(message.recipeId);
            EditorModel baseModel = data == null ? null : data.baseModel(message.recipeId);
            if (server == null || previous == null || baseModel == null) {
                reply(player, RecipeResultMessage.failure("No saved edit for recipe", message.recipeId));
                return;
            }
            if (!RecipeEditCoordinator.tryBegin(server)) {
                reply(player, RecipeResultMessage.failure("Another recipe reload is in progress", message.recipeId));
                return;
            }
            data.remove(message.recipeId);
            RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
                    if (error != null) {
                        data.restore(message.recipeId, previous, baseModel);
                        RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) ->
                                server.execute(() -> {
                                    RecipeEditCoordinator.finish(server);
                                    if (rollbackError == null) data.audit(actor(player), "RESET_ROLLBACK", null, previous);
                                    reply(player, RecipeResultMessage.failure(rollbackError == null
                                            ? "Recipe reset failed; change restored"
                                            : "Recipe reset failed; restore also failed; check server log", message.recipeId));
                                }));
                } else {
                    RecipeEditCoordinator.finish(server);
                    data.audit(actor(player), "RESET", previous, null);
                    reply(player, RecipeResultMessage.success("Recipe reset to original", message.recipeId));
                }
            }));
        });
        context.setPacketHandled(true);
    }

    private static void handleResult(RecipeResultMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientEditorState.applyResult(
                message.success, message.message, message.recipeId));
        context.setPacketHandled(true);
    }

    private static void reply(ServerPlayer player, RecipeResultMessage message) {
        if (player != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    private static String actor(ServerPlayer player) {
        return player.getUUID().toString() + "/" + player.getGameProfile().getName();
    }

    static final class RecipeEditMessage {
        private final String recipeId;
        private final String serializerId;
        private final String baseFingerprint;
        private final Map<String, String> fields;

        private RecipeEditMessage(String recipeId, String serializerId, String baseFingerprint, Map<String, String> fields) {
            this.recipeId = recipeId;
            this.serializerId = serializerId;
            this.baseFingerprint = baseFingerprint;
            this.fields = new LinkedHashMap<String, String>(fields);
        }

        static RecipeEditMessage fromPatch(RecipePatch patch) {
            return new RecipeEditMessage(patch.recipeId(), patch.serializerId(), patch.baseFingerprint(), patch.fields());
        }

        RecipePatch toPatch() {
            return new RecipePatch(recipeId, serializerId, baseFingerprint, fields);
        }

        private static void encode(RecipeEditMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.recipeId, 256);
            buffer.writeUtf(message.serializerId, 256);
            buffer.writeUtf(message.baseFingerprint, 128);
            RecipeEditPayloadRules.requireFieldCount(message.fields.size());
            buffer.writeVarInt(message.fields.size());
            for (Map.Entry<String, String> entry : message.fields.entrySet()) {
                RecipeEditPayloadRules.requireField(entry.getKey(), entry.getValue());
                buffer.writeUtf(entry.getKey(), 64);
                buffer.writeUtf(entry.getValue(), 512);
            }
        }

        private static RecipeEditMessage decode(FriendlyByteBuf buffer) {
            String recipeId = buffer.readUtf(256);
            String serializerId = buffer.readUtf(256);
            String fingerprint = buffer.readUtf(128);
            int count = buffer.readVarInt();
            RecipeEditPayloadRules.requireFieldCount(count);
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (int i = 0; i < count; i++) {
                String key = buffer.readUtf(64);
                String value = buffer.readUtf(512);
                RecipeEditPayloadRules.requireField(key, value);
                fields.put(key, value);
            }
            return new RecipeEditMessage(recipeId, serializerId, fingerprint, fields);
        }
    }

    static final class RecipeDeleteMessage {
        private final String recipeId;

        private RecipeDeleteMessage(String recipeId) { this.recipeId = recipeId; }

        private static void encode(RecipeDeleteMessage message, FriendlyByteBuf buffer) { buffer.writeUtf(message.recipeId, 256); }
        private static RecipeDeleteMessage decode(FriendlyByteBuf buffer) { return new RecipeDeleteMessage(buffer.readUtf(256)); }
    }

    static final class RecipeResultMessage {
        private final boolean success;
        private final String message;
        private final String recipeId;

        private RecipeResultMessage(boolean success, String message, String recipeId) {
            this.success = success;
            this.message = message;
            this.recipeId = recipeId;
        }

        static RecipeResultMessage success(String message, String recipeId) { return new RecipeResultMessage(true, message, recipeId); }
        static RecipeResultMessage failure(String message, String recipeId) { return new RecipeResultMessage(false, message, recipeId); }
        private static void encode(RecipeResultMessage message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.success);
            buffer.writeUtf(message.message, 256);
            buffer.writeUtf(message.recipeId, 256);
        }
        private static RecipeResultMessage decode(FriendlyByteBuf buffer) {
            return new RecipeResultMessage(buffer.readBoolean(), buffer.readUtf(256), buffer.readUtf(256));
        }
    }
}
