package de.landsmapbridge.xaero;

import de.landsmapbridge.ClaimStore;
import de.landsmapbridge.LandsMapBridge;
import de.landsmapbridge.protocol.Protocol;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.gui.GuiMap;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.List;

/** Right-click entries on the world map: claim or unclaim the selected chunks. */
public final class ClaimMenu {

    private ClaimMenu() {
    }

    public static void addOptions(GuiMap screen, List<RightClickOption> options, ResourceKey<Level> dimension, MapTileSelection selection) {
        if (!ClaimStore.INSTANCE.isConnected() || selection == null || dimension == null) {
            return;
        }
        if (selection.getLeft() > selection.getRight() || selection.getTop() > selection.getBottom()) {
            return;
        }
        long width = (long) selection.getRight() - (long) selection.getLeft() + 1L;
        long height = (long) selection.getBottom() - (long) selection.getTop() + 1L;
        if (width <= 0 || height <= 0) {
            return;
        }
        long area;
        try {
            area = Math.multiplyExact(width, height);
        } catch (ArithmeticException e) {
            area = Long.MAX_VALUE;
        }
        long max = ClaimStore.INSTANCE.maxClaimArea();
        if (area > max) {
            options.add(new RightClickOption("gui.landsmapbridge.too_large", Style.EMPTY.withColor(ChatFormatting.RED), options.size(), screen) {
                @Override
                public void onAction(Screen current) {
                }
            }.setNameFormatArgs(area, max));
            return;
        }
        options.add(option(screen, options.size(), dimension, selection, area, false));
        options.add(option(screen, options.size(), dimension, selection, area, true));
    }

    private static RightClickOption option(GuiMap screen, int index, ResourceKey<Level> dimension, MapTileSelection selection,
                                           long area, boolean unclaim) {
        // Copy the bounds now; the selection is cleared when the menu closes.
        Protocol.ClaimRequest request = new Protocol.ClaimRequest(dimension.identifier().toString(), unclaim,
                selection.getLeft(), selection.getTop(), selection.getRight(), selection.getBottom());
        String key = unclaim ? "gui.landsmapbridge.unclaim" : "gui.landsmapbridge.claim";
        return new RightClickOption(key, index, screen) {
            @Override
            public void onAction(Screen current) {
                Minecraft minecraft = Minecraft.getInstance();
                minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
                    if (confirmed) {
                        LandsMapBridge.sendClaimRequest(request);
                    }
                    minecraft.gui.setScreen(current);
                }, Component.translatable(key + ".confirm_title"), Component.translatable(key + ".confirm", area)));
            }
        }.setNameFormatArgs(area);
    }
}
