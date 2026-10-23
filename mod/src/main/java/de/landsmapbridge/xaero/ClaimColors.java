package de.landsmapbridge.xaero;

import de.landsmapbridge.ClaimStore;
import de.landsmapbridge.protocol.Protocol;
import de.landsmapbridge.protocol.Protocol.LandInfo;
import de.landsmapbridge.protocol.Protocol.Relation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Colours and tooltips shared by the world map and the minimap highlighter. */
final class ClaimColors {

    private static final int FILL_ALPHA = 0x46;
    private static final int BORDER_ALPHA = 0xD0;

    private ClaimColors() {
    }

    static int rgb(Relation relation) {
        return switch (relation) {
            case OWN -> 0x3FD94A;
            case MEMBER -> 0x3F8CFF;
            case ALLY -> 0xFFD23F;
            case ENEMY -> 0xFF3F3F;
            case NEUTRAL -> 0x9A9A9A;
        };
    }

    /**
     * Fills Xaero's 5-entry colour array: centre, top, right, bottom, left. Sides that face a
     * different land (or no land) get the stronger border colour.
     *
     * @return false when the chunk is not claimed
     */
    static boolean fill(int[] out, String dimension, int chunkX, int chunkZ) {
        ClaimStore store = ClaimStore.INSTANCE;
        int landId = store.landIdAt(dimension, chunkX, chunkZ);
        LandInfo land = landId == Protocol.NO_LAND ? null : store.landAt(dimension, chunkX, chunkZ);
        if (land == null) {
            return false;
        }
        int color = xaeroColor(rgb(land.relation()));
        int center = color | FILL_ALPHA;
        int border = color | BORDER_ALPHA;
        out[0] = center;
        out[1] = store.landIdAt(dimension, chunkX, chunkZ - 1) == landId ? center : border;
        out[2] = store.landIdAt(dimension, chunkX + 1, chunkZ) == landId ? center : border;
        out[3] = store.landIdAt(dimension, chunkX, chunkZ + 1) == landId ? center : border;
        out[4] = store.landIdAt(dimension, chunkX - 1, chunkZ) == landId ? center : border;
        return true;
    }

    static Component tooltip(String dimension, int chunkX, int chunkZ) {
        LandInfo land = ClaimStore.INSTANCE.landAt(dimension, chunkX, chunkZ);
        if (land == null) {
            return null;
        }
        MutableComponent text = Component.literal("□ ").withStyle(style -> style.withColor(rgb(land.relation())));
        text.append(Component.literal(land.name()).withStyle(ChatFormatting.WHITE));
        if (!land.owner().isEmpty()) {
            text.append(Component.literal(" (" + land.owner() + ")").withStyle(ChatFormatting.GRAY));
        }
        return text;
    }

    /** Xaero packs highlight colours as blue, green, red, alpha from the high byte down. */
    private static int xaeroColor(int rgb) {
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return blue << 24 | green << 16 | red << 8;
    }
}
