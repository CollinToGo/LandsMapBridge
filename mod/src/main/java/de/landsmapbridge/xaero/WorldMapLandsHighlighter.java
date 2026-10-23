package de.landsmapbridge.xaero;

import de.landsmapbridge.ClaimStore;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

import java.util.List;

/** Draws Lands claims on Xaero's World Map, registered through a mixin into its session. */
public final class WorldMapLandsHighlighter extends ChunkHighlighter {

    public WorldMapLandsHighlighter() {
        super(true);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return ClaimColors.fill(resultStore, dimension.identifier().toString(), chunkX, chunkZ) ? resultStore : null;
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return ClaimStore.INSTANCE.regionHash(dimension.identifier().toString(), regionX, regionZ);
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return ClaimStore.INSTANCE.regionHasClaims(dimension.identifier().toString(), regionX, regionZ);
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return ClaimStore.INSTANCE.landAt(dimension.identifier().toString(), chunkX, chunkZ) != null;
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return ClaimColors.tooltip(dimension.identifier().toString(), chunkX, chunkZ);
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return null;
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dimension, int blockX, int blockZ, int width) {
    }
}
