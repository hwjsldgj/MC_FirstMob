package com.example.firstmob.client;

import com.example.firstmob.registry.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.render.entity.VillagerEntityRenderer;

public class FirstmobClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 直接用村民渲染器，外观与村民一致
		EntityRendererRegistry.register(ModEntities.YAO_YONGSHOU, VillagerEntityRenderer::new);
	}
}
