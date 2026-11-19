package com.example.hush.registry;

import com.example.hush.Hush;
import com.example.hush.combat.CombatProfile;
import com.example.hush.entity.YaoYongshouEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
	/** 近战击退靠命中时向上 0.4 的顶飞，`attack_knockback` 保持 0。 */
	private static final double ATTACK_KNOCKBACK = 0.0D;

	public static final EntityType<YaoYongshouEntity> YAO_YONGSHOU = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(Hush.MOD_ID, "yao_yongshou"),
			EntityType.Builder.create(YaoYongshouEntity::new, SpawnGroup.MISC)
					.dimensions(0.6F, 1.95F)
					.eyeHeight(1.62F)
					.maxTrackingRange(10)
					.build("yao_yongshou")
	);

	private ModEntities() {
	}

	public static void register() {
		// 羊驼属性 + 血量/普通形态体型护甲 + 近战攻击力/击退（羊驼默认没有 attack_damage，调用近战会崩）。
		// 数值统一走 CombatProfile：生成时就是满血，Boss 形态只改体型/护甲，不改最大生命。
		FabricDefaultAttributeRegistry.register(YAO_YONGSHOU, LlamaEntity.createLlamaAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, CombatProfile.MAX_HEALTH)
				.add(EntityAttributes.GENERIC_SCALE, CombatProfile.NORMAL_SCALE)
				.add(EntityAttributes.GENERIC_ARMOR, CombatProfile.armor(1))
				.add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, CombatProfile.ARMOR_TOUGHNESS)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, CombatProfile.meleeDamage(1))
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, ATTACK_KNOCKBACK));
	}
}
