package com.friendmod;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.stream.Collectors;

/**
 * WorldContext v3.1
 * =================
 * Builds a live snapshot of the Minecraft world to give Gemini context.
 * Correctly filters out fake players (EntityPlayerMPFake) from "real players" list.
 */
public class WorldContext {

    public static String buildContext(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();

        StringBuilder ctx = new StringBuilder();
        ctx.append("=== LIVE MINECRAFT WORLD DATA (1.21.11) ===\n");

        // Time
        long time = world.getTimeOfDay() % 24000;
        String timeLabel =
            time < 1000  ? "sunrise" :
            time < 6000  ? "morning" :
            time < 12000 ? "afternoon" :
            time < 13000 ? "sunset — getting dark!" :
            time < 18000 ? "night — mobs are out!" :
                           "late night — very dangerous!";
        ctx.append("Time: ").append(timeLabel).append("\n");

        // Weather
        ctx.append("Weather: ").append(
            world.isThundering() ? "thunderstorm ⚡" :
            world.isRaining()    ? "rain 🌧" : "clear ☀"
        ).append("\n");

        // Dimension
        ctx.append("Dimension: ").append(world.getRegistryKey().getValue().getPath()).append("\n");

        // Position
        ctx.append("Player pos: X=").append(pos.getX())
           .append(" Y=").append(pos.getY())
           .append(" Z=").append(pos.getZ()).append("\n");

        // Health & hunger
        int hp = (int) player.getHealth();
        int hunger = player.getHungerManager().getFoodLevel();
        ctx.append("Player health: ").append(hp).append("/20");
        if (hp <= 4) ctx.append(" ⚠ CRITICAL");
        else if (hp <= 10) ctx.append(" (low)");
        ctx.append("\n");
        ctx.append("Player hunger: ").append(hunger).append("/20");
        if (hunger <= 6) ctx.append(" ⚠ Very hungry");
        ctx.append("\n");

        // Biome
        String biome = world.getBiome(pos).getKey()
            .map(k -> k.getValue().getPath().replace("_", " "))
            .orElse("unknown");
        ctx.append("Biome: ").append(biome).append("\n");
        ctx.append("Daytime: ").append(world.isDay() ? "yes" : "NO — mobs spawning!").append("\n");

        // Nearby hostiles
        Box range = new Box(pos).expand(20);
        List<MobEntity> hostiles = world.getEntitiesByClass(MobEntity.class, range, MobEntity::isHostile);
        if (hostiles.isEmpty()) {
            ctx.append("Nearby mobs: none — safe\n");
        } else {
            ctx.append("⚠ HOSTILE MOBS: ")
               .append(hostiles.stream().map(m -> m.getType().getName().getString()).collect(Collectors.joining(", ")))
               .append("\n");
        }

        // Held item
        ctx.append("Holding: ").append(player.getMainHandStack().getItem().getName().getString()).append("\n");

        // Block below
        ctx.append("Standing on: ").append(world.getBlockState(pos.down()).getBlock().getName().getString()).append("\n");

        // Real players online (exclude fake players)
        List<String> realPlayers = player.getServer().getPlayerManager().getPlayerList().stream()
            .filter(p -> !(p instanceof EntityPlayerMPFake))
            .map(p -> p.getName().getString())
            .collect(Collectors.toList());
        ctx.append("Real players online: ").append(String.join(", ", realPlayers)).append("\n");

        // Friend's state and inventory
        if (FriendMod.friendBot != null) {
            ctx.append("Friend's activity: ").append(FriendMod.friendBot.getCurrentState().name().toLowerCase()).append("\n");
            ctx.append("Friend's inventory: ").append(FriendMod.friendBot.getInventorySummary()).append("\n");
        }

        ctx.append("============================================\n");
        return ctx.toString();
    }
}
