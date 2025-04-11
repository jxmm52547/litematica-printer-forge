package xyz.jxmm.litematica_printer_forge.mixin.quasiEssentialClient;

import com.mojang.authlib.GameProfile;
import xyz.jxmm.litematica_printer_forge.utils.FakeAccurateBlockPlacement;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.player.ProfilePublicKey;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

//see https://github.com/senseiwells/EssentialClient/blob/1.19.x/src/main/java/me/senseiwells/essentialclient/mixins/betterAccurateBlockPlacement/ClientPlayerEntityMixin.java for reference!!

@Mixin(value = LocalPlayer.class, priority = 1200)
public abstract class ClientPlayerEntityMixin extends Player {
	public ClientPlayerEntityMixin(Level world, BlockPos pos, float yaw, GameProfile gameProfile, @Nullable ProfilePublicKey publicKey) {
		super(world, pos, yaw, gameProfile, publicKey);
	}

	@Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 2), require = 0)
	private void onSendPacketVehicle(ClientPacketListener clientPlayNetworkHandler, Packet<?> packet) {
		if (FakeAccurateBlockPlacement.requestedTicks <= -3 || FakeAccurateBlockPlacement.fakeDirection == null) {
			clientPlayNetworkHandler.send(packet);
			return;
		}
		clientPlayNetworkHandler.send(new ServerboundMovePlayerPacket.PosRot(
			this.getX(), -999.0D, this.getZ(),
			FakeAccurateBlockPlacement.fakeYaw,
			FakeAccurateBlockPlacement.fakePitch,
			this.isOnGround()
		));
	}

	@Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 3), require = 0)
	private void onSendPacketAll(ClientPacketListener clientPlayNetworkHandler, Packet<?> packet) {
		if (FakeAccurateBlockPlacement.requestedTicks <= -3 || FakeAccurateBlockPlacement.fakeDirection == null) {
			clientPlayNetworkHandler.send(packet);
			return;
		}
		clientPlayNetworkHandler.send(new ServerboundMovePlayerPacket.PosRot(
			this.getX(), this.getY(), this.getZ(),
			FakeAccurateBlockPlacement.fakeYaw,
			FakeAccurateBlockPlacement.fakePitch,
			this.isOnGround()
		));
	}

	@Redirect(method = "sendPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V", ordinal = 5), require = 0)
	private void onSendPacketLook(ClientPacketListener clientPlayNetworkHandler, Packet<?> packet) {
		if (FakeAccurateBlockPlacement.requestedTicks <= -3 || FakeAccurateBlockPlacement.fakeDirection == null) {
			clientPlayNetworkHandler.send(packet);
			return;
		}
		clientPlayNetworkHandler.send(
			new ServerboundMovePlayerPacket.Rot(
				FakeAccurateBlockPlacement.fakeYaw,
				FakeAccurateBlockPlacement.fakePitch,
				this.isOnGround()
			)
		);
	}

}