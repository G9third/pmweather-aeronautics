package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.AeroSurfaceCache;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelPlot.class, remap = false)
public abstract class LevelPlotAeroCacheMixin {
    @Shadow
    public abstract SubLevel getSubLevel();

    @Inject(method = "onBlockChange", at = @At("HEAD"), require = 0)
    private void pmweather_aeronautics$markAeroSurfaceDirty(final BlockPos pos,
                                                            final BlockState state,
                                                            final CallbackInfo ci) {
        if (this.getSubLevel() instanceof final ServerSubLevel serverSubLevel) {
            AeroSurfaceCache.markDirty(serverSubLevel);
        }
    }
}
