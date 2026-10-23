package de.landsmapbridge.plugin;

import de.landsmapbridge.protocol.Protocol;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.player.LandPlayer;
import me.angeschossen.lands.api.player.Selection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Claims or unclaims a rectangle of chunks. Claiming goes through a Lands selection, the same
 * path as the claim wand, so costs, limits, distance rules and permissions are Lands' own.
 * Lands has no selection unclaim, so for that the role and permission are checked here.
 */
final class ClaimRequestHandler {

    private static final String CLAIM_PERMISSION = "lands.command.claim";
    private static final String UNCLAIM_PERMISSION = "lands.command.unclaim";

    private final LandsMapBridgePlugin plugin;
    private final LandsIntegration lands;
    private final ClaimSync sync;
    /** Players with a request still running; one at a time each. */
    private final Set<UUID> busy = new HashSet<>();

    ClaimRequestHandler(LandsMapBridgePlugin plugin, LandsIntegration lands, ClaimSync sync) {
        this.plugin = plugin;
        this.lands = lands;
        this.sync = sync;
    }

    void handle(Player player, Protocol.ClaimRequest request) {
        if (request.minX() > request.maxX() || request.minZ() > request.maxZ()) {
            return;
        }
        if (request.area() > plugin.maxClaimArea()) {
            fail(player, "You can select at most " + plugin.maxClaimArea() + " chunks at once.");
            return;
        }
        NamespacedKey key = NamespacedKey.fromString(request.dimension());
        World world = key == null ? null : Bukkit.getWorld(key);
        if (world == null || lands.getWorld(world) == null) {
            fail(player, "Lands is not enabled in this world.");
            return;
        }
        if (!player.getWorld().equals(world)) {
            fail(player, "Go to that dimension to claim or unclaim there.");
            return;
        }
        if (!player.hasPermission(request.unclaim() ? UNCLAIM_PERMISSION : CLAIM_PERMISSION)) {
            fail(player, "You don't have permission to do this.");
            return;
        }
        LandPlayer landPlayer = lands.getLandPlayer(player.getUniqueId());
        Land land = landPlayer == null ? null : editLand(landPlayer);
        if (land == null) {
            fail(player, "Select the land to edit first with /lands edit <land>.");
            return;
        }
        if (!land.getDefaultArea().hasRoleFlag(player.getUniqueId(), Flags.LAND_CLAIM)) {
            fail(player, "Your role in " + land.getName() + " is not allowed to claim or unclaim.");
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            fail(player, "Your last claim request is still running.");
            return;
        }

        int before = countOwned(land, world, request);
        int target = request.unclaim() ? before : (int) request.area() - before;
        if (target == 0) {
            busy.remove(player.getUniqueId());
            fail(player, request.unclaim() ? "None of these chunks belong to " + land.getName() + "."
                    : "These chunks already belong to " + land.getName() + ".");
            return;
        }
        if (request.unclaim()) {
            unclaim(player, landPlayer, land, world, request, before);
        } else {
            claim(player, landPlayer, land, world, request, before);
        }
    }

    private void claim(Player player, LandPlayer landPlayer, Land land, World world, Protocol.ClaimRequest request, int before) {
        double y = player.getLocation().getY();
        Selection selection = Selection.of(landPlayer, false, false, false);
        selection.setPos1(new Location(world, request.minX() << 4, y, request.minZ() << 4));
        selection.setPos2(new Location(world, (request.maxX() << 4) + 15, y, (request.maxZ() << 4) + 15));
        selection.claim(land, true, false).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            selection.disable();
            if (error != null) {
                plugin.getLogger().warning("Selection claim failed: " + error);
            }
            int claimed = countOwned(land, world, request) - before;
            finish(player, land, false, claimed, (int) request.area() - before);
        }));
    }

    private void unclaim(Player player, LandPlayer landPlayer, Land land, World world, Protocol.ClaimRequest request, int owned) {
        Deque<int[]> todo = new ArrayDeque<>();
        for (int x = request.minX(); x <= request.maxX(); x++) {
            for (int z = request.minZ(); z <= request.maxZ(); z++) {
                if (land.hasChunk(world, x, z)) {
                    todo.add(new int[]{x, z});
                }
            }
        }
        unclaimNext(player, landPlayer, land, world, todo, 0, owned);
    }

    private void unclaimNext(Player player, LandPlayer landPlayer, Land land, World world, Deque<int[]> todo, int done, int total) {
        int[] chunk = todo.poll();
        if (chunk == null || !player.isOnline()) {
            finish(player, land, true, done, total);
            return;
        }
        land.unclaimChunk(world, chunk[0], chunk[1], landPlayer).whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            boolean removed = error == null && !land.hasChunk(world, chunk[0], chunk[1]);
            unclaimNext(player, landPlayer, land, world, todo, removed ? done + 1 : done, total);
        }));
    }

    private void finish(Player player, Land land, boolean unclaim, int done, int total) {
        busy.remove(player.getUniqueId());
        sync.markDirty();
        if (!player.isOnline()) {
            return;
        }
        String verb = unclaim ? "Unclaimed " : "Claimed ";
        NamedTextColor color = done == total ? NamedTextColor.GREEN : done == 0 ? NamedTextColor.RED : NamedTextColor.YELLOW;
        player.sendMessage(Component.text(verb + done + " of " + total + " chunks for " + land.getName() + ".", color));
    }

    private static int countOwned(Land land, World world, Protocol.ClaimRequest request) {
        int count = 0;
        for (int x = request.minX(); x <= request.maxX(); x++) {
            for (int z = request.minZ(); z <= request.maxZ(); z++) {
                if (land.hasChunk(world, x, z)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Land editLand(LandPlayer landPlayer) {
        Land land = landPlayer.getEditLand(false);
        if (land != null) {
            return land;
        }
        Collection<? extends Land> own = landPlayer.getLands();
        return own.size() == 1 ? own.iterator().next() : null;
    }

    private void fail(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.RED));
    }
}
