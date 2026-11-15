package com.example.hush.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.entity.projectile.LlamaSpitEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 姚永寿吐出的口水：伤害、穿甲值与击退档由发射时的阶段决定，
 * 伤害类型自定义且 scaling=never，因此和平难度下依然生效。
 */
public class YaoYongshouSpitEntity extends LlamaSpitEntity {
	/** 自定义伤害类型：data/hush/damage_type/yao_spit.json */
	public static final RegistryKey<DamageType> DAMAGE_TYPE =
			RegistryKey.of(RegistryKeys.DAMAGE_TYPE, com.example.hush.Hush.id("yao_spit"));

	/** 命中时的总伤害（含穿甲部分）。 */
	private float damage = 3.0F;
	/** 其中不参与护甲减伤的伤害。 */
	private float piercing;
	/** 命中击退强度；0 表示不击退。 */
	private double knockback;

	public YaoYongshouSpitEntity(EntityType<? extends LlamaSpitEntity> entityType, World world) {
		super(entityType, world);
	}

	/** 由发射方按当前阶段写入本次口水的伤害参数。 */
	public void setAttack(float damage, float piercing, double knockback) {
		this.damage = damage;
		this.piercing = piercing;
		this.knockback = knockback;
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
			RegistryEntry<DamageType> type = this.getWorld().getRegistryManager()
					.get(RegistryKeys.DAMAGE_TYPE).entryOf(DAMAGE_TYPE);
			DamageSource source = new YaoArmorPiercingDamageSource(type, this, livingOwner, this.piercing);
			living.damage(source, this.damage);
		}
		// 无论伤害是否生效（如创造模式），都照常击退。
		// takeKnockback 的 (x,z) 是「从目标指向攻击者」的方向，所以这里取速度的反方向。
		Vec3d velocity = this.getVelocity();
		double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		if (this.knockback > 0.0D && horizontal > 1.0E-4D) {
			living.takeKnockback(this.knockback, -velocity.x / horizontal, -velocity.z / horizontal);
		}
	}
}
