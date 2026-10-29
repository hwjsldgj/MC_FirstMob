package com.example.firstmob.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.world.World;

/**
 * 姚永寿：完全套用村民（VillagerEntity）的模型、AI、音效与交互。
 */
public class YaoYongshouEntity extends VillagerEntity {
	public YaoYongshouEntity(EntityType<? extends VillagerEntity> entityType, World world) {
		super(entityType, world);
	}
}
