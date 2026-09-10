package de.tomalbrc.toms_mobs.test;

import com.mojang.authlib.GameProfile;
import de.tomalbrc.bil.api.AnimatedEntity;
import de.tomalbrc.bil.core.element.CollisionElement;
import de.tomalbrc.toms_mobs.entity.goal.AnimatedMeleeAttackGoal;
import de.tomalbrc.toms_mobs.entity.hostile.Sculkling;
import de.tomalbrc.toms_mobs.registry.MobRegistry;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class TomsMobsGameTests {
    private static final List<EntityType<? extends Mob>> FLYERS = List.of(MobRegistry.BUTTERFLY, MobRegistry.EMPEROR_BUTTERFLY, MobRegistry.FIREMOTH);

    // The Borukva Main crash: the sculkling's target died mid-attack, so getTarget() went null while the goal kept ticking.
    @GameTest
    public void meleeGoalSurvivesTargetDying(GameTestHelper helper) {
        Sculkling sculkling = helper.spawnWithNoFreeWill(MobRegistry.SCULKLING, 1, 1, 1);
        Mob villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, 2, 1, 1);
        AnimatedMeleeAttackGoal goal = new AnimatedMeleeAttackGoal(sculkling, 0.55, true);
        sculkling.setTarget(villager);
        goal.start();

        villager.kill(helper.getLevel());
        helper.assertTrue(sculkling.getTarget() == null, "a dead target should read as no target");
        for (int i = 0; i < 40; i++) {
            goal.tick();
        }
        helper.succeed();
    }

    @GameTest
    public void meleeGoalSurvivesTargetGoingCreative(GameTestHelper helper) {
        Sculkling sculkling = helper.spawnWithNoFreeWill(MobRegistry.SCULKLING, 1, 1, 1);
        // the helper's mock players are pinned to creative, and the test server defaults to creative too
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), new GameProfile(UUID.randomUUID(), "target"), ClientInformation.createDefault());
        capturingConnection(helper, player, new ArrayList<>());
        player.setGameMode(GameType.SURVIVAL);
        AnimatedMeleeAttackGoal goal = new AnimatedMeleeAttackGoal(sculkling, 0.55, true);
        sculkling.setTarget(player);
        helper.assertTrue(sculkling.getTarget() == player, "a survival player should be a valid target (" + player.gameMode() + ", " + helper.getLevel().getDifficulty() + ")");
        goal.start();

        player.setGameMode(GameType.CREATIVE);
        helper.assertTrue(sculkling.getTarget() == null, "a creative player should read as no target");
        for (int i = 0; i < 40; i++) {
            goal.tick();
        }
        helper.succeed();
    }

    // Same scenario through the real AI loop. A sculkling only trips when its target dies right before a tick where
    // the goal runs unchecked (every other tick) outside the swing animation (about half the time). Sculklings spawned
    // together share that tick phase, so the targets die one at a time, each once its own sculkling is swinging.
    @GameTest(maxTicks = 600)
    public void sculklingsSurviveTargetsDyingMidFight(GameTestHelper helper) {
        floor(helper);
        List<Sculkling> sculklings = new ArrayList<>();
        List<Mob> targets = new ArrayList<>();
        for (int z = 0; z < 8; z++) {
            for (int x : new int[]{0, 4}) {
                Sculkling sculkling = helper.spawn(MobRegistry.SCULKLING, x, 1, z);
                Mob target = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, x + 1, 1, z);
                sculkling.setTarget(target);
                sculklings.add(sculkling);
                targets.add(target);
            }
        }

        // MeleeAttackGoal won't start in the first 20 game ticks and wandering interrupts it now and then
        var sequence = helper.startSequence();
        for (int i = 0; i < targets.size(); i++) {
            Sculkling sculkling = sculklings.get(i);
            Mob target = targets.get(i);
            sequence = sequence
                    .thenWaitUntil(() -> helper.assertTrue(sculkling.isAggressive(), "sculkling is not attacking"))
                    .thenExecute(() -> target.kill(helper.getLevel()))
                    .thenIdle(1);
        }
        sequence.thenExecuteAfter(20, () -> helper.assertTrue(sculklings.stream().allMatch(Entity::isAlive), "a sculkling went missing"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 100)
    public void boatsDoNotPickUpFlyers(GameTestHelper helper) {
        floor(helper);
        List<Mob> flyers = new ArrayList<>();
        for (int i = 0; i < FLYERS.size(); i++) {
            Vec3 pos = new Vec3(1.5 + 2 * i, 1, 1.5);
            helper.spawn(EntityTypes.OAK_BOAT, pos);
            flyers.add(helper.spawnWithNoFreeWill(FLYERS.get(i), pos));
        }

        helper.runAfterDelay(20, () -> {
            List<String> seated = flyers.stream().filter(Entity::isPassenger).map(TomsMobsGameTests::name).toList();
            helper.assertTrue(seated.isEmpty(), "picked up by a boat: " + seated);
            helper.succeed();
        });
    }

    // A moving minecart picks up whatever it could push, so it has to roll right through the flyers.
    @GameTest(maxTicks = 100)
    public void minecartsDoNotPickUpFlyers(GameTestHelper helper) {
        floor(helper);
        List<Mob> flyers = new ArrayList<>();
        for (int i = 0; i < FLYERS.size(); i++) {
            int z = 1 + 2 * i;
            for (int x = 0; x < 8; x++) {
                helper.setBlock(x, 1, z, Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, RailShape.EAST_WEST));
            }
            Entity minecart = helper.spawn(EntityTypes.MINECART, new Vec3(0.5, 1, z + 0.5));
            minecart.setDeltaMovement(0.3, 0, 0);
            flyers.add(helper.spawnWithNoFreeWill(FLYERS.get(i), new Vec3(4.5, 1, z + 0.5)));
        }

        helper.runAfterDelay(40, () -> {
            List<String> seated = flyers.stream().filter(Entity::isPassenger).map(TomsMobsGameTests::name).toList();
            helper.assertTrue(seated.isEmpty(), "picked up by a minecart: " + seated);
            helper.succeed();
        });
    }

    // Vehicles saved in the world before the fix still carry flyers as passengers.
    @GameTest(maxTicks = 100)
    public void flyersAlreadyInBoatsGetOut(GameTestHelper helper) {
        flyersGetOutOf(helper, EntityTypes.OAK_BOAT);
    }

    @GameTest(maxTicks = 100)
    public void flyersAlreadyInMinecartsGetOut(GameTestHelper helper) {
        flyersGetOutOf(helper, EntityTypes.MINECART);
    }

    private static void flyersGetOutOf(GameTestHelper helper, EntityType<?> vehicleType) {
        floor(helper);
        List<Mob> flyers = new ArrayList<>();
        for (int i = 0; i < FLYERS.size(); i++) {
            Vec3 pos = new Vec3(1.5 + 2 * i, 1, 4.5);
            Entity vehicle = helper.spawn(vehicleType, pos);
            Mob flyer = helper.spawnWithNoFreeWill(FLYERS.get(i), pos);
            helper.assertTrue(flyer.startRiding(vehicle, true, true), "could not seat " + name(flyer));
            flyers.add(flyer);
        }

        helper.runAfterDelay(5, () -> {
            List<String> seated = flyers.stream().filter(Entity::isPassenger).map(TomsMobsGameTests::name).toList();
            helper.assertTrue(seated.isEmpty(), "still sitting in " + vehicleType.getDescription().getString() + ": " + seated);
            helper.succeed();
        });
    }

    // NoAI pigs never move on their own and skip travel(), so any push shows up in their delta movement.
    @GameTest(maxTicks = 100)
    public void flyersNeitherPushNorGetPushed(GameTestHelper helper) {
        List<Mob> flyers = new ArrayList<>();
        List<Mob> pigs = new ArrayList<>();
        for (int i = 0; i < FLYERS.size(); i++) {
            flyers.add(helper.spawnWithNoFreeWill(FLYERS.get(i), new Vec3(1.5 + 2 * i, 1, 1.5)));
            pigs.add(helper.spawnWithNoFreeWill(EntityTypes.PIG, new Vec3(1.6 + 2 * i, 1, 1.5)));
        }

        helper.runAfterDelay(10, () -> {
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < flyers.size(); i++) {
                if (EntitySelector.pushableBy(pigs.get(i)).test(flyers.get(i))) {
                    problems.add(name(flyers.get(i)) + " can be pushed");
                }
                if (pigs.get(i).getDeltaMovement().horizontalDistanceSqr() > 0) {
                    problems.add("pig next to " + name(flyers.get(i)) + " got pushed");
                }
            }
            helper.assertTrue(problems.isEmpty(), String.join("; ", problems));
            helper.succeed();
        });
    }

    // The client pushes the local player out of BIL's invisible collision slime; a NEVER-collision team stops that.
    @GameTest
    public void flyerCollisionSlimesJoinANoCollisionTeam(GameTestHelper helper) {
        List<Packet<?>> sent = new ArrayList<>();
        ServerGamePacketListenerImpl connection = capturingConnection(helper, helper.makeMockServerPlayerInLevel(), sent);

        List<String> colliding = new ArrayList<>();
        for (int i = 0; i < FLYERS.size(); i++) {
            Mob flyer = helper.spawnWithNoFreeWill(FLYERS.get(i), new Vec3(1.5 + 2 * i, 1, 1.5));
            ElementHolder holder = holder(flyer);
            holder.startWatching(connection);
            if (!inNoCollisionTeam(sent, collisionSlime(holder))) {
                colliding.add(name(flyer));
            }
        }

        ElementHolder sculkling = holder(helper.spawnWithNoFreeWill(MobRegistry.SCULKLING, 1, 1, 5));
        sculkling.startWatching(connection);
        helper.assertTrue(!inNoCollisionTeam(sent, collisionSlime(sculkling)), "the sculkling's slime should keep colliding");
        helper.assertTrue(colliding.isEmpty(), "collision slime not in a no-collision team: " + colliding);
        helper.succeed();
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                helper.setBlock(x, 0, z, Blocks.STONE);
            }
        }
    }

    private static String name(Entity entity) {
        return entity.getName().getString();
    }

    private static ElementHolder holder(Mob mob) {
        return (ElementHolder) ((AnimatedEntity) mob).getHolder();
    }

    private static UUID collisionSlime(ElementHolder holder) {
        return holder.getElements().stream()
                .filter(CollisionElement.class::isInstance)
                .map(element -> ((CollisionElement) element).getUuid())
                .findFirst()
                .orElseThrow();
    }

    // Becomes the player's connection; everything the server sends lands in the sink, bundles unpacked.
    private static ServerGamePacketListenerImpl capturingConnection(GameTestHelper helper, ServerPlayer player, List<Packet<?>> sink) {
        return new ServerGamePacketListenerImpl(helper.getLevel().getServer(), new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override
            public void send(Packet<?> packet) {
                record(packet, sink);
            }

            @Override
            public void send(Packet<?> packet, @Nullable ChannelFutureListener listener) {
                record(packet, sink);
            }
        };
    }

    private static void record(Packet<?> packet, List<Packet<?>> sink) {
        if (packet instanceof BundlePacket<?> bundle) {
            bundle.subPackets().forEach(subPacket -> record(subPacket, sink));
        } else {
            sink.add(packet);
        }
    }

    private static boolean inNoCollisionTeam(List<Packet<?>> sent, UUID entity) {
        Set<String> noCollisionTeams = new HashSet<>();
        String teamOfEntity = null;
        for (Packet<?> packet : sent) {
            if (!(packet instanceof ClientboundSetPlayerTeamPacket team)) {
                continue;
            }
            team.getParameters()
                    .filter(parameters -> parameters.collisionRule() == Team.CollisionRule.NEVER)
                    .ifPresent(parameters -> noCollisionTeams.add(team.getName()));
            if (team.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD && team.getPlayers().contains(entity.toString())) {
                teamOfEntity = team.getName();
            }
        }
        return teamOfEntity != null && noCollisionTeams.contains(teamOfEntity);
    }
}
