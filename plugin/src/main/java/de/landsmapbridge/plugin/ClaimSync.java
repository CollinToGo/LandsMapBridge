package de.landsmapbridge.plugin;

import de.landsmapbridge.protocol.Protocol;
import de.landsmapbridge.protocol.Protocol.LandInfo;
import de.landsmapbridge.protocol.Protocol.Relation;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.applicationframework.util.ULID;
import me.angeschossen.lands.api.land.ChunkCoordinate;
import me.angeschossen.lands.api.land.Container;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps the claim map of every player running the mod up to date. Instead of trusting each
 * Lands event to carry the right data, it rebuilds a snapshot when something changed and
 * only sends the difference.
 */
final class ClaimSync {

    private final LandsMapBridgePlugin plugin;
    private final LandsIntegration lands;

    private final Map<ULID, Integer> landIds = new HashMap<>();
    private int nextLandId = 1;

    /** Dimension key, then packed chunk position, then land id. */
    private Map<String, Map<Long, Integer>> chunks = new HashMap<>();
    /** What each mod client was last told about the lands. */
    private final Map<UUID, Map<Integer, LandInfo>> sentLands = new HashMap<>();
    /** Mod clients that said hello but have not registered their channels yet. */
    private final Set<UUID> waitingForChannels = new HashSet<>();
    private boolean dirty = true;

    private static final Set<String> SYNC_CHANNELS = Set.of(Protocol.HELLO, Protocol.LANDS, Protocol.CHUNKS);

    ClaimSync(LandsMapBridgePlugin plugin, LandsIntegration lands) {
        this.plugin = plugin;
        this.lands = lands;
    }

    void markDirty() {
        dirty = true;
    }

    void forget(Player player) {
        sentLands.remove(player.getUniqueId());
        waitingForChannels.remove(player.getUniqueId());
    }

    void onHello(Player player, Protocol.Hello hello) {
        if (hello.version() != Protocol.VERSION) {
            plugin.getLogger().info(player.getName() + " uses protocol " + hello.version() + ", expected " + Protocol.VERSION);
            return;
        }
        plugin.getLogger().info(player.getName() + " joined with the Lands Map Bridge mod");
        // Fabric may say hello before it registers its channels, and Paper drops messages on
        // channels a client has not registered. In that case wait for the registration.
        if (!player.getListeningPluginChannels().containsAll(SYNC_CHANNELS)) {
            waitingForChannels.add(player.getUniqueId());
            return;
        }
        startSync(player);
    }

    void onChannelRegistered(Player player) {
        if (waitingForChannels.contains(player.getUniqueId())
                && player.getListeningPluginChannels().containsAll(SYNC_CHANNELS)) {
            waitingForChannels.remove(player.getUniqueId());
            startSync(player);
        }
    }

    private void startSync(Player player) {
        if (dirty) {
            flush();
        }
        send(player, Protocol.HELLO, new Protocol.Welcome(Protocol.VERSION, plugin.maxClaimArea()).encode());

        Map<Integer, LandInfo> infos = landInfos(player.getUniqueId());
        sentLands.put(player.getUniqueId(), infos);
        send(player, Protocol.LANDS, new Protocol.LandsUpdate(true, List.copyOf(infos.values()), new int[0]).encode());
        for (World world : Bukkit.getWorlds()) {
            Map<Long, Integer> dimension = chunks.getOrDefault(dimensionKey(world), Map.of());
            sendChunks(List.of(player), dimensionKey(world), true, dimension);
        }
    }

    int landId(Land land) {
        return landIds.computeIfAbsent(land.getULID(), ulid -> nextLandId++);
    }

    /** Runs every few ticks; does nothing unless a change was flagged. */
    void flush() {
        if (!dirty) {
            return;
        }
        Map<String, Map<Long, Integer>> current;
        try {
            current = snapshotChunks();
        } catch (ConcurrentModificationException e) {
            // Lands changes claims off the main thread; try again on the next run.
            return;
        }
        dirty = false;
        List<Player> clients = clients();
        if (!clients.isEmpty()) {
            for (String dimension : union(current.keySet(), chunks.keySet())) {
                Map<Long, Integer> before = chunks.getOrDefault(dimension, Map.of());
                Map<Long, Integer> after = current.getOrDefault(dimension, Map.of());
                Map<Long, Integer> changes = new HashMap<>();
                after.forEach((pos, id) -> {
                    if (!id.equals(before.get(pos))) {
                        changes.put(pos, id);
                    }
                });
                before.keySet().forEach(pos -> {
                    if (!after.containsKey(pos)) {
                        changes.put(pos, Protocol.NO_LAND);
                    }
                });
                if (!changes.isEmpty()) {
                    sendChunks(clients, dimension, false, changes);
                }
            }
            for (Player player : clients) {
                sendLandChanges(player);
            }
        }
        chunks = current;
    }

