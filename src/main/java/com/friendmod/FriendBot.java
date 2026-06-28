package com.friendmod;

import carpet.patches.EntityPlayerMPFake;
import carpet.helpers.EntityPlayerActionPack;
import carpet.network.ServerPlayerInterface;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.passive.HorseBaseEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.*;
import net.minecraft.world.GameMode;

import java.util.*;

public class FriendBot {

    public enum BotState {
        FOLLOWING, FIGHTING, MINING, BUILDING,
        EXPLORING, SLEEPING, CRAFTING, RIDING, IDLE
    }

    private volatile BotState currentState = BotState.FOLLOWING;

    private final MinecraftServer server;
    private final GeminiClient geminiClient;
    private final FriendNav nav = new FriendNav();
    private final FriendInventory smartInv = new FriendInventory();
    private final FriendCrafting crafting = new FriendCrafting();
    private final Random random = new Random();

    private EntityPlayerMPFake fakePlayer = null;

    private int tickCounter  = 0;
    private int stateTimer   = 0;
    private int respawnDelay = 0;
    private int dangerCooldown = 0;
    private int proactiveCooldown = 200;

    private BlockPos mineTarget = null;
    private int mineBreakTimer = 0;
    private int buildStep = 0;
    private Vec3d exploreTarget = null;
    private volatile String followingPlayerName = null;
    private volatile String pendingCraftRequest = null;
    private volatile String pendingCraftPlayerName = null;

    public FriendBot(MinecraftServer server) {
        this.server = server;
        this.geminiClient = new GeminiClient();
    }

    // Helper to get the action pack from a fake player
    private EntityPlayerActionPack actionPack() {
        return ((ServerPlayerInterface) fakePlayer).getActionPack();
    }

    public void spawn() {
        ServerWorld world = server.getOverworld();
        ServerPlayerEntity nearest = getNearestRealPlayer();
        if (nearest == null) return;

        BlockPos pos = nearest.getBlockPos().add(3, 0, 0);

        boolean ok = EntityPlayerMPFake.createFake(
            FriendConfig.getBotName(),
            server,
            new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
            0.0, 0.0,
            world.getRegistryKey(),
            GameMode.SURVIVAL,
            false
        );

        if (!ok) {
            FriendMod.LOGGER.warn("[FriendMod] createFake returned false — check carpet fakeplayer rules.");
            server.getCommandManager().executeWithPrefix(
                server.getCommandSource(), "carpet fakePlayers true"
            );
            EntityPlayerMPFake.createFake(
                FriendConfig.getBotName(), server,
                new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
                0.0, 0.0, world.getRegistryKey(), GameMode.SURVIVAL, false
            );
        }

        fakePlayer = findFakePlayer();

        if (fakePlayer != null) {
            FriendMod.LOGGER.info("[FriendMod] Friend (fake player) spawned at " + pos.toShortString());
            broadcastMessage("hey, I'm here. say '" + FriendConfig.getBotName() + " hello' if you want to actually talk");
            proactiveCooldown = 200;
        } else {
            FriendMod.LOGGER.error("[FriendMod] Could not find Friend in player list after spawn!");
        }
    }

    public void despawn() {
        if (fakePlayer != null && !fakePlayer.isRemoved()) {
            broadcastMessage("logging off, catch you later");
            fakePlayer.kill(server.getOverworld());
            fakePlayer = null;
        }
    }

    private EntityPlayerMPFake findFakePlayer() {
        String name = FriendConfig.getBotName();
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
        if (p instanceof EntityPlayerMPFake fake) return fake;
        return null;
    }

