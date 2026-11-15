package com.example.hush.mixin;

import com.example.hush.entity.YaoArmorPiercingDamageSource;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「N 点无视护甲」只把 N 点排除在护甲减伤之外，其余伤害照常过护甲：
 * 先把入场伤害减去穿刺值，走完原版护甲公式后，再把穿刺值原样加回。
 * 这样抗性、保护、吸收等后续结算仍由原版处理，且只触发一次受伤事件。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityArmorMixin {
	@ModifyVariable(method = "applyArmorToDamage", at = @At("HEAD"), argsOnly = true)
	private float hush$excludePiercingFromArmor(float amount, DamageSource source) {
		if (source instanceof YaoArmorPiercingDamageSource piercingSource) {
			return Math.max(0.0F, amount - piercingSource.getPiercing());
		}
		return amount;
	}

	@Inject(method = "applyArmorToDamage", at = @At("RETURN"), cancellable = true)
	private void hush$addPiercingBack(DamageSource source, float amount, CallbackInfoReturnable<Float> cir) {
		if (source instanceof YaoArmorPiercingDamageSource piercingSource && piercingSource.getPiercing() > 0.0F) {
			cir.setReturnValue(cir.getReturnValue() + piercingSource.getPiercing());
		}
	}
}
