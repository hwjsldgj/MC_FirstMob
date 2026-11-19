package com.example.hush.combat;

/**
 * 四阶段战斗的纯数值档案：血量阈值、护甲、伤害/穿甲/击退、距离分段。
 * <p>
 * 这里不引用任何 Minecraft 类，实体只做「取当前阶段 → 查表」，
 * 因此可以在 JUnit 里直接验证表值与阶段边界，不需要启动游戏。
 * <p>
 * 表值必须与 docs/SPEC.md 的 §3.1 阶段表、§6 数值总表逐项一致；
 * 改这里要同时改 SPEC、README 的距离速查与 {@code CombatProfileTest}。
 */
public final class CombatProfile {
	/** 阶段总数：1~4。 */
	public static final int PHASE_COUNT = 4;

	/** 最大生命：80% / 60% / 30% 三个阈值都按它计算。 */
	public static final double MAX_HEALTH = 100.0D;
	/** 普通形态体型。 */
	public static final double NORMAL_SCALE = 1.0D;
	/** Boss 形态体型：碰撞箱、眼高与模型一起放大。 */
	public static final double BOSS_SCALE = 2.0D;

	/** 阶段 2 的下限，同时也是进入 Boss 形态的血量阈值（<80%）。 */
	public static final float PHASE_2_HEALTH_RATIO = 0.8F;
	/** 阶段 3 的下限：60% 起完全锁定。 */
	public static final float PHASE_3_HEALTH_RATIO = 0.6F;
	/** 阶段 4 的上限：≤30% 狂暴。 */
	public static final float PHASE_4_HEALTH_RATIO = 0.3F;

	/** 所有阶段的护甲韧性都是 0。 */
	public static final double ARMOR_TOUGHNESS = 0.0D;

	/** 阶段 2 超过 32 格脱锁；阶段 3/4 永不脱锁。 */
	private static final double PHASE_2_GIVE_UP_RANGE = 32.0D;
	/** 阶段 4 超过 64 格只追不打。 */
	public static final double CHASE_ONLY_RANGE = 64.0D;
	/** 阶段 4 的随机档：17~24 格。 */
	public static final double RANDOM_BAND_MIN_RANGE = 17.0D;
	public static final double RANDOM_BAND_MAX_RANGE = 24.0D;
	/** 随机档里 75% 吐口水 8 点、25% 放声波 12 点，都带击退。 */
	public static final float RANDOM_SPIT_CHANCE = 0.75F;
	public static final float RANDOM_SPIT_DAMAGE = 8.0F;
	public static final float RANDOM_SONIC_DAMAGE = 12.0F;

	/** 下标 = 阶段 - 1，顺序固定为 1/2/3/4。 */
	private static final double[] ARMOR = { 5.0D, 10.0D, 15.0D, 20.0D };
	private static final double[] MELEE_DAMAGE = { 5.0D, 8.0D, 8.0D, 10.0D };
	private static final double[] MELEE_PIERCING = { 0.0D, 0.0D, 4.0D, 5.0D };
	private static final double[] SPIT_DAMAGE = { 3.0D, 4.0D, 5.0D, 6.0D };
	private static final double[] SPIT_PIERCING = { 0.0D, 0.0D, 3.0D, 4.0D };
	/** 阶段 1 无击退，阶段 2 击退 I，阶段 3/4 击退 II。 */
	private static final double[] SPIT_KNOCKBACK = { 0.0D, 0.9D, 1.4D, 1.4D };
	private static final double[] SONIC_DAMAGE = { 6.0D, 8.0D, 10.0D, 14.0D };
	/** 口水最大距离：阶段 1 是 24，其余 16。 */
	private static final double[] SPIT_RANGE = { 24.0D, 16.0D, 16.0D, 16.0D };
	/** 声波内边界：阶段 4 从 25 格起，其余 17 格。 */
	private static final double[] SONIC_MIN_RANGE = { 17.0D, 17.0D, 17.0D, 25.0D };
	/** 声波外边界（含边追边打）：24 / 32 / 48 / 64。 */
	private static final double[] SONIC_MAX_RANGE = { 24.0D, 32.0D, 48.0D, 64.0D };
	/** 追到多远停下：8 / 16 / 16 / 32。 */
	private static final double[] CHASE_STOP_RANGE = { 8.0D, 16.0D, 16.0D, 32.0D };

