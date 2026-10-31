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
	/** Boss 血量：羊驼默认只有 15~30，作为 boss 提到 100 点，配合屏幕顶部的血条。 */
	private static final double BOSS_MAX_HEALTH = 100.0D;
	/** Boss 体型：generic.scale 会自动放大碰撞箱、眼高与模型渲染，不需要改 EntityType 尺寸。 */
	private static final double BOSS_SCALE = 2.0D;
	/** Boss 护甲：20 点（原版满值 30，20 点约减伤 80%），配合 100 血让它扛得住正面消耗。 */
	private static final double BOSS_ARMOR = 20.0D;

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
		// 羊驼属性 + Boss 血量/体型/护甲 + 近战所需的攻击力/击退抗性（羊驼默认没有 attack_damage，调用近战会崩）
		FabricDefaultAttributeRegistry.register(YAO_YONGSHOU, LlamaEntity.createLlamaAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, BOSS_MAX_HEALTH)
				.add(EntityAttributes.GENERIC_SCALE, BOSS_SCALE)
				.add(EntityAttributes.GENERIC_ARMOR, BOSS_ARMOR)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4.0)
				.add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.5));
	}
}
