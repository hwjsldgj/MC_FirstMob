package com.example.hush.registry;

import com.example.hush.Hush;
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
	/** 生成时就是 100 血：Boss 形态只改体型/护甲，不改血量，血量比例阈值才好算。 */
	private static final double MAX_HEALTH = 100.0D;
	/** 普通形态体型 1 倍；掉到 80% 血以下时由实体自己写入 2 倍。 */
	private static final double NORMAL_SCALE = 1.0D;
	/** 普通（阶段 1）护甲 5 点；阶段 2/3/4 由实体每 tick 改写为 10/15/20。 */
	private static final double NORMAL_ARMOR = 5.0D;
	/** 所有阶段的护甲韧性都是 0。 */
	private static final double NORMAL_ARMOR_TOUGHNESS = 0.0D;
	/** 阶段 1 近战伤害 5 点；阶段 2~4 由实体按血量改写。 */
	private static final double ATTACK_DAMAGE = 5.0D;
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
		// 羊驼属性 + 血量/普通形态体型护甲 + 近战攻击力/击退（羊驼默认没有 attack_damage，调用近战会崩）
		FabricDefaultAttributeRegistry.register(YAO_YONGSHOU, LlamaEntity.createLlamaAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, MAX_HEALTH)
				.add(EntityAttributes.GENERIC_SCALE, NORMAL_SCALE)
				.add(EntityAttributes.GENERIC_ARMOR, NORMAL_ARMOR)
				.add(EntityAttributes.GENERIC_ARMOR_TOUGHNESS, NORMAL_ARMOR_TOUGHNESS)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, ATTACK_DAMAGE)
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, ATTACK_KNOCKBACK));
	}
}
