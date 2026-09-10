package de.tomalbrc.toms_mobs.util;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A scoreboard team that only exists on clients, with collision rule NEVER. Java clients push the local player
 * out of any living entity they know about, BIL's invisible collision slime included; entities in a NEVER team
 * push nobody. Bedrock players behind Geyser don't get this, Geyser ignores team collision rules.
 */
public final class NoCollisionTeam {
    private static final PlayerTeam TEAM = createTeam();
    private static final Set<ServerGamePacketListenerImpl> TEAM_SENT = ConcurrentHashMap.newKeySet();

    private NoCollisionTeam() {
    }

    public static void init() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> TEAM_SENT.remove(handler));
    }

    public static void add(ServerGamePacketListenerImpl player, UUID entity, Consumer<Packet<ClientGamePacketListener>> consumer) {
        if (TEAM_SENT.add(player)) {
            // true creates the team; false would only modify it, and the client drops changes to teams it doesn't have
            consumer.accept(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(TEAM, true));
        }
        // Never followed by a removal: the client throws when asked to remove an entry it doesn't have, which
        // drops the connection. Stale entries only cost a string each and are gone on reconnect.
        consumer.accept(ClientboundSetPlayerTeamPacket.createPlayerPacket(TEAM, entity.toString(), ClientboundSetPlayerTeamPacket.Action.ADD));
    }

    private static PlayerTeam createTeam() {
        PlayerTeam team = new PlayerTeam(new Scoreboard(), "toms_mobs_nopush");
        team.setCollisionRule(Team.CollisionRule.NEVER);
        team.setSeeFriendlyInvisibles(false);
        return team;
    }
}
