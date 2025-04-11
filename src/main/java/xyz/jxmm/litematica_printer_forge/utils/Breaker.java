package xyz.jxmm.litematica_printer_forge.utils;

import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The breaking needs to be done every tick, since the WorldUtils.easyPlaceOnUseTick (which calls our Printer)
 * is called multiple times per tick we cannot break blocks through that method. Or the speed will be twice the
 * normal speed and detectable by anti-cheats.
 */
public class Breaker implements IClientTickHandler {

	private boolean breakingBlock = false;
	private BlockPos pos;

	public Breaker() {
		TickHandler.getInstance().registerClientTickHandler(this);
	}

	public boolean startBreakingBlock(BlockPos pos, Minecraft mc) {
		this.breakingBlock = true;
		this.pos = pos;
		// Check for best tool in inventory
		if (mc.level.getBlockState(pos).getDestroySpeed(mc.level, pos) == 0) {
			mc.gameMode.startDestroyBlock(pos, Direction.UP);
			return false;
		}
		int bestSlotId = getBestItemSlotIdToMineBlock(mc, pos);
		// If slot isn't selected, change
		if (bestSlotId != -1) {
			ItemStack stack = mc.player.getInventory().getItem(bestSlotId);
			InventoryUtils.swapToItem(mc, stack);
		}
		// Start breaking
		BlockState blockState = mc.level.getBlockState(pos);
		if (blockState.getDestroyProgress(mc.player, mc.player.level, pos) >= 1.0F) {
			mc.gameMode.startDestroyBlock(pos, Direction.UP);
			return false;
		}
		TickHandler.getInstance().registerClientTickHandler(this);
		return true;
	}

	public boolean isBreakingBlock() {
		if (this.pos == null || Minecraft.getInstance().level == null) {
			return false;
		}
		if (Minecraft.getInstance().level.getBlockState(pos).getMaterial().isReplaceable()) {
			this.breakingBlock = false;
		}
		return this.breakingBlock;
	}

	public static int getBestItemSlotIdToMineBlock(Minecraft mc, BlockPos blockToMine) {
		int bestSlot = -1;
		float bestSpeed = 0;
		BlockState state = mc.level.getBlockState(blockToMine);
		for (int i = mc.player.getInventory().getContainerSize(); i >= 0; i--) {
			float speed = getBlockBreakingSpeed(state, mc, i);
			if ((speed > bestSpeed && speed > 1.0F)
				|| (speed >= bestSpeed && !mc.player.getInventory().getItem(i).isDamageableItem())) {
				bestSlot = i;
				bestSpeed = speed;
			}
		}
		return bestSlot;
	}

	public static int getBestItemSlotIdToMineState(Minecraft mc, BlockState state) {
		int bestSlot = -1;
		float bestSpeed = 0;
		for (int i = mc.player.getInventory().getContainerSize(); i >= 0; i--) {
			float speed = getBlockBreakingSpeed(state, mc, i);
			if ((speed > bestSpeed && speed > 1.0F)
				|| (speed >= bestSpeed && !mc.player.getInventory().getItem(i).isDamageableItem())) {
				bestSlot = i;
				bestSpeed = speed;
			}
		}
		return bestSlot;
	}

	public static float getBlockBreakingSpeed(BlockState block, Minecraft mc, int slotId) {
		if (slotId < -1 || slotId >= 36) {
			return 0;
		}
		float f = ((ItemStack) mc.player.getInventory().items.get(slotId)).getDestroySpeed(block);
		if (f > 1.0F) {
			int i = EnchantmentHelper.getBlockEfficiency(mc.player);
			ItemStack itemStack = mc.player.getInventory().getSelected();
			if (i > 0 && !itemStack.isEmpty()) {
				f += (float) (i * i + 1);
			}
		}
		return f;
	}

	@Override
	public void onClientTick(Minecraft mc) {
		if (!isBreakingBlock() || mc.player == null) {
			this.breakingBlock = false;
			return;
		}

		if (Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld()) { // Only continue mining while the correct keys are pressed
			Direction side = Direction.values()[0];
			if (mc.gameMode.continueDestroyBlock(pos, side)) {
				mc.particleEngine.crack(pos, side);
				mc.player.swing(InteractionHand.MAIN_HAND);
			}
		}

		if (!mc.level.getBlockState(pos).getMaterial().isReplaceable()) {
			this.breakingBlock = false;
			return;
		} // If block isn't broken yet, dont stop
		// Stop breaking
		this.breakingBlock = false;
		mc.gameMode.stopDestroyBlock();
	}

}
