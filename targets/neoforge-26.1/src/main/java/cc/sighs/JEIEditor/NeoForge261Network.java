package cc.sighs.JEIEditor;

import cc.sighs.JEIEditor.editor.RecipePatch;
import cc.sighs.JEIEditor.editor.RecipeEditPayloadRules;
import cc.sighs.JEIEditor.editor.EditorModel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

final class NeoForge261Network {
    private static final Logger LOGGER = LoggerFactory.getLogger("JEI Editor Network");

    private NeoForge261Network() {
    }

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToServer(RecipeEditPayload.TYPE, RecipeEditPayload.STREAM_CODEC, NeoForge261Network::handleRecipeEdit)
                .playToServer(RecipeEditDeletePayload.TYPE, RecipeEditDeletePayload.STREAM_CODEC, NeoForge261Network::handleRecipeDelete)
                .playToClient(RecipeEditResultPayload.TYPE, RecipeEditResultPayload.STREAM_CODEC, NeoForge261Network::handleRecipeResult);
    }

    static void send(RecipePatch patch) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(RecipeEditPayload.fromPatch(patch));
    }

    static void sendDelete(String recipeId) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new RecipeEditDeletePayload(recipeId));
    }

    private static void handleRecipeDelete(RecipeEditDeletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer)) {
                context.reply(RecipeEditResultPayload.failure("Permission denied", payload.recipeId));
                return;
            }
            MinecraftServer server = ((ServerPlayer) context.player()).level().getServer();
            if (server != null && !RecipeEditorPolicy.load(server).canEdit((ServerPlayer) context.player(), payload.recipeId)) {
                context.reply(RecipeEditResultPayload.failure("Permission or namespace policy denied", payload.recipeId));
                return;
            }
            if (server == null || Identifier.tryParse(payload.recipeId) == null) {
                context.reply(RecipeEditResultPayload.failure("Unknown recipe id", payload.recipeId));
                return;
            }
            RecipeEditsSavedData data = RecipeEditsSavedData.get(server);
            RecipePatch previous = data.patches().get(payload.recipeId);
            EditorModel baseModel = data.baseModel(payload.recipeId);
            if (previous == null || baseModel == null) {
                context.reply(RecipeEditResultPayload.failure("No saved edit for recipe", payload.recipeId));
                return;
            }
            if (!RecipeEditCoordinator.tryBegin(server)) {
                context.reply(RecipeEditResultPayload.failure("Another recipe reload is in progress", payload.recipeId));
                return;
            }
            data.remove(payload.recipeId);
            RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
                if (error != null) {
                    LOGGER.error("Failed to remove recipe edit for {}", payload.recipeId, error);
                    data.restore(payload.recipeId, previous, baseModel);
                    RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) ->
                            server.execute(() -> {
                                if (rollbackError != null) {
                                    LOGGER.error("Failed to restore removed recipe edit for {}", payload.recipeId, rollbackError);
                                }
                                if (rollbackError == null) data.audit(actor((ServerPlayer) context.player()), "RESET_ROLLBACK", null, previous);
                                RecipeEditCoordinator.finish(server);
                                context.reply(RecipeEditResultPayload.failure(rollbackError == null
                                        ? "Recipe reset failed; change restored"
                                        : "Recipe reset failed; restore also failed; check server log", payload.recipeId));
                            }));
                } else {
                    RecipeEditCoordinator.finish(server);
                    data.audit(actor((ServerPlayer) context.player()), "RESET", previous, null);
                    context.reply(RecipeEditResultPayload.success("Recipe reset to original", payload.recipeId));
                }
            }));
        });
    }

    private static void handleRecipeEdit(RecipeEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer)) {
                LOGGER.warn("Rejected recipe edit from player without permission: {}", context.player());
                context.reply(RecipeEditResultPayload.failure("Permission denied", ""));
                return;
            }
            try {
                RecipePatch patch = payload.toPatch();
                MinecraftServer server = ((ServerPlayer) context.player()).level().getServer();
                Identifier recipeId = Identifier.tryParse(patch.recipeId());
                if (server == null || recipeId == null) {
                    LOGGER.warn("Rejected recipe edit with unknown recipe id: {}", patch.recipeId());
                    context.reply(RecipeEditResultPayload.failure("Unknown recipe id", patch.recipeId()));
                    return;
                }
                if (!RecipeEditorPolicy.load(server).canEdit((ServerPlayer) context.player(), patch.recipeId())) {
                    context.reply(RecipeEditResultPayload.failure("Permission or namespace policy denied", patch.recipeId()));
                    return;
                }
                RecipeHolder<?> holder = server.getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, recipeId)).orElse(null);
                if (holder == null) {
                    LOGGER.warn("Rejected recipe edit for missing recipe: {}", patch.recipeId());
                    context.reply(RecipeEditResultPayload.failure("Recipe does not exist", patch.recipeId()));
                    return;
                }
                Identifier serializerId = net.minecraft.core.registries.BuiltInRegistries.RECIPE_SERIALIZER
                        .getKey(holder.value().getSerializer());
                if (serializerId == null || !serializerId.toString().equals(patch.serializerId())) {
                    LOGGER.warn("Rejected recipe edit with serializer mismatch: {}", patch.recipeId());
                    context.reply(RecipeEditResultPayload.failure("Recipe serializer mismatch", patch.recipeId()));
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
                    LOGGER.warn("Rejected recipe edit that cannot be applied: {}", patch.recipeId());
                    context.reply(RecipeEditResultPayload.failure("Recipe patch is stale or unsupported", patch.recipeId()));
                    return;
                }
                java.util.Optional<EditorModel> baseModel = RecipeEditorAdapters.createModel(
                        holder, server.registryAccess());
                if (!baseModel.isPresent()) {
                    context.reply(RecipeEditResultPayload.failure("Recipe is read-only in the MVP", patch.recipeId()));
                    return;
                }
                if (!RecipeEditCoordinator.tryBegin(server)) {
                    context.reply(RecipeEditResultPayload.failure("Another recipe reload is in progress", patch.recipeId()));
                    return;
                }
                final RecipePatch acceptedPatch = patch;
                RecipePatch previous = data.put(acceptedPatch, baseModel.get());
                RecipeEditsApplier.apply(server, data).whenComplete((ignored, error) -> server.execute(() -> {
                    if (error != null) {
                        LOGGER.error("Failed to apply recipe edit for {}", acceptedPatch.recipeId(), error);
                        data.restore(acceptedPatch.recipeId(), previous);
                        RecipeEditsApplier.apply(server, data).whenComplete((rollbackIgnored, rollbackError) ->
                                server.execute(() -> {
                                    if (rollbackError != null) {
                                        LOGGER.error("Failed to roll back recipe edit for {}", acceptedPatch.recipeId(), rollbackError);
                                    }
                                    if (rollbackError == null) data.audit(actor((ServerPlayer) context.player()), "SAVE_ROLLBACK", acceptedPatch, previous);
                                    RecipeEditCoordinator.finish(server);
                                    context.reply(RecipeEditResultPayload.failure(rollbackError == null
                                            ? "Recipe reload failed; change rolled back"
                                            : "Recipe reload failed; rollback also failed; check server log", acceptedPatch.recipeId()));
                                }));
                    } else {
                        RecipeEditCoordinator.finish(server);
                        data.audit(actor((ServerPlayer) context.player()), "SAVE", previous, acceptedPatch);
                        LOGGER.info("Applied recipe edit for {} and reloaded server recipes", acceptedPatch.recipeId());
                        context.reply(RecipeEditResultPayload.success("Recipe saved", acceptedPatch.recipeId()));
                    }
                }));
                LOGGER.info("Received recipe edit request for {} ({}) with {} fields from {}",
                        acceptedPatch.recipeId(), acceptedPatch.serializerId(), acceptedPatch.fields().size(), context.player().getName().getString());
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Rejected invalid recipe edit payload from {}: {}",
                        context.player().getName().getString(), exception.getMessage());
                context.reply(RecipeEditResultPayload.failure(
                        "Invalid recipe edit payload: " + exception.getMessage(), ""));
            }
        });
    }

    private static void handleRecipeResult(RecipeEditResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientEditorState.applyResult(payload.success, payload.message, payload.recipeId));
    }

    private static String actor(ServerPlayer player) {
        return player.getUUID().toString() + "/" + player.getName().getString();
    }

    static final class RecipeEditPayload implements CustomPacketPayload {
        static final Type<RecipeEditPayload> TYPE = new Type<RecipeEditPayload>(
                Identifier.fromNamespaceAndPath(JEIEditorNeoForge261.MOD_ID, "edit_recipe"));

        static final StreamCodec<RegistryFriendlyByteBuf, RecipeEditPayload> STREAM_CODEC =
                StreamCodec.of(RecipeEditPayload::write, RecipeEditPayload::read);

        private final String recipeId;
        private final String serializerId;
        private final String baseFingerprint;
        private final Map<String, String> fields;

        RecipeEditPayload(String recipeId, String serializerId, String baseFingerprint, Map<String, String> fields) {
            this.recipeId = recipeId;
            this.serializerId = serializerId;
            this.baseFingerprint = baseFingerprint;
            this.fields = new LinkedHashMap<String, String>(fields);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        RecipePatch toPatch() {
            return new RecipePatch(recipeId, serializerId, baseFingerprint, fields);
        }

        static RecipeEditPayload fromPatch(RecipePatch patch) {
            return new RecipeEditPayload(patch.recipeId(), patch.serializerId(), patch.baseFingerprint(), patch.fields());
        }

        private static void write(RegistryFriendlyByteBuf buffer, RecipeEditPayload payload) {
            buffer.writeUtf(payload.recipeId, 256);
            buffer.writeUtf(payload.serializerId, 256);
            buffer.writeUtf(payload.baseFingerprint, 128);
            RecipeEditPayloadRules.requireFieldCount(payload.fields.size());
            buffer.writeVarInt(payload.fields.size());
            for (Map.Entry<String, String> entry : payload.fields.entrySet()) {
                RecipeEditPayloadRules.requireField(entry.getKey(), entry.getValue());
                buffer.writeUtf(entry.getKey(), 64);
                buffer.writeUtf(entry.getValue(), 512);
            }
        }

        private static RecipeEditPayload read(RegistryFriendlyByteBuf buffer) {
            String recipeId = buffer.readUtf(256);
            String serializerId = buffer.readUtf(256);
            String baseFingerprint = buffer.readUtf(128);
            int fieldCount = buffer.readVarInt();
            RecipeEditPayloadRules.requireFieldCount(fieldCount);
            Map<String, String> fields = new LinkedHashMap<String, String>();
            for (int i = 0; i < fieldCount; i++) {
                String key = buffer.readUtf(64);
                String value = buffer.readUtf(512);
                RecipeEditPayloadRules.requireField(key, value);
                fields.put(key, value);
            }
            return new RecipeEditPayload(recipeId, serializerId, baseFingerprint, fields);
        }
    }

    static final class RecipeEditDeletePayload implements CustomPacketPayload {
        static final Type<RecipeEditDeletePayload> TYPE = new Type<RecipeEditDeletePayload>(
                Identifier.fromNamespaceAndPath(JEIEditorNeoForge261.MOD_ID, "delete_recipe_edit"));
        static final StreamCodec<RegistryFriendlyByteBuf, RecipeEditDeletePayload> STREAM_CODEC =
                StreamCodec.of(RecipeEditDeletePayload::write, RecipeEditDeletePayload::read);

        private final String recipeId;

        RecipeEditDeletePayload(String recipeId) {
            this.recipeId = recipeId;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void write(RegistryFriendlyByteBuf buffer, RecipeEditDeletePayload payload) {
            buffer.writeUtf(payload.recipeId, 256);
        }

        private static RecipeEditDeletePayload read(RegistryFriendlyByteBuf buffer) {
            return new RecipeEditDeletePayload(buffer.readUtf(256));
        }
    }

    static final class RecipeEditResultPayload implements CustomPacketPayload {
        static final Type<RecipeEditResultPayload> TYPE = new Type<RecipeEditResultPayload>(
                Identifier.fromNamespaceAndPath(JEIEditorNeoForge261.MOD_ID, "edit_recipe_result"));
        static final StreamCodec<RegistryFriendlyByteBuf, RecipeEditResultPayload> STREAM_CODEC =
                StreamCodec.of(RecipeEditResultPayload::write, RecipeEditResultPayload::read);

        private final boolean success;
        private final String message;
        private final String recipeId;

        private RecipeEditResultPayload(boolean success, String message, String recipeId) {
            this.success = success;
            this.message = message;
            this.recipeId = recipeId;
        }

        static RecipeEditResultPayload success(String message, String recipeId) {
            return new RecipeEditResultPayload(true, message, recipeId);
        }

        static RecipeEditResultPayload failure(String message, String recipeId) {
            return new RecipeEditResultPayload(false, message, recipeId);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        private static void write(RegistryFriendlyByteBuf buffer, RecipeEditResultPayload payload) {
            buffer.writeBoolean(payload.success);
            buffer.writeUtf(payload.message, 256);
            buffer.writeUtf(payload.recipeId, 256);
        }

        private static RecipeEditResultPayload read(RegistryFriendlyByteBuf buffer) {
            return new RecipeEditResultPayload(buffer.readBoolean(), buffer.readUtf(256), buffer.readUtf(256));
        }
    }
}
