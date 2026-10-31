package com.example.firstmob.client;

import com.example.firstmob.registry.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class FirstmobClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 村民模型 + 羊驼行为
		EntityRendererRegistry.register(ModEntities.YAO_YONGSHOU, YaoYongshouRenderer::new);
	}
}
