package xyz.jxmm.litematica_printer_forge.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Objects;

import static xyz.jxmm.litematica_printer_forge.LitematicaMixinMod.*;

public class MessageHolder {
	private static final HashSet<String> uniqueStrings = new HashSet<>();
	private static final HashSet<String> uniqueStringsAlways = new HashSet<>();
	private static final HashSet<String> uniquePacketInfos = new HashSet<>();
	private static final HashSet<String> errorLogger = new HashSet<>();
	private static String orderPreviousMessage = "";

	public static void sendPacketOrders(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (uniquePacketInfos.contains(string)) {
			return;
		}
		uniquePacketInfos.add(string);
		player.displayClientMessage(Component.nullToEmpty(string), false);
	}

	public static void sendDebugMessage(LocalPlayer player, String string) {
		if (DEBUG_EXTRA_MESSAGE.getBooleanValue()) {
			player.displayClientMessage(Component.nullToEmpty(string), false);
		}
	}

	public static void sendDebugMessage(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (DEBUG_EXTRA_MESSAGE.getBooleanValue()) {
			player.displayClientMessage(Component.nullToEmpty(string), false);
		}
	}

	public static void sendOrderMessage(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (DEBUG_ORDER_PLACEMENTS.getBooleanValue() && !Objects.equals(orderPreviousMessage, string)) {
			orderPreviousMessage = string;
			player.displayClientMessage(Component.nullToEmpty(string), false);
		}
	}

	public static void sendUniqueDebugMessage(LocalPlayer player, String string) {
		if (DEBUG_EXTRA_MESSAGE.getBooleanValue()) {
			if (!uniqueStrings.contains(string)) {
				player.displayClientMessage(Component.nullToEmpty(string), false);
				uniqueStrings.add(string);
			}
		}
	}

	public static void sendUniqueDebugMessage(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (DEBUG_EXTRA_MESSAGE.getBooleanValue()) {
			if (!uniqueStrings.contains(string)) {
				player.displayClientMessage(Component.nullToEmpty(string), false);
				uniqueStrings.add(string);
			}
		}
	}

	public static void sendMessageUncheckedUnique(LocalPlayer player, String string) {
		if (!errorLogger.contains(string)) {
			player.displayClientMessage(Component.nullToEmpty(string), false);
			errorLogger.add(string);
		}
	}

	public static void sendMessageUncheckedUnique(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (!errorLogger.contains(string)) {
			player.displayClientMessage(Component.nullToEmpty(string), false);
			errorLogger.add(string);
		}
	}

	public static void sendMessageUnchecked(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		player.displayClientMessage(Component.nullToEmpty(string), false);
	}

	public static void sendUniqueMessage(LocalPlayer player, String string) {
		if (!DEBUG_MESSAGE.getBooleanValue()) {
			uniqueStrings.clear();
			return;
		}
		if (!uniqueStrings.contains(string)) {
			player.displayClientMessage(Component.nullToEmpty(string), false);
			uniqueStrings.add(string);
		}
	}

	public static void sendUniqueMessageAlways(String string) {
		final LocalPlayer player = Minecraft.getInstance().player;
		if (!uniqueStringsAlways.contains(string)) {
			player.displayClientMessage(Component.nullToEmpty(string), false);
			uniqueStringsAlways.add(string);
		}
	}

	public static void sendUniqueMessage(LocalPlayer player, Object object) {
		String string = object.toString();
		sendUniqueMessage(player, string);
	}

	public static void sendUniqueMessageActionBar(LocalPlayer player, String string) {
		if (!DEBUG_MESSAGE.getBooleanValue()) {
			return;
		}
		if (!uniqueStrings.contains(string)) {
			player.displayClientMessage(Component.nullToEmpty(string), true);
			uniqueStrings.add(string);
		}
	}
	public static void sendDebugMessageActionBar(LocalPlayer player, String string) {
		if (!DEBUG_MESSAGE.getBooleanValue()) {
			return;
		}
		player.displayClientMessage(Component.nullToEmpty(string), true);
		uniqueStrings.add(string);
	}
}