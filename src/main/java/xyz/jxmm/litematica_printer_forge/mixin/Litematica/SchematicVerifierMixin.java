package xyz.jxmm.litematica_printer_forge.mixin.Litematica;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import xyz.jxmm.litematica_printer_forge.LitematicaMixinMod;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;

@Mixin(SchematicVerifier.class)
public class SchematicVerifierMixin {
	@Shadow
	private WorldSchematic worldSchematic;

	@Shadow
	@Final
	private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongStatesPositions;

	@Shadow
	@Final
	private static MutablePair<BlockState, BlockState> MUTABLE_PAIR;

	@Shadow
	@Final
	private HashSet<Pair<BlockState, BlockState>> ignoredMismatches;

	@Shadow
	@Final
	private Object2ObjectOpenHashMap<BlockPos, SchematicVerifier.BlockMismatch> blockMismatches;

	@Shadow
	private ClientLevel worldClient;

	@Inject(method = "checkBlockStates", at = @At("HEAD"), cancellable = true)
	private void handleInventory(int x, int y, int z, BlockState stateSchematic, BlockState stateClient, CallbackInfo ci) {
		if (!LitematicaMixinMod.VERIFY_INVENTORY.getBooleanValue()) {
			return;
		}
		MUTABLE_PAIR.setLeft(stateSchematic);
		MUTABLE_PAIR.setRight(stateClient);
		if (!this.ignoredMismatches.contains(MUTABLE_PAIR)) {
			if (stateClient == stateSchematic) {
				if (stateSchematic.getBlock() instanceof BaseEntityBlock) {
					WorldSchematic schematic = this.worldSchematic;
					BlockPos pos = new BlockPos(x, y, z);
					BlockEntity entity = schematic.getBlockEntity(pos);
					if (entity instanceof RandomizableContainerBlockEntity containerBlockEntity) {
						if (!containerBlockEntity.isEmpty()) {
							SchematicVerifier.BlockMismatch mismatch = new SchematicVerifier.BlockMismatch(SchematicVerifier.MismatchType.WRONG_STATE, stateSchematic, stateClient, 1);
							this.wrongStatesPositions.put(Pair.of(stateSchematic, stateClient), new BlockPos(x, y, z));
							this.blockMismatches.put(pos, mismatch);
							ItemUtils.setItemForBlock(this.worldClient, pos, stateClient);
							ItemUtils.setItemForBlock(this.worldSchematic, pos, stateSchematic);
							ci.cancel();
						}
					}
				}
			}
		}
	}
}

