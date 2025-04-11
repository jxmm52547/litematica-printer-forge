package xyz.jxmm.litematica_printer_forge.utils;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import xyz.jxmm.litematica_printer_forge.LitematicaMixinMod;
import net.minecraft.world.level.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.core.BlockPos;

import java.util.*;

@SuppressWarnings({"ConstantConditions", "unused"})
public
class ItemInputs {

	private static long handling = new Date().getTime();
	private static final HashSet<Long> handledPos = new HashSet<>();
	private static Map.Entry<Long, Long> entry;
	public static BlockPos clickedPos = null;

	public static void clear() {
		handledPos.clear();
	}

	public static boolean canHandle() {
		return new Date().getTime() > handling + LitematicaMixinMod.INVENTORY_OPERATIONS_WAIT.getIntegerValue();
	}

	private static void handle() {
		handling = new Date().getTime();
	}

	/***
	 @param player : player Entity
	 @param required : List of stacks, might be empty to return false
	 @return boolean : should process or not
	 ***/
	public static boolean matchStacks(List<ItemStack> required, List<Slot> current, LocalPlayer player, boolean allowNamed) {
		if (required.isEmpty()) {
			return false;
		}
		List<ItemStack> copy = new ArrayList<>();
		for (int i = 0; i < required.size(); i++) {
			ItemStack copiedStack = required.get(i).copy();
			copiedStack.shrink(current.get(i).getItem().getCount());
			copy.add(copiedStack);
		}
		int[] countArray = player.getInventory().items.stream().mapToInt(ItemStack::getCount).toArray();
		for (ItemStack itemStack : copy) {
			if (itemStack.isEmpty()) {
				continue;
			}
			//MessageHolder.sendUniqueDebugMessage("Checking for stack " + itemStack);
			int requiredAmount = itemStack.getCount();
			int i = 0;
			for (ItemStack playerStacks : player.getInventory().items) {
				if (countArray[i] <= 0) {
					i++;
					continue;
				}
				if (InventoryUtils.areItemsExact(itemStack, playerStacks, allowNamed)) {
					//MessageHolder.sendUniqueDebugMessage("Found stack " + itemStack + " with count " + countArray[i] + " at slot num" + i + " when remaining " + requiredAmount);
					if (countArray[i] >= requiredAmount) {
						countArray[i] -= requiredAmount;
						requiredAmount = 0;
					} else {
						requiredAmount -= countArray[i];
						countArray[i] = 0;
					}
				}
				//MessageHolder.sendUniqueDebugMessage("Item count array is " + Arrays.toString(countArray));
				if (requiredAmount <= 0) {
					break;
				}
				i++;
			}
			if (requiredAmount > 0) {
				return false;
			}
		}
		return true;
	}

	private static int getPreference(Minecraft client, AbstractContainerMenu screen, ItemStack itemStack, boolean allowNamed) {
		if (screen == null || itemStack.isEmpty()) {
			return -1;
		}
		for (int i = 0; i < screen.slots.size(); i++) {
			if (!(screen.getSlot(i).container instanceof Inventory)) {
				continue;
			}
			ItemStack playerStacks = screen.getSlot(i).getItem();
			if (InventoryUtils.areItemsExact(itemStack, playerStacks, allowNamed)) {
				return i;
			}
		}
		return -1;
	}

	private static List<Slot> getNonPlayerSlots(Minecraft client, AbstractContainerMenu screen) {
		List<Slot> retVal = new ArrayList<>();
		for (int i = 0; i < screen.slots.size(); i++) {
			if ((screen.getSlot(i).container instanceof Inventory)) {
				continue;
			}
			retVal.add(screen.getSlot(i));
		}
		return retVal;
	}

	private static void clearCursor(Minecraft client) {
		final LocalPlayer player = client.player;
		if (player.containerMenu != null && !player.containerMenu.getCarried().isEmpty()) {
			ItemStack cursorStack = player.containerMenu.getCarried();
			AbstractContainerMenu handler = player.containerMenu;
			for (int i = 0; i < handler.slots.size(); i++) {
				if (ItemStack.isSameItemSameTags(cursorStack, handler.getSlot(i).getItem())) {
					client.gameMode.handleInventoryMouseClick(handler.containerId, handler.getSlot(i).index, 0, ClickType.PICKUP, player);
					return;
				}
			}
			client.gameMode.handleInventoryMouseClick(handler.containerId, -999, 1, ClickType.THROW, player);
		}
	}

