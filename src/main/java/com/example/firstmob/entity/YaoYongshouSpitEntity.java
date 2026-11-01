package com.example.firstmob.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.projectile.LlamaSpitEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 姚永寿吐出的口水：命中后造成伤害并击退目标（原版羊驼口水伤害 1 且无击退）。
 * 伤害类型自定义且 scaling=never，因此在和平难度下依然生效。
 */
public class YaoYongshouSpitEntity extends LlamaSpitEntity {
	/** 自定义伤害类型：data/firstmob/damage_type/yao_spit.json */
	public static final RegistryKey<DamageType> DAMAGE_TYPE =
			RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of("firstmob", "yao_spit"));
	/** <60% 锁定时的穿甲口水：data/firstmob/damage_type/yao_spit_piercing.json，并登记到 minecraft:bypasses_armor。 */
	public static final RegistryKey<DamageType> PIERCING_DAMAGE_TYPE =
			RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of("firstmob", "yao_spit_piercing"));

	private static final float DAMAGE = 4.0F;
	/** 锁定档：5 点伤害且穿透护甲。 */
	private static final float LOCK_DAMAGE = 5.0F;
	private static final double KNOCKBACK = 0.9;

	public YaoYongshouSpitEntity(EntityType<? extends LlamaSpitEntity> entityType, World world) {
		super(entityType, world);
	}

	@Override
	protected void onEntityHit(EntityHitResult entityHitResult) {
		if (this.getWorld().isClient()) {
			return;
		}
		Entity hit = entityHitResult.getEntity();
		Entity owner = this.getOwner();
		if (!(hit instanceof LivingEntity living) || living == owner || living instanceof YaoYongshouEntity) {
			return;
		}
		if (owner instanceof LivingEntity livingOwner) {
			boolean piercing = livingOwner instanceof YaoYongshouEntity yao && yao.isLockMode();
			DamageSource source = this.getDamageSources().create(
					piercing ? PIERCING_DAMAGE_TYPE : DAMAGE_TYPE, this, livingOwner);
			living.damage(source, piercing ? LOCK_DAMAGE : DAMAGE);
		}
		// 无论伤害是否生效（如创造模式），都照样击退。
		// takeKnockback 的 (x,z) 是"从目标指向攻击者"的方向，所以这里取速度的反方向。
		Vec3d velocity = this.getVelocity();
		double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		if (horizontal > 1.0E-4) {
			living.takeKnockback(KNOCKBACK, -velocity.x / horizontal, -velocity.z / horizontal);
		}
	}
}
