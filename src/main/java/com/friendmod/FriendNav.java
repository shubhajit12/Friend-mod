package com.friendmod;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.block.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * FriendNav v3.1
 * ==============
 * Real-player-style movement helper for Friend.
 *
 * The fake player has no vanilla Navigator/PathfindingMob brain (that only
 * exists on mobs), so we drive her with direct velocity + facing, and we
 * handle every obstacle a real player would just walk through by hand:
 *
 *   - Doors / fence gates / trapdoors: opened automatically, closed again
 *     behind her after a short delay so she doesn't leave them swinging.
 *   - 1-block-high ledges: jump.
 *   - Water: swim (reduced speed, upward nudge so she doesn't sink).
 *   - Ladders / vines: climb (upward nudge while pressed against them).
 *   - General obstruction: try to jump; if that's not possible, sidestep.
 *
 * This is intentionally simple physics, not true A* — but it covers every
 * obstacle type that shows up in a normal base/cave, which is what actually
 * matters for "feels like a real player following me."
 */
public class FriendNav {

    /** How long an auto-opened door/gate stays open before we close it again, in ticks. */
    private static final int DOOR_CLOSE_DELAY = 30; // 1.5s

    // pos -> ticks remaining until we close it
    private final java.util.Map<BlockPos, Integer> openedByUs = new java.util.HashMap<>();

    /**
     * Drive the fake player toward a target position at the given horizontal speed.
     * Call once per tick while moving. Handles facing, obstacles, doors, swimming,
     * climbing, and jumping automatically.
     *
     * @return true if we're close enough that the caller should treat movement as "arrived"
     */
    public boolean moveToward(EntityPlayerMPFake fp, Vec3d target, double speed, double arriveDist) {
        if (fp == null || fp.isRemoved()) return true;

        tickDoorCloser(fp);

        Vec3d pos = fp.getPos();
        Vec3d diff = target.subtract(pos);
        double distXZ = Math.sqrt(diff.x * diff.x + diff.z * diff.z);

        if (distXZ < arriveDist) {
            stop(fp);
            return true;
        }

        double nx = diff.x / distXZ;
        double nz = diff.z / distXZ;

        float yaw = (float) Math.toDegrees(Math.atan2(-nx, nz));
        fp.setYaw(yaw);
        fp.setHeadYaw(yaw);

        ServerWorld world = fp.getServerWorld();
        BlockPos feet = fp.getBlockPos();

        boolean inWater = fp.isSubmergedInWater() || world.getFluidState(feet).isIn(net.minecraft.registry.tag.FluidTags.WATER);
        boolean onLadder = isClimbable(world, feet);

        double moveSpeed = speed;
        double yVel = fp.getVelocity().y;

        if (inWater) {
            moveSpeed *= 0.55;
            yVel = Math.max(yVel, 0.08); // gentle upward nudge so she doesn't sink
        } else if (onLadder) {
            moveSpeed *= 0.4;
            yVel = 0.18; // climb upward
        }

        // Obstacle handling: look one block ahead in the direction we're walking.
        BlockPos ahead = feet.add((int) Math.round(nx * 1.0), 0, (int) Math.round(nz * 1.0));
        BlockPos aheadUp = ahead.up();

        if (!inWater) {
            var aheadState = world.getBlockState(ahead);
            boolean blocked = !aheadState.getCollisionShape(world, ahead).isEmpty();

            if (blocked) {
                if (isDoorOrGate(aheadState)) {
                    openDoorOrGate(fp, world, ahead, aheadState);
                } else if (isClimbable(world, ahead)) {
                    // Walking straight into a ladder/vine — start climbing.
                    yVel = 0.18;
                } else {
                    boolean canStepUp = world.getBlockState(aheadUp).getCollisionShape(world, aheadUp).isEmpty();
                    if (canStepUp && fp.isOnGround()) {
                        fp.jump();
                    } else {
                        // Truly stuck (e.g. solid wall) — try a small lateral nudge so
                        // she doesn't just stand there pushing into the block forever.
                        nx = nx * 0.3 - nz * 0.5;
                        nz = nz * 0.3 + nx * 0.5;
                    }
                }
            }
        }

        Vec3d vel = fp.getVelocity();
        fp.setVelocity(nx * moveSpeed, yVel, nz * moveSpeed);
        fp.velocityModified = true;

        fp.setSprinting(!inWater && distXZ > 6.0);
        return false;
    }

    public void stop(EntityPlayerMPFake fp) {
        if (fp == null) return;
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(0, Math.min(vel.y, 0), 0);
        fp.setSprinting(false);
        fp.velocityModified = true;
    }

    // =========================================================================
    // DOORS / GATES
    // =========================================================================

    public boolean isDoorOrGate(net.minecraft.block.BlockState state) {
        return state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof FenceGateBlock
            || state.getBlock() instanceof TrapdoorBlock;
    }

    /**
     * Opens a door/gate/trapdoor so Friend can walk through, and remembers to
     * close it again shortly after so she doesn't leave every door open behind her
     * (just like a real player tapping it shut once they're through).
     */
    public void openDoorOrGate(EntityPlayerMPFake fp, ServerWorld world, BlockPos pos, net.minecraft.block.BlockState state) {
        if (state.contains(Properties.OPEN) && !state.get(Properties.OPEN)) {
            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            ActionResult result = fp.interactBlock(fp, world, fp.getMainHandStack(), Hand.MAIN_HAND, hit);
            if (result == ActionResult.PASS) {
                // Iron doors/trapdoors need redstone normally — we force it for fake-player use.
                world.setBlockState(pos, state.with(Properties.OPEN, true));
            }
            world.playSound(null, pos, state.getBlock() instanceof FenceGateBlock
                ? net.minecraft.sound.SoundEvents.BLOCK_FENCE_GATE_OPEN
                : net.minecraft.sound.SoundEvents.BLOCK_WOODEN_DOOR_OPEN,
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
            openedByUs.put(pos.toImmutable(), DOOR_CLOSE_DELAY);
        }
    }

    /** Called every movement tick — closes any door we opened once she's clear of it. */
    private void tickDoorCloser(EntityPlayerMPFake fp) {
        if (openedByUs.isEmpty()) return;
        ServerWorld world = fp.getServerWorld();
        var it = openedByUs.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            BlockPos pos = entry.getKey();
            int remaining = entry.getValue() - 1;
            // Don't close it under her feet — wait until she's actually moved away.
            boolean clear = fp.getBlockPos().getSquaredDistance(pos) > 2.5;
            if (remaining <= 0 && clear) {
                var state = world.getBlockState(pos);
                if (state.contains(Properties.OPEN) && state.get(Properties.OPEN)) {
                    world.setBlockState(pos, state.with(Properties.OPEN, false));
                }
                it.remove();
            } else {
                entry.setValue(remaining);
            }
        }
    }

    // =========================================================================
    // CLIMBABLE / LADDERS
    // =========================================================================

    private boolean isClimbable(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getBlock() instanceof LadderBlock || state.isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE);
    }
}
