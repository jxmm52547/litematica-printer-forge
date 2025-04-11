package xyz.jxmm.litematica_printer_forge.mixin.quasiEssentialClient;

import xyz.jxmm.litematica_printer_forge.utils.FakeAccurateBlockPlacement;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static xyz.jxmm.litematica_printer_forge.utils.FakeAccurateBlockPlacement.getPlayerFacing;

@Mixin(value = UseOnContext.class, priority = 1200)
public class ItemUsageContextMixin {
	@Inject(method = "getHorizontalDirection", at = @At("HEAD"), cancellable = true, require = 0)
	private void onGetFacing(CallbackInfoReturnable<Direction> cir) {
		Direction direction = getPlayerFacing();
		if (direction != null && FakeAccurateBlockPlacement.fakeDirection != null && FakeAccurateBlockPlacement.requestedTicks > -3 && FakeAccurateBlockPlacement.fakeDirection.getAxis() != Direction.Axis.Y) {
			cir.setReturnValue(direction);
		}
	}

	@Inject(method = "getRotation", at = @At("HEAD"), cancellable = true, require = 0)
	private void onGetYaw(CallbackInfoReturnable<Float> cir) {
		if (FakeAccurateBlockPlacement.requestedTicks > -3 && FakeAccurateBlockPlacement.fakeDirection != null) {
			cir.setReturnValue(FakeAccurateBlockPlacement.fakeYaw);
		}
	}
}