package de.landsmapbridge;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.highlight.DimensionHighlighterHandler;
import xaero.map.WorldMapSession;
import xaero.map.world.MapDimension;

import java.util.Collection;

/** Tells Xaero's maps to redraw claim highlights after the store changed. */
final class MapRefresher {

    private MapRefresher() {
    }

    static void refreshRegions(String dimension, Collection<Long> regions) {
        xaero.map.highlight.DimensionHighlighterHandler worldMap = worldMapHandler(dimension);
        DimensionHighlighterHandler minimap = minimapHandler();
        for (long region : regions) {
            int regionX = ClaimStore.unpackX(region);
            int regionZ = ClaimStore.unpackZ(region);
            if (worldMap != null) {
                worldMap.clearCachedHash(regionX, regionZ);
            }
            if (minimap != null) {
                minimap.requestRefresh(regionX, regionZ);
            }
        }
    }

    static void refreshAll() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        if (session != null && session.getMapProcessor().isMapWorldUsable()) {
            for (MapDimension dimension : session.getMapProcessor().getMapWorld().getDimensionsList()) {
                dimension.getHighlightHandler().clearCachedHashes();
            }
        }
        DimensionHighlighterHandler minimap = minimapHandler();
        if (minimap != null) {
            minimap.requestRefresh();
        }
    }

    private static xaero.map.highlight.DimensionHighlighterHandler worldMapHandler(String dimension) {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        Identifier id = Identifier.tryParse(dimension);
        if (session == null || id == null || !session.getMapProcessor().isMapWorldUsable()) {
            return null;
        }
        MapDimension mapDimension = session.getMapProcessor().getMapWorld().getDimension(ResourceKey.create(Registries.DIMENSION, id));
        return mapDimension == null ? null : mapDimension.getHighlightHandler();
    }

    private static DimensionHighlighterHandler minimapHandler() {
        XaeroMinimapSession session = XaeroMinimapSession.getCurrentSession();
        if (session == null || session.getMinimapProcessor() == null) {
            return null;
        }
        return session.getMinimapProcessor().getMinimapWriter().getDimensionHighlightHandler();
    }
}
