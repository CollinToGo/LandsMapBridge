package de.landsmapbridge;

import de.landsmapbridge.protocol.Protocol;
import de.landsmapbridge.protocol.Protocol.LandInfo;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client copy of the Lands claims. Written on the client thread when packets arrive, read by
 * Xaero's map threads, hence the concurrent maps.
 */
public final class ClaimStore {

    public static final ClaimStore INSTANCE = new ClaimStore();

    private final Map<Integer, LandInfo> lands = new ConcurrentHashMap<>();
    /** Dimension id, then packed chunk position, then land id. */
    private final Map<String, Map<Long, Integer>> chunks = new ConcurrentHashMap<>();
    /** Claimed chunks per 32x32 chunk region, so empty regions are skipped quickly. */
    private final Map<String, Map<Long, Integer>> regionCounts = new ConcurrentHashMap<>();
    /** Bumped whenever a region (or its border) looks different, feeds Xaero's region hash. */
    private final Map<String, Map<Long, Integer>> regionVersions = new ConcurrentHashMap<>();
    private volatile int landsVersion;

    private volatile boolean connected;
    private volatile int maxClaimArea;

    private ClaimStore() {
    }

    public void clear() {
        connected = false;
        lands.clear();
        chunks.clear();
        regionCounts.clear();
        regionVersions.clear();
        landsVersion++;
        MapRefresher.refreshAll();
    }

    /** True once the server plugin answered, i.e. claiming from the map works. */
    public boolean isConnected() {
        return connected;
    }

    public int maxClaimArea() {
        return maxClaimArea;
    }

    public void onWelcome(Protocol.Welcome welcome) {
        connected = true;
        maxClaimArea = welcome.maxClaimArea();
    }

    public void apply(Protocol.LandsUpdate update) {
        if (update.reset()) {
            lands.clear();
        }
        for (LandInfo land : update.lands()) {
            lands.put(land.id(), land);
        }
        for (int id : update.removed()) {
            lands.remove(id);
        }
        // Names and colours may change everywhere, so redraw every region.
        landsVersion++;
        MapRefresher.refreshAll();
    }

    public void apply(Protocol.ChunksUpdate update) {
        String dimension = update.dimension();
        Map<Long, Integer> dimChunks = chunks.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        Map<Long, Integer> counts = regionCounts.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        Map<Long, Integer> versions = regionVersions.computeIfAbsent(dimension, key -> new ConcurrentHashMap<>());
        Set<Long> touchedRegions = new HashSet<>();
        if (update.reset()) {
            for (Long pos : dimChunks.keySet()) {
                touchedRegions.add(regionOf(unpackX(pos), unpackZ(pos)));
            }
            dimChunks.clear();
            counts.clear();
        }
        for (int i = 0; i < update.landIds().length; i++) {
            int x = update.chunkX()[i];
            int z = update.chunkZ()[i];
            int landId = update.landIds()[i];
            long pos = pack(x, z);
            long region = regionOf(x, z);
            Integer previous = landId == Protocol.NO_LAND ? dimChunks.remove(pos) : dimChunks.put(pos, landId);
            if (previous == null && landId != Protocol.NO_LAND) {
                counts.merge(region, 1, Integer::sum);
            } else if (previous != null && landId == Protocol.NO_LAND) {
                counts.computeIfPresent(region, (key, count) -> count > 1 ? count - 1 : null);
            }
            // Borders are drawn against neighbours, so edge chunks touch the next region too.
            touchedRegions.add(region);
            touchedRegions.add(regionOf(x - 1, z));
            touchedRegions.add(regionOf(x + 1, z));
            touchedRegions.add(regionOf(x, z - 1));
            touchedRegions.add(regionOf(x, z + 1));
        }
        for (long region : touchedRegions) {
            versions.merge(region, 1, Integer::sum);
        }
        MapRefresher.refreshRegions(dimension, touchedRegions);
    }

    public LandInfo landAt(String dimension, int chunkX, int chunkZ) {
        Map<Long, Integer> dimChunks = chunks.get(dimension);
        if (dimChunks == null) {
            return null;
        }
        Integer id = dimChunks.get(pack(chunkX, chunkZ));
        return id == null ? null : lands.get(id);
    }

    /** Land id at a chunk, {@link Protocol#NO_LAND} when unclaimed. */
    public int landIdAt(String dimension, int chunkX, int chunkZ) {
        Map<Long, Integer> dimChunks = chunks.get(dimension);
        Integer id = dimChunks == null ? null : dimChunks.get(pack(chunkX, chunkZ));
        return id == null ? Protocol.NO_LAND : id;
    }

    public boolean regionHasClaims(String dimension, int regionX, int regionZ) {
        Map<Long, Integer> counts = regionCounts.get(dimension);
        return counts != null && counts.containsKey(pack(regionX, regionZ));
    }

    public int regionHash(String dimension, int regionX, int regionZ) {
        Map<Long, Integer> versions = regionVersions.get(dimension);
        int version = versions == null ? 0 : versions.getOrDefault(pack(regionX, regionZ), 0);
        return 31 * version + landsVersion;
    }

    static long pack(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    static int unpackX(long pos) {
        return (int) (pos >> 32);
    }

    static int unpackZ(long pos) {
        return (int) pos;
    }

    private static long regionOf(int chunkX, int chunkZ) {
        return pack(chunkX >> 5, chunkZ >> 5);
    }
}
