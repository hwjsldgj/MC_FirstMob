package com.example.firstmob.registry;

import com.example.firstmob.Firstmob;
import com.example.firstmob.entity.YaoYongshouEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.LlamaEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModEntities {
	public static final EntityType<YaoYongshouEntity> YAO_YONGSHOU = Registry.register(
			Registries.ENTITY_TYPE,
			Identifier.of(Firstmob.MOD_ID, "yao_yongshou"),
			EntityType.Builder.create(YaoYongshouEntity::new, SpawnGroup.MISC)
					.dimensions(0.6F, 1.95F)
					.eyeHeight(1.62F)
					.maxTrackingRange(10)
					.build("yao_yongshou")
	);

	private ModEntities() {
	}

	public static void register() {
		// 羊驼属性 + 近战所需的攻击力/击退抗性（羊驼默认没有 attack_damage，调用近战会崩）
		FabricDefaultAttributeRegistry.register(YAO_YONGSHOU, LlamaEntity.createLlamaAttributes()
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.5));
	}
}
