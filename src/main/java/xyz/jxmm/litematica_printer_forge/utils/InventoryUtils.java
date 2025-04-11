package xyz.jxmm.litematica_printer_forge.utils;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import xyz.jxmm.litematica_printer_forge.LitematicaMixinMod;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import static xyz.jxmm.litematica_printer_forge.utils.Printer.isSleeping;

public class InventoryUtils {
	private static int ptr = -1;
	public static int lastCount = 0;
	public static int itemChangeCount = 0;
	public static Item handlingItem = null;
	public static Item previousItem = null; //only used for checks
	public static int trackedSelectedSlot = -1;
	public static HashMap<Integer, Item> usedSlots = new LinkedHashMap<>();
	public static HashMap<Integer, Integer> slotCounts = new LinkedHashMap<>();

	public static void tick() {
		if (!isSleeping && Configs.Generic.EASY_PLACE_MODE.getBooleanValue() && Configs.Generic.EASY_PLACE_HOLD_ENABLED.getBooleanValue() && Hotkeys.EASY_PLACE_ACTIVATION.getKeybind().isKeybindHeld()) {
			for (int i = 0; i < 9; i++) {
				if (!usedSlots.containsKey(i)) {
					continue;
				}
				if (slotCounts.get(i) <= 0) {
					usedSlots.remove(i);
					slotCounts.remove(i);
				}
			}
		} else {
			trackedSelectedSlot = -1;
			previousItem = null;
			handlingItem = null;
			usedSlots.clear();
			slotCounts.clear();
		}
	}


	public static void decrementCount() {
		if (lastCount > 0) {
			lastCount--;
			slotCounts.computeIfPresent(trackedSelectedSlot, (key, value) -> value - 1);
		}
	}
	public static void decrementCount(boolean isCreative) {
		if (isCreative) lastCount = 65536;
		if (lastCount > 0) {
			lastCount--;
			slotCounts.computeIfPresent(trackedSelectedSlot, (key, value) -> value - 1);
		}
	}
	private static int getPtr() {
		ptr++;
		ptr = ptr % 9;
		return ptr;
	}

	public static int getAvailableSlot(Item item) {
		if (usedSlots.containsValue(item)) {
			for (Integer i : usedSlots.keySet()) {
				if (usedSlots.get(i) == item) {
					return i;
				}
			}
			return -1;
		}
		if (usedSlots.size() == 9) { //full
			return getPtr();
		}
		for (int i = 0; i < 9; i++) {
			if (usedSlots.containsKey(i)) {
				continue;
			}
			return i;
		}
		return -1;
	}

	private static int searchSlot(Item item) {
		for (Integer i : usedSlots.keySet()) {
			if (usedSlots.get(i) == item && slotCounts.getOrDefault(i, 0) > 0) {
				return i;
			}
		}
		return -1;
	}

	public static boolean hasEmptyHotbar() {
		return usedSlots.size() < 9;
	}

	public static ItemStack getMainHandStack(LocalPlayer player) {
		return player.getMainHandItem();
	}

	public static boolean areItemsExact(ItemStack a, ItemStack b) {
		return ItemStack.isSameIgnoreDurability(a, b) && ItemStack.tagMatches(a, b);
	}

	public static boolean areItemsExact(ItemStack a, ItemStack b, boolean allowNamed) {
		if (allowNamed) {
			return areItemsExactAllowNamed(a, b);
		}
		return ItemStack.isSameIgnoreDurability(a, b) && ItemStack.tagMatches(a, b);
	}

	public static boolean areItemsExactCount(ItemStack a, ItemStack b, boolean allowNamed) {
		if (a.getCount() != b.getCount()) {
			return false;
		}
		if (allowNamed) {
			return areItemsExactAllowNamed(a, b);
		}
		return ItemStack.isSameIgnoreDurability(a, b) && ItemStack.tagMatches(a, b);
	}

	public static boolean areItemsExactAllowNamed(ItemStack a, ItemStack b) {
		if (a.getItem() instanceof TieredItem || b.getItem() instanceof TieredItem) { //safety
			return false;
		}
		return ItemStack.isSameIgnoreDurability(a, b) || a.getMaxStackSize() == b.getMaxStackSize() && a.hasCustomHoverName() && b.hasCustomHoverName();
	}

