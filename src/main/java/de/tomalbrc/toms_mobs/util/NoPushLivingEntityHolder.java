package de.tomalbrc.toms_mobs.util;

import de.tomalbrc.bil.api.AnimatedEntity;
import de.tomalbrc.bil.core.holder.entity.living.LivingEntityHolder;
import de.tomalbrc.bil.core.model.Model;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

// Keeps BIL's collision slime (lead anchor, death poof) but stops it from pushing Java players around.
public class NoPushLivingEntityHolder<T extends LivingEntity & AnimatedEntity> extends LivingEntityHolder<T> {
    public NoPushLivingEntityHolder(T parent, Model model) {
        super(parent, model);
    }

    @Override
    protected void startWatchingExtraPackets(ServerGamePacketListenerImpl player, Consumer<Packet<@NotNull ClientGamePacketListener>> consumer) {
        super.startWatchingExtraPackets(player, consumer);
        NoCollisionTeam.add(player, this.collisionElement.getUuid(), consumer);
    }
}