    public void tick() {
        tickCounter++;
        stateTimer++;

        if (fakePlayer == null || fakePlayer.isRemoved()) {
            fakePlayer = findFakePlayer();
            if (fakePlayer == null) {
                respawnDelay++;
                if (respawnDelay >= 100) {
                    respawnDelay = 0;
                    FriendMod.LOGGER.warn("[FriendMod] Friend gone — respawning...");
                    spawn();
                }
                return;
            }
        }

        if (dangerCooldown > 0) dangerCooldown--;
        if (proactiveCooldown > 0) proactiveCooldown--;

        if (tickCounter % 100 == 0) {
            smartInv.autoEquipArmor(fakePlayer);
            if (currentState == BotState.FIGHTING) smartInv.autoEquipWeapon(fakePlayer);
        }

        if (tickCounter % 20 == 0) smartInv.autoEat(fakePlayer);

        if (currentState != BotState.FIGHTING && dangerCooldown == 0 && tickCounter % 20 == 0) {
            checkForDanger();
        }

        if (proactiveCooldown == 0 && (currentState == BotState.FOLLOWING || currentState == BotState.IDLE)
                && tickCounter % 20 == 0 && random.nextInt(100) < 2) {
            maybeReactToWorld();
        }

        switch (currentState) {
            case FOLLOWING  -> tickFollow();
            case FIGHTING   -> tickFight();
            case MINING     -> tickMine();
            case BUILDING   -> tickBuild();
            case EXPLORING  -> tickExplore();
            case SLEEPING   -> tickSleep();
            case CRAFTING   -> tickCraft();
            case RIDING     -> tickRiding();
            case IDLE       -> tickIdle();
        }
    }

    private void moveToward(Vec3d target, double speed) {
        nav.moveToward(fakePlayer, target, speed, 0.5);
    }

    private void stopMoving() {
        nav.stop(fakePlayer);
    }

    private void maybeReactToWorld() {
        ServerPlayerEntity target = getFollowTarget();
        if (target == null || fakePlayer == null) return;

        String worldCtx = WorldContext.buildContext(target);
        String situation = pickReactionPrompt(target);
        if (situation == null) return;

        proactiveCooldown = 400 + random.nextInt(800);
        String name = target.getName().getString();
        Thread t = new Thread(() -> {
            String line = geminiClient.reactUnprompted(name, worldCtx, situation);
            broadcastMessage(line);
        });
        t.setDaemon(true);
        t.setName("FriendMod-ProactiveReact");
        t.start();
    }

    private String pickReactionPrompt(ServerPlayerEntity target) {
        ServerWorld world = target.getServerWorld();
        long time = world.getTimeOfDay() % 24000;
        int hp = (int) target.getHealth();
        int hunger = target.getHungerManager().getFoodLevel();

        Box range = new Box(fakePlayer.getBlockPos()).expand(15);
        boolean hostilesNear = !world.getEntitiesByClass(MobEntity.class, range,
            m -> (m instanceof HostileEntity) && m != fakePlayer).isEmpty();

        if (hostilesNear) return "You just noticed something hostile nearby that hasn't attacked yet.";
        if (hp <= 8) return "You just noticed the player you're with is at low health.";
        if (hunger <= 6) return "You just noticed the player you're with looks hungry.";
        if (time > 12500 && time < 13500) return "The sun is setting right now and night is coming.";
        if (time > 0 && time < 200) return "It just became morning.";
        if (random.nextInt(3) == 0) return "Nothing urgent is happening — say something small and random, like real friends do during quiet moments in a game.";
        return null;
    }