	public static boolean requiresSwap(LocalPlayer player, ItemStack stack) {
		int selectedSlot = player.getInventory().selected;
		if (usedSlots.get(selectedSlot) != null) {
			return stack.getItem() != usedSlots.get(selectedSlot) || slotCounts.getOrDefault(selectedSlot, 0) <= 0;
		}
		return previousItem == null || lastCount == 0 ? !areItemsExact(getMainHandStack(player), stack) : !areItemsExact(previousItem.getDefaultInstance(), stack);
	}

	public static boolean canSwap(LocalPlayer player, ItemStack stack) {
		if (player.getAbilities().instabuild) {
			return true;
		}
		int slotNum = player.getInventory().findSlotMatchingItem(stack);
		return slotNum != -1 && areItemsExact(player.getInventory().getItem(slotNum), stack);
	}

	synchronized public static boolean swapToItem(Minecraft client, ItemStack stack) {
		MessageHolder.sendOrderMessage("Trying to swap item into " + stack.getItem());
		LocalPlayer player = client.player;
		int maxChange = LitematicaMixinMod.PRINTER_MAX_ITEM_CHANGES.getIntegerValue();
		if (player == null || client.gameMode == null) {
			return false;
		}
		//player.getInventory().updateItems();
		if (stack.getItem() != handlingItem) {
			if (maxChange != 0 && itemChangeCount > maxChange) {
				MessageHolder.sendOrderMessage("Exceeded item change count");
				return false;
			}
		}
		if (!requiresSwap(player, stack)) {
			assert trackedSelectedSlot == -1 || trackedSelectedSlot == player.getInventory().selected : "Selected slot changed for external reason! : expected %s, current %s".formatted(trackedSelectedSlot, player.getInventory().selected);
			assert previousItem == null || previousItem == stack.getItem() : "Handling item :  " + handlingItem + " was not equal to " + stack.getItem();
			MessageHolder.sendOrderMessage("Didn't require swap for item " + stack.getItem() + " previous handling item : " + previousItem);
			lastCount = player.getAbilities().instabuild ? 65536 : getMainHandStack(player).getCount();
			if (usedSlots.containsValue(stack.getItem())) {
				if (searchSlot(stack.getItem()) != trackedSelectedSlot) {
					MessageHolder.sendMessageUncheckedUnique("Hotbar has duplicate item references, which should not happen!");
				}
			}
			trackedSelectedSlot = player.getInventory().selected;
			usedSlots.put(player.getInventory().selected, getMainHandStack(player).getItem());
			slotCounts.put(player.getInventory().selected, lastCount);
			previousItem = stack.getItem();
			return true;
		}
		if (usedSlots.containsValue(stack.getItem())) {
			int slot = searchSlot(stack.getItem());
			if (slot != -1) {
				player.getInventory().selected = slot;
				trackedSelectedSlot = player.getInventory().selected;
				usedSlots.put(trackedSelectedSlot, stack.getItem());
				slotCounts.put(trackedSelectedSlot, stack.getCount());
				lastCount = stack.getCount();
				previousItem = stack.getItem();
				handlingItem = previousItem;
				MessageHolder.sendOrderMessage("Selected slot " + player.getInventory().selected + " based on cache for " + stack.getItem());
				client.getConnection().send(new ServerboundSetCarriedItemPacket(player.getInventory().selected));
				return !player.getInventory().getSelected().isEmpty();
			}
		}
		if (survivalSwap(client, player, stack)) {
			usedSlots.put(player.getInventory().selected, stack.getItem());
			slotCounts.put(trackedSelectedSlot, getMainHandStack(player).getCount());
			MessageHolder.sendOrderMessage("Swapped to item " + stack.getItem());
			handlingItem = stack.getItem();
			previousItem = handlingItem;
			itemChangeCount++;
			return true;
		}
		return creativeSwap(client, player, stack);
	}

	public static int findSlotMatchingItem(LocalPlayer player, ItemStack stack) {
		return player.getInventory().findSlotMatchingItem(stack);
	}

	@SuppressWarnings("ConstantConditions")
	private static boolean creativeSwap(Minecraft client, LocalPlayer player, ItemStack stack) {
		if (!player.getAbilities().instabuild) {
			return false;
		}
		int selectedSlot = getAvailableSlot(stack.getItem());
		if (selectedSlot == -1) {
			return false;
		}
		MessageHolder.sendOrderMessage("Clicked creative stack " + stack.getItem() + " for slot " + selectedSlot);
		//player.getInventory().addPickBlock(stack);
		player.getInventory().selected = selectedSlot;
		client.gameMode.handleCreativeModeItemAdd(stack, 36 + selectedSlot);
		client.getConnection().send(new ServerboundSetCarriedItemPacket(player.getInventory().selected));
		trackedSelectedSlot = selectedSlot;
		player.getInventory().items.set(selectedSlot, stack);
		usedSlots.put(player.getInventory().selected, stack.getItem());
		slotCounts.put(trackedSelectedSlot, 65536);
		lastCount = 65536;
		handlingItem = stack.getItem();
		previousItem = handlingItem;
		itemChangeCount++;
		return true;
	}

