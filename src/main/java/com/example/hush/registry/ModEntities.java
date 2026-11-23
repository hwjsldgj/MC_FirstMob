package com.example.hush.registry;

import com.example.hush.Hush;
import com.example.hush.combat.CombatProfile;
import com.example.hush.entity.YaoYongshouEntity;
import com.example.hush.entity.YaoYongshouSpitEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
	/** 近战击退靠命中时向上 0.4 的顶飞，`attack_knockback` 保持 0。 */
	private static final double ATTACK_KNOCKBACK = 0.0D;

	public static final EntityType<YaoYongshouEntity> YAO_YONGSHOU = Registry.register(
			Registries.ENTITY_TYPE,
			Hush.id("yao_yongshou"),
			EntityType.Builder.create(YaoYongshouEntity::new, SpawnGroup.MISC)
					.dimensions(0.6F, 1.95F)
					.eyeHeight(1.62F)
					.maxTrackingRange(10)
					.build("yao_yongshou")
	);

	/**
	 * 口水：自有实体类型，物理与渲染沿用原版羊驼口水（尺寸、追踪参数照抄原版）。
	 * <p>不再复用 {@code minecraft:llama_spit}，避免与真正的羊驼口水混淆。
	 */
	public static final EntityType<YaoYongshouSpitEntity> YAO_SPIT = Registry.register(
			Registries.ENTITY_TYPE,
			Hush.id("yao_spit"),
			EntityType.Builder.<YaoYongshouSpitEntity>create(YaoYongshouSpitEntity::new, SpawnGroup.MISC)
					.dimensions(0.25F, 0.25F)
					.maxTrackingRange(4)
					.trackingTickInterval(10)
					.build("yao_spit")
	);

	public static void register() {
		// 基础属性照抄原版羊驼（移动 0.175、跟随 40、台阶 1、落地 6、摔伤 ×0.5），
		// 再叠加血量/普通形态体型护甲 + 近战攻击力/击退（MobEntity 默认没有 attack_damage，调用近战会崩）。
		// 数值统一走 CombatProfile：生成时就是满血，Boss 形态只改体型/护甲，不改最大生命。
		FabricDefaultAttributeRegistry.register(YAO_YONGSHOU, MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, CombatProfile.MAX_HEALTH)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.175D)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 40.0D)
				.add(EntityAttributes.GENERIC_STEP_HEIGHT, 1.0D)
				.add(EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 6.0D)
				.add(EntityAttributes.GENERIC_FALL_DAMAGE_MULTIPLIER, 0.5D)
				.add(EntityAttributes.GENERIC_SCALE, CombatProfile.NORMAL_SCALE)
				.add(EntityAttributes.GENERIC_ARMOR, CombatProfile.armor(1))
				.add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, CombatProfile.ARMOR_TOUGHNESS)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, CombatProfile.meleeDamage(1))
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, ATTACK_KNOCKBACK));
	}

	private ModEntities() {
	}
}
