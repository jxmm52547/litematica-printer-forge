package xyz.jxmm.litematica_printer_forge.utils;

import com.google.common.collect.Lists;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

//see IMixinConfigPlugin
public class MixinConfigPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		List<String> fakeLookMixins = Lists.newArrayList(

			"xyz.jxmm.litematica_printer_forge.mixin.EssentialClient.FakeLookMixin"
		);
		List<String> desyncOptions = Lists.newArrayList(

			"xyz.jxmm.litematica_printer_forge.mixin.MinecraftClient.ClientPlayerInteractionManagerMixin",
			"xyz.jxmm.litematica_printer_forge.mixin.MinecraftClient.ClientPlayNetworkHandlerMixin"
		);

		if (fakeLookMixins.contains(mixinClassName)) {
			return LoadingModList.get().getModFiles().contains(LoadingModList.get().getModFileById("curtain"));
		}
		if (desyncOptions.contains(mixinClassName)) {
			return LoadingModList.get().getModFiles().contains(LoadingModList.get().getModFileById("matchrevisions"));
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}
}