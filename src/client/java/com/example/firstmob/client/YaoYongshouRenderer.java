package com.example.firstmob.client;

import com.example.firstmob.entity.YaoYongshouEntity;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.util.Identifier;

/**
 * 僵尸外形：人形骨架 + 原版僵尸贴图，手臂姿势由 {@link YaoYongshouModel} 复用僵尸动画。
 */
public class YaoYongshouRenderer extends MobEntityRenderer<YaoYongshouEntity, YaoYongshouModel> {
	private static final Identifier TEXTURE = Identifier.of("minecraft", "textures/entity/zombie/zombie.png");

	public YaoYongshouRenderer(EntityRendererFactory.Context context) {
		super(context, new YaoYongshouModel(context.getPart(EntityModelLayers.ZOMBIE)), 0.5F);
	}

	@Override
	public Identifier getTexture(YaoYongshouEntity entity) {
		return TEXTURE;
	}
}