	public static void execute(Minecraft client) {
		if (canHandle()) {
			handle();
		} else {
			MessageHolder.sendUniqueMessageActionBar(client.player, "Cooldown....");
			return;
		}
		boolean allowNamed = LitematicaMixinMod.INVENTORY_OPERATIONS_FILTER_ALLOW_NAMED.getBooleanValue();
		BlockPos where = rayCast(client);
		if (where == null) {
			MessageHolder.sendUniqueMessageActionBar(client.player, "Failed to raycast");
			return;
		}
		if (handledPos.contains(where.asLong())) {
			MessageHolder.sendUniqueMessageActionBar(client.player, "Position is already handled");
			clickedPos = null;
			client.player.hasContainerOpen();
			return;
		}
		if (client.player.containerMenu == client.player.inventoryMenu) {
			MessageHolder.sendUniqueMessageActionBar(client.player, "Screen is not extra screen");
			return;
		}
		client.player.containerMenu.resumeRemoteUpdates();
		client.player.containerMenu.sendAllDataToRemote();
		List<ItemStack> requiredStacks = getRaycastRequiredItemStacks(client);
		if (requiredStacks.isEmpty()) {
			MessageHolder.sendUniqueDebugMessage("required stacks were empty for " + where.toShortString());
			client.player.hasContainerOpen();
			return;
		}
		MessageHolder.sendUniqueDebugMessage("Handled pos " + where.toShortString());
		List<Slot> nonPlayerSlot = getNonPlayerSlots(client, client.player.containerMenu);
		if (matchStacks(requiredStacks, nonPlayerSlot, client.player, allowNamed)) {
			//MessageHolder.sendUniqueDebugMessage("Required pos " + where.toShortString());

			//MessageHolder.sendUniqueDebugMessage(nonPlayerSlot.toString());
			if (requiredStacks.size() != nonPlayerSlot.size()) {
				MessageHolder.sendMessageUncheckedUnique(client.player, "Sizes differ as " + requiredStacks.size() + " but non-player slot size : " + nonPlayerSlot.size());
				return;
			}
			if (entry == null || entry.getKey() != where.asLong()) {
				entry = Map.entry(where.asLong(), new Date().getTime() + LitematicaMixinMod.INVENTORY_OPERATIONS_WAIT.getIntegerValue());
				return;
			} else if (entry.getValue() > new Date().getTime()) {
				return;
			} else {
				entry = null;
			}
			boolean allCorrect = true;
			for (int j = 0; j < LitematicaMixinMod.INVENTORY_OPERATIONS_RETRY.getIntegerValue(); j++) {
				for (int i = 0; i < requiredStacks.size(); i++) {
					if (InventoryUtils.areItemsExactCount(nonPlayerSlot.get(i).getItem(), requiredStacks.get(i), allowNamed)) {
						continue;
					}
					sendItem(client, nonPlayerSlot.get(i), requiredStacks.get(i), allowNamed);
					if (!InventoryUtils.areItemsExactCount(nonPlayerSlot.get(i).getItem(), requiredStacks.get(i), allowNamed)) {
						allCorrect = false;
					}
				}
			}
			if (allCorrect) {
				handledPos.add(where.asLong());
				MessageHolder.sendUniqueDebugMessage("Successfully done operation at " + where.toShortString());
				if (LitematicaMixinMod.INVENTORY_OPERATIONS_CLOSE_SCREEN.getBooleanValue()) {
					client.player.hasContainerOpen();
				}
			} else {
				MessageHolder.sendUniqueDebugMessage("Partially failed to send all items at " + where.toShortString() + ", will retry");
			}
		} else {
			MessageHolder.sendUniqueDebugMessage("Does not have enough item for " + where.toShortString() + "!");
			if (LitematicaMixinMod.INVENTORY_OPERATIONS_CLOSE_SCREEN.getBooleanValue()) {
				client.player.hasContainerOpen();
			}
		}
	}

