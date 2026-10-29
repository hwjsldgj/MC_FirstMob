package com.example.firstmob.registry;

import com.example.firstmob.Firstmob;

import net.minecraft.item.Item;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
	/** 姚永寿刷怪蛋 */
	public static final Item YAO_YONGSHOU_SPAWN_EGG = Registry.register(
			Registries.ITEM,
			Identifier.of(Firstmob.MOD_ID, "yao_yongshou_spawn_egg"),
			new SpawnEggItem(ModEntities.YAO_YONGSHOU, 0x8A6D3B, 0x5C4033, new Item.Settings())
	);

	private ModItems() {
	}

	public static void register() {
		// 类加载即完成注册，这里只用于显式触发初始化
	}
}
