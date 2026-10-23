package de.landsmapbridge.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import de.landsmapbridge.xaero.MinimapLandsHighlighter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.highlight.HighlighterRegistry;
import xaero.hud.minimap.module.MinimapSession;

/** Same as {@link WorldMapSessionMixin}, for the minimap's own highlighter list. */
@Mixin(value = MinimapSession.class, remap = false)
public class MinimapSessionMixin {

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lxaero/common/minimap/highlight/HighlighterRegistry;end()V"))
    private void landsmapbridge$registerHighlighter(CallbackInfo ci, @Local HighlighterRegistry registry) {
        registry.register(new MinimapLandsHighlighter());
    }
}
