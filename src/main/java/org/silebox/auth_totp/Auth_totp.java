package org.silebox.auth_totp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import net.fabricmc.api.ModInitializer;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import oshi.util.tuples.Pair;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;


public class Auth_totp implements ModInitializer {
    private static final Path modConfigPath = FabricLoader.getInstance().getConfigDir().resolve("auth_totp");
    private static final Database database = new Database(modConfigPath);
    public static final GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
    public static final HashSet<UUID> blockedPlayers = new HashSet<>();
    public static final Map<UUID, GoogleAuthenticatorKey> newUsers = new Hashtable<>();
    private record SavedLocation(RegistryKey<World> dimension, Vec3d pos, float yaw, float pitch) {}
    private static final Map<UUID, SavedLocation> frozenPositions = new HashMap<>();
    private static final Map<UUID, Pair<LocalDateTime, String>> sessions = new HashMap<>();
    public static final String MOD_ID = "auth_totp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Auth_totp");

        Config.Load();

        ServerPlayConnectionEvents.JOIN.register(this::PlayerJoinEvent);
        ServerPlayConnectionEvents.DISCONNECT.register(this::PlayerDisconnectEvent);

        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

        PlayerBlockBreakEvents.BEFORE.register(this::PlayerBlockBreakEvent);

        UseBlockCallback.EVENT.register(this::UseBlockCallbackEvent);

        CommandRegistrationCallback.EVENT.register(this::AuthCommand);

        LOGGER.info("Auth_totp intialized");
    }

    private boolean isPlayerBlocked(PlayerEntity player) {
        return blockedPlayers.contains(player.getUuid());
    }

    private boolean PlayerBlockBreakEvent(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        return !isPlayerBlocked(player);
    }

    private ActionResult UseBlockCallbackEvent(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        return blockedPlayers.contains(player.getUuid()) ? ActionResult.FAIL : ActionResult.SUCCESS;
    }

    private void PlayerDisconnectEvent(ServerPlayNetworkHandler handler, MinecraftServer server) {
    ServerPlayerEntity player = handler.player;
    if (!checkPlayerSession(player)) return;

    sessions.replace(player.getUuid(), new Pair<>(LocalDateTime.now(), player.getIp()));
    }

    private void PlayerJoinEvent(ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) {
        ServerPlayerEntity player = handler.player;
        boolean playerHasSession = checkPlayerSession(player);
        if (playerHasSession) {
            PlayerAllowed(player);
            return;
        }

        PlayerNotAllowed(player);

        if (database.IsUserRegistered(player.getUuid().toString())) {
            player.sendMessage(Text.literal("To log-in, use ")
                            .append(
                                    Text.literal("/auth login <INSERT CODE>")
                                        .setStyle(Style.EMPTY
                                                .withColor(Formatting.GOLD)
                                                .withClickEvent(new ClickEvent.SuggestCommand("/auth login ")
                                        )))
                            );
        } else {
            final GoogleAuthenticatorKey key = googleAuthenticator.createCredentials();
            String qr_params = String.format("otpauth://totp/%s:%s?secret=%s&issuer=%s",Config.INSTANCE.serverName, player.getStringifiedName(), key.getKey(), Config.INSTANCE.serverName);
            String qr_encoded = URLEncoder.encode(qr_params);
            String full_url = String.format("https://quickchart.io/qr?text=%s", qr_encoded);
            Text qr_message = Text.literal("Click here to scan QR for google authenticator")
                    .setStyle(Style.EMPTY
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(full_url)))
                            .withColor(Formatting.BLUE)
                            .withUnderline(true));
            player.sendMessage(qr_message);
            player.sendMessage(Text.literal("To register, use ")
                        .append(
                                Text.literal("/auth register <INSERT CODE FROM AUTHENTICATOR>")
                                .setStyle(Style.EMPTY
                                        .withColor(Formatting.GOLD)
                                        .withClickEvent(new ClickEvent.SuggestCommand("/auth register "))))
                        );
            newUsers.put(player.getUuid(), key);
        }
    }

    private void AuthCommand(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("auth")
                .then(CommandManager.literal("register")
                        .then(CommandManager.argument("code", IntegerArgumentType.integer())
                                .executes(this::AuthCommandRegister)))
                .then(CommandManager.literal("login")
                        .then(CommandManager.argument("code", IntegerArgumentType.integer())
                                .executes(this::AuthCommandLogin))));
    }

    private int AuthCommandRegister(CommandContext<ServerCommandSource> commandContext) {
        ServerPlayerEntity player = commandContext.getSource().getPlayer();
        int code = IntegerArgumentType.getInteger(commandContext, "code");

        if (player == null) return -1;

        if (!newUsers.containsKey(player.getUuid())) return -1;

        GoogleAuthenticatorKey key = newUsers.get(player.getUuid());
        String secret = key.getKey();

        boolean auth_result = googleAuthenticator.authorize(secret, code);

        if (!auth_result) {
            player.sendMessage(Text.literal("Auth failed").formatted(Formatting.RED), false);
            return -1;
        }

        database.AddUser(player.getUuid().toString(), secret);
        newUsers.remove(player.getUuid());
        PlayerAllowed(player);

        return 1;
    }

    private int AuthCommandLogin(CommandContext<ServerCommandSource> commandContext) {
        ServerPlayerEntity player = commandContext.getSource().getPlayer();
        int code = IntegerArgumentType.getInteger(commandContext, "code");

        if (player == null) return -1;

        if (newUsers.containsKey(player.getUuid())) return -1;

        if (!database.IsUserRegistered(player.getUuid().toString())) return -1;

        Optional<String> secret = database.GetSecretKey(player.getUuid().toString());
        if (secret.isEmpty()) return -1;

        String secret_code = secret.get();

        boolean auth_result = googleAuthenticator.authorize(secret_code, code);

        if (!auth_result) {
            player.sendMessage(Text.literal("Auth failed").formatted(Formatting.RED), false);
            return -1;
        }

        PlayerAllowed(player);

        return 1;
    }

    private void PlayerNotAllowed(ServerPlayerEntity player) {
        frozenPositions.put(player.getUuid(), new SavedLocation(
            player.getEntityWorld().getRegistryKey(),
            player.getEntityPos(),
            player.getYaw(),
            player.getPitch()
        ));
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.MINING_FATIGUE,
                StatusEffectInstance.INFINITE,
                255, false, false
        ));

        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.BLINDNESS,
            StatusEffectInstance.INFINITE,
            255, false, false
        ));

        blockedPlayers.add(player.getUuid());
    }

    private void PlayerAllowed(ServerPlayerEntity player) {

        player.removeStatusEffect(StatusEffects.MINING_FATIGUE);
        player.removeStatusEffect(StatusEffects.BLINDNESS);
        blockedPlayers.remove(player.getUuid());
        frozenPositions.remove(player.getUuid());

        if (sessions.containsKey(player.getUuid())) sessions.replace(player.getUuid(), new Pair<>(LocalDateTime.now(), player.getIp()));
        else sessions.put(player.getUuid(), new Pair<>(LocalDateTime.now(), player.getIp()));

        player.sendMessage(Text.literal("Successful auth!").formatted(Formatting.GREEN), false);
    }

    private boolean checkPlayerSession(ServerPlayerEntity player) {
        if (!sessions.containsKey(player.getUuid())) return false;

        LocalDateTime maxTimeout = LocalDateTime.now().minusSeconds(Config.INSTANCE.sessionTimeout);
        LocalDateTime playerTime = sessions.get(player.getUuid()).getA();
        String lastIp = sessions.get(player.getUuid()).getB();

        if (playerTime.isBefore(maxTimeout)) {
            sessions.remove(player.getUuid());
            return false;
        }

        if (!player.getIp().equals(lastIp)) {
            sessions.remove(player.getUuid());
            return false;
        }

        return true;
    }

    private void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            SavedLocation loc = frozenPositions.get(player.getUuid());
            if (loc == null) continue;

            if (player.getEntityPos().squaredDistanceTo(loc.pos) > 1.0) {
                player.teleport(
                        server.getWorld(loc.dimension),
                        loc.pos.x, loc.pos.y, loc.pos.z,
                        Set.of(),
                        player.getYaw(), player.getPitch(),
                        false
                );

                player.setVelocity(0,0,0);
            }
        }
    }
}
