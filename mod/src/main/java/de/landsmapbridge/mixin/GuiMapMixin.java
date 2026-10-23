package de.landsmapbridge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import de.landsmapbridge.xaero.ClaimMenu;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.GuiMap;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/** Adds the Lands claim and unclaim entries to the world map's right-click menu. */
@Mixin(value = GuiMap.class, remap = false)
public class GuiMapMixin {

    @Shadow
    private ResourceKey<Level> rightClickDim;

    @Shadow
    private MapTileSelection mapTileSelection;

    // Inserted where Open Parties and Claims adds its options, before "Export".
    @Inject(method = "getRightClickOptions", at = @At(value = "INVOKE", target = "Lxaero/map/mods/SupportMods;pac()Z"))
    private void landsmapbridge$addClaimOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir,
                                                @Local ArrayList<RightClickOption> options) {
        ClaimMenu.addOptions((GuiMap) (Object) this, options, rightClickDim, mapTileSelection);
    }
}
