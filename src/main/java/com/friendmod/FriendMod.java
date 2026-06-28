package com.friendmod;

import carpet.patches.EntityPlayerMPFake;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FriendMod v3.2 — Main Entry Point
 * ====================================
 * Requires: Fabric 1.21.11 + Carpet 1.4.194
 *
 * - Friend is an EntityPlayerMPFake (full player body, rainbow skin)
 * - Carpet's fakePlayers rule is auto-enabled on startup
 * - All players can talk to Friend (multiplayer)
 * - Friend ignores her own chat messages
 */
public class FriendMod implements ModInitializer {

    public static final String MOD_ID = "friendmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static FriendBot friendBot = null;

    @Override
    public void onInitialize() {
        LOGGER.info("[FriendMod] v3.2 loading — AI friend (fake player, Carpet 1.4.194, MC 1.21.11)");
        FriendConfig.load();

        // ── SERVER STARTED ────────────────────────────────────────────────────
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // Enable Carpet's fakePlayers rule so createFake() works
            server.getCommandManager().executeWithPrefix(
                server.getCommandSource(), "carpet fakePlayers true"
            );
            LOGGER.info("[FriendMod] Carpet fakePlayers enabled. Friend will spawn when first player joins.");
            friendBot = new FriendBot(server);
        });

        // ── SERVER STOPPING ───────────────────────────────────────────────────
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (friendBot != null) friendBot.despawn();
        });

        // ── TICK ──────────────────────────────────────────────────────────────
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (friendBot != null) friendBot.tick();
        });

        // ── PLAYER JOIN ───────────────────────────────────────────────────────
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity joined = handler.player;
            // Ignore fake players (including Friend herself)
            if (joined instanceof EntityPlayerMPFake) return;

            String botName = FriendConfig.getBotName();
            long realCount = server.getPlayerManager().getPlayerList().stream()
                .filter(p -> !(p instanceof EntityPlayerMPFake))
                .count();

            if (friendBot != null) {
                EntityPlayerMPFake existing = (EntityPlayerMPFake) server.getPlayerManager().getPlayer(botName);
                if (existing == null) {
                    // First real player joining — spawn Friend
                    server.execute(() -> friendBot.spawn());
                } else if (realCount > 1) {
                    // Extra player joined — greet them
                    server.execute(() -> friendBot.broadcastMessage(
                        "Oh hey, §b" + joined.getName().getString() + "§f just joined! Welcome! 👋💕"
                    ));
                }
            }
        });

        // ── PLAYER LEAVE ──────────────────────────────────────────────────────
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player instanceof EntityPlayerMPFake) return;
            String name = handler.player.getName().getString();
            long remaining = server.getPlayerManager().getPlayerList().stream()
                .filter(p -> !(p instanceof EntityPlayerMPFake))
                .count();

            if (friendBot != null && remaining <= 1) {
                server.execute(() -> friendBot.broadcastMessage(
                    "Aw, " + name + " left! Come back soon! 💖"
                ));
            }
        });

        // ── CHAT ──────────────────────────────────────────────────────────────
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            // Ignore fake players
            if (sender instanceof EntityPlayerMPFake) return;

            String botName = FriendConfig.getBotName();
            String content = message.getContent().getString();
            ServerPlayerEntity player = (ServerPlayerEntity) sender;

            if (content.toLowerCase().startsWith(botName.toLowerCase())) {
                String userText = content.substring(botName.length()).trim();

                if (userText.isEmpty()) {
                    player.sendMessage(Text.literal("§d[" + botName + "] §fHey! What's up? 💕"), false);
                    return;
                }

                if (friendBot != null) {
                    // Show "thinking" dot immediately
                    player.sendMessage(Text.literal("§d[" + botName + "] §7..."), false);

                    String finalText = userText;
                    Thread t = new Thread(() -> friendBot.handlePlayerMessage(player, finalText));
                    t.setDaemon(true);
                    t.setName("FriendMod-AI-" + player.getName().getString());
                    t.start();
                }
            }
        });

        LOGGER.info("[FriendMod] Ready! Say '" + FriendConfig.getBotName() + " hello' in chat to talk to her!");
    }
}