	private CombatProfile() {
	}

	/**
	 * 按当前血量比例取阶段：Boss 形态下限是 2，普通形态恒为 1。
	 *
	 * @param healthRatio 当前血量 / 最大血量
	 * @param bossForm    是否处于 Boss 形态
	 */
	public static int phaseFor(float healthRatio, boolean bossForm) {
		if (!bossForm) {
			return 1;
		}
		if (healthRatio >= PHASE_3_HEALTH_RATIO) {
			return 2;
		}
		if (healthRatio > PHASE_4_HEALTH_RATIO) {
			return 3;
		}
		return 4;
	}

	/** 护甲：5 / 10 / 15 / 20。 */
	public static double armor(int phase) {
		return ARMOR[index(phase)];
	}

	/** 近战总伤害：5 / 8 / 8 / 10。 */
	public static double meleeDamage(int phase) {
		return MELEE_DAMAGE[index(phase)];
	}

	/** 近战穿甲：0 / 0 / 4 / 5。 */
	public static double meleePiercing(int phase) {
		return MELEE_PIERCING[index(phase)];
	}

	/** 口水伤害：3 / 4 / 5 / 6。 */
	public static double spitDamage(int phase) {
		return SPIT_DAMAGE[index(phase)];
	}

	/** 口水穿甲：0 / 0 / 3 / 4（17~24 随机档另计，无穿甲）。 */
	public static double spitPiercing(int phase) {
		return SPIT_PIERCING[index(phase)];
	}

	/** 口水击退：0 / 0.9 / 1.4 / 1.4。 */
	public static double spitKnockback(int phase) {
		return SPIT_KNOCKBACK[index(phase)];
	}

	/** 声波伤害：6 / 8 / 10 / 14。 */
	public static double sonicDamage(int phase) {
		return SONIC_DAMAGE[index(phase)];
	}

	/** 口水最大距离：阶段 1 为 24 格，其余 16 格。 */
	public static double spitRange(int phase) {
		return SPIT_RANGE[index(phase)];
	}

	/** 声波内边界：阶段 4 从 25 格起，其余 17 格。 */
	public static double sonicMinRange(int phase) {
		return SONIC_MIN_RANGE[index(phase)];
	}

	/** 声波外边界：阶段 4 到 64 格，阶段 3 到 48 格。 */
	public static double sonicMaxRange(int phase) {
		return SONIC_MAX_RANGE[index(phase)];
	}

	/** 追击停止距离：8 / 16 / 16 / 32。 */
	public static double chaseStopRange(int phase) {
		return CHASE_STOP_RANGE[index(phase)];
	}

	/** 脱锁距离：阶段 2 为 32 格，阶段 3/4 永不脱锁（返回 {@link Double#MAX_VALUE}）。 */
	public static double giveUpRange(int phase) {
		return phase >= 3 ? Double.MAX_VALUE : PHASE_2_GIVE_UP_RANGE;
	}

	/** 阶段 4 的 17~24 格随机档：只有阶段 4 且落在区间内才成立。 */
	public static boolean inRandomBand(int phase, double distanceSquared) {
		return phase == PHASE_COUNT
				&& distanceSquared >= RANDOM_BAND_MIN_RANGE * RANDOM_BAND_MIN_RANGE
				&& distanceSquared <= RANDOM_BAND_MAX_RANGE * RANDOM_BAND_MAX_RANGE;
	}

	/** 把阶段编号夹到 1~4，越界时退回边界档位，避免数组越界。 */
	private static int index(int phase) {
		return Math.clamp(phase - 1, 0, PHASE_COUNT - 1);
	}
}