    private void tickFollow() {
        ServerPlayerEntity target = getFollowTarget();
        if (target == null) return;

        double distSq = fakePlayer.squaredDistanceTo(target);

        if (distSq > 9.0) {
            moveToward(target.getPos(), distSq > 64 ? 0.9 : 0.55);
        } else {
            stopMoving();
            Vec3d diff = target.getEyePos().subtract(fakePlayer.getEyePos());
            float yaw   = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
            float pitch = (float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x*diff.x + diff.z*diff.z)));
            fakePlayer.setYaw(yaw);
            fakePlayer.setPitch(pitch);
            fakePlayer.setHeadYaw(yaw);
        }

        if (tickCounter % 120 == 0) doIdleAnimation();
    }

    private void tickFight() {
        if (fakePlayer == null) return;
        ServerWorld world = fakePlayer.getServerWorld();
        Box range = new Box(fakePlayer.getBlockPos()).expand(20);

        List<MobEntity> hostiles = world.getEntitiesByClass(
            MobEntity.class, range,
            mob -> (mob instanceof HostileEntity) && mob != fakePlayer
        );

        if (hostiles.isEmpty()) {
            broadcastMessage("alright that's everything, heading back");
            currentState = BotState.FOLLOWING;
            stateTimer = 0;
            dangerCooldown = 200;
            return;
        }

        MobEntity target = hostiles.stream()
            .min(Comparator.comparingDouble(m -> m.squaredDistanceTo(fakePlayer)))
            .orElse(null);

        if (target != null && !target.isRemoved()) {
            double distSq = fakePlayer.squaredDistanceTo(target);
            if (distSq > 9.0) {
                moveToward(target.getPos(), 0.9);
            } else {
                stopMoving();
                Vec3d diff = target.getEyePos().subtract(fakePlayer.getEyePos());
                fakePlayer.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
                actionPack().start(
                    EntityPlayerActionPack.ActionType.ATTACK,
                    EntityPlayerActionPack.Action.once()
                );
            }
        }

        if (stateTimer > 1200) {
            broadcastMessage("ok I need a breather, that's enough fighting for now");
            currentState = BotState.FOLLOWING;
            stateTimer = 0;
            dangerCooldown = 100;
        }
    }

    private void tickMine() {
        if (fakePlayer == null) return;

        if (mineTarget == null) {
            mineTarget = findNearestOre(20);
            if (mineTarget == null) {
                broadcastMessage("nothing close by, we'd have to go deeper for ore");
                currentState = BotState.FOLLOWING;
                stateTimer = 0;
                return;
            }
            broadcastMessage("got something at " + mineTarget.toShortString() + ", going for it");
            mineBreakTimer = 0;
            smartInv.autoEquipToolFor(fakePlayer, fakePlayer.getServerWorld().getBlockState(mineTarget));
        }

        double distSq = fakePlayer.getBlockPos().getSquaredDistance(mineTarget);
        if (distSq > 9.0) {
            moveToward(Vec3d.ofCenter(mineTarget), 0.7);
        } else {
            stopMoving();
            Vec3d diff = Vec3d.ofCenter(mineTarget).subtract(fakePlayer.getEyePos());
            fakePlayer.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
            fakePlayer.setPitch((float) Math.toDegrees(-Math.atan2(diff.y, Math.sqrt(diff.x*diff.x+diff.z*diff.z))));

            actionPack().start(
                EntityPlayerActionPack.ActionType.ATTACK,
                EntityPlayerActionPack.Action.continuous()
            );
            mineBreakTimer++;

            ServerWorld world = fakePlayer.getServerWorld();
            if (world.getBlockState(mineTarget).isAir() || mineBreakTimer > 80) {
                actionPack().start(
                    EntityPlayerActionPack.ActionType.ATTACK,
                    EntityPlayerActionPack.Action.once()
                );
                String oreName = mineBreakTimer <= 80
                    ? world.getBlockState(mineTarget).getBlock().getName().getString()
                    : "ore";
                broadcastMessage("nice, got some " + oreName);
                mineTarget = null;
                mineBreakTimer = 0;
            }
        }

        if (stateTimer > 1800) {
            actionPack().stop(EntityPlayerActionPack.ActionType.ATTACK);
            broadcastMessage("that's enough mining for now, found some decent stuff though");
            currentState = BotState.FOLLOWING;
            mineTarget = null;
            stateTimer = 0;
        }
    }

    private void tickBuild() {
        ServerPlayerEntity player = getNearestRealPlayer();
        if (player == null) { currentState = BotState.FOLLOWING; return; }
        if (fakePlayer == null) return;

        ServerWorld world = fakePlayer.getServerWorld();
        BlockPos base = player.getBlockPos().add(6, 0, 0);

        int[][] layout = {
            {0,0,0},{1,0,0},{2,0,0},{0,0,1},{2,0,1},{0,0,2},{1,0,2},{2,0,2},
            {2,1,0},{0,1,0},{0,1,2},{2,1,2},{1,1,0},{1,1,2},{2,1,1},
            {2,2,0},{0,2,0},{0,2,2},{2,2,2},{1,2,0},{1,2,2},{2,2,1},{0,2,1},
            {0,3,0},{1,3,0},{2,3,0},{0,3,1},{1,3,1},{2,3,1},{0,3,2},{1,3,2},{2,3,2}
        };

        if (buildStep >= layout.length) {
            broadcastMessage("there, that should do it for shelter");
            currentState = BotState.FOLLOWING;
            buildStep = 0;
            stateTimer = 0;
            return;
        }

        if (tickCounter % 10 == 0) {
            int[] off = layout[buildStep];
            BlockPos placePos = base.add(off[0], off[1], off[2]);

            if (world.getBlockState(placePos).isAir()) {
                moveToward(Vec3d.ofCenter(placePos).subtract(0, 1, 0), 0.5);

                fakePlayer.getInventory().setStack(fakePlayer.getInventory().selectedSlot,
                    new ItemStack(Items.OAK_PLANKS, 64));
                actionPack().start(
                    EntityPlayerActionPack.ActionType.USE,
                    EntityPlayerActionPack.Action.once()
                );
                world.setBlockState(placePos, Blocks.OAK_PLANKS.getDefaultState());
            }
            buildStep++;

            int total = layout.length;
            if (buildStep == total/4)   broadcastMessage("25% in, still going");
            if (buildStep == total/2)   broadcastMessage("halfway there");
            if (buildStep == 3*total/4) broadcastMessage("almost done");
        }

        if (stateTimer > 2400) {
            broadcastMessage("that's as far as I got, still usable though");
            currentState = BotState.FOLLOWING;
            buildStep = 0;
            stateTimer = 0;
        }
    }

    private void tickExplore() {
        if (fakePlayer == null) { currentState = BotState.FOLLOWING; return; }

        if (exploreTarget == null) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist  = 40 + random.nextDouble() * 40;
            exploreTarget = fakePlayer.getPos().add(
                Math.cos(angle) * dist, 0, Math.sin(angle) * dist
            );
            broadcastMessage("gonna go scout around a bit, be back soon");
        }

        moveToward(exploreTarget, 0.75);

        if (stateTimer > 400) {
            broadcastMessage("back. terrain out there's kind of interesting actually");
            currentState = BotState.FOLLOWING;
            exploreTarget = null;
            stateTimer = 0;
        }
    }

    private void tickSleep() {
        if (fakePlayer == null) { currentState = BotState.FOLLOWING; return; }
        ServerWorld world = fakePlayer.getServerWorld();

        if (stateTimer == 1) {
            BlockPos bedPos = fakePlayer.getBlockPos().add(1, 0, 0);
            if (world.getBlockState(bedPos).isAir()) {
                world.setBlockState(bedPos, Blocks.RED_BED.getDefaultState());
            }
            moveToward(Vec3d.ofCenter(bedPos), 0.7);
            broadcastMessage("putting down a bed, let's skip this night");
        }

        if (stateTimer == 20) {
            actionPack().start(
                EntityPlayerActionPack.ActionType.USE,
                EntityPlayerActionPack.Action.once()
            );
        }

        stopMoving();

        if (stateTimer > 120) {
            broadcastMessage("morning. let's go");
            currentState = BotState.FOLLOWING;
            stateTimer = 0;
        }
    }

    private void tickCraft() {
        if (fakePlayer == null) { currentState = BotState.FOLLOWING; return; }
        stopMoving();

        if (stateTimer == 1) broadcastMessage("let's see what I can put together");

        if (stateTimer == 40) {
            String request = pendingCraftRequest != null ? pendingCraftRequest : "sticks";
            FriendCrafting.CraftResult result = crafting.craft(fakePlayer, request);

            if (result.success) {
                String itemName = result.crafted.getItem().getName().getString().toLowerCase();
                broadcastMessage("made a " + itemName + ", here you go");
            } else {
                broadcastMessage("couldn't make that — " + result.message);
            }

            pendingCraftRequest = null;
            pendingCraftPlayerName = null;
            currentState = BotState.FOLLOWING;
            stateTimer = 0;
        }
    }

    private void tickRiding() {
        if (fakePlayer == null || !fakePlayer.hasVehicle()) {
            currentState = BotState.FOLLOWING;
            stateTimer = 0;
            return;
        }
        if (stateTimer > 6000) {
            broadcastMessage("ok I've been sitting here a while, getting off");
            fakePlayer.stopRiding();
            currentState = BotState.FOLLOWING;
            stateTimer = 0;
        }
    }

    private void tickIdle() {
        if (tickCounter % 80 == 0) doIdleAnimation();
        if (stateTimer > 200) { currentState = BotState.FOLLOWING; stateTimer = 0; }
    }

    private void mountNearestVehicle() {
        if (fakePlayer == null) return;
        ServerWorld world = fakePlayer.getServerWorld();
        Box range = new Box(fakePlayer.getBlockPos()).expand(8);

        Entity nearest = world.getEntitiesByClass(Entity.class, range, e ->
            (e instanceof BoatEntity || e instanceof HorseBaseEntity || e instanceof AbstractMinecartEntity)
            && e.getPassengerList().isEmpty()
        ).stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(fakePlayer))).orElse(null);

        if (nearest == null) {
            broadcastMessage("don't see anything close by to ride");
            return;
        }

        double distSq = fakePlayer.squaredDistanceTo(nearest);
        if (distSq > 9.0) {
            moveToward(nearest.getPos(), 0.8);
        }
        if (distSq <= 4.0) {
            fakePlayer.startRiding(nearest, true);
            currentState = BotState.RIDING;
            stateTimer = 0;
        }
    }

    private void giveItemTo(ServerPlayerEntity recipient, String requestedText) {
        if (fakePlayer == null || recipient == null) return;
        var inv = fakePlayer.getInventory();

        String lower = requestedText.toLowerCase();
        int foundSlot = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String name = stack.getItem().getName().getString().toLowerCase();
            if (lower.contains(name) || name.contains(extractKeyword(lower))) {
                foundSlot = i;
                break;
            }
        }
        if (foundSlot == -1) {
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty()) { foundSlot = i; break; }
            }
        }

        if (foundSlot == -1) {
            broadcastMessage("I've got nothing to give you right now");
            return;
        }

        ItemStack stack = inv.getStack(foundSlot);
        ItemStack giveStack = stack.split(Math.min(stack.getCount(), 1));
        recipient.getInventory().insertStack(giveStack);
        if (!giveStack.isEmpty() && giveStack.getCount() > 0) {
            recipient.dropItem(giveStack, false);
        }
    }

    private String extractKeyword(String text) {
        String[] words = text.split("\\s+");
        return words.length > 0 ? words[words.length - 1] : "";
    }

    private void openNearestInteractable() {
        if (fakePlayer == null) return;
        ServerWorld world = fakePlayer.getServerWorld();
        BlockPos center = fakePlayer.getBlockPos();

        for (int r = 1; r <= 4; r++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    for (int y = -1; y <= 1; y++) {
                        BlockPos check = center.add(x, y, z);
                        var state = world.getBlockState(check);
                        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)
                                || state.isOf(Blocks.FURNACE) || nav.isDoorOrGate(state)) {
                            moveToward(Vec3d.ofCenter(check), 0.6);
                            if (fakePlayer.squaredDistanceTo(Vec3d.ofCenter(check)) <= 4.0) {
                                if (nav.isDoorOrGate(state)) {
                                    nav.openDoorOrGate(fakePlayer, world, check, state);
                                } else {
                                    actionPack().start(
                                        EntityPlayerActionPack.ActionType.USE,
                                        EntityPlayerActionPack.Action.once()
                                    );
                                }
                            }
                            return;
                        }
                    }
                }
            }
        }
        broadcastMessage("don't see a chest or door close enough to open");
    }

    public void doEmote(String type) {
        if (fakePlayer == null) return;
        switch (type.toLowerCase()) {
            case "dance" -> {
                fakePlayer.jump(); fakePlayer.jump();
                broadcastMessage("*does a little victory dance*");
            }
            case "wave"  -> broadcastMessage("*waves*");
            case "bow"   -> broadcastMessage("*takes a bow*");
            case "spin"  -> {
                fakePlayer.setYaw(fakePlayer.getYaw() + 360f);
                broadcastMessage("*spins around*");
            }
            default      -> broadcastMessage("*strikes a pose*");
        }
    }

    private void checkForDanger() {
        if (fakePlayer == null) return;
        ServerWorld world = fakePlayer.getServerWorld();
        Box range = new Box(fakePlayer.getBlockPos()).expand(10);

        boolean danger = !world.getEntitiesByClass(
            MobEntity.class, range,
            mob -> (mob instanceof HostileEntity) && mob != fakePlayer
        ).isEmpty();

        if (danger) {
            broadcastMessage("hold up, mobs incoming — I've got it");
            currentState = BotState.FIGHTING;
            stateTimer = 0;
            dangerCooldown = 200;
        }
    }

    public void handlePlayerMessage(ServerPlayerEntity player, String userText) {
        String playerName = player.getName().getString();
        CommandParser.CommandType cmd = CommandParser.parse(userText);
        String worldCtx = WorldContext.buildContext(player);

        switch (cmd) {
            case FOLLOW -> {
                server.execute(() -> { currentState = BotState.FOLLOWING; followingPlayerName = playerName; stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally to agreeing to follow them, as yourself, 1 sentence.]"));
            }
            case COME -> {
                server.execute(() -> { if (fakePlayer != null) moveToward(player.getPos(), 1.0); followingPlayerName = playerName; currentState = BotState.FOLLOWING; stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, "Player wants you to come over right now. React naturally as yourself, 1 sentence."));
            }
            case STOP -> {
                server.execute(() -> { currentState = BotState.IDLE; stopMoving(); stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally to stopping, as yourself, 1 sentence.]"));
            }
            case FIGHT -> {
                server.execute(() -> { currentState = BotState.FIGHTING; stateTimer = 0; smartInv.autoEquipWeapon(fakePlayer); });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about getting into a fight, as yourself, 1 sentence.]"));
            }
            case MINE -> {
                server.execute(() -> { currentState = BotState.MINING; mineTarget = null; stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about going mining, as yourself, 1 sentence.]"));
            }
            case BUILD -> {
                server.execute(() -> { currentState = BotState.BUILDING; buildStep = 0; stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about building a shelter, as yourself, 1 sentence.]"));
            }
            case EXPLORE -> {
                server.execute(() -> { currentState = BotState.EXPLORING; exploreTarget = null; stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about going to explore, as yourself, 1 sentence.]"));
            }
            case SLEEP -> {
                server.execute(() -> { currentState = BotState.SLEEPING; stateTimer = 0; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about placing a bed and sleeping, as yourself, 1 sentence.]"));
            }
            case CRAFT -> {
                server.execute(() -> { currentState = BotState.CRAFTING; stateTimer = 0; pendingCraftRequest = userText; pendingCraftPlayerName = playerName; });
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about crafting something, as yourself. Your items: " + smartInv.summarize(fakePlayer) + "]"));
            }
            case INVENTORY -> reply(player, geminiClient.chat(playerName, worldCtx,
                "Player asked what you're carrying. Your items: " + smartInv.summarize(fakePlayer) + ". Describe it casually as yourself, 1-2 sentences."));
            case EMOTE -> {
                String e = extractEmote(userText);
                server.execute(() -> doEmote(e));
                reply(player, geminiClient.chat(playerName, worldCtx, "You just did the emote: " + e + ". React naturally as yourself, 1 sentence."));
            }
            case RIDE -> {
                server.execute(this::mountNearestVehicle);
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about hopping on a ride, as yourself, 1 sentence.]"));
            }
            case DISMOUNT -> {
                server.execute(() -> {
                    if (fakePlayer != null) fakePlayer.stopRiding();
                    currentState = BotState.FOLLOWING;
                    stateTimer = 0;
                });
                reply(player, geminiClient.chat(playerName, worldCtx, "You just got off whatever you were riding. React naturally as yourself, 1 sentence."));
            }
            case GIVE -> {
                server.execute(() -> giveItemTo(player, userText));
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about handing something over, as yourself, 1 sentence.]"));
            }
            case OPEN_CONTAINER -> {
                server.execute(this::openNearestInteractable);
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about opening something nearby, as yourself, 1 sentence.]"));
            }
            case EAT -> {
                server.execute(() -> smartInv.autoEat(fakePlayer));
                reply(player, geminiClient.chat(playerName, worldCtx, userText + " [React naturally about grabbing a bite, as yourself, 1 sentence.]"));
            }
            case HELP -> reply(player,
                "here's what I can actually respond to: follow, stop, come here, fight, mine, build, " +
                "explore, sleep, craft, inventory, dance/wave/bow/spin, ride/get off, give me [item], " +
                "open the chest/door, status. or just talk to me normally.");
            case FORGET -> { geminiClient.clearMemory(playerName); reply(player, "ok, clean slate. what's up?"); }
            case STATUS -> reply(player, geminiClient.chat(playerName, worldCtx,
                "Player asked what you're doing. You are currently: " + currentState.name().toLowerCase() + ". Answer casually as yourself, 1 sentence."));
            case CHAT -> reply(player, geminiClient.chat(playerName, worldCtx, userText));
        }
    }

    private void reply(ServerPlayerEntity player, String msg) {
        String prefix = "§d[" + FriendConfig.getBotName() + "] §f";
        String full = msg.startsWith("§") ? msg : prefix + msg;
        server.execute(() -> player.sendMessage(Text.literal(full), false));
    }

    public void broadcastMessage(String msg) {
        String prefix = "§d[" + FriendConfig.getBotName() + "] §f";
        String full = msg.startsWith("§") ? msg : prefix + msg;
        server.execute(() -> server.getPlayerManager().broadcast(Text.literal(full), false));
    }

    private ServerPlayerEntity getNearestRealPlayer() {
        String botName = FriendConfig.getBotName();
        return server.getPlayerManager().getPlayerList().stream()
            .filter(p -> !p.getName().getString().equals(botName))
            .filter(p -> !(p instanceof EntityPlayerMPFake))
            .min(Comparator.comparingDouble(p -> fakePlayer != null
                ? p.squaredDistanceTo(fakePlayer) : 0))
            .orElse(null);
    }

    private ServerPlayerEntity getFollowTarget() {
        if (followingPlayerName != null) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(followingPlayerName);
            if (p != null && !(p instanceof EntityPlayerMPFake)) return p;
        }
        return getNearestRealPlayer();
    }

    private BlockPos findNearestOre(int maxRadius) {
        if (fakePlayer == null) return null;
        ServerWorld world = fakePlayer.getServerWorld();
        BlockPos center = fakePlayer.getBlockPos();
        for (int r = 1; r <= maxRadius; r++) {
            for (int y = -r; y <= r; y++) {
                for (int x = -r; x <= r; x++) {
                    for (int z = -r; z <= r; z++) {
                        if (Math.abs(x) != r && Math.abs(y) != r && Math.abs(z) != r) continue;
                        BlockPos check = center.add(x, y, z);
                        if (world.getBlockState(check).getBlock().getTranslationKey().contains("ore"))
                            return check;
                    }
                }
            }
        }
        return null;
    }

    public String getInventorySummary() {
        return smartInv.summarize(fakePlayer);
    }

    private String extractEmote(String text) {
        String t = text.toLowerCase();
        if (t.contains("dance")) return "dance";
        if (t.contains("wave"))  return "wave";
        if (t.contains("bow"))   return "bow";
        if (t.contains("spin"))  return "spin";
        return "pose";
    }

    private void doIdleAnimation() {
        if (fakePlayer == null) return;
        switch (random.nextInt(4)) {
            case 0 -> fakePlayer.jump();
            case 1 -> fakePlayer.setYaw(random.nextFloat() * 360f - 180f);
            case 2 -> {
                ServerPlayerEntity p = getNearestRealPlayer();
                if (p != null) {
                    Vec3d diff = p.getEyePos().subtract(fakePlayer.getEyePos());
                    fakePlayer.setYaw((float) Math.toDegrees(Math.atan2(-diff.x, diff.z)));
                    fakePlayer.setHeadYaw(fakePlayer.getYaw());
                }
            }
            case 3 -> stopMoving();
        }
    }

    public BotState getCurrentState()         { return currentState; }
    public EntityPlayerMPFake getFakePlayer() { return fakePlayer; }
    public GeminiClient getGeminiClient()     { return geminiClient; }
}
