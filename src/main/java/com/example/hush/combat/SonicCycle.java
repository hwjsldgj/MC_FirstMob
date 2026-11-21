package com.example.hush.combat;

/**
 * 声波节拍：蓄力 {@link #CHARGE_TICKS} tick 后开一炮，到 {@link #INTERVAL_TICKS} tick 归零重来。
 * <p>
 * Boss 阶段与阶段 1 的声波共用同一套节拍，只是状态各自独立（实体里两个实例）。
 * 不依赖 Minecraft 类型，由 {@code SonicCycleTest} 覆盖。
 */
public final class SonicCycle {
	/** 从 0 开始数，第 34 tick 开炮。 */
	public static final int CHARGE_TICKS = 34;

	/** 一个完整循环的长度。 */
	public static final int INTERVAL_TICKS = 160;

	private int ticks;

	/** 当前循环内已走过的 tick 数（0 表示可以开始蓄力）。 */
	public int ticks() {
		return this.ticks;
	}

	/** 尚未开始蓄力。调用方据此决定是否播蓄力音效。 */
	public boolean isIdle() {
		return this.ticks == 0;
	}

	/** 正在蓄力、这一发还没打出去（不含刚开炮的那一 tick）。 */
	public boolean isCharging() {
		return this.ticks > 0 && this.ticks < CHARGE_TICKS;
	}

	/** 推进一 tick；返回本 tick 是否该开炮。 */
	public boolean tick() {
		this.ticks++;
		boolean fire = this.ticks == CHARGE_TICKS;
		if (this.ticks >= INTERVAL_TICKS) {
			this.ticks = 0;
		}
		return fire;
	}

	/** 中断当前循环（换目标、切近战/口水、退出声波模式）。 */
	public void reset() {
		this.ticks = 0;
	}
}
