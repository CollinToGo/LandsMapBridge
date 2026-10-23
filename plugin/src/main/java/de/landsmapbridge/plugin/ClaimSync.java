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
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the claim map of every player running the mod up to date. Only lands reported as
 * changed are read again, and only the difference is sent.
 */
final class ClaimSync {

    private final LandsMapBridgePlugin plugin;
    private final LandsIntegration lands;

    private final Map<ULID, Integer> landIds = new HashMap<>();
    private int nextLandId = 1;

    /** Dimension key, then packed chunk position, then land id. */
    private final Map<String, Map<Long, Integer>> chunks = new HashMap<>();
    /** The chunks last read for each land, by land id and dimension key. */
    private final Map<Integer, Map<String, Set<Long>>> landChunks = new HashMap<>();
    /** Chunk count at the last read, for the cheap periodic check. */
    private final Map<Integer, Integer> landChunkCounts = new HashMap<>();
    private final Map<Integer, ULID> ulidsById = new HashMap<>();

    /** What each mod client was last told about the lands. */
    private final Map<UUID, Map<Integer, LandInfo>> sentLands = new HashMap<>();
    /** Mod clients that said hello but have not registered their channels yet. */
    private final Set<UUID> waitingForChannels = new HashSet<>();
    private final Map<UUID, String> ownerNames = new HashMap<>();

    // Lands may fire events off the main thread, so these two are written concurrently.
    private final Set<ULID> changedLands = ConcurrentHashMap.newKeySet();
    private volatile boolean infosChanged;

    private static final Set<String> SYNC_CHANNELS = Set.of(Protocol.HELLO, Protocol.LANDS, Protocol.CHUNKS);

    ClaimSync(LandsMapBridgePlugin plugin, LandsIntegration lands) {
        this.plugin = plugin;
        this.lands = lands;
    }

    /** The land's chunks (and maybe its name or members) changed. */
    void landChanged(Land land) {
        if (land != null) {
            changedLands.add(land.getULID());
        }
        infosChanged = true;
    }

    /** Names, relations or wars changed, but no chunks. */
    void infosChanged() {
        infosChanged = true;
    }

    /**
     * Catches changes Lands sends no event for. Compares chunk counts only, which is cheap, and
     * marks lands that differ (or appeared or vanished) for a full read.
     */
    void checkAll() {
        Set<Integer> seen = new HashSet<>();
        for (Land land : lands.getLands()) {
            int id = landId(land);
            seen.add(id);
            Integer count = landChunkCounts.get(id);
            if (count == null || count != land.getChunksAmount()) {
                changedLands.add(land.getULID());
            }
        }
        for (Integer id : landChunks.keySet()) {
            if (!seen.contains(id)) {
                changedLands.add(ulidsById.get(id));
            }
        }
        infosChanged = true;
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
        flush();
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
        return landIds.computeIfAbsent(land.getULID(), ulid -> {
            int id = nextLandId++;
            ulidsById.put(id, ulid);
            return id;
        });
    }

    /** Runs every few ticks; does nothing unless a change was flagged. */
    void flush() {
        Map<String, Map<Long, Integer>> changes = new HashMap<>();
        if (!changedLands.isEmpty()) {
            List<ULID> pending = new ArrayList<>(changedLands);
            Map<ULID, Map<String, Set<Long>>> reads = new HashMap<>();
            try {
                for (ULID ulid : pending) {
                    Land land = lands.getLandByULID(ulid);
                    reads.put(ulid, land == null ? Map.of() : readChunks(land));
                }
            } catch (ConcurrentModificationException e) {
                // Lands changes claims off the main thread; try again on the next run.
                return;
            }
            pending.forEach(changedLands::remove);
            reads.forEach((ulid, after) -> apply(landIds.get(ulid), after, changes));
        }

        List<Player> clients = clients();
        if (!clients.isEmpty()) {
            changes.forEach((dimension, entries) -> sendChunks(clients, dimension, false, entries));
            if (infosChanged) {
                for (Player player : clients) {
                    sendLandChanges(player);
                }
            }
        }
        infosChanged = false;
    }

    /** Stores a land's fresh chunks and records what changed per dimension. */
    private void apply(Integer id, Map<String, Set<Long>> after, Map<String, Map<Long, Integer>> changes) {
        if (id == null) {
            return;
        }
        Map<String, Set<Long>> before = landChunks.getOrDefault(id, Map.of());
        for (String dimension : union(before.keySet(), after.keySet())) {
            Set<Long> old = before.getOrDefault(dimension, Set.of());
            Set<Long> now = after.getOrDefault(dimension, Set.of());
            Map<Long, Integer> owners = chunks.computeIfAbsent(dimension, key -> new HashMap<>());
            Map<Long, Integer> dimChanges = changes.computeIfAbsent(dimension, key -> new HashMap<>());
            for (long pos : old) {
                // Another land may have taken the chunk already in this same flush.
                if (!now.contains(pos) && owners.remove(pos, id)) {
                    dimChanges.put(pos, Protocol.NO_LAND);
                }
            }
            for (long pos : now) {
                if (!id.equals(owners.put(pos, id))) {
                    dimChanges.put(pos, id);
                }
            }
        }
        if (after.isEmpty() && lands.getLandByULID(ulidsById.get(id)) == null) {
            landChunks.remove(id);
            landChunkCounts.remove(id);
        } else {
            landChunks.put(id, after);
            landChunkCounts.put(id, after.values().stream().mapToInt(Set::size).sum());
        }
    }

    private static Map<String, Set<Long>> readChunks(Land land) {
        Map<String, Set<Long>> result = new HashMap<>();
        for (Container container : land.getContainers()) {
            World world = container.getWorld().getWorld();
            if (world == null) {
                continue;
            }
            Set<Long> dimension = result.computeIfAbsent(dimensionKey(world), key -> new HashSet<>());
            for (ChunkCoordinate chunk : container.getChunks()) {
                dimension.add(pack(chunk.getX(), chunk.getZ()));
            }
        }
        return result;
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

    private String ownerName(UUID owner) {
        if (owner == null) {
            return "";
        }
        String cached = ownerNames.get(owner);
        if (cached != null) {
            return cached;
        }
        String name = Bukkit.getOfflinePlayer(owner).getName();
        if (name == null) {
            return "";
        }
        ownerNames.put(owner, name);
        return name;
    }

    private void sendChunks(List<Player> targets, String dimension, boolean reset, Map<Long, Integer> entries) {
        if (!reset && entries.isEmpty()) {
            return;
        }
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