	/***
	 * Part of the execution
	 * @param client : Minecraft
	 * @param targetSlot : Integer of slot defined as slot.id
	 * @param stack : Wanted slot to send
	 * @param allowNamed : allows Named item to go instead
	 */
	private static void sendItem(Minecraft client, int targetSlot, ItemStack stack, boolean allowNamed) {
		clearCursor(client);
		clearUnmatchTargetSlot(client, targetSlot, stack, allowNamed);
		int holding = getPreference(client, client.player.containerMenu, stack, allowNamed);
		if (holding == -1) {
			return;
		} //actually we can do this
		AbstractContainerMenu screenHandler = client.player.containerMenu;
		leftClickSlot(screenHandler, holding);
		for (int i = 0; i < stack.getCount() - screenHandler.getSlot(targetSlot).getItem().getCount(); i++) {
			rightClickSlot(screenHandler, targetSlot);
		}
		leftClickSlot(screenHandler, holding);
		MessageHolder.sendUniqueDebugMessage("Sent item from " + holding + " to " + targetSlot);
		//client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, targetSlot, holding, SlotActionType.SWAP, client.player);
	}

	private static void sendItem(Minecraft client, Slot targetSlot, ItemStack stack, boolean allowNamed) {
		sendItem(client, targetSlot.index, stack, allowNamed);
	}

	private static void clearUnmatchTargetSlot(Minecraft client, int targetSlot, ItemStack wantedItem, boolean allowNamed) {
		ItemStack slotStack = client.player.containerMenu.getSlot(targetSlot).getItem();
		if (InventoryUtils.areItemsExact(slotStack, wantedItem, allowNamed) && slotStack.getCount() <= wantedItem.getCount()) {
			return;
		}
		//else we need to clear
		AbstractContainerMenu gui = client.player.containerMenu;
		leftClickSlot(gui, targetSlot);
		clearCursor(client);
	}

	private static void leftClickSlot(AbstractContainerMenu gui, int slotNum) {
		clickSlot(gui, slotNum, 0, ClickType.PICKUP);
	}

	private static void rightClickSlot(AbstractContainerMenu gui, int slotNum) {
		clickSlot(gui, slotNum, 1, ClickType.PICKUP);
	}

	private static void shiftClickSlot(AbstractContainerMenu gui, int slotNum) {
		clickSlot(gui, slotNum, 0, ClickType.QUICK_MOVE);
	}

	public static void clickSlot(AbstractContainerMenu gui, int slotNum, int button, ClickType action) {
		if (slotNum >= 0 && slotNum < gui.slots.size()) {
			Slot slot = gui.getSlot(slotNum);
			clickSlot(gui, slot, button, action);
		}
	}

	public static void clickSlot(AbstractContainerMenu gui, Slot slot, int button, ClickType action) {
		try {
			Minecraft.getInstance().gameMode.handleInventoryMouseClick(gui.containerId, slot.index, button, action, Minecraft.getInstance().player);
		} catch (Exception e) {
			MessageHolder.sendMessageUncheckedUnique(Minecraft.getInstance().player, "Clicking slot failed ");
			MessageHolder.sendMessageUncheckedUnique(Minecraft.getInstance().player, e.getMessage());
		}
	}

	public static List<ItemStack> getRaycastRequiredItemStacks(Minecraft minecraftClient) {
		List<ItemStack> retVal = new ArrayList<>();
		Screen screen = minecraftClient.screen;
		if (screen == null) {
			return retVal;
		}
		if (screen instanceof InventoryScreen) {
			MessageHolder.sendUniqueDebugMessage(minecraftClient.player, "Screen was InventoryScreen");
			return retVal;
		}
		BlockPos context = rayCast(minecraftClient);
		if (context == null) {
			return retVal;
		}
		if (handledPos.contains(context.asLong())) {
			MessageHolder.sendUniqueDebugMessage(minecraftClient.player, "Screen was already registered");
			return retVal;
		}
		if (context == null) {
			return retVal;
		}
		if (!(screen instanceof ContainerScreen || screen instanceof DispenserScreen || screen instanceof HopperScreen)) {
			return retVal;
		}
		return InventoryUtils.getRequiredStackInSchematic(SchematicWorldHandler.getSchematicWorld(), minecraftClient, context);
	}

	private static BlockPos rayCast(Minecraft minecraftClient) {
		if (clickedPos == null) {
			MessageHolder.sendUniqueMessageActionBar(minecraftClient.player, "Current raycast is set to null");
			return null;
		}
		MessageHolder.sendUniqueMessageActionBar(minecraftClient.player, "Current raycast is set to " + clickedPos.toShortString());
		BlockPos castedPos = clickedPos;
		Block block = minecraftClient.level.getBlockState(castedPos).getBlock();
		if (block instanceof BaseEntityBlock) {
			if (block instanceof HopperBlock || block instanceof ChestBlock || block instanceof DispenserBlock) {
				return castedPos;
			}
		}
		return null;
	}


}