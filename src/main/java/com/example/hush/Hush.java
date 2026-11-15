package com.example.hush;

import com.example.hush.registry.ModEntities;
import com.example.hush.registry.ModItems;
import com.example.hush.registry.ModSounds;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

import net.minecraft.item.ItemGroups;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hush implements ModInitializer {
	public static final String MOD_ID = "hush";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModEntities.register();
		ModItems.register();
		ModSounds.register();

		// 把刷怪蛋放进创造模式「刷怪蛋」页签
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS)
				.register(entries -> entries.add(ModItems.YAO_YONGSHOU_SPAWN_EGG));

		LOGGER.info("[Hush] 姚永寿已注册");
	}

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}
}
