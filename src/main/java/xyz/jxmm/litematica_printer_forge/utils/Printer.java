package xyz.jxmm.litematica_printer_forge.utils;

import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager.PlacementPart;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.util.*;
import fi.dy.masa.litematica.util.RayTraceUtils.RayTraceWrapper;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerRange;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.block.piston.*;
import net.minecraft.world.level.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.LavaFluid;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalShape;


import javax.annotation.Nullable;
import java.util.*;

import static xyz.jxmm.litematica_printer_forge.LitematicaMixinMod.*;

@SuppressWarnings("ConstantConditions")
public class Printer {

	private static final HashSet<Long> signCache = new HashSet<>();
	private static final LinkedHashMap<Map.Entry<Long, Boolean>, PositionCache> positionCache = new LinkedHashMap<>();
	// For printing delay
	public static boolean isSleeping = false;
	public static long lastPlaced = new Date().getTime();
	private static boolean shouldSleepLonger = false;
	public static Breaker breaker = new Breaker();
	public static int worldBottomY = 0;
	public static int worldTopY = 256;
	private static final LinkedHashMap<Long, String> causeMap = new LinkedHashMap<>();
	private static final Long2LongOpenHashMap referenceSet = new Long2LongOpenHashMap();


	// TODO: This must be moved to another class and not be static.
	// Simulates and returns if player can place block as wanted.
	private static boolean simulateFacingData(BlockState state, BlockPos blockPos, Vec3 hitVec) {
		if (!state.getProperties().contains(BlockStateProperties.FACING) && !state.getProperties().contains(BlockStateProperties.HORIZONTAL_FACING)) {
			return true;
		}
		//blocks that does not require facing
		if (state.is(Blocks.HOPPER) || state.is(BlockTags.SHULKER_BOXES)) {
			return true;
		}
		// int 0 : none, 1 : clockwise, 2 : counterclockwise, 3 : reverse
		LocalPlayer player = Minecraft.getInstance().player;
		BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.NORTH, blockPos, false);
		Block block = state.getBlock();
		BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, state.getBlock().asItem().getDefaultInstance(), hitResult);
		BlockState testState;
		try {
			testState = block.getStateForPlacement(ctx);
		} catch (Exception e) { //doors wtf
			MessageHolder.sendMessageUncheckedUnique("Cannot get tested orientation of given block "+ state.getBlock().getName());
			//fallback to player horizontal facing...
			return player.getDirection() == fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(state);
		}
		if (testState == null) {
			MessageHolder.sendMessageUncheckedUnique("Cannot get tested orientation of given block "+ state.getBlock().getName());
			return player.getDirection() == fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(state);
		}
		Direction testFacing = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(testState);
		return testFacing == fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(state);
	}

	public static boolean canPickBlock(Minecraft mc, BlockState preference, BlockPos pos) {
		Level world = SchematicWorldHandler.getSchematicWorld();
		ItemStack stack = isReplaceableWaterFluidSource(preference) && PRINTER_PLACE_ICE.getBooleanValue() ? Items.ICE.getDefaultInstance() : MaterialCache.getInstance().getRequiredBuildItemForState(preference, world, pos);
		if (!stack.isEmpty() && stack.getItem() != Items.AIR) {
			Inventory inv = mc.player.getInventory();
			if (!mc.player.getAbilities().instabuild) {
				int slot = inv.findSlotMatchingItem(stack);
				if (slot == -1) {
					return false;
				}
				if (EASY_PLACE_MODE_HOTBAR_ONLY.getBooleanValue()) {
					return slot < 9;
				}
			}
			return true;
		}
		return true;
	}

	public static boolean canPickItem(Minecraft mc, ItemStack stack) {
		if (!stack.isEmpty()) {
			Inventory inv = mc.player.getInventory();
			if (!mc.player.getAbilities().instabuild) {
				int slot = inv.findSlotMatchingItem(stack);
				if (slot == -1) {
					return false;
				}
				if (EASY_PLACE_MODE_HOTBAR_ONLY.getBooleanValue()) {
					return slot < 9;
				}
			}
			return true;
		}
		return false;
	}

	/**
	 * New doSchematicWorldPickBlock that allows you to choose which block you want
	 */
	@OnlyIn(Dist.CLIENT)
	synchronized public static boolean doSchematicWorldPickBlock(Minecraft mc, BlockState preference,
	                                                             BlockPos pos) {
		Level world = SchematicWorldHandler.getSchematicWorld();
		ItemStack stack = isReplaceableWaterFluidSource(preference) && PRINTER_PLACE_ICE.getBooleanValue() ? Items.ICE.getDefaultInstance() : MaterialCache.getInstance().getRequiredBuildItemForState(preference, world, pos);
		if (!FakeAccurateBlockPlacement.canHandleOther(stack.getItem())) {
			return false;
		}
		if (!stack.isEmpty()) {
			if (USE_INVENTORY_CACHE.getBooleanValue()) {
				return xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.swapToItem(mc, stack);
			} else {
				InventoryUtils.schematicWorldPickBlock(stack, pos, world, mc);
				return mc.player.getMainHandItem().sameItemStackIgnoreDurability(stack);
			}
		}
		return false;
	}

	@OnlyIn(Dist.CLIENT)
	synchronized public static boolean doSchematicWorldPickBlock(Minecraft mc, ItemStack stack) {
		if (!FakeAccurateBlockPlacement.canHandleOther(stack.getItem())) {
			return false;
		}
		if (!stack.isEmpty()) {
			if (USE_INVENTORY_CACHE.getBooleanValue()) {
				return xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.swapToItem(mc, stack);
			} else {
				fi.dy.masa.malilib.util.InventoryUtils.swapItemToMainHand(stack, mc);
				return mc.player.getMainHandItem().sameItemStackIgnoreDurability(stack);
			}
		}
		return false;
	}
	public static InteractionResult doEasyPlaceFakeRotation(Minecraft mc) { //force normal easyplace action, ignore condition checks
		if (FakeAccurateBlockPlacement.isHandling()){
			MessageHolder.sendDebugMessage(mc.player, "Passed because already handling something");
			return InteractionResult.PASS;
		}
		RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(mc.level, mc.player, 6);
		FakeAccurateBlockPlacement.requestedTicks = Math.max(-2, FakeAccurateBlockPlacement.requestedTicks);
		if (traceWrapper == null) {
			return InteractionResult.PASS;
		}
		BlockHitResult trace = traceWrapper.getBlockHitResult();
		if (trace == null) {
			return InteractionResult.PASS;
		}
		Level world = SchematicWorldHandler.getSchematicWorld();
		Level clientWorld = mc.level;
		BlockPos blockPos = trace.getBlockPos();
		if (isPositionCached(blockPos, false)){
			MessageHolder.sendDebugMessage(mc.player, "Passed because position "+ blockPos.toShortString() + " is cached");
			return InteractionResult.PASS;
		}
		BlockState schematicState = world.getBlockState(blockPos);
		BlockState clientState = clientWorld.getBlockState(blockPos);
		if (schematicState.getBlock().getName().equals(clientState.getBlock().getName()) || schematicState.isAir()) {
			MessageHolder.sendDebugMessage(mc.player, "Passed because position "+ blockPos.toShortString() + " is satisfied");
			return InteractionResult.FAIL;
		}
		if (FakeAccurateBlockPlacement.canHandleOther(schematicState.getBlock().asItem()) && canPickBlock(mc, schematicState, blockPos)) {
			MessageHolder.sendOrderMessage("Requested " + schematicState + " at " +blockPos.toShortString());
			FakeAccurateBlockPlacement.request(schematicState, blockPos);
			return InteractionResult.SUCCESS;
		}
		MessageHolder.sendDebugMessage(mc.player, "Passed because position "+ blockPos.toShortString() + " cannot pick block or cannot handle other, handling "+ FakeAccurateBlockPlacement.currentHandling);
		return InteractionResult.FAIL;
	}
	public static InteractionResult doEasyPlaceNormally(Minecraft mc) { //force normal easyplace action, ignore condition checks
		RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(mc.level, mc.player, 6);
		if (traceWrapper == null) {
			return InteractionResult.PASS;
		}
		BlockHitResult trace = traceWrapper.getBlockHitResult();
		if (trace == null) {
			return InteractionResult.PASS;
		}
		Level world = SchematicWorldHandler.getSchematicWorld();
		Level clientWorld = mc.level;
		BlockPos blockPos = trace.getBlockPos();
		BlockState schematicState = world.getBlockState(blockPos);
		BlockState clientState = clientWorld.getBlockState(blockPos);
		if (schematicState.getBlock().getName().equals(clientState.getBlock().getName())) {
			return InteractionResult.FAIL;
		}
		ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(schematicState);
		if (!stack.isEmpty()) {
			InventoryUtils.schematicWorldPickBlock(stack, blockPos, world, mc);
			InteractionHand hand = EntityUtils.getUsedHandForItem(mc.player, stack);
			if (hand == null) {
				return InteractionResult.FAIL;
			}
			Vec3 hitPos;
			Direction sideOrig = trace.getDirection();
			Direction side = applyPlacementFacing(schematicState, sideOrig, clientState);
			if (ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
				hitPos = applyCarpetProtocolHitVec(blockPos, schematicState);
			} else {
				hitPos = applyHitVec(blockPos, schematicState, side);
			}
			BlockHitResult hitResult = new BlockHitResult(hitPos, side, blockPos, false);
			boolean canContinue;
			if (!FAKE_ROTATION_BETA.getBooleanValue() || ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) { //Accurateblockplacement, or vanilla but no fake
				canContinue = mc.gameMode.useItemOn(mc.player, hand, hitResult).consumesAction(); //PLACE block
				cacheEasyPlacePosition(blockPos, false);
			} else {
				canContinue = FakeAccurateBlockPlacement.request(schematicState, blockPos);
			}
			if (canContinue) {
				return InteractionResult.SUCCESS;
			} else {
				return InteractionResult.FAIL;
			}
		}
		return InteractionResult.FAIL;
	}

	private static void recordCause(BlockPos pos, String reason, BlockPos reasonPos) {
		if (!DEBUG_MESSAGE.getBooleanValue()) {
			return;
		}
		if (reasonPos != null) {
			if (pos.asLong() == reasonPos.asLong()) {
				causeMap.put(pos.asLong(), "self registered+\n");
				//throw new AssertionError("Position should not equal to reason position!");
			}
			referenceSet.put(pos.asLong(), reasonPos.asLong());
		}
		causeMap.put(pos.asLong(), reason + '\n');
	}

	private static void recordCause(BlockPos pos, String reason) {
		recordCause(pos, reason, null);
	}

	private static String getReason(Long pos) {
		return "<" + internalGetReason(pos, null, 0) + ">";
	}

	private static String internalGetReason(Long pos, LongOpenHashSet set, int count) {
		if (count > 10) {
			return BlockPos.of(pos).toShortString() + "RECURSIVE_COUNT_EXCEED";
		}
		if (set == null) {
			set = new LongOpenHashSet();
		}
		if (set.contains((long) pos)) {
			return BlockPos.of(pos).toShortString() + "Recursive ";
		}
		if (referenceSet.containsKey((long) pos)) {
			set.add((long) pos);
			return causeMap.getOrDefault(pos, BlockPos.of(pos).toShortString() + " : Not registered") + " " + internalGetReason(referenceSet.get((long) pos), set, count + 1);
		}
		return causeMap.getOrDefault(pos, BlockPos.of(pos).toShortString() + " : Not registered");
	}

	private static boolean isPositionWithinBox(Box box, BlockPos pos) {
		if (box == null) {
			return true;
		}
		BlockPos start = box.getPos1();
		BlockPos end = box.getPos2();
		BlockPos ref1 = new BlockPos(Math.min(start.getX(), end.getX()), Math.min(start.getY(), end.getY()), Math.min(start.getZ(), end.getZ()));
		BlockPos ref2 = new BlockPos(Math.max(start.getX(), end.getX()), Math.max(start.getY(), end.getY()), Math.max(start.getZ(), end.getZ()));
		return (ref1.getX() <= pos.getX() && pos.getX() <= ref2.getX() && ref1.getY() <= pos.getY() && pos.getY() <= ref2.getY() && ref1.getZ() <= pos.getZ() && pos.getZ() <= ref2.getZ());
	}

	@OnlyIn(Dist.CLIENT)
	synchronized public static InteractionResult doPrinterAction(Minecraft mc) {
		xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.itemChangeCount = 0;
		if (!DEBUG_MESSAGE.getBooleanValue()) {
			causeMap.clear(); //reduce ram usage
		}
		if (!BEDROCK_BREAKING.getBooleanValue()) {
			BedrockBreaker.clear();
		}
		FakeAccurateBlockPlacement.requestedTicks = Math.max(-2, FakeAccurateBlockPlacement.requestedTicks);
		if (breaker.isBreakingBlock()) {
			mc.player.displayClientMessage(Component.nullToEmpty("Handling breakBlock!"), true);
			return InteractionResult.SUCCESS;
		}
		if (INVENTORY_OPERATIONS.getBooleanValue()) {
			ItemInputs.execute(mc);
			mc.player.displayClientMessage(Component.nullToEmpty("Handling inventory operation!"), true);
			return InteractionResult.PASS;
		} else {
			ItemInputs.clear();
		}
		if (new Date().getTime() < lastPlaced + 1000.0 * EASY_PLACE_MODE_DELAY.getDoubleValue()) {
			mc.player.displayClientMessage(Component.nullToEmpty("Handling delay"), true);
			return InteractionResult.PASS;
		} else {
			isSleeping = false;
		}
		boolean isCreative = mc.player.isCreative();
		BlockPos tracePos = mc.player.blockPosition();
		int posX = tracePos.getX();
		int posY = tracePos.getY();
		int posZ = tracePos.getZ();

		RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(mc.level, mc.player, 6);
		//RayTraceWrapper traceWrapper = RayTraceUtils.getGenericTrace(mc.world, mc.player, 6, true); previous litematica code
		if (traceWrapper != null) {
			BlockHitResult trace = traceWrapper.getBlockHitResult();
			tracePos = trace.getBlockPos();
			posX = tracePos.getX();
			posY = tracePos.getY();
			posZ = tracePos.getZ();
		}

		boolean ClearArea = CLEAR_AREA_MODE.getBooleanValue(); // if its true, will ignore everything and remove fluids.
		boolean UseCobble = CLEAR_AREA_MODE_COBBLESTONE.getBooleanValue() && ClearArea;
		boolean ClearSnow = CLEAR_AREA_MODE_SNOWPREVENT.getBooleanValue() && ClearArea;
		boolean CanUseProtocol = ACCURATE_BLOCK_PLACEMENT.getBooleanValue();
		boolean FillInventory = PRINTER_PUMPKIN_PIE_FOR_COMPOSTER.getBooleanValue();
		ItemStack composableItem = Items.PUMPKIN_PIE.getDefaultInstance();
		List<PlacementPart> allPlacementsTouchingSubChunk = DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(tracePos);
		Box selectedBox = null;
		if (allPlacementsTouchingSubChunk.isEmpty() && !ClearArea) {
			if (BEDROCK_BREAKING.getBooleanValue()) {
				BedrockBreaker.scheduledTickHandler(mc, null);
			}
			return InteractionResult.PASS;
		}
		int maxX = 0;
		int maxY = 0;
		int maxZ = 0;
		int minX = 0;
		int minY = 0;
		int minZ = 0;
		int rangeX = EASY_PLACE_MODE_RANGE_X.getIntegerValue();
		int rangeY = EASY_PLACE_MODE_RANGE_Y.getIntegerValue();
		int rangeZ = EASY_PLACE_MODE_RANGE_Z.getIntegerValue();
		if (rangeX == 0 && rangeY == 0 && rangeZ == 0 && traceWrapper != null) {
			return doEasyPlaceNormally(mc);
		}
		boolean foundBox = false;
		if (ClearArea) {
			foundBox = true;
			maxX = posX + rangeX;
			maxY = posY + rangeY;
			maxZ = posZ + rangeZ;
			minX = posX - rangeX;
			minY = posY - rangeY;
			minZ = posZ - rangeZ;
		} else {
			for (PlacementPart part : allPlacementsTouchingSubChunk) {
				IntBoundingBox pbox = part.getBox();
				if (pbox.containsPos(tracePos)) {

					ImmutableMap<String, Box> boxes = part.getPlacement()
						.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED);

					for (Box box : boxes.values()) {

						final int boxXMin = Math.min(box.getPos1().getX(), box.getPos2().getX());
						final int boxYMin = Math.min(box.getPos1().getY(), box.getPos2().getY());
						final int boxZMin = Math.min(box.getPos1().getZ(), box.getPos2().getZ());
						final int boxXMax = Math.max(box.getPos1().getX(), box.getPos2().getX());
						final int boxYMax = Math.max(box.getPos1().getY(), box.getPos2().getY());
						final int boxZMax = Math.max(box.getPos1().getZ(), box.getPos2().getZ());
						if (posX < boxXMin || posX > boxXMax || posY < boxYMin || posY > boxYMax || posZ < boxZMin
							|| posZ > boxZMax) {
							continue;
						}
						minX = boxXMin;
						maxX = boxXMax;
						minY = boxYMin;
						maxY = boxYMax;
						minZ = boxZMin;
						maxZ = boxZMax;
						foundBox = true;
						selectedBox = box;
						break;
					}

					break;
				}
			}
		}

		if (!foundBox) {
			if (BEDROCK_BREAKING.getBooleanValue()) {
				BedrockBreaker.scheduledTickHandler(mc, null);
			}
			return InteractionResult.PASS;
		}
		LayerRange range = DataManager.getRenderLayerRange(); //add range following
		int MaxReach = Math.max(Math.max(rangeX, rangeY), rangeZ);
		boolean breakBlocks = PRINTER_BREAK_BLOCKS.getBooleanValue();
		boolean Flippincactus = FLIPPIN_CACTUS.getBooleanValue();
		boolean ExplicitObserver = PRINTER_OBSERVER_AVOID_ALL.getBooleanValue();
		ItemStack Mainhandstack = mc.player.getMainHandItem();
		boolean Cactus = Mainhandstack.getItem().getDescriptionId().contains("cactus") && Flippincactus;
		boolean MaxFlip = Flippincactus && Cactus;
		boolean smartRedstone = PRINTER_SMART_REDSTONE_AVOID.getBooleanValue();
		Direction[] facingSides = Direction.orderedByNearest(mc.player);
		Direction primaryFacing = facingSides[0];
		Direction horizontalFacing = primaryFacing; // For use in blocks with only horizontal rotation

		int index = 0;
		while (horizontalFacing.getAxis() == Direction.Axis.Y && index < facingSides.length) {
			horizontalFacing = facingSides[index++];
		}

		Level world = SchematicWorldHandler.getSchematicWorld();

		/*
		 * TODO: THIS IS REALLY BAD IN TERMS OF EFFICIENCY. I suggest using some form of
		 * search with a built in datastructure first Maybe quadtree? (I dont know how
		 * MC works)
		 */

		int maxInteract = PRINTER_MAX_BLOCKS.getIntegerValue();
		int interact = 0;

		int fromX = Math.max(posX - rangeX, minX);
		int fromY = Math.max(posY - rangeY, minY);
		int fromZ = Math.max(posZ - rangeZ, minZ);

		int toX = Math.min(posX + rangeX, maxX);
		int toY = Math.min(posY + rangeY, maxY);
		int toZ = Math.min(posZ + rangeZ, maxZ);

		toY = Math.max(Math.min(toY, worldTopY), worldBottomY);
		fromY = Math.max(Math.min(fromY, worldTopY), worldBottomY);

		fromX = Math.max(fromX, (int) mc.player.getX() - rangeX);
		fromY = Math.max(fromY, (int) mc.player.getY() - rangeY);
		fromZ = Math.max(fromZ, (int) mc.player.getZ() - rangeZ);

		toX = Math.min(toX, (int) mc.player.getX() + rangeX);
		toY = Math.min(toY, (int) mc.player.getY() + rangeY);
		toZ = Math.min(toZ, (int) mc.player.getZ() + rangeZ);
		long startTime = new Date().getTime();
		for (int y = fromY; y <= toY; y++) {
			for (int x = fromX; x <= toX; x++) {
				for (int z = fromZ; z <= toZ; z++) {
					if (interact >= maxInteract) {
						if (shouldSleepLonger) {
							shouldSleepLonger = false;
							lastPlaced = Math.max(lastPlaced, new Date().getTime() + SLEEP_AFTER_CONSUME.getIntegerValue());
						} else {
							lastPlaced = Math.max(lastPlaced, new Date().getTime());
						}
						return InteractionResult.SUCCESS;
					}
					if (FakeAccurateBlockPlacement.emptyWaitingQueue()) {
						interact++;
					}
					if (FakeAccurateBlockPlacement.shouldReturnValue) {
						FakeAccurateBlockPlacement.shouldReturnValue = false;
						return InteractionResult.SUCCESS;
					}
					double dx = mc.player.getX() - x - 0.5;
					double dy = mc.player.getY() - y - 0.5;
					double dz = mc.player.getZ() - z - 0.5;

					if (dx * dx + dy * dy + dz * dz > MaxReach * MaxReach) {
						continue;
					}

					BlockPos pos = new BlockPos(x, y, z);
					if (xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.hasItemInSchematic(world, pos)) {
						MessageHolder.sendUniqueMessageAlways("Inventory in " + pos.toShortString() + " has Item inside!");
					}
					BlockState stateSchematic;
					BlockState stateClient;
					updateSignText(mc, world, pos);
					if (!breakBlocks && !ClearArea && !Flippincactus && !BEDROCK_BREAKING.getBooleanValue()) {
						if (world.isEmptyBlock(pos)) {
							continue;
						} else {
							if (world.getBlockState(pos) == mc.level.getBlockState(pos)) {
								continue;
							}
						}
					}
					if (breakBlocks) {
						if (PRINTER_BREAK_IGNORE_EXTRA.getBooleanValue() && world.isEmptyBlock(pos)) {
							continue;
						}
					}
					stateSchematic = world.getBlockState(pos);
					stateClient = mc.level.getBlockState(pos);
					if (!ClearArea) {
						if (!range.isPositionWithinRange(pos)) {
							continue;
						}
						if (breakBlocks && stateSchematic != null && !(stateClient.getBlock() instanceof SnowLayerBlock) &&
							!stateClient.isAir() &&
							!(stateClient.is(Blocks.WATER) || stateClient.is(Blocks.LAVA) || stateClient.is(Blocks.BUBBLE_COLUMN)) &&
							!stateClient.is(Blocks.PISTON_HEAD) && !stateClient.is(Blocks.MOVING_PISTON)) {
							if (!stateClient.getBlock().getName().equals(stateSchematic.getBlock().getName()) ||
								(stateClient.getBlock() instanceof SlabBlock && stateSchematic.getBlock() instanceof SlabBlock && stateClient.getValue(SlabBlock.TYPE) != stateSchematic.getValue(SlabBlock.TYPE))
									&& dx * dx + Math.pow(dy + 1.5, 2) + dz * dz <= MaxReach * MaxReach) {

								if (mc.player.getAbilities().instabuild) {
									mc.gameMode.startDestroyBlock(pos, Direction.DOWN);
									interact++;

									if (interact >= maxInteract) {
										if (shouldSleepLonger) {
											shouldSleepLonger = false;
											lastPlaced = Math.max(lastPlaced, new Date().getTime() + SLEEP_AFTER_CONSUME.getIntegerValue());
										} else {
											lastPlaced = Math.max(lastPlaced, new Date().getTime());
										}
										return InteractionResult.SUCCESS;
									}
								} else if (BedrockBreaker.isBlockNotInstantBreakable(stateClient.getBlock()) && BEDROCK_BREAKING.getBooleanValue()) {
									mc.player.displayClientMessage(Component.nullToEmpty("Handling printerBedrockBreaking!"), true);
									interact += BedrockBreaker.scheduledTickHandler(mc, pos);
									continue;
								} else if (BEDROCK_BREAKING.getBooleanValue()) {
									mc.player.displayClientMessage(Component.nullToEmpty("Handling printerBedrockBreaking!"), true);
									interact += BedrockBreaker.scheduledTickHandler(mc, null);
									continue;
								} else if (!positionStorage.hasPos(pos)) { // For survival
									boolean replaceable = mc.level.getBlockState(pos).getMaterial().isReplaceable();
									if (!replaceable && mc.level.getBlockState(pos).getDestroySpeed(world, pos) == -1) {
										continue;
									}
									if (replaceable || mc.level.getBlockState(pos).getDestroySpeed(world, pos) == 0) {
										mc.gameMode.startDestroyBlock(pos, Direction.DOWN);
										return InteractionResult.SUCCESS;
									}
									if (!replaceable) {
										if (breaker.startBreakingBlock(pos, mc)) {
											return InteractionResult.SUCCESS;
										}
									} // it need to avoid unbreakable blocks and just added and lava, but its not block so somehow made it work
									continue;
								}
							}
						}
						// Abort if there is already a block in the target position
						if (MaxFlip || printerCheckCancel(stateSchematic, stateClient)) {
							/*
							 * Sometimes, blocks have other states like the delay on a repeater. So, this
							 * code clicks the block until the state is the same I don't know if Schematica
							 * does this too, I just did it because I work with a lot of redstone
							 */
							if (!MaxFlip && !stateClient.isAir() && !mc.player.isShiftKeyDown() && !isPositionCached(pos, true)) {
								Block cBlock = stateClient.getBlock();
								Block sBlock = stateSchematic.getBlock();

								if (cBlock.getName().equals(sBlock.getName())) {
									Direction facingSchematic = fi.dy.masa.malilib.util.BlockUtils
										.getFirstPropertyFacingValue(stateSchematic);
									Direction facingClient = fi.dy.masa.malilib.util.BlockUtils
										.getFirstPropertyFacingValue(stateClient);

									if (facingSchematic == facingClient) {
										int clickTimes = 0;
										Direction side = Direction.NORTH;
										if (sBlock instanceof RepeaterBlock && !ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
											int clientDelay = stateClient.getValue(RepeaterBlock.DELAY);
											int schematicDelay = stateSchematic.getValue(RepeaterBlock.DELAY);
											if (clientDelay != schematicDelay) {

												if (clientDelay < schematicDelay) {
													clickTimes = schematicDelay - clientDelay;
												} else if (clientDelay > schematicDelay) {
													clickTimes = schematicDelay + (4 - clientDelay);
												}
											}
											side = Direction.UP;
										} else if (sBlock instanceof ComparatorBlock && !ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
											if (stateSchematic.getValue(ComparatorBlock.MODE) != stateClient
												.getValue(ComparatorBlock.MODE)) {
												clickTimes = 1;
											}
											side = Direction.UP;
										} else if (sBlock instanceof LeverBlock) {
											if (stateSchematic.getValue(LeverBlock.POWERED) != stateClient
												.getValue(LeverBlock.POWERED)) {
												clickTimes = 1;
											}

											/*
											 * I dont know if this direction code is needed. I am just doing it anyway to
											 * make it "make sense" to the server (I am emulating what the client does so
											 * the server isn't confused)
											 */
											if (stateClient.getValue(LeverBlock.FACE) == AttachFace.CEILING) {
												side = Direction.DOWN;
											} else if (stateClient.getValue(LeverBlock.FACE) == AttachFace.FLOOR) {
												side = Direction.UP;
											} else {
												side = stateClient.getValue(LeverBlock.FACING);
											}

										} else if (sBlock instanceof TrapDoorBlock) {
											if (stateSchematic.getMaterial() != Material.METAL && stateSchematic
												.getValue(TrapDoorBlock.OPEN) != stateClient.getValue(TrapDoorBlock.OPEN)) {
												clickTimes = 1;
											}
										} else if (sBlock instanceof FenceGateBlock) {
											if (stateSchematic.getValue(FenceGateBlock.OPEN) != stateClient
												.getValue(FenceGateBlock.OPEN)) {
												clickTimes = 1;
											}
										} else if (sBlock instanceof DoorBlock) {
											if (stateClient.getMaterial() != Material.METAL && stateSchematic
												.getValue(DoorBlock.OPEN) != stateClient.getValue(DoorBlock.OPEN)) {
												clickTimes = 1;
											}
										} else if (sBlock instanceof NoteBlock) {
											int note = stateClient.getValue(NoteBlock.NOTE);
											int targetNote = stateSchematic.getValue(NoteBlock.NOTE);
											if (note != targetNote) {
												if (note < targetNote) {
													clickTimes = targetNote - note;
												} else if (note > targetNote) {
													clickTimes = targetNote + (25 - note);
												}
											}
										} else if (sBlock instanceof ComposterBlock && FillInventory) {
											if (!FakeAccurateBlockPlacement.canHandleOther(composableItem.getItem())) {
												continue;
											}
											int level = stateClient.getValue(ComposterBlock.LEVEL);
											int Schematiclevel = stateSchematic.getValue(ComposterBlock.LEVEL);
											if (level != Schematiclevel && !(level == 7 && Schematiclevel == 8)) {
												InteractionHand hand = InteractionHand.MAIN_HAND;
												if (xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.swapToItem(mc, composableItem)) {
													Vec3 hitPos = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
													BlockHitResult hitResult = new BlockHitResult(hitPos, side, pos, false);
													mc.gameMode.useItemOn(mc.player, hand, hitResult); //COMPOSTER
													xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
													cacheEasyPlacePosition(pos, false);
													if (shouldSleepLonger) {
														shouldSleepLonger = false;
														lastPlaced = Math.max(lastPlaced, new Date().getTime() + 200 + SLEEP_AFTER_CONSUME.getIntegerValue());
													} else {
														lastPlaced = Math.max(lastPlaced, new Date().getTime() + 200);
													}
													return InteractionResult.SUCCESS;
												}
											} else {
												cacheEasyPlacePosition(pos, true);
											}
										} else if (!isPositionCached(pos, false) && PRINTER_PLACE_MINECART.getBooleanValue() && sBlock instanceof DetectorRailBlock && cBlock instanceof DetectorRailBlock) {
											if (!shouldAvoidPlaceCart(pos, world) && placeCart(stateSchematic, mc, pos)) {
												continue;
											}
										}
										for (int i = 0; i < clickTimes; i++) // Click on the block a few times
										{
											InteractionHand hand = InteractionHand.MAIN_HAND;

											Vec3 hitPos = Vec3.atCenterOf(pos);

											BlockHitResult hitResult = new BlockHitResult(hitPos, side, pos, false);

											mc.gameMode.useItemOn(mc.player, hand, hitResult); //NOTEBLOCK, REPEATER...
											interact++;
										}

										if (clickTimes > 0) {
											cacheEasyPlacePosition(pos, true, 3600);
										}

									} //can place vanilla
								}
							} else if (!ClearArea && MaxFlip) {
								Block cBlock = stateClient.getBlock();
								Block sBlock = stateSchematic.getBlock();
								if (cBlock.getName().equals(sBlock.getName())) {
									boolean ShapeBoolean = false;
									boolean ShouldFix = false;
									if (sBlock instanceof BaseRailBlock) {
										if (sBlock instanceof RailBlock) {
											String SchematicRailShape = stateSchematic.getValue(RailBlock.SHAPE).toString();
											String ClientRailShape = stateClient.getValue(RailBlock.SHAPE).toString();
											ShouldFix = !Objects.equals(SchematicRailShape, ClientRailShape);
											ShapeBoolean = !Objects.equals(SchematicRailShape, ClientRailShape) && ((Objects.equals(SchematicRailShape, "south_west") || Objects.equals(SchematicRailShape, "north_west") ||
												Objects.equals(SchematicRailShape, "south_east") || Objects.equals(SchematicRailShape, "north_east")) && (Objects.equals(ClientRailShape, "south_west") ||
												Objects.equals(ClientRailShape, "north_west") || Objects.equals(ClientRailShape, "south_east") || Objects.equals(ClientRailShape, "north_east")) ||
												(Objects.equals(SchematicRailShape, "east_west") || Objects.equals(SchematicRailShape, "north_south")) && (Objects.equals(ClientRailShape, "east_west") || Objects.equals(ClientRailShape, "north_south")));
										} else {
											String SchematicRailShape = stateSchematic.getValue(PoweredRailBlock.SHAPE).toString();
											String ClientRailShape = stateClient.getValue(PoweredRailBlock.SHAPE).toString();
											ShouldFix = !Objects.equals(SchematicRailShape, ClientRailShape);
											ShapeBoolean = !Objects.equals(SchematicRailShape, ClientRailShape) && (Objects.equals(SchematicRailShape, "east_west") || Objects.equals(SchematicRailShape, "north_south")) &&
												(Objects.equals(ClientRailShape, "east_west") || Objects.equals(ClientRailShape, "north_south"));
										}
									} else if (sBlock instanceof ObserverBlock || sBlock instanceof PistonBaseBlock || sBlock instanceof RepeaterBlock || sBlock instanceof ComparatorBlock || sBlock instanceof FenceGateBlock || sBlock instanceof TrapDoorBlock) {
										Direction facingSchematic = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateSchematic);
										Direction facingClient = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateClient);
										ShouldFix = facingSchematic != facingClient;
										ShapeBoolean = facingClient.getOpposite().equals(facingSchematic);
									}
									Direction side = Direction.UP;
									if (ShapeBoolean) {
										InteractionHand hand = InteractionHand.MAIN_HAND;
										Vec3 hitPos = Vec3.atCenterOf(pos);
										BlockHitResult hitResult = new BlockHitResult(hitPos, side, pos, false);
										mc.gameMode.useItemOn(mc.player, hand, hitResult); //CACTUS
										cacheEasyPlacePosition(pos, true);
										interact++;
									} else if (breakBlocks && ShouldFix) { //cannot fix via flippincactus
										mc.gameMode.startDestroyBlock(pos, Direction.DOWN);//by one hit possible?
										breaker.startBreakingBlock(pos, mc); //register
										return InteractionResult.SUCCESS;
									}
									continue;
								}
							} //flip
							continue;
						} //cancel normal placing
					}
					if (!ClearArea && MaxFlip) {
						mc.player.displayClientMessage(Component.nullToEmpty("Handling printerFlippinCactus!"), true);
						continue;
					}
					if (isPositionCached(pos, false) || BEDROCK_BREAKING.getBooleanValue() || (!(stateSchematic.getBlock() instanceof NetherPortalBlock) && stateSchematic.isAir() && !ClearArea)) {
						continue;
					}
					ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(stateSchematic);
					Block cBlock = stateClient.getBlock();
					Block sBlock = stateSchematic.getBlock();
					if (ClearArea) {
						mc.player.displayClientMessage(Component.nullToEmpty("Handling printerClear*!"), true);
						if (isReplaceableWaterFluidSource(stateClient)) {
							if (!UseCobble) {
								stack = Items.SPONGE.getDefaultInstance();
							} else {
								stack = Items.COBBLESTONE.getDefaultInstance();
							}
						} else if (stateClient.getFluidState().getType() instanceof LavaFluid && stateClient.hasProperty(LiquidBlock.LEVEL) && stateClient.getValue(LiquidBlock.LEVEL) == 0) {
							if (!UseCobble) {
								stack = Items.SLIME_BLOCK.getDefaultInstance();
							} else {
								stack = Items.COBBLESTONE.getDefaultInstance();
							}
						} else if (ClearSnow && cBlock instanceof SnowLayerBlock) {
							stack = Items.STRING.getDefaultInstance();
						} else {
							continue;
						}
					}
					if (ClearArea) {
						InteractionHand hand = InteractionHand.MAIN_HAND;
						if (ClearArea && xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.swapToItem(mc, stack)) {
							Vec3 hitPos = Vec3.atCenterOf(pos).add(0, 0.5, 0);
							BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, pos, false);
							mc.gameMode.useItemOn(mc.player, hand, hitResult); //FLUID REMOVAL
							xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
							interact++;
							cacheEasyPlacePosition(pos, false);
							sleepWhenRequired(mc);
							if (isReplaceableFluidSource(stateClient) || cBlock instanceof SnowLayerBlock) {
								lastPlaced = new Date().getTime() + 200;
							}
						}
						continue;
					}
					if (!FakeAccurateBlockPlacement.canPlace(stateSchematic, pos)) {
						continue;
					}
					if (sBlock instanceof PistonHeadBlock || stateSchematic.is(Blocks.MOVING_PISTON)) {
						continue;
					}
					if (stateSchematic == stateClient) {
						causeMap.remove(pos.asLong());
						continue;
					}
					if (cBlock != sBlock && !stateClient.getMaterial().isReplaceable()) {
						MessageHolder.sendUniqueMessage(mc.player, sBlock.getDescriptionId() + " at " + pos.toShortString() + " is blocking placement of " + cBlock.getDescriptionId() + "!!");
						continue;
					}
					if (canPickBlock(mc, stateSchematic, pos)) {
						if (willFall(stateSchematic, mc.level, pos)) {
							recordCause(pos, stateSchematic.getBlock().getDescriptionId() + " at " + pos.toShortString() + " is Falling block", pos.below());
							MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
							continue;
						} else if (!PRINTER_PLACE_ICE.getBooleanValue() && stateSchematic.is(Blocks.WATER)) {
							recordCause(pos, stateSchematic.getBlock().getDescriptionId() + " at " + pos.toShortString() + " is water");
							MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
							continue;
						} else if (!PRINTER_PLACE_ICE.getBooleanValue() && stateSchematic.is(Blocks.LAVA)) {
							recordCause(pos, stateSchematic.getBlock().getDescriptionId() + " at " + pos.toShortString() + " is lava");
							MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
							continue;
						} else if (sBlock instanceof SandBlock || sBlock instanceof DragonEggBlock || sBlock instanceof ConcretePowderBlock || sBlock instanceof GravelBlock || sBlock instanceof AnvilBlock) {
							BlockPos Offsetpos = new BlockPos(x, y - 1, z);
							BlockState OffsetstateSchematic = world.getBlockState(Offsetpos);
							BlockState OffsetstateClient = mc.level.getBlockState(Offsetpos);
							if (OffsetstateClient.isAir() || (breakBlocks && !OffsetstateClient.getBlock().getName().equals(OffsetstateSchematic.getBlock().getName()))) {
								recordCause(pos, stateSchematic.getBlock().getDescriptionId() + " at " + pos.toShortString() + " is Falling block", pos.below());
								MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
								continue;
							}
						}
						// BUD, for positions near piston with BUD, place block first.
						if (smartRedstone) {
							if (sBlock instanceof PoweredBlock) {
								if (isQCable(mc, world, pos)) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + "will QC, waiting other block");
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								}
							} else if (sBlock instanceof TntBlock) {
								if (mc.level.hasNeighborSignal(pos)) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " is now receiving power!");
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								}
							} else if (sBlock instanceof PistonBaseBlock) {
								if (!shouldExtendQC(mc, world, pos)) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " is QC");
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								} else if (hasNearbyRedirectDust(mc, world, pos)) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " has redirectable dust nearby at " + hasNearbyRedirectDustPos(mc, world, pos).toShortString());
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								}
								if (cantAvoidExtend(mc.level, pos, world)) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " will unexpectedly extend");
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								}
								if (shouldSuppressExtend(world, pos) && hasWrongStateNearby(mc, world, pos)) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + " is BUD but has wrong state nearby \n" + hasWrongStateNearbyReason(mc, world, pos), hasWrongStateNearbyPos(mc, world, pos));
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								}
								if (willExtendInWorld(world, pos, stateSchematic.getValue(PistonBaseBlock.FACING)) != stateSchematic.getValue(PistonBaseBlock.EXTENDED) && directlyPowered(world, pos, stateSchematic.getValue(PistonBaseBlock.FACING))) {
									if (PRINTER_SUPPRESS_PUSH_LIMIT.getBooleanValue()) {
										recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " should respect push limit because its directly powered");
										MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
										continue;
									}
									MessageHolder.sendUniqueMessage(mc.player, sBlock.getDescriptionId() + " at " + " is placed ignoring push limit checks, check printerSuppressPushLimitPistons option.");
								}
							} else if (sBlock instanceof ObserverBlock) {
								if (ObserverUpdateOrder(mc, world, pos, selectedBox)) {
									if (FLIPPIN_CACTUS.getBooleanValue() && canBypass(mc, world, pos)) {
										stateSchematic = stateSchematic.setValue(ObserverBlock.FACING, stateSchematic.getValue(ObserverBlock.FACING).getOpposite());
									} else {
										BlockPos causedPos = ObserverUpdateOrderPos(mc, world, pos);
										if (causedPos.asLong() == pos.asLong()) {
											MessageHolder.sendUniqueMessage(mc.player, "Observer at " + pos.toShortString() + " is causing self-blocking, check manually");
										}
										recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " is waiting for ", causedPos);
										MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
										continue;
									}
								}
							}
						}
						if (smartRedstone && ExplicitObserver) {
							BlockPos observerPos = isObserverCantAvoidOutput(mc, world, pos);
							if (observerPos != null) {
								recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " is waiting for preceded observer at " + observerPos.toShortString(), observerPos);
								MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
								continue;
							}
							if (sBlock instanceof ObserverBlock) {
								Map.Entry<Boolean, BlockPos> value = isWatchingCorrectState(mc, world, pos, null, true);
								if (!value.getKey()) {
									recordCause(pos, sBlock.getDescriptionId() + " at " + pos.toShortString() + " can't be placed due to " + value.getValue().toShortString(), value.getValue());
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								}
							}
						}
						if (sBlock instanceof NetherPortalBlock && !sBlock.getName().equals(cBlock.getName()) && PortalShape.findEmptyPortalShape(mc.level, pos, Direction.Axis.X).isPresent()) {
							ItemStack lightStack = Items.FIRE_CHARGE.getDefaultInstance();
							if (mc.player.getInventory().findSlotMatchingItem(lightStack) == -1) {
								lightStack = Items.FLINT_AND_STEEL.getDefaultInstance();
							}
							InteractionHand hand = InteractionHand.MAIN_HAND;
							BlockPos offsetPos = new BlockPos(x, y - 1, z);
							BlockState offsetStateSchematic = world.getBlockState(offsetPos);
							BlockState offsetStateClient = mc.level.getBlockState(offsetPos);
							if (mc.player.getInventory().findSlotMatchingItem(lightStack) == -1 || offsetStateClient.isAir() || (!offsetStateClient.getBlock().getName().equals(offsetStateSchematic.getBlock().getName()))) {
								continue;
							}
							if (doSchematicWorldPickBlock(mc, lightStack)) {
								Vec3 hitPos = Vec3.atCenterOf(new BlockPos(x, y - 1, z)).add(0, 0.5, 0);
								BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, new BlockPos(x, y - 1, z), false);
								mc.gameMode.useItemOn(mc.player, hand, hitResult); //LIGHT
								cacheEasyPlacePosition(pos, false);
								sleepWhenRequired(mc);
								if (shouldSleepLonger) {
									shouldSleepLonger = false;
									lastPlaced = Math.max(lastPlaced, new Date().getTime() + 200 + SLEEP_AFTER_CONSUME.getIntegerValue());
								} else {
									lastPlaced = Math.max(lastPlaced, new Date().getTime() + 200);
								}
								interact++;
							}
						}
						Direction facing = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateSchematic);
						if (facing != null) {
							facing = facing.getOpposite();
						}
						if (stateSchematic.getBlock() instanceof BaseRailBlock) {
							facing = convertRailShapetoFace(stateSchematic);
						}
						if (facing != null) {
							FacingData facedata = FacingData.getFacingData(stateSchematic);
							if (facedata == null && !(stateSchematic.getBlock() instanceof BaseRailBlock) && !simulateFacingData(stateSchematic, pos, Vec3.atCenterOf(pos)) ) {
								MessageHolder.sendMessageUncheckedUnique(mc.player, stateSchematic.getBlock() + " does not have facing data, please add this!");
								if (PRINTER_SKIP_UNKNOWN_BLOCKSTATE.getBooleanValue()) continue;

							}
							if (!(CanUseProtocol && IsBlockSupportedCarpet(stateSchematic.getBlock())) && !FAKE_ROTATION_BETA.getBooleanValue() && !canPlaceFace(facedata, stateSchematic, primaryFacing, horizontalFacing)) {
								continue;
							}

							if ((stateSchematic.getBlock() instanceof DoorBlock
								&& stateSchematic.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
								|| (stateSchematic.getBlock() instanceof BedBlock
								&& stateSchematic.getValue(BedBlock.PART) == BedPart.HEAD)) {
								continue;
							}
						}

						// Exception for signs (edge case)
						if (stateSchematic.getBlock() instanceof SignBlock
							&& !(stateSchematic.getBlock() instanceof WallSignBlock)) {
							if ((Mth.floor((double) ((180.0F + mc.player.getYRot()) * 16.0F / 360.0F) + 0.5D)
								& 15) != stateSchematic.getValue(StandingSignBlock.ROTATION)) {
								continue;
							}
						}
						Direction sideOrig = Direction.NORTH;
						BlockPos npos = pos;
						Direction side = applyPlacementFacing(stateSchematic, sideOrig, stateClient);
						Block blockSchematic = stateSchematic.getBlock();
						//Don't place waterlogged block's original block before fluid since its painful
						// 1. if
						if (PRINTER_PLACE_ICE.getBooleanValue() &&
							(isReplaceableWaterFluidSource(stateSchematic) && stateClient.getMaterial().isReplaceable() && !isReplaceableWaterFluidSource(stateClient) && !stateClient.is(Blocks.LAVA) ||
								PRINTER_WATERLOGGED_WATER_FIRST.getBooleanValue() && stateClient.getMaterial().isReplaceable() && containsWaterloggable(stateSchematic))
						) {
							ItemStack iceStack = Items.ICE.getDefaultInstance();
							if (!FakeAccurateBlockPlacement.canHandleOther(iceStack.getItem())) {
								continue;
							}
							if (xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.swapToItem(mc, iceStack)) {
								mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(pos.getX(), pos.getY(), pos.getZ()), Direction.DOWN, pos, false));
								xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
								cacheEasyPlacePosition(pos, false);
								sleepWhenRequired(mc);
								interact++;
							} //ICE
							else {
								recordCause(pos, "Can't pick item " + Items.ICE.getDescriptionId() + " at " + pos.toShortString());
							}
							continue;
						}
						if (!canPickBlock(mc, stateSchematic, pos)) {
							//mc.player.displayClientMessage(Component.nullToEmpty("Can't pick block"),true);
							recordCause(pos, "Can't pick item " + stateSchematic.getBlock().asItem().getDescriptionId() + " at " + pos.toShortString());
							MessageHolder.sendUniqueMessage(mc.player, "Can't pick item " + stateSchematic.getBlock().asItem().getDescriptionId() + " at " + pos.toShortString());
							continue;
						}
						if (!blockSchematic.canSurvive(stateSchematic, mc.level, pos)) {
							recordCause(pos, stateSchematic.getBlock().toString() + "(" + pos.toShortString() + ", can't be placed)");
							MessageHolder.sendUniqueMessage(mc.player, stateSchematic.getBlock().getDescriptionId() + " can't be placed at " + pos.toShortString());
							continue;
						}
						if (blockSchematic instanceof GrindstoneBlock) {
							placeGrindStone(stateSchematic, mc, pos);
							interact++;
							continue;
						}
						if (blockSchematic instanceof TrapDoorBlock && !CanUseProtocol && !FAKE_ROTATION_BETA.getBooleanValue()) {
							placeTrapDoor(stateSchematic, mc, pos);
							interact++;
							continue;
						}
						int miliseconds = EASY_PLACE_CACHE_TIME.getIntegerValue();
						if (blockSchematic instanceof FaceAttachedHorizontalDirectionalBlock || blockSchematic instanceof TorchBlock || blockSchematic instanceof WallSkullBlock
							|| blockSchematic instanceof LadderBlock
							|| blockSchematic instanceof TripWireHookBlock || blockSchematic instanceof WallSignBlock ||
							blockSchematic instanceof EndRodBlock || blockSchematic instanceof BaseCoralFanBlock) {

							/*
							 * Some blocks, especially wall mounted blocks must be placed on another for
							 * directionality to work Basically, the block pos sent must be a "clicked"
							 * block.
							 */
							if (blockSchematic instanceof ButtonBlock || blockSchematic instanceof LeverBlock) {
								AttachFace wallMountLocation = stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
								if (wallMountLocation == AttachFace.FLOOR) {
									npos = pos.below();
								} else if (wallMountLocation == AttachFace.CEILING) {
									npos = pos.above();
								} else {
									npos = pos.offset(stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACING).getOpposite().getNormal());
								}
							} else if (blockSchematic instanceof TorchBlock) {
								if (blockSchematic instanceof WallTorchBlock || blockSchematic instanceof RedstoneWallTorchBlock) {
									npos = pos.offset(stateSchematic.getValue(WallTorchBlock.FACING).getOpposite().getNormal());
								} else {
									npos = pos.below();
								}
								if (hasGui(world.getBlockState(npos).getBlock())) {
									if (FAKE_ROTATION_BETA.getBooleanValue() && interact < maxInteract) {
										if (FakeAccurateBlockPlacement.request(stateSchematic, pos)) {
											interact++;
										}
										continue;
									}
									recordCause(pos, "Torch at " + pos.toShortString() + " can't be placed due to " + world.getBlockState(npos).getBlock().getDescriptionId() + "at " + npos.toShortString() + " has GUI");
									MessageHolder.sendUniqueMessage(mc.player, "Torch at " + pos.toShortString() + " can't be placed due to " + world.getBlockState(npos).getBlock().getDescriptionId() + "at " + npos.toShortString() + " has GUI");
									continue;
								}
							} else if (blockSchematic instanceof BaseCoralFanBlock) {
								if (blockSchematic instanceof BaseCoralWallFanBlock) {
									npos = pos.offset(stateSchematic.getValue(BaseCoralWallFanBlock.FACING).getOpposite().getNormal());
								} else {
									npos = pos.below();
								}
							} else {
								npos = pos.offset(side.getOpposite().getNormal()); //offset block for 'side'
							}
							//Any : if we have block in testPos, then we can place with wanted direction.
							//Trapdoors : it can be placed in air with player direction's opposite.
							//Else : can't be placed except End Rod.
							if (!mc.level.getBlockState(npos).getMaterial().isReplaceable()) {
								//npos is blockPos to be hit.
								//instead, hitVec should have 1 corresponding to direction property.
								//but First check if its block with GUI*
								Block checkGui = mc.level.getBlockState(npos).getBlock();
								if (!mc.player.isSecondaryUseActive() && hasGui(checkGui)) {
									if (blockSchematic instanceof TrapDoorBlock && FAKE_ROTATION_BETA.getBooleanValue() && interact < maxInteract) {
										if (FakeAccurateBlockPlacement.request(stateSchematic, pos)) {
											interact++;
										}
										continue;
									}
									//Has GUI so clickPos can't be clicked.
									recordCause(pos, stateSchematic.getBlock().getDescriptionId() + " can't be placed at " + pos.toShortString() + "because " + npos.toShortString() + " has GUI");
									MessageHolder.sendUniqueMessage(mc.player, getReason(pos.asLong()));
									continue;
								} else if (blockSchematic instanceof TorchBlock) {
									//no gui, just place
									if (blockSchematic instanceof WallTorchBlock || blockSchematic instanceof RedstoneWallTorchBlock) {
										MessageHolder.sendDebugMessage(mc.player, "placing wall torch clicking " + npos.toShortString() + " torch facing : " + stateSchematic.getValue(WallTorchBlock.FACING).toString());
										Vec3 hitVec = Vec3.atCenterOf(npos).add(Vec3.atLowerCornerOf(stateSchematic.getValue(WallTorchBlock.FACING).getNormal()).multiply(0.5,0.5,0.5));
										if (stateSchematic.hasProperty(RedstoneTorchBlock.LIT) && !stateSchematic.getValue(RedstoneTorchBlock.LIT)) {
											cacheEasyPlacePosition(pos.above(), false, miliseconds);
										}
										Direction required = stateSchematic.getValue(WallTorchBlock.FACING);
										if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
											cacheEasyPlacePosition(pos, false);
											interact++;
											if (stateSchematic.hasProperty(RedstoneTorchBlock.LIT) && !stateSchematic.getValue(RedstoneTorchBlock.LIT)) {
												cacheEasyPlacePosition(pos.above(), false, miliseconds);
											}
											mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, required, npos, false)); //place block
											xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
											sleepWhenRequired(mc);
										}
										continue;
									}
									Vec3 hitVec = Vec3.atCenterOf(npos).add(Vec3.atLowerCornerOf(Direction.UP.getNormal()).multiply(0.5, 0.5, 0.5));
									if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
										MessageHolder.sendDebugMessage(mc.player, "Placing torch clicking " + npos.toShortString());
										MessageHolder.sendDebugMessage(mc.player, "\t Wanted torch pos : " + pos.toShortString());
										MessageHolder.sendDebugMessage(mc.player, "\t HitVec applied : " + hitVec);
										MessageHolder.sendDebugMessage(mc.player, "\t Side applied : " + Direction.UP);
										cacheEasyPlacePosition(pos, false);
										interact++;
										if (stateSchematic.hasProperty(RedstoneTorchBlock.LIT) && !stateSchematic.getValue(RedstoneTorchBlock.LIT)) {
											cacheEasyPlacePosition(pos.above(), false, miliseconds);
										}
										mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, Direction.UP, npos, false)); //place block
										xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
										sleepWhenRequired(mc);
									}
									continue;
								} else if (canPlaceFace(FacingData.getFacingData(stateSchematic), stateSchematic, primaryFacing, horizontalFacing)) { // no gui
									Direction required = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateSchematic);
									required = applyPlacementFacing(stateSchematic, required, stateClient);
									Vec3 hitVec = applyHitVec(npos, stateSchematic, required);
									if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
										cacheEasyPlacePosition(pos, false);
										interact++;
										if (stateSchematic.hasProperty(RedstoneTorchBlock.LIT) && !stateSchematic.getValue(RedstoneTorchBlock.LIT)) {
											cacheEasyPlacePosition(pos.above(), false, 700);
										}
										mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, required, npos, false)); //place block
										xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
										sleepWhenRequired(mc);
									}
									continue;
								}
							} else if (blockSchematic instanceof TrapDoorBlock) { //check direction is opposite of player's
								Direction trapdoor = stateSchematic.getValue(TrapDoorBlock.FACING);
								if (horizontalFacing.getOpposite() == trapdoor) {
									if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
										cacheEasyPlacePosition(pos, false);
										mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atLowerCornerOf(pos),
											stateSchematic.getValue(TrapDoorBlock.FACING).getOpposite(), pos, false)); //place block
										xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
										sleepWhenRequired(mc);
										interact++;
									}
									continue;
								}
							} else if (blockSchematic instanceof GrindstoneBlock) {
								Direction direction = stateSchematic.getValue(GrindstoneBlock.FACING);
								if ((primaryFacing.getAxis() == Direction.Axis.Y && horizontalFacing == direction) || (primaryFacing.getAxis() != Direction.Axis.Y && horizontalFacing == direction.getOpposite())) {
									if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
										cacheEasyPlacePosition(pos, false);
										mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atLowerCornerOf(pos),
											stateSchematic.getValue(GrindstoneBlock.FACING).getOpposite(), pos, false)); //place block
										xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
										sleepWhenRequired(mc);
										interact++;
									}
								}
								continue;
							} else { //Only end rod.
								if (blockSchematic instanceof EndRodBlock) {
									if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
										cacheEasyPlacePosition(pos, false);
										mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos),
											stateSchematic.getValue(EndRodBlock.FACING), pos, false)); //place block
										xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
										interact++;
										sleepWhenRequired(mc);
									}
								}
								continue;
							}

						} //End of trapdoor / wall mounted blocks

						InteractionHand hand = InteractionHand.MAIN_HAND;

						Vec3 hitPos;
						// Carpet Accurate Placement protocol support, plus BlockSlab support
						if (CanUseProtocol && IsBlockSupportedCarpet(stateSchematic.getBlock())) {
							hitPos = applyCarpetProtocolHitVec(npos, stateSchematic);
						} else {
							hitPos = applyHitVec(npos, stateSchematic, side);
						}

						// Mark that this position has been handled (use the non-offset position that is
						// checked above)
						BlockHitResult hitResult = new BlockHitResult(hitPos, side, npos, false);

						//System.out.printf("pos: %s side: %s, hit: %s\n", pos, side, hitPos);
						// pos, side, hitPos
						if (stateSchematic.getBlock() instanceof SnowLayerBlock) {
							stateClient = mc.level.getBlockState(npos);
							if (stateClient.isAir() || stateClient.getBlock() instanceof SnowLayerBlock
								&& stateClient.getValue(SnowLayerBlock.LAYERS) < stateSchematic.getValue(SnowLayerBlock.LAYERS)) {
								side = Direction.UP;
								hitResult = new BlockHitResult(hitPos, side, npos, false);
								if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
									cacheEasyPlacePosition(pos, false);
									interact++;
									mc.gameMode.useItemOn(mc.player, hand, hitResult); //SNOW LAYERS
									xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
									sleepWhenRequired(mc);
								}
							}
							continue;
						}
						//finally places block
						if (smartRedstone) {
							if (stateSchematic.hasProperty(RedstoneTorchBlock.LIT) && !stateSchematic.getValue(RedstoneTorchBlock.LIT)) {
								cacheEasyPlacePosition(pos.above(), false, 700);
							}
							Set<BlockPos> shouldCache = ObserverCantAvoidPos(mc, world, pos);
							if (!shouldCache.isEmpty()) {
								shouldCache.forEach(a -> {
									MessageHolder.sendDebugMessage("Caching position " + a.toShortString() + " because observer can't avoid ");
									cacheEasyPlacePosition(a, true, (int) Math.ceil(Math.sqrt(a.distSqr(pos)) * 100));
								});
							}
						}
						if (!FAKE_ROTATION_BETA.getBooleanValue() || ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) { //Accurateblockplacement, or vanilla but no fake
							if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
								MessageHolder.sendOrderMessage("Places block " + blockSchematic + " at " + pos.toShortString());
								mc.gameMode.useItemOn(mc.player, hand, hitResult); //PLACE BLOCK
								xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
								cacheEasyPlacePosition(pos, false);
								sleepWhenRequired(mc);
								interact++;
							}
							continue;
						} else {
							if (!(sBlock instanceof LiquidBlock)) {
								if (interact < maxInteract && FakeAccurateBlockPlacement.request(stateSchematic, pos)) {
									interact++;
								}
							}
						}
						if (stateSchematic.getBlock() instanceof SlabBlock
							&& stateSchematic.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
							stateClient = mc.level.getBlockState(npos);

							if (stateClient.getBlock() instanceof SlabBlock
								&& stateClient.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
								side = applyPlacementFacing(stateSchematic, sideOrig, stateClient);
								hitResult = new BlockHitResult(hitPos, side, npos, false);
								if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
									mc.gameMode.useItemOn(mc.player, hand, hitResult); //double slab
									xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
									cacheEasyPlacePosition(pos, false);
									sleepWhenRequired(mc);
									interact++;
								}
								continue;
							}
						}
						if (stateSchematic.getBlock() instanceof SeaPickleBlock
							&& stateSchematic.getValue(SeaPickleBlock.PICKLES) > 1) {
							stateClient = mc.level.getBlockState(npos);
							if (stateClient.getBlock() instanceof SeaPickleBlock
								&& stateClient.getValue(SeaPickleBlock.PICKLES) < stateSchematic.getValue(SeaPickleBlock.PICKLES)) {
								side = applyPlacementFacing(stateSchematic, sideOrig, stateClient);
								hitResult = new BlockHitResult(hitPos, side, npos, false);
								if (doSchematicWorldPickBlock(mc, stateSchematic, pos)) {
									mc.gameMode.useItemOn(mc.player, hand, hitResult); //double slab
									xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.decrementCount(isCreative);
									cacheEasyPlacePosition(pos, false);
									sleepWhenRequired(mc);
									interact++;
								}
								continue;
							}
						}

						if (interact >= maxInteract) {
							if (shouldSleepLonger) {
								shouldSleepLonger = false;
								lastPlaced = Math.max(lastPlaced, new Date().getTime() + SLEEP_AFTER_CONSUME.getIntegerValue());
							} else {
								lastPlaced = Math.max(lastPlaced, new Date().getTime());
							}
							return InteractionResult.SUCCESS;
						}

					} else {
						MessageHolder.sendUniqueMessage(mc.player, sBlock.getDescriptionId() + " can't be picked !!");
					}
				}
			}

		}

		if (interact > 0) {
			if (shouldSleepLonger) {
				shouldSleepLonger = false;
				lastPlaced = Math.max(lastPlaced, new Date().getTime() + SLEEP_AFTER_CONSUME.getIntegerValue());
			} else {
				lastPlaced = Math.max(lastPlaced, new Date().getTime());
			}
			return InteractionResult.SUCCESS;
		}
		if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem) && !(mc.player.getOffhandItem().getItem() instanceof BlockItem)) {
			return InteractionResult.PASS;
		}
		return InteractionResult.FAIL;
	}

	private static boolean willFall(BlockState stateSchematic, Level clientWorld, BlockPos pos) {
		if (stateSchematic.getBlock() instanceof ScaffoldingBlock) {
			return !stateSchematic.getBlock().canSurvive(stateSchematic, clientWorld, pos);
		}
		return false;
	}

	/*
		returns if redstone block should not be placed (before piston)
	 */
	private static boolean isQCable(Minecraft mc, Level world, BlockPos pos) {
		BlockPos posoffset = pos.below();
		BlockPos poseast = posoffset.east();
		BlockPos poswest = posoffset.west();
		BlockPos posnorth = posoffset.north();
		BlockPos possouth = posoffset.south();
		Iterable<BlockPos> OffsetIterable = List.of(poseast, poswest, posnorth, possouth);
		for (BlockPos Position : OffsetIterable) {
			BlockState stateClient = mc.level.getBlockState(Position);
			BlockState stateSchematic = world.getBlockState(Position);
			if (!(stateSchematic.getBlock() instanceof PistonBaseBlock)) {
				continue;
			}
			if (stateSchematic.getValue(PistonBaseBlock.EXTENDED)) {
				continue;
			}
			if (stateClient.isAir()) { //very basic qc
				return true;
			} else if (!hasNoUpdatableState(mc, world, Position)) {
				return true;
			} else if (stateClient.getBlock() instanceof PistonBaseBlock && stateSchematic.getValue(PistonBaseBlock.FACING).equals(Direction.UP)) {
				if (!world.getBlockState(Position.above()).getBlock().equals(mc.level.getBlockState(Position.above()).getBlock())) {
					return true;
				}
			}
		}
		BlockState stateSchematic = world.getBlockState(posoffset.below());
		return stateSchematic.getBlock() instanceof PistonBaseBlock && !stateSchematic.getValue(PistonBaseBlock.EXTENDED) && !world.getBlockState(posoffset).getBlock().equals(mc.level.getBlockState(posoffset).getBlock());
	}

	private static boolean hasNoUpdatableState(Minecraft mc, Level world, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (world.getBlockState(pos.offset(direction.getNormal())) != mc.level.getBlockState(pos.offset(direction.getNormal()))) {
				if (!isNoteBlockInstrumentError(mc, world, pos.offset(direction.getNormal())) && !isDoorHingeError(mc, world, pos.offset(direction.getNormal()))) {
					if (world.isEmptyBlock(pos.offset(direction.getNormal())) && mc.level.isEmptyBlock(pos.offset(direction.getNormal()))) {
						continue;
					}
					return false;
				}
			}
		}
		return true;
	}

	private static boolean hasNearbyRedirectDust(Minecraft mc, Level world, BlockPos pos) { //temporary code, just direct redirection check nearby
		for (Direction direction : Direction.values()) {
			if (!isCorrectDustState(mc, world, pos.offset(direction.getNormal()))) {
				return true;
			}
			if (direction.getAxis() != Direction.Axis.Y && !isCorrectDustState(mc, world, BlockPos.of(BlockPos.offset(2, direction)))) {
				return true;
			}
			if (!isCorrectDustState(mc, world, pos.offset(direction.getNormal()).above())) {
				return true;
			}
			if (direction.getAxis() != Direction.Axis.Y && !isCorrectDustState(mc, world, BlockPos.of(BlockPos.offset(2, direction)).above())) {
				return true;
			}
		}
		return false;
	}

	private static BlockPos hasNearbyRedirectDustPos(Minecraft mc, Level world, BlockPos pos) { //temporary code, just direct redirection check nearby
		for (Direction direction : Direction.values()) {
			if (!isCorrectDustState(mc, world, pos.offset(direction.getNormal()))) {
				return pos.offset(direction.getNormal());
			}
			if (!isCorrectDustState(mc, world, BlockPos.of(BlockPos.offset(2, direction)))) {
				return BlockPos.of(BlockPos.offset(2, direction));
			}
			if (!isCorrectDustState(mc, world, pos.offset(direction.getNormal()).above())) {
				return pos.offset(direction.getNormal()).above();
			}
			if (!isCorrectDustState(mc, world, BlockPos.of(BlockPos.offset(2, direction)).above())) {
				return BlockPos.of(BlockPos.offset(2, direction)).above();
			}
		}
		return null;
	}

	private static boolean cantAvoidExtend(Level world, BlockPos pos, Level schematicWorld) {
		if (!schematicWorld.getBlockState(pos).getValue(PistonBaseBlock.EXTENDED)) {
			return willExtendInWorld(world, pos, schematicWorld.getBlockState(pos).getValue(PistonBaseBlock.FACING));
		}
		return false;
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	private static boolean isCorrectDustState(Minecraft mc, Level world, BlockPos pos) {
		BlockState ClientState = mc.level.getBlockState(pos);
		BlockState SchematicState = world.getBlockState(pos);
		if (!SchematicState.is(Blocks.REDSTONE_WIRE)) {
			return true;
		}
		if (!ClientState.is(Blocks.REDSTONE_WIRE)) {
			return false;
		}
		return SchematicState.getValue(RedStoneWireBlock.EAST) == ClientState.getValue(RedStoneWireBlock.EAST) &&
			SchematicState.getValue(RedStoneWireBlock.WEST) == ClientState.getValue(RedStoneWireBlock.WEST) &&
			SchematicState.getValue(RedStoneWireBlock.SOUTH) == ClientState.getValue(RedStoneWireBlock.SOUTH) &&
			SchematicState.getValue(RedStoneWireBlock.NORTH) == ClientState.getValue(RedStoneWireBlock.NORTH) &&
			Objects.equals(SchematicState.getValue(RedStoneWireBlock.POWER) == 0, ClientState.getValue(RedStoneWireBlock.POWER) == 0);
	}

	private static boolean shouldExtendQC(Minecraft mc, Level world, BlockPos pos) {
		return willExtendInWorld(mc.level, pos, world.getBlockState(pos).getValue(PistonBaseBlock.FACING)) == world.getBlockState(pos).getValue(PistonBaseBlock.EXTENDED);
	}

	/*
		returns if piston is powered + but its not extended in schematic, can be BUD or direct power
	 */
	private static boolean shouldSuppressExtend(Level world, BlockPos pos) {
		return willExtendInWorld(world, pos, world.getBlockState(pos).getValue(PistonBaseBlock.FACING)) && !world.getBlockState(pos).getValue(PistonBaseBlock.EXTENDED);
	}

	/*
		returns if piston is DIRECTLY powered by redstone block, can't be solved via QC references.
	 */
	private static boolean directlyPowered(Level schematicWorld, BlockPos pos, Direction pistonFace) {
		for (Direction lv : Direction.values()) {
			if (lv == pistonFace) {
				continue;
			}
			if (schematicWorld.getBlockState(pos.offset(lv.getNormal())).is(Blocks.REDSTONE_BLOCK)) {
				return true;
			}
		}
		return false;
	}

	private static boolean willExtendInWorld(Level world, BlockPos pos, Direction pistonFace) {
		for (Direction lv : Direction.values()) {
			if (lv == pistonFace || !world.hasSignal(pos.offset(lv.getNormal()), lv)) {
				continue;
			}
			//Observer client error wtf?
			boolean hasObserver = false;
			for (Direction dir : Direction.values()) {
				BlockState observerState = world.getBlockState(pos.offset(lv.getNormal()).offset(dir.getNormal()));
				if (observerState.is(Blocks.OBSERVER)) {
					if (observerState.getValue(ObserverBlock.POWERED)) {
						hasObserver = true;
						break;
					}
				}
			}
			BlockState adjState = world.getBlockState(pos.offset(lv.getNormal()));
			if (adjState.is(Blocks.OBSERVER)) {
				if (adjState.getValue(ObserverBlock.POWERED)) {
					hasObserver = true;
				}
			}
			if (hasObserver) {
				continue;
			}
			return true;
		}
		if (world.hasSignal(pos, Direction.DOWN)) {
			return true;
		}
		BlockPos lv2 = pos.above();
		for (Direction lv3 : Direction.values()) {
			if (lv3 == Direction.DOWN || !world.hasSignal(lv2.offset(lv3.getNormal()), lv3)) {
				continue;
			}
			BlockState qcState = world.getBlockState(lv2.offset(lv3.getNormal()));
			if (qcState.is(Blocks.OBSERVER) && qcState.getValue(ObserverBlock.FACING) == lv3 && qcState.getValue(ObserverBlock.POWERED)) {
				continue;
			}
			return true;
		}
		return false;
	}

	/* * *
	returns if block is observer output but observer can't avoid update
	If its true, then block should be placed after observer update is done
	Case A : Observer is facing wall attached : observer - wall - output
	Case B : Observer is facing Noteblock from horizontal : observer - block below noteblock - noteblock - output
	Case C : Observer is facing wire connected to observer's up offset
	 * * */
	@SuppressWarnings({"ConstantConditions"})
	private static BlockPos isObserverCantAvoidOutput(Minecraft mc, Level schematicWorld, BlockPos pos) {
		if (isQCableBlock(schematicWorld.getBlockState(pos))) {
			if (schematicWorld.getBlockState(pos.above(2)).is(Blocks.OBSERVER) && schematicWorld.getBlockState(pos.above(2)).getValue(ObserverBlock.FACING) == Direction.UP) {
				if (mc.level.getBlockState(pos.above(3)) != schematicWorld.getBlockState(pos.above(3)) || mc.level.getBlockState(pos.above(2)).hasProperty(ObserverBlock.POWERED) && mc.level.getBlockState(pos.above(2)).getValue(ObserverBlock.POWERED)) {
					MessageHolder.sendDebugMessage("Position at " + pos.toShortString() + " has observer that will QC, but not watching correct state");
					return pos.above(3);
				}
			}
		}
		for (Direction direction : Direction.values()) {
			BlockState offsetState = schematicWorld.getBlockState(pos.offset(direction.getNormal()));
			if (offsetState.getBlock() instanceof ObserverBlock && offsetState.getValue(ObserverBlock.FACING) == direction) {
				Map.Entry<Boolean, BlockPos> value = isWatchingCorrectState(mc, schematicWorld, pos.offset(direction.getNormal()), null, false);
				if (!value.getKey()) {
					return pos.offset(direction.getNormal());
				}
			}
			//QC
			if (direction == Direction.UP || direction == Direction.DOWN || !isQCableBlock(schematicWorld, pos)) {
				continue;
			}
			//Horizontal,
			BlockPos qcPos = pos.offset(direction.getNormal()).above();
			BlockState qcState = schematicWorld.getBlockState(qcPos);
			BlockState existingState = mc.level.getBlockState(qcPos);
			if (qcState.getBlock() instanceof ObserverBlock && !existingState.is(Blocks.OBSERVER) && qcState.getValue(ObserverBlock.FACING) == direction) {
				Map.Entry<Boolean, BlockPos> value = isWatchingCorrectState(mc, schematicWorld, qcPos, null, false);
				if (!value.getKey()) {
					return pos.offset(direction.getNormal());
				}
			}
			// again, QC + powerable block uhh
			else if (qcState.isRedstoneConductor(schematicWorld, qcPos)) {
				qcPos = qcPos.offset(direction.getNormal());
				qcState = schematicWorld.getBlockState(qcPos);
				if (qcState.getBlock() instanceof ObserverBlock && !existingState.is(Blocks.OBSERVER) && qcState.getValue(ObserverBlock.FACING) == direction) {
					Map.Entry<Boolean, BlockPos> value = isWatchingCorrectState(mc, schematicWorld, qcPos, null, false);
					if (!value.getKey()) {
						return pos.offset(direction.getNormal());
					}
				}
			}
		}
		return null;
	}

	private static boolean sleepWhenRequired(Minecraft mc) {
		if (!USE_INVENTORY_CACHE.getBooleanValue()) {
			return false;
		}
		if (SLEEP_AFTER_CONSUME.getIntegerValue() > 0 && xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.lastCount <= 0) {
			shouldSleepLonger = true;
			lastPlaced = new Date().getTime() + SLEEP_AFTER_CONSUME.getIntegerValue();
			MessageHolder.sendUniqueMessageActionBar(mc.player, "Sleeping because stack is emptied!");
			isSleeping = true;
			return true;
		}
		return false;
	}

	private static boolean isQCableBlock(Level world, BlockPos pos) {
		Block block = world.getBlockState(pos).getBlock();
		return (!AVOID_CHECK_ONLY_PISTONS.getBooleanValue() && block instanceof DispenserBlock) || block instanceof PistonBaseBlock;
	}

	private static boolean isQCableBlock(BlockState blockState) {
		Block block = blockState.getBlock();
		return (!AVOID_CHECK_ONLY_PISTONS.getBooleanValue() && block instanceof DispenserBlock) || block instanceof PistonBaseBlock;
	}

	/***
	 *
	 * @param mc : client
	 * @param schematicWorld : schematic world
	 * @param pos : BlockPos
	 * @param recursive : Sets of position checked
	 * @param allowFirst : direct search of wallmount / walls / etc at first
	 * @return Entry : correct / position caused
	 */
	@SuppressWarnings({"ConstantConditions"})
	private static Map.Entry<Boolean, BlockPos> isWatchingCorrectState(Minecraft mc, Level schematicWorld, BlockPos pos, Set<Long> recursive, boolean allowFirst) {
		//observer, then recursive
		if (recursive == null) {
			recursive = new HashSet<>();
		}
		if (recursive.contains(pos.asLong())) {
			return Map.entry(true, pos);
		}
		BlockState clientState = mc.level.getBlockState(pos);
		BlockState schematicState = schematicWorld.getBlockState(pos);
		if (schematicState.getBlock() instanceof ObserverBlock) {
			Direction facing = schematicState.getValue(ObserverBlock.FACING);
			recursive.add(pos.asLong());
			if (allowFirst && ObserverCantAvoid(mc, schematicWorld, facing, pos)) {
				return Map.entry(true, pos);
			} else {
				Map.Entry<Boolean, BlockPos> entry = isWatchingCorrectState(mc, schematicWorld, pos.offset(facing.getNormal()), recursive, allowFirst);
				if (entry.getKey()) {
					return entry;
				} else {
					return Map.entry(false, pos);
				}
			}
		}// virtual observers then go recursive
		else {
			if (schematicState == Blocks.VOID_AIR.defaultBlockState() || schematicState == Blocks.BARRIER.defaultBlockState() || schematicState.isAir()) {
				return Map.entry(true, pos);
			} else if (clientState != schematicState) {
				//but check wire...
				if (isNoteBlockInstrumentError(mc, schematicWorld, pos) || isDoorHingeError(mc, schematicWorld, pos)) {
					return Map.entry(true, pos);
				}
				if (isClientPowerError(mc, schematicWorld, clientState, schematicState, pos)) {
					return Map.entry(true, pos);
				}
				return Map.entry(false, pos);
			}
		}
		return Map.entry(true, pos);
	}

	private static boolean isClientPowerError(Minecraft mc, Level world, BlockState clientState, BlockState schematicState, BlockPos pos) {
		//handles client error, mostly, dropper being powered directly, hopper being powered etc
		if (clientState.getBlock() != schematicState.getBlock()) {
			return false;
		}
		if (schematicState.is(Blocks.DROPPER) || schematicState.is(Blocks.DISPENSER)) {
			if (schematicState.getValue(DropperBlock.TRIGGERED) != clientState.getValue(DropperBlock.TRIGGERED)) {
				boolean isReceiving = mc.level.hasNeighborSignal(pos) || mc.level.hasNeighborSignal(pos.above());
				if (isReceiving != clientState.getValue(DropperBlock.TRIGGERED)) {
					mc.level.setBlockAndUpdate(pos, clientState.setValue(DropperBlock.TRIGGERED, isReceiving));
				}
				return mc.level.getBlockState(pos) == schematicState;
			}
		} else if (schematicState.is(Blocks.NOTE_BLOCK)) { //special case
			if (schematicState.getValue(NoteBlock.POWERED) != clientState.getValue(NoteBlock.POWERED)) {
				boolean isReceiving = mc.level.hasNeighborSignal(pos);
				mc.level.setBlockAndUpdate(pos, clientState.setValue(NoteBlock.POWERED, isReceiving)); //lets fix
				return isNoteBlockInstrumentError(mc, world, pos);
			}
		} else if (schematicState.is(Blocks.HOPPER)) {
			if (schematicState.getValue(HopperBlock.ENABLED) != clientState.getValue(HopperBlock.ENABLED)) {
				boolean isReceiving = mc.level.hasNeighborSignal(pos);
				mc.level.setBlockAndUpdate(pos, clientState.setValue(HopperBlock.ENABLED, !isReceiving));
				return mc.level.getBlockState(pos) == schematicState;
			}
		}
		return false;
	}

	private static boolean ObserverCantAvoid(Minecraft mc, Level world, Direction facingSchematic, BlockPos pos) {
		//returns true if observer should be placed regardless of state
		BlockPos posOffset = pos.offset(facingSchematic.getNormal());
		BlockState OffsetStateSchematic = world.getBlockState(posOffset);
		Block offsetBlock = OffsetStateSchematic.getBlock();
		if (OffsetStateSchematic.is(Blocks.NOTE_BLOCK)) {
			if (isNoteBlockInstrumentError(mc, world, posOffset) || isDoorHingeError(mc, world, posOffset)) {
				//everything is correct but litematica error
				return true;
			}
		}
		if (facingSchematic.equals(Direction.UP)) {
			return offsetBlock instanceof WallBlock || offsetBlock instanceof ComparatorBlock || offsetBlock instanceof DoorBlock ||
				offsetBlock instanceof RepeaterBlock || offsetBlock instanceof FallingBlock ||
				offsetBlock instanceof BaseRailBlock || offsetBlock instanceof NoteBlock ||
				offsetBlock instanceof BubbleColumnBlock || offsetBlock instanceof RedStoneWireBlock ||
				((offsetBlock instanceof FaceAttachedHorizontalDirectionalBlock) && OffsetStateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.FLOOR);
		} else if (facingSchematic.equals(Direction.DOWN)) {
			return offsetBlock instanceof WallBlock || offsetBlock instanceof FaceAttachedHorizontalDirectionalBlock && OffsetStateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING;
		} else {
			return offsetBlock instanceof WallBlock || offsetBlock instanceof IronBarsBlock || offsetBlock instanceof FenceBlock || OffsetStateSchematic.is(Blocks.IRON_BARS) || offsetBlock instanceof FaceAttachedHorizontalDirectionalBlock &&
				OffsetStateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.WALL && OffsetStateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACING) == facingSchematic || hasDustOrAscendingRails(world, facingSchematic, pos);
		}
	}

	private static boolean hasDustOrAscendingRails(Level schematicWorld, Direction watching, BlockPos observerPos) {
		BlockPos possible = observerPos.offset(watching.getNormal());
		BlockState state = schematicWorld.getBlockState(possible);
		if (state.is(Blocks.REDSTONE_WIRE)) {
			//ascending_'opposite' directions
			//watching.getOpposite should have 'up'
			RedstoneSide connection = state.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(watching.getOpposite()));
			return connection == RedstoneSide.UP;

		} else if (state.getBlock() instanceof PoweredRailBlock) {
			switch (watching) {
				case NORTH -> {
					return state.getValue(PoweredRailBlock.SHAPE) == RailShape.ASCENDING_SOUTH;
				}
				case SOUTH -> {
					return state.getValue(PoweredRailBlock.SHAPE) == RailShape.ASCENDING_NORTH;
				}
				case EAST -> {
					return state.getValue(PoweredRailBlock.SHAPE) == RailShape.ASCENDING_WEST;
				}
				case WEST -> {
					return state.getValue(PoweredRailBlock.SHAPE) == RailShape.ASCENDING_EAST;
				}
				default -> {
					return false;
				}
			}
		}
		return false;
	}

	private static List<BlockPos> getNeighborsExcept(BlockPos pos, Direction except) {
		List<BlockPos> retVal = new ArrayList<>(5);
		for (Direction direction : Direction.values()) {
			if (direction == except.getOpposite()) {
				continue;
			}
			retVal.add(pos.offset(direction.getNormal()));
		}
		return retVal;
	}

	/*
		if block is observer updating block, then return position to not place until its finished
	 */
	private static Set<BlockPos> ObserverCantAvoidPos(Minecraft mc, Level world, BlockPos pos) {
		//returns true if observer should be placed regardless of state
		BlockPos posOffset;
		Set<BlockPos> relatedPos = new HashSet<>(6);
		BlockState offsetStateSchematic;
		BlockState targetState = world.getBlockState(pos);
		Block block = targetState.getBlock();
		if (block instanceof ComparatorBlock || block instanceof RepeaterBlock || block instanceof FallingBlock ||
			block instanceof BaseRailBlock || block instanceof RedStoneWireBlock || block instanceof DoorBlock ||
			((block instanceof FaceAttachedHorizontalDirectionalBlock) && targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.FLOOR)) {
			//check downward
			if (world.getBlockState(pos.below()).is(Blocks.OBSERVER) && world.getBlockState(pos.below()).getValue(ObserverBlock.FACING) == Direction.UP) {
				relatedPos.add(pos.below(2));
				relatedPos.addAll(getNeighborsExcept(pos, Direction.DOWN));
				if (world.getBlockState(pos.below(3)).getBlock() instanceof PistonBaseBlock) {
					relatedPos.add(pos.below(3));
				}
				return relatedPos;
			}
		}
		if (block instanceof NoteBlock) {
			if (!isNoteBlockInstrumentError(mc, world, pos)) {
				relatedPos.add(pos.below(2));
				relatedPos.addAll(getNeighborsExcept(pos, Direction.DOWN));
				if (world.getBlockState(pos.below(3)).getBlock() instanceof PistonBaseBlock) {
					relatedPos.add(pos.below(3));
				}
				return relatedPos;
			}
		} else if (block instanceof DoorBlock) {
			if (!isDoorHingeError(mc, world, pos)) {
				relatedPos.add(pos.below(2));
				relatedPos.addAll(getNeighborsExcept(pos, Direction.DOWN));
				if (world.getBlockState(pos.below(3)).getBlock() instanceof PistonBaseBlock) {
					relatedPos.add(pos.below(3));
				}
				return relatedPos;
			}
		}
		for (Direction direction : Direction.values()) {
			posOffset = pos.offset(direction.getNormal());
			offsetStateSchematic = world.getBlockState(posOffset);
			if (offsetStateSchematic.is(Blocks.OBSERVER) && offsetStateSchematic.getValue(ObserverBlock.FACING) == direction.getOpposite()) {
				if (block instanceof WallBlock) {
					relatedPos.add(BlockPos.of(BlockPos.offset(2, direction)));
					relatedPos.addAll(getNeighborsExcept(pos, direction));
					if (world.getBlockState(BlockPos.of(BlockPos.offset(2, direction)).below()).getBlock() instanceof PistonBaseBlock) {
						relatedPos.add(BlockPos.of(BlockPos.offset(2, direction)).below());
					}
				} else if (block instanceof FaceAttachedHorizontalDirectionalBlock) {
					if (targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING && direction == Direction.UP ||
						targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.FLOOR && direction == Direction.DOWN ||
						targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.WALL && targetState.getValue(FaceAttachedHorizontalDirectionalBlock.FACING) == direction.getOpposite()
					) {
						relatedPos.add(BlockPos.of(BlockPos.offset(2, direction)));
						relatedPos.addAll(getNeighborsExcept(pos, direction));
						if (world.getBlockState(BlockPos.of(BlockPos.offset(2, direction)).below()).getBlock() instanceof PistonBaseBlock) {
							relatedPos.add(BlockPos.of(BlockPos.offset(2, direction)).below());
						}
					}
				} else if (block instanceof PoweredRailBlock || block instanceof RedStoneWireBlock) {
					if (hasDustOrAscendingRails(world, direction.getOpposite(), pos)) {
						relatedPos.add(BlockPos.of(BlockPos.offset(2, direction)));
						relatedPos.addAll(getNeighborsExcept(pos, direction));
					}
				}
			}
		}
		return relatedPos;
	}


	private static boolean shouldAvoidPlaceCart(BlockPos pos, Level schematicWorld) {
		//avoids TNT priming
		for (Direction direction : Direction.values()) {
			if (schematicWorld.getBlockState(pos.below().offset(direction.getNormal())).is(Blocks.TNT)) {
				return true;
			}
		}
		return false;
	}

	// returns should call continue in loop
	@SuppressWarnings({"ConstantConditions"})
	private static boolean placeCart(BlockState state, Minecraft client, BlockPos pos) {
		if (state.is(Blocks.DETECTOR_RAIL) && state.getValue(DetectorRailBlock.POWERED) != client.level.getBlockState(pos).getValue(DetectorRailBlock.POWERED) && canPickItem(client, Items.MINECART.getDefaultInstance()) && client.player.position().distanceTo(Vec3.atLowerCornerOf(pos)) < 4.5) {
			Vec3 clickPos = Vec3.atLowerCornerOf(pos).add(0.5, 0.125, 0.5);
			if (!FakeAccurateBlockPlacement.canHandleOther(Items.MINECART)) {
				return false;
			}
			if (xyz.jxmm.litematica_printer_forge.utils.InventoryUtils.swapToItem(client, Items.MINECART.getDefaultInstance())) {
				InteractionResult actionResult = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(clickPos, Direction.UP, pos, false)); //place block
				if (actionResult.consumesAction()) {
					cacheEasyPlacePosition(pos, false, 600);
					return true;
				}
			}
			return false;
		}
		return false;
	}

	@SuppressWarnings({"ConstantConditions"})
	private static void placeGrindStone(BlockState state, Minecraft client, BlockPos pos) {
		if (FAKE_ROTATION_BETA.getBooleanValue()) {
			FakeAccurateBlockPlacement.request(state, pos);
			return;
			//place in air
		}
		if (!canAttachGrindstone(state, client, pos) && !isFacingCorrectly(state, client.player)) {
			return;
		}
		Direction side = state.getValue(GrindstoneBlock.FACING);
		AttachFace location = state.getValue(GrindstoneBlock.FACE);
		BlockPos clickPos;
		Vec3 hitVec;
		if (canAttachGrindstone(state, client, pos)) {
			//offset positions
			if (location == AttachFace.CEILING) {
				clickPos = pos.above();
			} else if (location == AttachFace.FLOOR) {
				clickPos = pos.below();
			} else {
				clickPos = pos.offset(side.getOpposite().getNormal());
			}
			hitVec = Vec3.atCenterOf(clickPos).add(Vec3.atLowerCornerOf(side.getNormal()).multiply(0.5, 0.5, 0.5));
			if (doSchematicWorldPickBlock(client, state, pos)) {
				cacheEasyPlacePosition(pos, false);
				client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, side, clickPos, false)); //place block
			}
		} else {
			if (isFacingCorrectly(state, client.player)) {
				hitVec = Vec3.atCenterOf(pos);
				clickPos = pos;
				if (doSchematicWorldPickBlock(client, state, pos)) {
					cacheEasyPlacePosition(pos, false);
					client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, side, clickPos, false)); //place block
				}
			}
		}
	}

	@SuppressWarnings({"ConstantConditions"})
	private static boolean canAttachGrindstone(BlockState state, Minecraft client, BlockPos pos) {
		if (FAKE_ROTATION_BETA.getBooleanValue()) {
			return true;
			//place in air.
		}
		Direction facing = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
		AttachFace location = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
		//case ceil
		if (location == AttachFace.CEILING) {
			return !client.level.getBlockState(pos.above()).getMaterial().isReplaceable() && client.player.getDirection() == facing.getOpposite() && !hasGui(client.level.getBlockState(pos.above()).getBlock()) || client.player.isSecondaryUseActive();
		} else if (location == AttachFace.FLOOR) {
			return !client.level.getBlockState(pos.below()).getMaterial().isReplaceable() && client.player.getDirection() == facing.getOpposite() && !hasGui(client.level.getBlockState(pos.below()).getBlock()) || client.player.isSecondaryUseActive();
		} else {
			return !client.level.getBlockState(pos.offset(facing.getOpposite().getNormal())).getMaterial().isReplaceable() && !hasGui(client.level.getBlockState(pos.offset(facing.getOpposite().getNormal())).getBlock()) || client.player.isSecondaryUseActive();
		}
	}

	private static boolean isFacingCorrectly(BlockState state, LocalPlayer player) {
		//if we can't attach, use player's directions
		Direction facing = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
		AttachFace location = state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
		Direction[] facingOrder = Direction.orderedByNearest(player);
		if (location == AttachFace.CEILING) {
			//primary should be UP
			//secondary should be same as facing
			return facingOrder[0] == Direction.UP && player.getDirection() == facing;
		} else if (location == AttachFace.FLOOR) {
			return facingOrder[0] == Direction.DOWN && player.getDirection() == facing;
		} else {
			return facingOrder[0] == facing.getOpposite();
		}
	}

	private static void placeTrapDoor(BlockState state, Minecraft client, BlockPos pos) {
		//check if it can be clicked on face, then place inside block
		Direction side = state.getValue(TrapDoorBlock.FACING);
		BlockPos clickPos;
		Vec3 hitVec;
		if (client.level.getBlockState(pos.offset(side.getOpposite().getNormal())).getMaterial().isReplaceable()) {
			//place inside block
			clickPos = pos;
			if (client.player.getDirection().getOpposite() == side) {
				side = state.getValue(TrapDoorBlock.HALF) == Half.TOP ? Direction.DOWN : Direction.UP;
			} else {
				return;
			}
			hitVec = Vec3.atLowerCornerOf(clickPos).add(0.5, 0.5, 0.5);
		} else {
			clickPos = pos.offset(side.getOpposite().getNormal());
			hitVec = Vec3.atLowerCornerOf(clickPos).add(0.5, 0.5, 0.5).add(Vec3.atLowerCornerOf(side.getNormal()).multiply(0.5, 0.5, 0.5));
		}
		if (doSchematicWorldPickBlock(client, state, pos)) {
			cacheEasyPlacePosition(pos, false);
			client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, side, clickPos, false)); //place block
		}
	}

	private static boolean isNoteBlockInstrumentError(Minecraft mc, Level world, BlockPos pos) {
		BlockState stateA = world.getBlockState(pos);
		BlockState stateB = mc.level.getBlockState(pos);
		return stateA.is(Blocks.NOTE_BLOCK) && stateB.is(Blocks.NOTE_BLOCK) &&
			stateA.getValue(NoteBlock.POWERED) == stateB.getValue(NoteBlock.POWERED) &&
			world.getBlockState(pos.below()).getMaterial().isReplaceable() == mc.level.getBlockState(pos.offset(Direction.DOWN.getNormal())).getMaterial().isReplaceable();
	}

	private static boolean isDoorHingeError(Minecraft mc, Level world, BlockPos pos) {
		BlockState stateA = world.getBlockState(pos);
		BlockState stateB = mc.level.getBlockState(pos);
		return stateA.hasProperty(DoorBlock.HINGE) && stateB.hasProperty(DoorBlock.HINGE) &&
			stateA.getValue(DoorBlock.POWERED) == stateB.getValue(DoorBlock.POWERED) &&
			stateA.getValue(DoorBlock.FACING) == stateB.getValue(DoorBlock.FACING) &&
			stateA.getValue(DoorBlock.OPEN) == stateB.getValue(DoorBlock.OPEN) &&
			stateA.getValue(DoorBlock.HALF) == stateB.getValue(DoorBlock.HALF);
	}

	private static boolean ObserverUpdateOrder(Minecraft mc, Level world, BlockPos pos, Box selectedBox) {
		//returns true if observer should not be placed
		boolean ExplicitObserver = PRINTER_OBSERVER_AVOID_ALL.getBooleanValue();
		BlockState stateSchematic = world.getBlockState(pos);
		BlockPos posOffset;
		BlockState OffsetStateSchematic;
		BlockState OffsetStateClient;
		if (stateSchematic.getValue(ObserverBlock.POWERED)) {
			return false;
		}
		Direction facingSchematic = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateSchematic);
		assert facingSchematic != null;
		boolean observerCantAvoid = ObserverCantAvoid(mc, world, facingSchematic, pos);
		if (observerCantAvoid) {
			return false;
		}
		posOffset = pos.offset(facingSchematic.getNormal());
		if (!isPositionWithinBox(selectedBox, posOffset)) {
			return false;
		}
		OffsetStateSchematic = world.getBlockState(posOffset);
		OffsetStateClient = mc.level.getBlockState(posOffset);
		if (OffsetStateSchematic.is(Blocks.BARRIER)) {
			return false;
		}
		if (OffsetStateSchematic.is(Blocks.OBSERVER) && OffsetStateSchematic.getValue(ObserverBlock.FACING) == facingSchematic.getOpposite()) {
			return false;
		}
		if (OffsetStateSchematic.getBlock() instanceof DoorBlock && OffsetStateClient.getBlock() instanceof DoorBlock &&
			OffsetStateSchematic.getValue(DoorBlock.POWERED) == OffsetStateClient.getValue(DoorBlock.POWERED) &&
			OffsetStateSchematic.getValue(DoorBlock.FACING) == OffsetStateClient.getValue(DoorBlock.FACING)) {
			return false;
		}
		if (ExplicitObserver) {
			if (OffsetStateSchematic.is(Blocks.BARRIER) || OffsetStateClient.isAir() && OffsetStateSchematic.isAir() || OffsetStateSchematic.is(Blocks.VOID_AIR)) {
				return false;
			} //cave air wtf
			if (isClientPowerError(mc, world, OffsetStateClient, OffsetStateSchematic, posOffset)) {
				return false;
			}
			return !OffsetStateSchematic.toString().equals(OffsetStateClient.toString());
		}
		return !OffsetStateClient.getBlock().equals(OffsetStateSchematic.getBlock());
	}

	private static BlockPos ObserverUpdateOrderPos(Minecraft mc, Level world, BlockPos pos) {
		//returns true if observer should not be placed
		boolean ExplicitObserver = PRINTER_OBSERVER_AVOID_ALL.getBooleanValue();
		BlockState stateSchematic = world.getBlockState(pos);
		BlockPos posOffset;
		BlockState OffsetStateSchematic;
		BlockState OffsetStateClient;
		if (stateSchematic.getValue(ObserverBlock.POWERED)) {
			return null;
		}
		Direction facingSchematic = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateSchematic);
		assert facingSchematic != null;
		boolean observerCantAvoid = ObserverCantAvoid(mc, world, facingSchematic, pos);
		if (observerCantAvoid) {
			return null;
		}
		posOffset = pos.offset(facingSchematic.getNormal());
		assert pos != posOffset;
		OffsetStateSchematic = world.getBlockState(posOffset);
		OffsetStateClient = mc.level.getBlockState(posOffset);
		if (OffsetStateSchematic.is(Blocks.BARRIER)) {
			return null;
		} else if (OffsetStateSchematic.is(Blocks.OBSERVER) && OffsetStateSchematic.getValue(ObserverBlock.FACING) == facingSchematic.getOpposite()) {
			return null;
		} else if (OffsetStateSchematic.getBlock() instanceof DoorBlock && OffsetStateClient.getBlock() instanceof DoorBlock &&
			OffsetStateSchematic.getValue(DoorBlock.POWERED) == OffsetStateClient.getValue(DoorBlock.POWERED) &&
			OffsetStateSchematic.getValue(DoorBlock.FACING) == OffsetStateClient.getValue(DoorBlock.FACING)) //hinge error
		{
			return null;
		}
		if (ExplicitObserver) {
			if (OffsetStateSchematic.is(Blocks.BARRIER) || OffsetStateClient.isAir() && OffsetStateSchematic.isAir()) {
				return null;
			} //cave air wtf
			if (!OffsetStateSchematic.toString().equals(OffsetStateClient.toString())) {
				if (isClientPowerError(mc, world, OffsetStateClient, OffsetStateSchematic, posOffset)) {
					return null;
				}
				return posOffset;
			}
		}

		if (!OffsetStateClient.getBlock().equals(OffsetStateSchematic.getBlock())) {
			return posOffset;
		}
		return null;
	}

	/*
	 * Checks if the block can be placed in the correct orientation if player is
	 * facing a certain direction Dont place block if orientation will be wrong
	 */
	private static boolean canPlaceFace(FacingData facedata, BlockState stateSchematic,
	                                    Direction primaryFacing, Direction horizontalFacing) {
		if (stateSchematic.is(Blocks.GRINDSTONE)) {
			return true;
		}
		Direction facing = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(stateSchematic);
		if (stateSchematic.getBlock() instanceof BaseRailBlock) {
			facing = convertRailShapetoFace(stateSchematic);
		}
		if (facing != null && facedata != null) {

			switch (facedata.type) {
				case 0: // All directions (ie, observers and pistons)
					if (facedata.isReversed) {
						return facing.getOpposite() == primaryFacing;
					} else {
						return facing == primaryFacing;
					}

				case 1: // Only Horizontal directions (ie, repeaters and comparators)
					if (facedata.isReversed) {
						return facing.getOpposite() == horizontalFacing;
					} else {
						return facing == horizontalFacing;
					}
				case 2: // Wall mountable, such as a lever, only use player direction if not on wall.
					return stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.WALL
						|| (facing == horizontalFacing && stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING ? (primaryFacing == Direction.UP && horizontalFacing == stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)) : (primaryFacing == Direction.DOWN && horizontalFacing == stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACING)));
				case 3: //rotated, why, anvil, WNES order
					return horizontalFacing.getClockWise() == facing;
				case 4: //rails
					return facing == horizontalFacing || facing == horizontalFacing.getOpposite();
				//return facing == horizontalFacing || facing == horizontalFacing.getOpposite();
				default: // Ignore rest -> TODO: Other blocks like anvils, etc...
					return true;
			}
		} else {
			if (stateSchematic.getBlock() instanceof TorchBlock && !(stateSchematic.getBlock() instanceof WallTorchBlock) && !(stateSchematic.getBlock() instanceof RedstoneWallTorchBlock)) {
				return Direction.DOWN == primaryFacing;
			}
			return true;
		}
	}

	private static boolean isReplaceableFluidSource(BlockState checkState) {
		return checkState.getBlock() instanceof LiquidBlock && checkState.getValue(LiquidBlock.LEVEL) == 0 ||
			checkState.getBlock() instanceof BubbleColumnBlock ||
			checkState.is(Blocks.SEAGRASS) || checkState.is(Blocks.TALL_SEAGRASS) ||
			checkState.getBlock() instanceof SimpleWaterloggedBlock && checkState.getValue(BlockStateProperties.WATERLOGGED) && checkState.getMaterial().isReplaceable();
	}

	private static boolean isReplaceableWaterFluidSource(BlockState checkState) {
		return checkState.is(Blocks.SEAGRASS) || checkState.is(Blocks.TALL_SEAGRASS) ||
			checkState.is(Blocks.WATER) && checkState.hasProperty(LiquidBlock.LEVEL) && checkState.getValue(LiquidBlock.LEVEL) == 0 ||
			checkState.getBlock() instanceof BubbleColumnBlock ||
			checkState.getBlock() instanceof SimpleWaterloggedBlock && checkState.hasProperty(BlockStateProperties.WATERLOGGED) && checkState.getValue(BlockStateProperties.WATERLOGGED) && checkState.getMaterial().isReplaceable();
	}

	private static boolean containsWaterloggable(BlockState state) {
		return state.getBlock() instanceof SimpleWaterloggedBlock && state.getValue(BlockStateProperties.WATERLOGGED);
	}

	private static boolean printerCheckCancel(BlockState stateSchematic, BlockState stateClient) {
		Block blockSchematic = stateSchematic.getBlock();
		if (blockSchematic instanceof SeaPickleBlock && stateSchematic.getValue(SeaPickleBlock.PICKLES) > 1) {
			Block blockClient = stateClient.getBlock();

			if (blockClient instanceof SeaPickleBlock && !Objects.equals(stateClient.getValue(SeaPickleBlock.PICKLES), stateSchematic.getValue(SeaPickleBlock.PICKLES))) {
				return blockSchematic != blockClient;
			}
		}
		if (blockSchematic instanceof SnowLayerBlock) {
			Block blockClient = stateClient.getBlock();

			if (blockClient instanceof SnowLayerBlock && stateClient.getValue(SnowLayerBlock.LAYERS) < stateSchematic.getValue(SnowLayerBlock.LAYERS)) {
				return false;
			}
		}
		if (blockSchematic instanceof SlabBlock && stateSchematic.getValue(SlabBlock.TYPE) == SlabType.DOUBLE) {
			Block blockClient = stateClient.getBlock();

			if (blockClient instanceof SlabBlock && stateClient.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
				return blockSchematic != blockClient;
			}
		}
		if (blockSchematic instanceof ComposterBlock && stateSchematic.getValue(ComposterBlock.LEVEL) > 0 && stateClient.getBlock() instanceof ComposterBlock) {
			return !Objects.equals(stateClient.getValue(ComposterBlock.LEVEL), stateSchematic.getValue(ComposterBlock.LEVEL));
		}
		Block blockClient = stateClient.getBlock();
		if (blockClient instanceof SnowLayerBlock && stateClient.getValue(SnowLayerBlock.LAYERS) < 3 && !(stateSchematic.getBlock() instanceof SnowLayerBlock)) {
			return false;
		}
		return !stateClient.isAir() && !stateClient.getMaterial().isReplaceable();
	}

	/**
	 * Apply hit vectors (used to be Carpet hit vec protocol, but I think it is
	 * uneccessary now with orientation/states programmed in)
	 *
	 * @param pos   BlockPos
	 * @param state BlockState
	 * @param side  random side
	 * @return Vec3
	 */
	public static Vec3 applyHitVec(BlockPos pos, BlockState state, Direction side) {

		double dx;
		double dy;
		double dz;
		Block block = state.getBlock();

		/*
		 * I dont know if this is needed, just doing to mimick client According to the
		 * MC protocol wiki, the protocol expects a 1 on a side that is clicked
		 */
		Vec3 clickPos = Vec3.atLowerCornerOf(pos);
		if (!(block instanceof GrindstoneBlock) && block instanceof FaceAttachedHorizontalDirectionalBlock || block instanceof TorchBlock || block instanceof WallSkullBlock
			|| block instanceof LadderBlock
			|| block instanceof TripWireHookBlock || block instanceof WallSignBlock ||
			block instanceof EndRodBlock || block instanceof BaseCoralFanBlock) {
			if (block instanceof BaseCoralFanBlock && !(block instanceof BaseCoralWallFanBlock)) {
				side = Direction.UP;
				clickPos = Vec3.atCenterOf(pos.below()).add(Vec3.atLowerCornerOf(side.getNormal()).multiply(0.5, 0.5, 0.5));
			} else if (block instanceof TorchBlock && !(block instanceof WallTorchBlock) && !(block instanceof RedstoneWallTorchBlock)) {
				side = Direction.UP;
				clickPos = Vec3.atCenterOf(pos.below()).add(Vec3.atLowerCornerOf(side.getNormal()).multiply(0.5, 0.5, 0.5));
			} else if (side == null || state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE) && state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) != AttachFace.WALL) {
				if (state.hasProperty(FaceAttachedHorizontalDirectionalBlock.FACE) && state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE) == AttachFace.CEILING) {
					side = Direction.DOWN;
				} else {
					side = Direction.UP;
				}
				clickPos = clickPos.add(0.5, 0.5, 0.5).add(Vec3.atLowerCornerOf(side.getNormal()).multiply(0.5, 0.5, 0.5));
			} else {
				clickPos = clickPos.add(0.5, 0.5, 0.5).add(Vec3.atLowerCornerOf(side.getNormal()).multiply(0.5, 0.5, 0.5));
			}
			//We are here because we can't use protocol.
		}
		dx = clickPos.x;
		dy = clickPos.y;
		dz = clickPos.z;
		if (block instanceof StairBlock) {
			if (state.getValue(StairBlock.HALF) == Half.TOP) {
				dy += 0.9;
			} else {
				dy += 0;
			}
		} else if (block instanceof SlabBlock && state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
			if (state.getValue(SlabBlock.TYPE) == SlabType.TOP) {
				dy += 0.9;
			} else {
				dy += 0;
			}
		} else if (block instanceof TrapDoorBlock) {
			if (state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
				dy += 0.9;
			} else {
				dy += 0;
			}
		}
		return new Vec3(dx, dy, dz);
	}

	private static boolean canBypass(Minecraft mc, Level world, BlockPos pos) {
		Direction direction = world.getBlockState(pos).getValue(ObserverBlock.FACING);
		BlockPos posOffset = pos.offset(direction.getOpposite().getNormal());
		return world.getBlockState(posOffset) == null || world.getBlockState(posOffset).isAir() || (!hasPowerRelatedState(mc.level.getBlockState(posOffset).getBlock())) && mc.level.getBlockState(posOffset).getBlock().getName() == world.getBlockState(posOffset).getBlock().getName();

	}

	private static boolean hasGui(Block checkGui) {
		return checkGui instanceof CraftingTableBlock || checkGui instanceof GrindstoneBlock || checkGui instanceof LeverBlock || checkGui instanceof TrapDoorBlock ||
			checkGui instanceof ButtonBlock || checkGui instanceof DoorBlock || checkGui instanceof FenceGateBlock ||
			checkGui instanceof BedBlock || checkGui instanceof NoteBlock || checkGui instanceof BaseEntityBlock;
	}

	private static boolean hasPowerRelatedState(Block block) {
		return block instanceof LeavesBlock || block instanceof LiquidBlock || block instanceof ObserverBlock || block instanceof PistonBaseBlock || block instanceof PoweredRailBlock || block instanceof DetectorRailBlock ||
			block instanceof DispenserBlock || block instanceof DiodeBlock || block instanceof LeverBlock || block instanceof TrapDoorBlock || block instanceof RedstoneTorchBlock ||
			block instanceof DoorBlock || block instanceof RedStoneWireBlock || block instanceof RedStoneOreBlock || block instanceof RedstoneLampBlock || block instanceof NoteBlock || block instanceof FenceGateBlock ||
			block instanceof ScaffoldingBlock;
	}

	/*
		returns if its block that can update neighbors
	 */
	private static boolean hasWrongStateNearby(Minecraft mc, Level schematicWorld, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos checkPos = pos.offset(direction.getNormal());
			if (hasPowerRelatedState(schematicWorld.getBlockState(checkPos).getBlock()) && schematicWorld.getBlockState(checkPos) != mc.level.getBlockState(checkPos)) {
				return true;
			}
		}
		return false;
	}

	private static String hasWrongStateNearbyReason(Minecraft mc, Level schematicWorld, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos checkPos = pos.offset(direction.getNormal());
			if (hasPowerRelatedState(schematicWorld.getBlockState(checkPos).getBlock()) && schematicWorld.getBlockState(checkPos) != mc.level.getBlockState(checkPos)) {
				return "!" + checkPos.toShortString() + " STATE " + schematicWorld.getBlockState(checkPos).toString() + " does not match with current state : " + mc.level.getBlockState(checkPos).toString() + "!";
			}
		}
		return null;
	}

	private static BlockPos hasWrongStateNearbyPos(Minecraft mc, Level schematicWorld, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos checkPos = pos.offset(direction.getNormal());
			if (hasPowerRelatedState(schematicWorld.getBlockState(checkPos).getBlock()) && schematicWorld.getBlockState(checkPos) != mc.level.getBlockState(checkPos)) {
				return checkPos;
			}
		}
		return null;
	}

	public static Vec3 applyTorchHitVec(BlockPos pos, Vec3 hitVecIn, Direction side) {
		double x = pos.getX();
		double y = pos.getY();
		double z = pos.getZ();

		double dx = hitVecIn.x;
		double dy = hitVecIn.y;
		double dz = hitVecIn.z;
		if (side == Direction.UP) {
			dy = 1;
		} else if (side == Direction.DOWN) {
			dy = -1;
		} else if (side == Direction.EAST) {
			dx = 1;
		} else if (side == Direction.WEST) {
			dx = -1;
		} else if (side == Direction.SOUTH) {
			dz = 1;
		} else if (side == Direction.NORTH) {
			dz = -1;
		}
		return new Vec3(x + dx, y + dy, z + dz);
	}

	private static void updateSignText(Minecraft mc, Level schematicWorld, BlockPos pos) {
		if (isPositionCached(pos, false)) {
			return;
		}
		if (mc.screen instanceof SignEditScreen || !schematicWorld.getBlockState(pos).is(BlockTags.SIGNS) || signCache.contains(pos.asLong())) {
			return;
		}
		@Nullable BlockEntity entity = schematicWorld.getBlockEntity(pos);
		if (entity == null) {
			return;
		}
		@Nullable BlockEntity clientEntity = mc.level.getBlockEntity(pos);
		if (clientEntity == null) {
			return;
		}
		if (entity instanceof SignBlockEntity signBlockEntity && clientEntity instanceof SignBlockEntity clientSignEntity) {
			if (clientSignEntity.getMessage(0, false).getContents() != ComponentContents.EMPTY || clientSignEntity.getMessage(1, false).getContents() != ComponentContents.EMPTY ||
				clientSignEntity.getMessage(2, false).getContents() != ComponentContents.EMPTY ||
				clientSignEntity.getMessage(3, false).getContents() != ComponentContents.EMPTY) {
				MessageHolder.sendDebugMessage("Text already exists in " + pos.toShortString());
				signCache.add(pos.asLong());
				return;
			}
			MessageHolder.sendDebugMessage("Tries to copy sign text in " + pos.toShortString());
			signCache.add(pos.asLong());
			mc.getConnection().send(new ServerboundSignUpdatePacket(signBlockEntity.getBlockPos(), signBlockEntity.getMessage(0, false).getString(), signBlockEntity.getMessage(1, false).getString(), signBlockEntity.getMessage(2, false).getString(), signBlockEntity.getMessage(3, false).getString()));
		}
	}

	/*
	 * Gets the direction necessary to build the block oriented correctly. TODO:
	 * Need a better way to do this.
	 */
	private static Boolean IsBlockSupportedCarpet(Block SchematicBlock) {
		if (SchematicBlock instanceof FaceAttachedHorizontalDirectionalBlock || SchematicBlock instanceof WallSkullBlock ||
			SchematicBlock instanceof BaseRailBlock || SchematicBlock instanceof TorchBlock || SchematicBlock instanceof BaseCoralFanBlock) {
			return false;
		}
		return ADVANCED_ACCURATE_BLOCK_PLACEMENT.getBooleanValue() || SchematicBlock instanceof GlazedTerracottaBlock || SchematicBlock instanceof ObserverBlock || SchematicBlock instanceof RepeaterBlock || SchematicBlock instanceof TrapDoorBlock ||
			SchematicBlock instanceof ComparatorBlock || SchematicBlock instanceof DispenserBlock || SchematicBlock instanceof PistonBaseBlock || SchematicBlock instanceof StairBlock;
	} //Current carpet extra does not handle other facingBlocks, gnembon please update it

	static Direction applyPlacementFacing(BlockState stateSchematic, Direction side, BlockState stateClient) {
		Block blockSchematic = stateSchematic.getBlock();
		Block blockClient = stateClient.getBlock();

		if (blockSchematic instanceof SlabBlock) {
			if (stateSchematic.getValue(SlabBlock.TYPE) == SlabType.DOUBLE && blockClient instanceof SlabBlock
				&& stateClient.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
				if (stateClient.getValue(SlabBlock.TYPE) == SlabType.TOP) {
					return Direction.DOWN;
				} else {
					return Direction.UP;
				}
			}
			// Single slab
			else {
				return Direction.NORTH;
			}
		} else if (/*blockSchematic instanceof LogBlock ||*/ blockSchematic instanceof RotatedPillarBlock) {
			Direction.Axis axis = stateSchematic.getValue(RotatedPillarBlock.AXIS);
			// Logs and pillars only have 3 directions that are important
			if (axis == Direction.Axis.X) {
				return Direction.WEST;
			} else if (axis == Direction.Axis.Y) {
				return Direction.DOWN;
			} else if (axis == Direction.Axis.Z) {
				return Direction.NORTH;
			}

		} else if (blockSchematic instanceof WallSignBlock) {
			return stateSchematic.getValue(WallSignBlock.FACING);
		} else if (blockSchematic instanceof WallSkullBlock) {
			return stateSchematic.getValue(WallSignBlock.FACING);
		} else if (blockSchematic instanceof SignBlock) {
			return Direction.UP;
		} else if (blockSchematic instanceof FaceAttachedHorizontalDirectionalBlock) {
			AttachFace location = stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACE);
			if (location == AttachFace.FLOOR) {
				return Direction.UP;
			} else if (location == AttachFace.CEILING) {
				return Direction.DOWN;
			} else {
				return stateSchematic.getValue(FaceAttachedHorizontalDirectionalBlock.FACING);
			}
		} else if (blockSchematic instanceof BaseCoralWallFanBlock) {
			return stateSchematic.getValue(BaseCoralWallFanBlock.FACING);
		} else if (blockSchematic instanceof BaseCoralFanBlock) {
			return Direction.UP;
		} else if (blockSchematic instanceof HopperBlock) {
			return stateSchematic.getValue(HopperBlock.FACING).getOpposite();
		} else if (blockSchematic instanceof LightningRodBlock) {
			return stateSchematic.getValue(LightningRodBlock.FACING);
		}  else if (stateSchematic.is(BlockTags.SHULKER_BOXES)) {
			return stateSchematic.getValue(ShulkerBoxBlock.FACING);
		} else if (blockSchematic instanceof TorchBlock) {
			if (blockSchematic instanceof WallTorchBlock || blockSchematic instanceof RedstoneWallTorchBlock) {
				return stateSchematic.getValue(WallTorchBlock.FACING);
			} else {
				return Direction.UP;
			}
		} else if (blockSchematic instanceof LadderBlock) {
			return stateSchematic.getValue(LadderBlock.FACING);
		} else if (blockSchematic instanceof TrapDoorBlock) {
			if (ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
				return Direction.UP; //Placement State fixing first
			}
			return stateSchematic.getValue(TrapDoorBlock.FACING);
		} else if (blockSchematic instanceof TripWireHookBlock) {
			return stateSchematic.getValue(TripWireHookBlock.FACING);
		} else if (blockSchematic instanceof EndRodBlock) {
			return stateSchematic.getValue(EndRodBlock.FACING);
		} else if (blockSchematic instanceof AnvilBlock) {
			if (ADVANCED_ACCURATE_BLOCK_PLACEMENT.getBooleanValue() || ACCURATE_BLOCK_PLACEMENT.getBooleanValue() && IsBlockSupportedCarpet(blockSchematic)) {
				return stateSchematic.getValue(AnvilBlock.FACING);
			}
			return stateSchematic.getValue(AnvilBlock.FACING).getCounterClockWise();
		} else if (blockSchematic instanceof BaseRailBlock) {
			return convertRailShapetoFace(stateSchematic);
		}

		// TODO: Add more for other blocks
		return side;
	}

	public static Direction convertRailShapetoFace(BlockState state) {
		String RailShape;
		if (state.getBlock() instanceof RailBlock) {
			RailShape = state.getValue(RailBlock.SHAPE).toString();
		} else {
			RailShape = state.getValue(PoweredRailBlock.SHAPE).toString();
		}
		if (RailShape.contains("east") || RailShape.contains("west")) {
			return Direction.EAST;
		} else {
			return Direction.NORTH;
		}
	}

	public static boolean isPositionCached(BlockPos pos, boolean useClicked) {
		long currentTime = System.nanoTime();
		boolean cached = false;

		for (Map.Entry<Long, Boolean> keys : List.copyOf(positionCache.keySet())) {
			PositionCache val = positionCache.get(keys);
			boolean expired = val.hasExpired(currentTime);

			if (expired) {
				positionCache.remove(keys);
			} else if (val.getPos().equals(pos)) {
				// Item placement and "using"/"clicking" (changing delay for repeaters) are
				// diffferent
				if (!useClicked || val.hasClicked) {
					cached = true;
				}
				// Keep checking and removing old entries if there are a fair amount
				if (positionCache.size() < 16) {
					break;
				}
			}
		}
		return cached;
	}

	public static void cacheEasyPlacePosition(BlockPos pos, boolean useClicked) {
		PositionCache item = new PositionCache(pos, System.nanoTime(), useClicked ? EASY_PLACE_CACHE_TIME.getIntegerValue() * 1000000L : 2800000000L);
		// TODO: Create a separate cache for clickable items, as this just makes
		// duplicates
		if (useClicked) {
			item.hasClicked = true;
		}
		Map.Entry<Long, Boolean> entry = Map.entry(pos.asLong(), useClicked);
		if (positionCache.containsKey(entry)) {
			PositionCache value = positionCache.get(entry);
			if (item.timeout > value.timeout) {
				positionCache.put(entry, item);
			}
		} else {
			positionCache.put(entry, item);
		}
	}

	public static void cacheEasyPlacePosition(BlockPos pos, boolean useClicked, int miliseconds) {
		PositionCache item = new PositionCache(pos, System.nanoTime(), miliseconds * 1000000L);
		// TODO: Create a separate cache for clickable items, as this just makes
		// duplicates
		if (useClicked) {
			item.hasClicked = true;
		}
		Map.Entry<Long, Boolean> entry = Map.entry(pos.asLong(), useClicked);
		if (positionCache.containsKey(entry)) {
			PositionCache value = positionCache.get(entry);
			if (item.timeout > value.timeout) {
				positionCache.put(entry, item);
			}
		} else {
			positionCache.put(entry, item);
		}
	}

	public static Vec3 applyCarpetProtocolHitVec(BlockPos pos, BlockState state) {
		if (Configs.Generic.EASY_PLACE_PROTOCOL.getOptionListValue() == EasyPlaceProtocol.V3) {
			return WorldUtils.applyPlacementProtocolV3(pos, state, new Vec3(pos.getX(), pos.getY(), pos.getZ()));
		}
		double code = 0;
		double y = pos.getY();
		double z = pos.getZ();
		Block block = state.getBlock();
		Direction facing = fi.dy.masa.malilib.util.BlockUtils.getFirstPropertyFacingValue(state);
		int railEnumCode = getRailShapeOrder(state);
		final int propertyIncrement = 16;
		if (facing == null && railEnumCode == 32 && !(block instanceof SlabBlock)) {
			return new Vec3(pos.getX(), y, z);
		}
		if (facing != null) {
			code = facing.get3DDataValue();
		} else if (railEnumCode != 32) {
			code = railEnumCode;
		}
		if (block instanceof RepeaterBlock) {
			code += ((state.getValue(RepeaterBlock.DELAY))) * (propertyIncrement);
		} else if (block instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.HALF) == Half.TOP) {
			code += propertyIncrement;
		} else if (block instanceof ComparatorBlock && state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT) {
			code += propertyIncrement;
		} else if (block instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.TOP) {
			code += propertyIncrement;
		} else if (block instanceof SlabBlock && state.getValue(SlabBlock.TYPE) != SlabType.DOUBLE) {
			if (state.getValue(SlabBlock.TYPE) == SlabType.TOP) //side should not be down
			{
				y += 0.9;
				//code += propertyIncrement; //slab type by protocol soon?
			}
		}
		if (code >= 0) {
			return new Vec3(code * 2 + 2 + pos.getX(), y, z);
		}
		return new Vec3(pos.getX(), y, z);
	}

	public static Integer getRailShapeOrder(BlockState state) {
		Block stateBlock = state.getBlock();
		if (stateBlock instanceof BaseRailBlock) {
			if (stateBlock instanceof RailBlock) {
				return state.getValue(RailBlock.SHAPE).ordinal();
			} else if (stateBlock instanceof DetectorRailBlock) {
				return state.getValue(DetectorRailBlock.SHAPE).ordinal();
			} else {
				return state.getValue(PoweredRailBlock.SHAPE).ordinal();
			}
		} else {
			return 32;
		}
	}


	public static class PositionCache {
		private final BlockPos pos;
		private final long timeout;
		public boolean hasClicked = false;

		private PositionCache(BlockPos pos, long time, long timeout) {
			this.pos = pos;
			this.timeout = time + timeout;
		}

		public BlockPos getPos() {
			return this.pos;
		}

		public boolean hasExpired(long currentTime) {
			return currentTime > this.timeout;
		}
	}
}
