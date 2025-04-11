package xyz.jxmm.litematica_printer_forge.utils;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class positionStorage {
	private final static Map<Long, Boolean> positionMap = new LinkedHashMap<Long, Boolean>();

	public static void clear() {
		positionMap.clear();
	}

	public static boolean hasPos(BlockPos pos) {
		Long asLong = pos.asLong();
		if (positionMap.containsKey(asLong)) {
			return positionMap.get(asLong);
		}
		return false;
	}

	public static void registerPos(BlockPos pos, Boolean val) {
		positionMap.put(pos.asLong(), val);
	}

	public static void refresh(Level world) {
		for (Long longPos : positionMap.keySet().stream().filter(longPos -> !positionMap.get(longPos) && !match(world.getBlockState(BlockPos.of(longPos)).getBlock())).collect(Collectors.toList())) {
			positionMap.remove(longPos);
		}
	}

	private static boolean match(Block block) {
		return block == Blocks.PISTON || block == Blocks.REDSTONE_TORCH || block == Blocks.SLIME_BLOCK;
	}

	public static ArrayList<BlockPos> getFalseMarkedHasBlockPosInAttackRange(Level world, Vec3 pos, int attackRange) {
		ArrayList<BlockPos> FalseMarkedList = new ArrayList<>();
		for (Long position : positionMap.keySet()) {
			BlockPos blockPos = BlockPos.of(position);
			if (!positionMap.get(position) && match(world.getBlockState(blockPos).getBlock()) && blockPos.closerToCenterThan(pos, attackRange)) {
				FalseMarkedList.add(blockPos);
			}
		}
		return FalseMarkedList;
	}
}