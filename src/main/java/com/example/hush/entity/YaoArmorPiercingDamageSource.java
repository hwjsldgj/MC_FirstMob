package com.example.hush.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * 带「部分无视护甲」的伤害源：其中 {@link #getPiercing()} 点伤害不参与护甲减伤，
 * 其余照常结算护甲、抗性与保护。由 {@code LivingEntityArmorMixin} 在
 * {@code LivingEntity#applyArmorToDamage} 里拆开处理。
 */
public class YaoArmorPiercingDamageSource extends DamageSource {
	private final float piercing;

	public YaoArmorPiercingDamageSource(RegistryEntry<DamageType> type, Entity source, Entity attacker,
			float piercing) {
		super(type, source, attacker);
		this.piercing = Math.max(0.0F, piercing);
	}

	public float getPiercing() {
		return this.piercing;
	}
}
