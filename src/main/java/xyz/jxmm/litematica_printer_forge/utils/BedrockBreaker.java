package xyz.jxmm.litematica_printer_forge.utils;

import net.minecraft.world.level.block.Block;  //import net.minecraft.block.Block;
import net.minecraft.world.level.block.state.BlockState;  //import net.minecraft.block.BlockState;
import net.minecraft.world.level.block.Blocks;  //import net.minecraft.block.Blocks;
import net.minecraft.world.level.block.TorchBlock;  //import net.minecraft.block.TorchBlock;
import net.minecraft.client.Minecraft;  //import net.minecraft.client.MinecraftClient;
import net.minecraft.client.multiplayer.ClientLevel;  //import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.entity.player.Inventory;  //import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.world.item.ItemStack;  //import net.minecraft.item.ItemStack;
import net.minecraft.world.item.Items;  //import net.minecraft.item.Items;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;  //import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;  //import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;  //import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.world.InteractionHand;  //import net.minecraft.util.Hand;
import net.minecraft.world.phys.BlockHitResult;  //import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.core.BlockPos;  //import net.minecraft.util.math.BlockPos;
import net.minecraft.core.Direction;  //import net.minecraft.util.math.Direction;
import net.minecraft.world.phys.Vec3;  //import net.minecraft.util.math.Vec3d;
import net.minecraft.world.level.Level;  //import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

import static xyz.jxmm.litematica_printer_forge.LitematicaMixinMod.*;

