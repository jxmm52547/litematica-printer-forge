package xyz.jxmm.litematica_printer_forge.mixin.Litematica;

import fi.dy.masa.litematica.util.WorldUtils;
import xyz.jxmm.litematica_printer_forge.LitematicaMixinMod;
import xyz.jxmm.litematica_printer_forge.utils.MessageHolder;
import xyz.jxmm.litematica_printer_forge.utils.Printer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static xyz.jxmm.litematica_printer_forge.LitematicaMixinMod.PRINTER_ONLY_FAKE_ROTATION_MODE;

@Mixin(value = WorldUtils.class, remap = false, priority = 1010)
public class WorldUtilsMixin {
	private static boolean hasSent = false;

	/**
	 * @author joe mama // fixed by AngelBottomless
	 */
	//@Overwrite
	@Inject(
		method = "doEasyPlaceAction",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void onDoEasyPlaceAction(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir) {
		if (mc.player == null) {
			return;
		}
		if (PRINTER_ONLY_FAKE_ROTATION_MODE.getBooleanValue()) {
			LitematicaMixinMod.USE_INVENTORY_CACHE.setBooleanValue(false);
			cir.setReturnValue(Printer.doEasyPlaceFakeRotation(mc));
		}
		else {
			if (LitematicaMixinMod.PRINTER_OFF.getBooleanValue()) {
				return;
			}
			InteractionResult defaultResult = InteractionResult.SUCCESS;
			try {
				defaultResult = Printer.doPrinterAction(mc);
			} catch (NullPointerException e) {
				//in case of NPE, print log instead
				MessageHolder.sendMessageUncheckedUnique(mc.player, e.getMessage());
				if (!hasSent && mc.player != null) {
					mc.player.displayClientMessage(Component.nullToEmpty("Null pointer exception has occured, please upload log at https://github.com/aria1th/litematica-printer/issues"), false);
					hasSent = true;
				}
			} catch (AssertionError e) {
				MessageHolder.sendOrderMessage("Order error happened " + e.getMessage());
				MessageHolder.sendMessageUncheckedUnique(mc.player, "Order Error Happened " + e.getMessage());
				cir.setReturnValue(InteractionResult.FAIL);
				return;
			}
			cir.setReturnValue(defaultResult);
			//return defaultResult;
		}
	}
}
