package com.axes.pmweather_aeronautics.mixin;

import com.axes.pmweather_aeronautics.OffroadWheelContactAdapter;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the exact contact normal used by Offroad's shared TireLike wheel implementation. */
@Pseudo
@Mixin(targets = {
        "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity$TerrainCastResult",
        "dev.qwxon.tracks.content.blocks.sable_track.SableTrackBlockEntity$TerrainCastResult"
}, remap = false)
public abstract class OffroadTerrainCastResultMixin {
    @Inject(method = "normal()Lnet/minecraft/core/Direction;", at = @At("RETURN"), require = 0)
    private void pmaero$terrainNormal(final CallbackInfoReturnable<Direction> cir) {
        OffroadWheelContactAdapter.terrainNormal(cir.getReturnValue());
    }

    @Inject(method = "subLevel()Ldev/ryanhcode/sable/sublevel/SubLevel;", at = @At("RETURN"), require = 0)
    private void pmaero$terrainSubLevel(final CallbackInfoReturnable<SubLevel> cir) {
        OffroadWheelContactAdapter.terrainSubLevel(cir.getReturnValue());
    }
}
