package com.example.hush.client;

import com.example.hush.registry.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class HushClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 僵尸模型 + 羊驼行为
		EntityRendererRegistry.register(ModEntities.YAO_YONGSHOU, YaoYongshouRenderer::new);
	}
}
