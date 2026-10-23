package de.landsmapbridge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import de.landsmapbridge.xaero.WorldMapLandsHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.WorldMapSession;
import xaero.map.highlight.HighlighterRegistry;

/** Xaero only registers its own highlighters, so ours is added right before the list is sealed. */
@Mixin(value = WorldMapSession.class, remap = false)
public class WorldMapSessionMixin {

    @Inject(method = "init", at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V"))
    private void landsmapbridge$registerHighlighter(CallbackInfo ci, @Local HighlighterRegistry registry) {
        registry.register(new WorldMapLandsHighlighter());
    }
}
