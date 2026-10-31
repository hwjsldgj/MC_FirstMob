package com.example.firstmob.mixin;

import com.example.firstmob.entity.YaoYongshouEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听玩家发出的音效（受伤、吃东西、落地、游泳、挖掘……，脚步声由实体侧过滤）。
 * <p>玩家自己的音效都会走 {@link PlayerEntity#playSound(SoundEvent, float, float)}，
 * 所以拦这里就能拿到"谁在发声、在哪发声"，且不会误把怪物/方块的音效算进来。
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerSoundMixin {
	@Inject(method = "playSound(Lnet/minecraft/sound/SoundEvent;FF)V", at = @At("HEAD"))
	private void firstmob$reportPlayerSound(SoundEvent sound, float volume, float pitch, CallbackInfo ci) {
		PlayerEntity player = (PlayerEntity) (Object) this;
		YaoYongshouEntity.onPlayerSound(player, sound);
	}
}
