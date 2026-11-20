package com.example.hush.client;

import com.example.hush.registry.ModEntities;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.LlamaSpitEntityRenderer;

public class HushClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 僵尸模型 + 羊驼行为
		EntityRendererRegistry.register(ModEntities.YAO_YONGSHOU, YaoYongshouRenderer::new);
		// 口水用自有实体类型，渲染仍沿用原版羊驼口水（贴图与粒子不变）
		EntityRendererRegistry.register(ModEntities.YAO_SPIT, LlamaSpitEntityRenderer::new);
	}
}