	@SuppressWarnings("ConstantConditions")
	private static boolean survivalSwap(Minecraft client, LocalPlayer player, ItemStack stack) {
		if (!canSwap(player, stack)) {
			return false;
		}
		if (areItemsExact(player.getOffhandItem(), stack) && !areItemsExact(getMainHandStack(player), stack)) {
			lastCount = client.player.getAbilities().instabuild ? 65536 : client.player.getOffhandItem().getCount();
			client.getConnection().send(new ServerboundPlayerActionPacket(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
			return true;
		}
		int slot = findSlotMatchingItem(player, stack);
		if (slot == -1) {
			return false;
		}
		if (Inventory.isHotbarSlot(slot)) {
			if (usedSlots.get(slot) != null) {
				MessageHolder.sendOrderMessage("Hotbar slot should have been handled before, so it must be error!");
				MessageHolder.sendOrderMessage("Expected : " + usedSlots.get(slot) + " but current client handles : " + stack.getItem());
				return false;
			}
			player.getInventory().selected = slot;
			trackedSelectedSlot = slot;
			MessageHolder.sendOrderMessage("Selected hotbar Slot " + slot);
			lastCount = player.getAbilities().instabuild ? 65536 : player.getInventory().getItem(slot).getCount();
			client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
		} else {
			int selectedSlot = getAvailableSlot(stack.getItem());
			if (selectedSlot == -1) {
				MessageHolder.sendOrderMessage("All hotbar slots are used");
				return false;
			}
			lastCount = player.getAbilities().instabuild ? 65536 : player.getInventory().getItem(slot).getCount();
			MessageHolder.sendOrderMessage("Slot at " + slot + "(%s)".formatted(player.getInventory().getItem(slot).getItem()) + " is swapped with " + selectedSlot + "(%s)".formatted(player.getInventory().items.get(selectedSlot)));
			usedSlots.put(selectedSlot, stack.getItem());
			client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, selectedSlot, ClickType.SWAP, player);
			player.getInventory().selected = selectedSlot;
			trackedSelectedSlot = selectedSlot;

		}
		try {
			assert getMainHandStack(player).sameItemStackIgnoreDurability(stack);
		} catch (Exception e) {
			MessageHolder.sendMessageUncheckedUnique(player, stack.toString() + " does not match with " + player.getMainHandItem() + "!");
		}
		return true;
	}

	public static List<ItemStack> getRequiredStackInSchematic(Level schematicWorld, Minecraft minecraftClient, BlockPos pos) {
		final LocalPlayer player = minecraftClient.player;
		List<ItemStack> result = new ArrayList<>();
		BlockEntity blockEntity = schematicWorld.getBlockEntity(pos);
		if (blockEntity == null) {
			return result;
		}
		if (blockEntity instanceof RandomizableContainerBlockEntity containerBlockEntity) {
			if (containerBlockEntity.isEmpty()) {
				return result;
			}
			if (containerBlockEntity.stillValid(player)) {
				for (int i = 0; i < (containerBlockEntity).getContainerSize(); i++) {
					result.add(containerBlockEntity.getItem(i));
				}
			} else {
				MessageHolder.sendMessageUncheckedUnique(player, "Container at " + pos.toShortString() + "can't be opened by player!");
			}
		}
		return result;
	}

	public static boolean hasItemInSchematic(Level schematicWorld, BlockPos pos) {
		BlockEntity blockEntity = schematicWorld.getBlockEntity(pos);
		if (blockEntity == null) {
			return false;
		}
		if (blockEntity instanceof RandomizableContainerBlockEntity containerBlockEntity) {
			if (containerBlockEntity.isEmpty()) {
				return false;
			}
			for (int i = 0; i < (containerBlockEntity).getContainerSize(); i++) {
				if (!containerBlockEntity.getItem(i).isEmpty()) {
					return false;
				}
			}
		}
		return false;
	}
}