// since 1.19, you can't swap items too fast (huh)
@SuppressWarnings("ConstantConditions")
public class BedrockBreaker {
	public static long lastPlaced = new Date().getTime();
	public static Long CurrentTick = 0L;
	static List<Direction> HORIZONTAL = List.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH);
	private static final Map<Long, PositionCache> targetPosMap = new LinkedHashMap<>();
	static int rangeX = EASY_PLACE_MODE_RANGE_X.getIntegerValue();
	static int rangeY = EASY_PLACE_MODE_RANGE_Y.getIntegerValue();
	static int rangeZ = EASY_PLACE_MODE_RANGE_Z.getIntegerValue();
	static int MaxReach = Math.max(Math.max(rangeX, rangeY), rangeZ);

	public static void clear() {
		targetPosMap.clear();
		positionStorage.clear();
	}

	private static boolean shouldExtend(Level world, BlockPos pos, Direction pistonFace) {
		for (Direction direction : Direction.values()) {
			if (direction != pistonFace && world.hasSignal(pos.offset(direction.getNormal()), direction)) {
				return true;
			}
		}
		if (world.hasSignal(pos, Direction.DOWN)) {
			return true;
		} else {
			BlockPos blockPos = pos.above();
			for (Direction qcDirections : Direction.values()) {
				if (qcDirections != Direction.DOWN && world.hasSignal(blockPos.offset(qcDirections.getNormal()), qcDirections)) {
					return true;
				}
			}
			return false;
		}
	}

	@Nullable
	public static TorchPath getPistonTorchPosDir(Minecraft mc, BlockPos bedrockPos) {
		for (Direction lv : Direction.values()) {
			if (!ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
				if (lv != Direction.DOWN && lv != Direction.UP) {
					continue;
				}
			}
			BlockPos pistonPos = bedrockPos.offset(lv.getNormal());
			if (!mc.level.getBlockState(pistonPos).isAir() || !isBlockPosinYRange(pistonPos)) {
				continue;
			}
			for (Direction pistonFacing : Direction.values()) {
				if (pistonFacing.getOpposite() == lv) {
					continue;
				}
				BlockPos checkAir = pistonPos.offset(pistonFacing.getNormal());
				if (!isBlockPosinYRange(checkAir)) {
					continue;
				}
				if (shouldExtend(mc.level, pistonPos, pistonFacing)) {
					continue;
				}
				if (mc.level.getBlockState(checkAir).isAir() || mc.level.getBlockState(checkAir).getMaterial().isReplaceable()) {
					TorchData torchdata = getPossiblePowerableTorchPosFace(mc, bedrockPos, pistonPos, checkAir);
					if (torchdata != null) {
						TorchPath torchPath = new TorchPath(torchdata.TorchPos, torchdata.Torchfacing, pistonPos, pistonFacing, lv.getOpposite());
						if (torchdata.SlimePos != null) {
							torchPath.slimePos = torchdata.SlimePos;
						}
						return torchPath;
					}
				}
			}
		}
		return null;
	}

	public static boolean isBlockPosinYRange(BlockPos checkPos) {
		return (checkPos.getY() < Printer.worldTopY && Printer.worldBottomY < checkPos.getY());
	}

	@Nullable
	public static TorchData getPossiblePowerableTorchPosFace(Minecraft mc, BlockPos pos1, BlockPos pistonPos, BlockPos pos2) {
		Level world = mc.level;
		boolean forceSlimeBlock = BEDROCK_BREAKING_FORCE_TORCH.getBooleanValue();
		for (Direction hd : HORIZONTAL) { //normal 4 dir
			BlockPos torchCheck = pistonPos.offset(hd.getNormal());
			if (!isBlockPosinYRange(torchCheck)) {
				continue;
			}
			if (torchCheck.equals(pos1) || torchCheck.equals(pos2)) {
				continue;
			}
			if (!world.getBlockState(torchCheck).isAir() && !world.getBlockState(torchCheck).getMaterial().isReplaceable()) {
				continue;
			}
			// check torch can be placed
			if (!world.getBlockState(torchCheck.below()).is(Blocks.PISTON) && TorchBlock.canSupportCenter(world, torchCheck.below(), Direction.DOWN)) {
				return new TorchData(torchCheck, Direction.UP);
			} else if (forceSlimeBlock && canPlaceSlime(mc)) {
				BlockPos slimePos = torchCheck.below();
				if (slimePos.equals(pos2)) {
					continue;
				}
				if (world.getBlockState(slimePos).isAir() || world.getBlockState(slimePos).getMaterial().isReplaceable()) {
					//placeSlime(mc, slimePos);
					TorchData torchData = new TorchData(torchCheck, Direction.UP);
					torchData.registerSlimePos(slimePos);
					return torchData;
				}
			} else {
				for (Direction hd2 : HORIZONTAL) {
					if (hd2 == hd) {
						continue;
					}
					if (canPlaceAt(hd2, world, torchCheck)) {
						return new TorchData(torchCheck, hd2);
					}
				}
			}
		}
		for (Direction hd : HORIZONTAL) { //qc
			BlockPos torchCheck = pistonPos.above().offset(hd.getNormal());
			if (!isBlockPosinYRange(torchCheck)) {
				continue;
			}
			if (torchCheck.equals(pos1) || torchCheck.equals(pos2)) {
				continue;
			}
			if (!world.getBlockState(torchCheck).isAir() && !world.getBlockState(torchCheck).getMaterial().isReplaceable()) {
				continue;
			}
			// check torch can be placed
			if (!world.getBlockState(torchCheck.below()).is(Blocks.PISTON) && TorchBlock.canSupportCenter(world, torchCheck.below(), Direction.DOWN)) {
				return new TorchData(torchCheck, Direction.UP);
			} else if (forceSlimeBlock && canPlaceSlime(mc)) {
				BlockPos slimePos = torchCheck.below();
				if (slimePos.equals(pos2)) {
					continue;
				}
				if (!isBlockPosinYRange(slimePos)) {
					continue;
				}
				if (world.getBlockState(slimePos).isAir() || world.getBlockState(slimePos).getMaterial().isReplaceable()) {
					TorchData torchData = new TorchData(torchCheck, Direction.UP);
					//placeSlime(mc, slimePos);
					torchData.registerSlimePos(slimePos);
					return torchData;
				}
			} else {
				for (Direction hd2 : HORIZONTAL) {
					if (hd2 == hd) {
						continue;
					}
					if (canPlaceAt(hd2, world, torchCheck)) {
						return new TorchData(torchCheck, hd2);
					}
				}
			}
		}
		BlockPos torchCheck = pistonPos.below(); // down
		if (pos2 != torchCheck && isBlockPosinYRange(torchCheck)) {
			if (torchCheck.equals(pos1) || torchCheck.equals(pos2)) {
				return null;
			}
			if (!world.getBlockState(torchCheck).isAir() && !world.getBlockState(torchCheck).getMaterial().isReplaceable()) {
				return null;
			}
			if (!world.getBlockState(torchCheck.below()).is(Blocks.PISTON) && TorchBlock.canSupportCenter(world, torchCheck.below(), Direction.DOWN)) {
				return new TorchData(torchCheck, Direction.UP);
			} else if (forceSlimeBlock && canPlaceSlime(mc)) {
				BlockPos slimePos = torchCheck.below();
				if (slimePos.equals(pos2)) {
					return null;
				}
				if (isBlockPosinYRange(slimePos) && world.getBlockState(slimePos).isAir() || world.getBlockState(slimePos).getMaterial().isReplaceable()) {
					TorchData torchData = new TorchData(torchCheck, Direction.UP);
					torchData.registerSlimePos(slimePos);
					return torchData;
				}
			}
		}
		return null;
	}

	public static void removeScheduledPos(Minecraft mc) {
		for (Long position : targetPosMap.keySet().stream().filter(position ->
			targetPosMap.get(position) != null && CurrentTick - targetPosMap.get(position).SysTime > 200L && targetPosMap.get(position).isIdle()).collect(Collectors.toList())) {
			targetPosMap.remove(position);
		}
		for (Long position : targetPosMap.keySet().stream().filter(position ->
			targetPosMap.get(position).canSafeRemove(mc.level)).toList()) {
			targetPosMap.remove(position);
		}
	}

	public static boolean canPlaceAt(Direction lv, Level world, BlockPos pos) {
		BlockPos lv2 = pos.offset(lv.getOpposite().getNormal());
		BlockState lv3 = world.getBlockState(lv2);
		if (lv3.is(Blocks.PISTON)) {
			return false;
		}
		return lv3.isFaceSturdy(world, lv2, lv);
	}

	public static void placePiston(Minecraft mc, BlockPos pos, Direction facing) {
		final ItemStack PistonStack = Items.PISTON.getDefaultInstance();
		InventoryUtils.swapToItem(mc, PistonStack);
		MessageHolder.sendDebugMessage("Places piston at %s with facing %s".formatted(pos.toShortString(), facing));
		//mc.getConnection().sendPacket(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selectedSlot));
		if (ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
			placeViaCarpet(mc, pos, facing);
		} else {
			placeViaPacketReversed(mc, pos, facing, false);
		}
	}

	public static void placePiston(Minecraft mc, BlockPos pos, Direction facing, boolean sync) {
		final ItemStack PistonStack = Items.PISTON.getDefaultInstance();
		InventoryUtils.swapToItem(mc, PistonStack);
		if (sync) {
			mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selected));
		}
		MessageHolder.sendDebugMessage("Places piston at %s with facing %s".formatted(pos.toShortString(), facing));
		//mc.getConnection().sendPacket(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selectedSlot));
		if (ACCURATE_BLOCK_PLACEMENT.getBooleanValue()) {
			placeViaCarpet(mc, pos, facing);
		} else {
			placeViaPacketReversed(mc, pos, facing, false);
		}
	}

	public static void placeSlime(Minecraft mc, BlockPos pos) {
		final ItemStack SlimeStack = Items.SLIME_BLOCK.getDefaultInstance();
		InventoryUtils.swapToItem(mc, SlimeStack);
		MessageHolder.sendDebugMessage("Places slime at %s".formatted(pos.toShortString()));
		//mc.getConnection().sendPacket(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selectedSlot));
		placeViaCarpet(mc, pos, Direction.UP);
	}

	public static void placeViaCarpet(Minecraft mc, BlockPos pos, Direction facing) {
		positionStorage.registerPos(pos, true);
		Vec3 hitVec = new Vec3(pos.getX() + 2 + (facing.get3DDataValue() * 2), pos.getY(), pos.getZ());
		BlockHitResult hitResult = new BlockHitResult(hitVec, facing, pos, false);
		InteractionHandleTweakPlacementPacket(mc, hitResult);
	}

	public static void placeViaPacketReversed(Minecraft mc, BlockPos pos, Direction facing, boolean ShouldOffset) {
		positionStorage.registerPos(pos, true);
		int px = pos.getX();
		int py = pos.getY();
		int pz = pos.getZ();
		Vec3 hitPos = new Vec3(px, py, pz);
		if (ShouldOffset) {
			if (facing == Direction.DOWN) {
				py += 0;
			} else if (facing == Direction.UP) {
				py += 0;
			} else if (facing == Direction.NORTH) {
				pz += 1;
			} else if (facing == Direction.SOUTH) {
				pz -= 1;
			} else if (facing == Direction.EAST) {
				px -= 1;
			} else if (facing == Direction.WEST) {
				px += 1;
			}
		}
		BlockPos npos = new BlockPos(px, py, pz);
		if (ShouldOffset) {
			hitPos = Printer.applyTorchHitVec(npos, new Vec3(0.5, 0.5, 0.5), facing);
			if (facing == Direction.DOWN) {
				facing = Direction.UP;
			}
		}
		float OriginPitch = mc.player.getViewXRot(1.0f);
		float OriginYaw = mc.player.getViewYRot(1.0f);
		if (facing == Direction.DOWN) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(OriginYaw, -90.0f, mc.player.isOnGround()));
		} else if (facing == Direction.UP) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(OriginYaw, 90.0f, mc.player.isOnGround()));
		} else if (facing == Direction.EAST) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(90.0f, OriginPitch, mc.player.isOnGround()));
		} else if (facing == Direction.WEST) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(-90.0f, OriginPitch, mc.player.isOnGround()));
		} else if (facing == Direction.NORTH) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(0.0f, OriginPitch, mc.player.isOnGround()));
		} else if (facing == Direction.SOUTH) {
			mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(180.0f, OriginPitch, mc.player.isOnGround()));
		}
		BlockHitResult hitResult = new BlockHitResult(hitPos, facing, npos, false);
		InteractionHandleTweakPlacementPacket(mc, hitResult);
		mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(OriginYaw, OriginPitch, mc.player.isOnGround()));
	}

	public static void InteractionHandleTweakPlacementPacket(Minecraft mc, BlockHitResult hitResult) {
		//mc.getConnection().sendPacket(new PlayerInteractBlockC2SPacket(InteractionHand.MAIN_InteractionHand, hitResult, 64));
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
	}

	public static void placeTorch(Minecraft mc, BlockPos pos, Direction torchFacing) {
		final ItemStack redstoneTorchStack = Items.REDSTONE_TORCH.getDefaultInstance();
		InventoryUtils.swapToItem(mc, redstoneTorchStack);
		BlockPos npos;
		if (torchFacing.getAxis() == Direction.Axis.Y) {
			npos = pos.below();
			torchFacing = Direction.UP;
		} else {
			npos = pos.offset(torchFacing.getOpposite().getNormal());
		}
		MessageHolder.sendDebugMessage("Places torch at %s with facing %s".formatted(pos.toShortString(), torchFacing));
		Vec3 hitVec = Vec3.atCenterOf(npos).add(Vec3.atLowerCornerOf(torchFacing.getNormal()).multiply(0.5, 0.5, 0.5));
		BlockHitResult hitResult = new BlockHitResult(hitVec, torchFacing, npos, false);
		MessageHolder.sendDebugMessage("Hitresult is %s %s".formatted(hitVec, npos.toShortString()));
		mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
		positionStorage.registerPos(pos, true);
	}


	public static boolean canProcess(Minecraft mc, BlockPos pos) {
		double SafetyDistance = BEDROCK_BREAKING_RANGE_SAFE.getIntegerValue();
		if (positionAnyNear(mc, pos, SafetyDistance)) {
			return false;
		}
		if (targetPosMap.containsKey(pos.asLong())) {
			return targetPosMap.get(pos.asLong()).isIdle();
		}
		return true;
	}

	public static boolean positionAnyNear(Minecraft mc, BlockPos pos, double distance) {
		for (Long position : targetPosMap.keySet()) {
			@Nullable PositionCache item = targetPosMap.get(position);
			if (item == null) {
				continue;
			}
			if (isPositionInRange(mc, BlockPos.of(position)) && item.distanceLessThan(pos, distance) && !item.isIdle()) {
				return true;
			}
		}
		return false;
	}

	public static boolean isItemPrePared(Minecraft mc) {
		Inventory inv = mc.player.getInventory();
		ItemStack PistonStack = Items.PISTON.getDefaultInstance();
		ItemStack RedstoneTorchStack = Items.REDSTONE_TORCH.getDefaultInstance();
		return inv.findSlotMatchingItem(PistonStack) != -1 && inv.findSlotMatchingItem(RedstoneTorchStack) != -1;
	}

	public static boolean canPlaceSlime(Minecraft mc) {
		Inventory inv = mc.player.getInventory();
		ItemStack SlimeStack = Items.SLIME_BLOCK.getDefaultInstance();
		return inv.findSlotMatchingItem(SlimeStack) != -1;
	}

	public static void switchTool(Minecraft mc) {
		int bestSlotId = Breaker.getBestItemSlotIdToMineState(mc, Blocks.PISTON.defaultBlockState());
		if (bestSlotId == -1) {
			return;
		}
		ItemStack stack = mc.player.getInventory().getItem(bestSlotId);
		MessageHolder.sendDebugMessage("Swaps to Pickaxe " + stack);
		InventoryUtils.swapToItem(mc, stack);
		MessageHolder.sendDebugMessage("Holding stack " + mc.player.getMainHandItem());
		mc.getConnection().send(new ServerboundSetCarriedItemPacket(mc.player.getInventory().selected));
	}


	public static void attackBlock(Minecraft mc, BlockPos pos, Direction direction) {
		if (mc.level.getBlockState(pos).isAir()) {
			return;
		}
		mc.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, direction, 64));
		//positionStorage.registerPos(pos, false);
	}


	public static boolean isBlockNotInstantBreakable(Block block) {
		return block.equals(Blocks.BEDROCK) || block.equals(Blocks.OBSIDIAN);
	}

	public static boolean isPositionInRange(Minecraft mc, BlockPos pos) {
		double pX = mc.player.getX();
		double pY = mc.player.getY();
		double pZ = mc.player.getZ();
		int aX = pos.getX();
		int aY = pos.getY();
		int aZ = pos.getZ();
		return (pX - aX) * (pX - aX) + (pY - aY) * (pY - aY) + (pZ - aZ) * (pZ - aZ) < MaxReach * MaxReach;
	}

	public static int processRemainder(Minecraft mc, int maxInteract) {
		//positionStorage.refresh(mc.level);
		int ret = 0;
		switchTool(mc);
		ArrayList<BlockPos> attackList = positionStorage.getFalseMarkedHasBlockPosInAttackRange(mc.level, mc.player.position(), MaxReach);
		for (BlockPos position : attackList) {
			if (ret >= maxInteract) {
				return ret;
			}
			attackBlock(mc, position, Direction.UP);
			ret++;
		}
		return ret;
	}

	synchronized public static int scheduledTickHandler(Minecraft mc, @Nullable BlockPos pos) {
		if (!isItemPrePared(mc)) {
			MessageHolder.sendUniqueMessage(mc.player, "[BedrockBreaking]Items is not prepared, requires Redstone torch, Piston block + haste2 + eff 5 diamond+ pickaxe.");
			return 0;
		}
		rangeX = EASY_PLACE_MODE_RANGE_X.getIntegerValue();
		rangeY = EASY_PLACE_MODE_RANGE_Y.getIntegerValue();
		rangeZ = EASY_PLACE_MODE_RANGE_Z.getIntegerValue(); //reset range values
		int maxInteract = PRINTER_MAX_BLOCKS.getIntegerValue();
		int interacted = 0;
		interacted += BedrockBreaker.processRemainder(mc, maxInteract);
		if (interacted >= maxInteract) {
			return interacted;
		}
		MaxReach = Math.max(Math.max(rangeX, rangeY), rangeZ);
		removeScheduledPos(mc);
		if (pos != null && !targetPosMap.containsKey(pos.asLong()) && isPositionInRange(mc, pos) && canProcess(mc, pos) && new Date().getTime() - lastPlaced > 1000.0 * EASY_PLACE_MODE_DELAY.getDoubleValue()) {
			TorchPath torch = getPistonTorchPosDir(mc, pos);
			if (torch != null && torch.isAllPosInRange(mc)) {
				lastPlaced = new Date().getTime();
				BlockPos TorchPos = torch.TorchPos;
				Direction TorchFacing = torch.Torchfacing;
				BlockPos PistonPos = torch.PistonPos;
				Direction PistonFacing = torch.Pistonfacing;
				Direction PistonExtendFacing = torch.PistonBreakableFacing;
				BlockPos SlimePos = torch.slimePos;
				MessageHolder.sendDebugMessage("Will place Torch at %s, facing %s \n Piston at %s, Facing %s, and changes as %s \n Optional Slime at %s".formatted(TorchPos.toShortString(), TorchFacing, PistonPos.toShortString(), PistonFacing, PistonExtendFacing, SlimePos));
				if (SlimePos != null) {
					placeSlime(mc, SlimePos);
				}
				placeTorch(mc, TorchPos, TorchFacing);
				placePiston(mc, PistonPos, PistonFacing);
				interacted += 2;
				targetPosMap.put(pos.asLong(), new PositionCache(PistonPos, PistonExtendFacing, TorchPos, pos, SlimePos));
			}
		}
		for (Long posLong : targetPosMap.keySet()) {
			if (interacted >= maxInteract) {
				return interacted;
			}
			PositionCache item = targetPosMap.get(posLong);
			if (item == null || !item.isAllPosInRange(mc)) {
				continue;
			}
			interacted += item.doSomething(mc);
		}
		return interacted;
	}

	public static void tick() {
		CurrentTick += 1L;
	}

	public static class PositionCache {
		public final BlockPos pistonPos;
		public final Direction facing;
		public final BlockPos torchPos;
		public final BlockPos targetPos;
		public long SysTime;
		public final BlockPos slimePos;
		public State state;

		public enum State {
			WAIT,
			EXTENDED,
			IDLE,
			FAIL,
			DONE,
			CLEAR
		}

		private PositionCache(BlockPos pistonPos, Direction facing, BlockPos torchPos, BlockPos targetPos, BlockPos slimePos) {
			this.pistonPos = pistonPos;
			this.facing = facing;
			this.torchPos = torchPos;
			this.targetPos = targetPos;
			this.SysTime = CurrentTick;
			this.slimePos = slimePos;
			this.state = State.WAIT;
		}

		public void setFalse() {
			positionStorage.registerPos(this.pistonPos, false);
			positionStorage.registerPos(this.torchPos, false);
			if (this.slimePos != null) {
				positionStorage.registerPos(this.slimePos, false);
			}
		}

		public boolean isAllPosInRange(Minecraft mc) {
			return mc.player.distanceToSqr(Vec3.atCenterOf(this.pistonPos)) < MaxReach * MaxReach && mc.player.distanceToSqr(Vec3.atCenterOf(this.torchPos)) < MaxReach * MaxReach &&
				mc.player.distanceToSqr(Vec3.atCenterOf(this.targetPos)) < MaxReach * MaxReach && (this.slimePos == null || mc.player.distanceToSqr(Vec3.atCenterOf(this.slimePos)) < MaxReach * MaxReach);
		}

		public boolean canSafeRemove(Level world) {
			return (this.state == State.DONE || this.state == State.CLEAR) && world.getBlockState(this.torchPos).isAir() &&
				world.getBlockState(this.pistonPos).isAir() && (this.slimePos == null || world.getBlockState(this.slimePos).isAir());
		}

		private void refresh(ClientLevel world) {
			switch (this.state) {
				case WAIT: {
					if (CurrentTick == this.SysTime + 1 || CurrentTick > this.SysTime + 4) {
						this.state = State.EXTENDED;
					}
				}
				case IDLE: {
					if (this.SysTime + BEDROCK_BREAKING_CLEAR_WAIT.getIntegerValue() < CurrentTick) {
						this.setFalse();
						this.state = world.getBlockState(this.targetPos).is(Blocks.BEDROCK) ? State.FAIL : State.DONE;
					}
				}
			}
		}

		public int doSomething(Minecraft mc) {
			refresh(mc.level);
			switch (this.state) {
				case EXTENDED -> {
					this.processBreaking(mc);
					return 4;
				}
				case FAIL -> {
					this.resetFailure(mc);
					return 3;
				}
			}
			return 0;
		}

		public boolean isIdle() {
			return this.state == State.CLEAR || this.state == State.DONE;
		}

		public boolean distanceLessThan(BlockPos ReferPos, double distance) {
			BlockPos pos = this.targetPos;
			int aX = pos.getX();
			int aY = pos.getY();
			int aZ = pos.getZ();
			int pX = ReferPos.getX();
			int pY = ReferPos.getY();
			int pZ = ReferPos.getZ();
			return ((pX - aX) * (pX - aX) + (pY - aY) * (pY - aY) + (pZ - aZ) * (pZ - aZ)) < distance * distance;
		}

		public void processBreaking(Minecraft mc) {
			switchTool(mc);
			if (slimePos != null && !mc.level.getBlockState(slimePos).isAir()) {
				attackBlock(mc, torchPos, Direction.UP);
				attackBlock(mc, slimePos, Direction.UP);
				MessageHolder.sendDebugMessage("Broke slime at " + slimePos.toShortString());
			} else {
				attackBlock(mc, torchPos, Direction.UP);
				MessageHolder.sendDebugMessage("Broke torch at " + torchPos.toShortString());
			}
			attackBlock(mc, pistonPos, Direction.UP);
			MessageHolder.sendDebugMessage("Broke piston at " + pistonPos.toShortString());

			placePiston(mc, pistonPos, facing, true);
			this.state = State.IDLE;
		}

		public void resetFailure(Minecraft mc) {
			switchTool(mc);
			if (slimePos != null && !mc.level.getBlockState(slimePos).isAir()) {
				attackBlock(mc, torchPos, Direction.UP);
				attackBlock(mc, slimePos, Direction.UP);
				MessageHolder.sendDebugMessage("Broke slime at + (failure) " + slimePos.toShortString());
			} else {
				attackBlock(mc, torchPos, Direction.UP);
				MessageHolder.sendDebugMessage("Broke torch at + (failure) " + torchPos.toShortString());
			}
			MessageHolder.sendDebugMessage("Broke piston at + (failure) " + pistonPos.toShortString());
			attackBlock(mc, pistonPos, Direction.UP);
			this.state = State.CLEAR;
			this.setFalse();
		}
	}

	public static class TorchPath {
		private final BlockPos TorchPos;
		private final Direction Torchfacing;
		private final BlockPos PistonPos;
		private final Direction Pistonfacing;
		private final Direction PistonBreakableFacing;
		private BlockPos slimePos;

		public TorchPath(BlockPos TorchPos, Direction Torchfacing, BlockPos PistonPos, Direction Pistonfacing, Direction PistonBreakableFacing) {
			this.TorchPos = TorchPos;
			this.Torchfacing = Torchfacing;
			this.Pistonfacing = Pistonfacing;
			this.PistonPos = PistonPos;
			this.PistonBreakableFacing = PistonBreakableFacing;
		}

		public boolean isAllPosInRange(Minecraft mc) {
			return mc.player.distanceToSqr(Vec3.atCenterOf(this.TorchPos)) < MaxReach * MaxReach && mc.player.distanceToSqr(Vec3.atCenterOf(this.PistonPos)) < MaxReach * MaxReach &&
				(this.slimePos == null || mc.player.distanceToSqr(Vec3.atCenterOf(this.slimePos)) < MaxReach * MaxReach);
		}

	}

	public static class TorchData {
		private final BlockPos TorchPos;
		private final Direction Torchfacing;
		private BlockPos SlimePos = null;

		public TorchData(BlockPos TorchPos, Direction Torchfacing) {
			this.TorchPos = TorchPos;
			this.Torchfacing = Torchfacing;
		}

		public void registerSlimePos(BlockPos slimePos) {
			this.SlimePos = slimePos;
		}
	}
}
