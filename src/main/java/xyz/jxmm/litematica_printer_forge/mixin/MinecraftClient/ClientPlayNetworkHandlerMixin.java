package xyz.jxmm.litematica_printer_forge.mixin.MinecraftClient;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.tool.ToolMode;
import xyz.jxmm.litematica_printer_forge.LitematicaMixinMod;
import xyz.jxmm.litematica_printer_forge.utils.Printer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

	@Shadow
	@Final
	private Minecraft minecraft;
	private static boolean isSynced = false;

	/*
			SyncHandler defined at ServerPlayerEntity is responsible to sync, but actually client process actions and executes too. so Server sync does not match with client, which causes desync.
			We can see ghost items in this context, especially with high ping. But, if there's no packet loss, whatever client has executed will be done in order correctly, like click recipe -> press Q in result slot even if its empty.
			So server sync is not totally required for most cases.
		 */
	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true, require = 0)
	private void onUpdateSlots(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		final LocalPlayer player = this.minecraft.player;
		if (!isSynced && player != null) {
			isSynced = true;
			while (packet.getStateId() != player.containerMenu.getStateId()) {
				player.containerMenu.incrementStateId();
			}
			//player.sendMessage(Text.of("Matched rev on start : "+ packet.getRevision()));
			return;
		}
		if (player != null && LitematicaMixinMod.DEBUG_PACKET_SYNC.getBooleanValue()) {
			int rev = player.containerMenu.getStateId();
			//MessageHolder.sendPacketOrders("Recieved packet of revision " + packet.getRevision() + " current revision is " + rev);
			if (LitematicaMixinMod.DISABLE_SYNC.getBooleanValue()) {
				if (!(this.minecraft.screen instanceof CreativeModeInventoryScreen) && shouldCancel(rev, packet.getStateId())) {
					ci.cancel();
				} else {
					while (packet.getStateId() != player.containerMenu.getStateId()) {
						player.containerMenu.incrementStateId();
					}
					if (packet.getSlot() == -1) {
						//okay wtf? server is actually trying to disconnect client.
						if (packet.getContainerId() == -1 && !(this.minecraft.screen instanceof CreativeModeInventoryScreen)) {
							this.minecraft.execute(() -> player.containerMenu.setCarried(packet.getItem()));
						}
						return;
					}
					this.minecraft.execute(() -> player.containerMenu.setItem(packet.getSlot(), packet.getStateId(), packet.getItem()));
				}
				//MessageHolder.sendMessageUnchecked("Cancelled ");
			}
			return;
		}
		if (Printer.isSleeping) {
			return;
		}
		if (DataManager.getToolMode() != ToolMode.REBUILD && Configs.Generic.EASY_PLACE_MODE.getBooleanValue() && Configs.Generic.EASY_PLACE_HOLD_ENABLED.getBooleanValue() && Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld()) {
			if (LitematicaMixinMod.DISABLE_SYNC.getBooleanValue()) {
				ci.cancel();
			}
		}
	}

	@Inject(method = "handleDisconnect", at = @At("HEAD"))
	private void handleDisconnect(ClientboundDisconnectPacket p_104954_, CallbackInfo ci) {
		isSynced = false;
	}

	@Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true, require = 0)
	private void onUpdateSelectSlots(ClientboundSetCarriedItemPacket packet, CallbackInfo ci) {
		if (Printer.isSleeping) {
			return;
		}
		if (DataManager.getToolMode() != ToolMode.REBUILD && Configs.Generic.EASY_PLACE_MODE.getBooleanValue() && Configs.Generic.EASY_PLACE_HOLD_ENABLED.getBooleanValue() && Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld()) {
			if (LitematicaMixinMod.DISABLE_SYNC.getBooleanValue()) {
				ci.cancel();
			}
		}
	}

	private static boolean shouldCancel(int current, int packet) {
		if (current == packet) {
			return false;
		}
		int abs = Math.abs(current - packet);
		if (abs > 1024 && abs < 32760) {
			return false;
		}
		return (Math.abs(current - packet) > 32760) == (current < packet);
	}

}