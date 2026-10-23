package de.landsmapbridge.xaero;

import de.landsmapbridge.ClaimStore;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/** Draws Lands claims on Xaero's Minimap, registered through a mixin into its session. */
public final class MinimapLandsHighlighter extends ChunkHighlighter {

    public MinimapLandsHighlighter() {
        super(true);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return ClaimColors.fill(resultStore, dimension.identifier().toString(), chunkX, chunkZ) ? resultStore : null;
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
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dimension, int chunkX, int chunkZ, int width) {
        Component tooltip = ClaimColors.tooltip(dimension.identifier().toString(), chunkX, chunkZ);
        if (tooltip != null) {
            compiler.addLine(tooltip);
        }
    }
}
