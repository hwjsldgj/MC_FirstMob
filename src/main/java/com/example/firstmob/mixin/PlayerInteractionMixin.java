package com.example.firstmob.mixin;

import com.example.firstmob.entity.YaoYongshouEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 挖方块、与方块交互（放方块等）本身不走玩家音效通道，
 * 这里补一刀，让这些动作也算"玩家弄出的动静"。
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class PlayerInteractionMixin {
	@Shadow
	@Final
	protected ServerPlayerEntity player;

	@Inject(method = "tryBreakBlock", at = @At("HEAD"))
	private void firstmob$onBreakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		YaoYongshouEntity.onPlayerAction(this.player);
	}

	@Inject(method = "interactBlock", at = @At("HEAD"))
	private void firstmob$onInteractBlock(ServerPlayerEntity player, World world, ItemStack stack, Hand hand,
			BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
		YaoYongshouEntity.onPlayerAction(this.player);
	}
}
