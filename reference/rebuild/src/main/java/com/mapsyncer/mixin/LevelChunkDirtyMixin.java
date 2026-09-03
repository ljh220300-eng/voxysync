package com.mapsyncer.mixin;

import com.mapsyncer.server.DirtyRegionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkDirtyMixin {
    @Inject(
        method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
        at = @At("RETURN")
    )
    private void mapsyncer$markDirtyRegion(BlockPos pos, BlockState state, int flags,
                                           CallbackInfoReturnable<BlockState> cir) {
        BlockState oldState = cir.getReturnValue();
        if (oldState == null || oldState == state || oldState.equals(state)) {
            return;
        }

        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level instanceof ServerLevel serverLevel) {
            DirtyRegionTracker.markDirty(serverLevel, pos);
        }
    }
}
