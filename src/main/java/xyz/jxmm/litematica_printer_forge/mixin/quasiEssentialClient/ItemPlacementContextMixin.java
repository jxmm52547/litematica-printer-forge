package xyz.jxmm.litematica_printer_forge.mixin.quasiEssentialClient;

import xyz.jxmm.litematica_printer_forge.LitematicaMixinMod;
import xyz.jxmm.litematica_printer_forge.utils.FakeAccurateBlockPlacement;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockPlaceContext.class, priority = 1200)
public class ItemPlacementContextMixin {

	@Inject(method = "getNearestLookingDirection", at = @At("HEAD"), cancellable = true, require = 0)
	private void onGetDirection(CallbackInfoReturnable<Direction> cir) {
		if (FakeAccurateBlockPlacement.fakeDirection != null && FakeAccurateBlockPlacement.requestedTicks > -3) {
			cir.setReturnValue(FakeAccurateBlockPlacement.getFacingOrder()[0]);
		}
	}

	@Inject(method = "getNearestLookingVerticalDirection", at = @At("HEAD"), cancellable = true, require = 0)
	private void onGetVerticalDirection(CallbackInfoReturnable<Direction> cir) {
		if (FakeAccurateBlockPlacement.fakeDirection != null && FakeAccurateBlockPlacement.requestedTicks > -3 && FakeAccurateBlockPlacement.fakeDirection.getAxis() == Direction.Axis.Y) {
			cir.setReturnValue(FakeAccurateBlockPlacement.fakeDirection);
		}
	}

	@Redirect(method = "getNearestLookingDirections", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/Direction;orderedByNearest(Lnet/minecraft/world/entity/Entity;)[Lnet/minecraft/core/Direction;"), require = 0)
	private Direction[] onGetArrayDirections(Entity entity) {
		if (!LitematicaMixinMod.DISABLE_SINGLEPLAYER_HANDLE.getBooleanValue() && FakeAccurateBlockPlacement.fakeDirection != null && FakeAccurateBlockPlacement.requestedTicks > -3) {
			return FakeAccurateBlockPlacement.getFacingOrder();
		}
		return Direction.orderedByNearest(entity);
	}
}