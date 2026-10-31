package com.example.firstmob.client;

import com.example.firstmob.entity.YaoYongshouEntity;

import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.util.Identifier;

/**
 * 只借用村民的实体模型与贴图，其余行为由 YaoYongshouEntity（羊驼）提供。
 */
public class YaoYongshouRenderer extends MobEntityRenderer<YaoYongshouEntity, VillagerResemblingModel<YaoYongshouEntity>> {
	private static final Identifier TEXTURE = Identifier.of("minecraft", "textures/entity/villager/villager.png");

	public YaoYongshouRenderer(EntityRendererFactory.Context context) {
		super(context, new VillagerResemblingModel<>(context.getPart(EntityModelLayers.VILLAGER)), 0.5F);
	}

	@Override
	public Identifier getTexture(YaoYongshouEntity entity) {
		return TEXTURE;
	}
}
