package com.example.hush.entity;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.world.ServerWorld;

/**
 * 传送落点搜索：在给定水平坐标上下扫一段高度，找一块放得下 3×3×4 体型的空地。
 * <p>只做方块判定，不改变实体状态；选点策略（角度、半径、重试次数）仍留在实体里。
 */
public final class TeleportSearch {
	/** 落点要求 3×3 的地面。 */
	private static final int SPACE_RADIUS = 1;

	/** 落点要求 4 格净空，Boss 形态 2 倍体型也放得下。 */
	private static final int SPACE_HEIGHT = 4;

	private TeleportSearch() {
	}

	/** 在给定水平坐标上下扫 8 格，找 3×3×4 空地的落点；找不到返回 null。 */
	public static Vec3d findLandingSpot(ServerWorld world, double x, double baseY, double z) {
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int startY = MathHelper.floor(baseY) + 8;
		int endY = MathHelper.floor(baseY) - 8;
		for (int y = startY; y >= endY; y--) {
			pos.set(x, y, z);
			long chunkPos = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
			if (!world.isChunkLoaded(chunkPos)) {
				continue;
			}
			if (isSpaceClear(world, pos)) {
				return new Vec3d(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
			}
		}
		return null;
	}

	/** 3×3 脚下实心 + 3×3×4 全空气。 */
	public static boolean isSpaceClear(ServerWorld world, BlockPos origin) {
		for (int dx = -SPACE_RADIUS; dx <= SPACE_RADIUS; dx++) {
			for (int dz = -SPACE_RADIUS; dz <= SPACE_RADIUS; dz++) {
				BlockPos ground = origin.add(dx, -1, dz);
				if (!world.getBlockState(ground).isSolidBlock(world, ground)) {
					return false;
				}
				for (int dy = 0; dy < SPACE_HEIGHT; dy++) {
					if (!world.getBlockState(origin.add(dx, dy, dz)).isAir()) {
						return false;
					}
				}
			}
		}
		return true;
	}
}