    private void sendLandChanges(Player player) {
        Map<Integer, LandInfo> before = sentLands.getOrDefault(player.getUniqueId(), Map.of());
        Map<Integer, LandInfo> after = landInfos(player.getUniqueId());
        List<LandInfo> changed = new ArrayList<>();
        after.forEach((id, info) -> {
            if (!info.equals(before.get(id))) {
                changed.add(info);
            }
        });
        int[] removed = before.keySet().stream().filter(id -> !after.containsKey(id)).mapToInt(Integer::intValue).toArray();
        sentLands.put(player.getUniqueId(), after);
        if (!changed.isEmpty() || removed.length > 0) {
            send(player, Protocol.LANDS, new Protocol.LandsUpdate(false, changed, removed).encode());
        }
    }

    private Map<String, Map<Long, Integer>> snapshotChunks() {
        Map<String, Map<Long, Integer>> result = new HashMap<>();
        for (Land land : lands.getLands()) {
            int id = landId(land);
            for (Container container : land.getContainers()) {
                World world = container.getWorld().getWorld();
                if (world == null) {
                    continue;
                }
                Map<Long, Integer> dimension = result.computeIfAbsent(dimensionKey(world), key -> new HashMap<>());
                for (ChunkCoordinate chunk : container.getChunks()) {
                    dimension.put(pack(chunk.getX(), chunk.getZ()), id);
                }
            }
        }
        return result;
    }

    private Map<Integer, LandInfo> landInfos(UUID viewer) {
        LandPlayer landPlayer = lands.getLandPlayer(viewer);
        Collection<? extends Land> viewerLands = landPlayer != null ? landPlayer.getLands() : List.of();
        Map<Integer, LandInfo> result = new HashMap<>();
        for (Land land : lands.getLands()) {
            int id = landId(land);
            result.put(id, new LandInfo(id, land.getName(), ownerName(land.getOwnerUID()), relation(land, viewer, viewerLands)));
        }
        return result;
    }

    private static Relation relation(Land land, UUID viewer, Collection<? extends Land> viewerLands) {
        if (viewer.equals(land.getOwnerUID())) {
            return Relation.OWN;
        }
        if (land.isTrusted(viewer)) {
            return Relation.MEMBER;
        }
        Relation result = Relation.NEUTRAL;
        for (Land own : viewerLands) {
            if (own.hasWarEntity(land) || land.hasWarEntity(own)
                    || own.getRelation(land) == me.angeschossen.lands.api.relations.Relation.ENEMY) {
                return Relation.ENEMY;
            }
            if (own.getRelation(land) == me.angeschossen.lands.api.relations.Relation.ALLY) {
                result = Relation.ALLY;
            }
        }
        return result;
    }

    private static String ownerName(UUID owner) {
        if (owner == null) {
            return "";
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return Objects.requireNonNullElse(player.getName(), "");
    }

    private void sendChunks(List<Player> targets, String dimension, boolean reset, Map<Long, Integer> entries) {
        List<Map.Entry<Long, Integer>> list = new ArrayList<>(entries.entrySet());
        int offset = 0;
        do {
            int size = Math.min(Protocol.MAX_CHUNKS_PER_PACKET, list.size() - offset);
            int[] x = new int[size];
            int[] z = new int[size];
            int[] ids = new int[size];
            for (int i = 0; i < size; i++) {
                Map.Entry<Long, Integer> entry = list.get(offset + i);
                x[i] = (int) (entry.getKey() >> 32);
                z[i] = (int) (long) entry.getKey();
                ids[i] = entry.getValue();
            }
            // Only the first packet may reset, the rest add to it.
            byte[] data = new Protocol.ChunksUpdate(dimension, reset && offset == 0, x, z, ids).encode();
            for (Player player : targets) {
                send(player, Protocol.CHUNKS, data);
            }
            offset += size;
        } while (offset < list.size());
    }

    private List<Player> clients() {
        List<Player> result = new ArrayList<>();
        for (UUID uuid : sentLands.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                result.add(player);
            }
        }
        return result;
    }

    private void send(Player player, String channel, byte[] data) {
        player.sendPluginMessage(plugin, channel, data);
    }

    static String dimensionKey(World world) {
        return world.getKey().toString();
    }

    static long pack(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private static <T> List<T> union(Collection<T> a, Collection<T> b) {
        List<T> result = new ArrayList<>(a);
        for (T item : b) {
            if (!a.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }
}
