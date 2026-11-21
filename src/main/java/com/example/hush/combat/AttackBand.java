package com.example.hush.combat;

/**
 * 按阶段与距离决定 Boss 这一 tick 用哪种攻击。
 * <p>顺序即优先级：近战 → 口水 → 阶段 4 随机档 → 声波 → 只追不打。
 * 纯查表，不依赖 Minecraft，由 {@code AttackBandTest} 覆盖。
 */
public enum AttackBand {
	/** 5 格内只近战。 */
	MELEE,
	/** 口水距离：阶段 1 为 6~24，其余 6~16。 */
	SPIT,
	/** 阶段 4 的 17~24 格：口水/声波 3:1。 */
	RANDOM,
	/** 声波段：17~32 / 17~48 / 25~64，边追边打。 */
	SONIC,
	/** 声波区间之外（阶段 4 >64 格）：只追不打。 */
	CHASE_ONLY;

	/** 距离（平方）落在哪一段；边界归更近的一档。 */
	public static AttackBand forDistance(int phase, double distanceSquared) {
		if (distanceSquared <= square(CombatProfile.MELEE_RANGE)) {
			return MELEE;
		}
		if (distanceSquared <= square(CombatProfile.spitRange(phase))) {
			return SPIT;
		}
		if (CombatProfile.inRandomBand(phase, distanceSquared)) {
			return RANDOM;
		}
		if (distanceSquared >= square(CombatProfile.sonicMinRange(phase))
				&& distanceSquared <= square(CombatProfile.sonicMaxRange(phase))) {
			return SONIC;
		}
		return CHASE_ONLY;
	}

	private static double square(double value) {
		return value * value;
	}
}
