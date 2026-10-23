package de.landsmapbridge.plugin;

import de.landsmapbridge.protocol.Protocol;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.events.ChunkDeleteEvent;
import me.angeschossen.lands.api.events.ChunkPostClaimEvent;
import me.angeschossen.lands.api.events.LandCreateEvent;
import me.angeschossen.lands.api.events.LandDeleteEvent;
import me.angeschossen.lands.api.events.LandOwnerChangeEvent;
import me.angeschossen.lands.api.events.LandRenameEvent;
import me.angeschossen.lands.api.events.LandTrustPlayerEvent;
import me.angeschossen.lands.api.events.LandUntrustPlayerEvent;
import me.angeschossen.lands.api.events.PlayerLeaveLandEvent;
import me.angeschossen.lands.api.events.land.LandConvertEvent;
import me.angeschossen.lands.api.events.land.LandEvent;
import me.angeschossen.lands.api.events.land.claiming.LandMergeEvent;
import me.angeschossen.lands.api.events.land.claiming.LandUnclaimAllEvent;
import me.angeschossen.lands.api.events.land.create.LandPostCreateEvent;
import me.angeschossen.lands.api.events.memberholder.MemberHolderRelationChangeEvent;
import me.angeschossen.lands.api.events.nation.edit.NationCreateEvent;
import me.angeschossen.lands.api.events.nation.edit.NationDeleteEvent;
import me.angeschossen.lands.api.events.nation.member.NationTrustLandEvent;
import me.angeschossen.lands.api.events.nation.member.NationUntrustLandEvent;
import me.angeschossen.lands.api.events.war.WarEndEvent;
import me.angeschossen.lands.api.events.war.WarStartEvent;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class LandsMapBridgePlugin extends JavaPlugin implements Listener {

    /** Lands events after which the map data may have changed. */
    private static final List<Class<? extends Event>> CHANGE_EVENTS = List.of(
            ChunkPostClaimEvent.class, ChunkDeleteEvent.class, LandUnclaimAllEvent.class, LandMergeEvent.class,
            LandCreateEvent.class, LandPostCreateEvent.class, LandDeleteEvent.class, LandRenameEvent.class,
            LandOwnerChangeEvent.class, LandConvertEvent.class, LandTrustPlayerEvent.class, LandUntrustPlayerEvent.class,
            PlayerLeaveLandEvent.class, MemberHolderRelationChangeEvent.class, WarStartEvent.class, WarEndEvent.class,
            NationCreateEvent.class, NationDeleteEvent.class, NationTrustLandEvent.class, NationUntrustLandEvent.class);

    private ClaimSync sync;
    private ClaimRequestHandler claimHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        LandsIntegration lands = LandsIntegration.of(this);
        sync = new ClaimSync(this, lands);
        claimHandler = new ClaimRequestHandler(this, lands, sync);

        var messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, Protocol.HELLO);
        messenger.registerOutgoingPluginChannel(this, Protocol.LANDS);
        messenger.registerOutgoingPluginChannel(this, Protocol.CHUNKS);
        messenger.registerIncomingPluginChannel(this, Protocol.HELLO,
                (channel, player, data) -> sync.onHello(player, Protocol.Hello.decode(data)));
        messenger.registerIncomingPluginChannel(this, Protocol.CLAIM,
                (channel, player, data) -> claimHandler.handle(player, Protocol.ClaimRequest.decode(data)));

        getServer().getPluginManager().registerEvents(this, this);
        for (Class<? extends Event> type : CHANGE_EVENTS) {
            getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR,
                    (listener, event) -> onLandsEvent(event), this, true);
        }

        // Load every land once, then pick up changes no event tells us about (admin commands,
        // expired lands, ...) with a cheap chunk count comparison.
        sync.checkAll();
        long resyncTicks = Math.max(1, getConfig().getLong("resync-seconds", 30)) * 20L;
        getServer().getScheduler().runTaskTimer(this, sync::checkAll, resyncTicks, resyncTicks);
        getServer().getScheduler().runTaskTimer(this, sync::flush, 10L, 10L);
    }

    private void onLandsEvent(Event event) {
        if (event instanceof LandMergeEvent merge) {
            landChanged(merge.getLand());
            landChanged(merge.getToMerge());
        } else if (event instanceof LandEvent landEvent) {
            landChanged(landEvent.getLand());
        } else {
            sync.infosChanged();
        }
    }

    private void landChanged(Land land) {
        sync.landChanged(land);
        // Some changes land after their event without one of their own, like the chunks Lands
        // claims right after a land is created. Read the land once more a second later.
        getServer().getScheduler().runTaskLater(this, () -> sync.landChanged(land), 20L);
    }

    @EventHandler
    public void onChannelRegister(PlayerRegisterChannelEvent event) {
        sync.onChannelRegistered(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sync.forget(event.getPlayer());
    }

    int maxClaimArea() {
        return Math.max(1, getConfig().getInt("max-claim-area", 256));
    }
}
