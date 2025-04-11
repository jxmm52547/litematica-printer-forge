package xyz.jxmm.litematica_printer_forge.mixin.MinecraftClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class ClientPlayerInteractionManagerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "handleInventoryMouseClick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 1)
	private void getNextRevision(int syncId, int slotId, int button, ClickType actionType, Player player, CallbackInfo ci) {
		player.containerMenu.incrementStateId();
	}

	@Inject(method = "handleCreativeModeItemAdd", at = @At("TAIL"), require = 1)
	private void getNextRevision(ItemStack stack, int slotId, CallbackInfo ci) {
		final LocalPlayer player = this.minecraft.player;
		if (player != null) {
			if (!(player.containerMenu instanceof CreativeModeInventoryScreen.ItemPickerMenu) && !player.getInventory().getItem(slotId).equals(stack)) {
				player.containerMenu.incrementStateId();
				//player.sendMessage(Text.of("Slot was "+ slotId+ " stack was " +stack));
				player.containerMenu.setItem(slotId, player.containerMenu.incrementStateId(), stack);
			}
		}
	}
}