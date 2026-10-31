package com.example.firstmob.client;

import com.example.firstmob.entity.YaoYongshouEntity;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.CrossbowPosing;

/**
 * 僵尸外形：沿用 64×64 的人形骨架（{@code EntityModelLayers.ZOMBIE}），
 * 手臂姿势直接复用原版 {@link CrossbowPosing#meleeAttack}，
 * 于是待机是僵尸的双臂前伸，近战挥击也是同一套抬手/下压动画。
 * <p>
 * 原版 {@code ZombieEntityModel} / {@code IronGolemEntityModel} 的泛型都绑死在
 * {@code ZombieEntity} / {@code IronGolemEntity} 上，无法直接套到本实体，所以这里只借骨架与动画函数。
 */
public class YaoYongshouModel extends BipedEntityModel<YaoYongshouEntity> {
	public YaoYongshouModel(ModelPart root) {
		super(root);
	}

	@Override
	public void setAngles(YaoYongshouEntity entity, float limbAngle, float limbDistance, float animationProgress,
			float headYaw, float headPitch) {
		super.setAngles(entity, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
		// 第三个参数 false = 僵尸待机姿势（双臂约 -80° 前伸）；挥击幅度由 handSwingProgress 驱动
		CrossbowPosing.meleeAttack(this.leftArm, this.rightArm, false, this.handSwingProgress, animationProgress);
	}
}
