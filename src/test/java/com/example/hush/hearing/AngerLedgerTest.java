package com.example.hush.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 把 SPEC §4.2（分类与愤怒）与 §4 的配对/限频规则钉在测试里。
 * <p>
 * 这些用例只依赖 {@link AngerLedger}，不需要启动游戏；实体只负责把 tick 数与距离传进来。
 */
class AngerLedgerTest {
	private static final int DECAY_INTERVAL = 20;
	private static final int MEMORY_TICKS = 100;
	private static final int PAIR_WINDOW = 2;

	private final AngerLedger ledger = new AngerLedger();
	private final UUID alice = UUID.randomUUID();
	private final UUID bob = UUID.randomUUID();

	@Test
	@DisplayName("初始愤怒为 0，累加与上限")
	void angerAccumulatesAndClamps() {
		assertEquals(0, this.ledger.anger(this.alice));
		assertEquals(12, this.ledger.addAnger(this.alice, 12));
		assertEquals(24, this.ledger.addAnger(this.alice, 12));
		assertEquals(AngerLedger.ANGER_MAX, this.ledger.addAnger(this.alice, 500), "超过 100 截断");
		assertEquals(100, this.ledger.anger(this.alice));
	}

	@Test
	@DisplayName("setAnger 截断到 0~100")
	void setAngerClamps() {
		this.ledger.setAnger(this.alice, 150);
		assertEquals(100, this.ledger.anger(this.alice));
		this.ledger.setAnger(this.alice, -5);
		assertEquals(0, this.ledger.anger(this.alice));
	}

	@Test
	@DisplayName("未到间隔不衰减，到点全体 -1")
	void decayOnlyAtInterval() {
		this.ledger.addAnger(this.alice, 5);
		for (int i = 0; i < DECAY_INTERVAL - 1; i++) {
			assertFalse(this.ledger.decayIfDue(i, DECAY_INTERVAL, MEMORY_TICKS), "第 " + (i + 1) + " tick 不该衰减");
		}
		assertEquals(5, this.ledger.anger(this.alice));
		assertTrue(this.ledger.decayIfDue(DECAY_INTERVAL, DECAY_INTERVAL, MEMORY_TICKS));
		assertEquals(4, this.ledger.anger(this.alice));
	}

	@Test
	@DisplayName("衰减时 1 分直接归零移除，2 分留 1")
	void decayRemovesLastPoint() {
		this.ledger.addAnger(this.alice, 1);
		this.ledger.addAnger(this.bob, 2);
		for (int tick = 0; tick < DECAY_INTERVAL; tick++) {
			this.ledger.decayIfDue(tick, DECAY_INTERVAL, MEMORY_TICKS);
		}
		assertEquals(0, this.ledger.anger(this.alice));
		assertEquals(1, this.ledger.anger(this.bob));
		assertEquals(List.of(this.bob), this.ledger.angeredPlayers(), "0 分玩家不出现在主嫌候选里");
	}

	@Test
	@DisplayName("音效补充与 GameEvent 的配对窗口是 2 tick")
	void eventPairWindow() {
		this.ledger.markEventHeard(this.alice, 100L);
		assertTrue(this.ledger.wasEventHeardRecently(this.alice, 100L, PAIR_WINDOW));
		assertTrue(this.ledger.wasEventHeardRecently(this.alice, 102L, PAIR_WINDOW));
		assertFalse(this.ledger.wasEventHeardRecently(this.alice, 103L, PAIR_WINDOW));
		assertFalse(this.ledger.wasEventHeardRecently(this.bob, 100L, PAIR_WINDOW), "按玩家隔离");
	}

	@Test
	@DisplayName("GameEvent 晚到：窗口内撤回补充，窗口外不撤回")
	void rollbackSupplementWithinWindow() {
		this.ledger.markSupplement(this.alice, 100L);
		this.ledger.addAnger(this.alice, 12);
		assertTrue(this.ledger.rollbackSupplement(this.alice, 102L, PAIR_WINDOW, 12));
		assertEquals(0, this.ledger.anger(this.alice));

		this.ledger.markSupplement(this.alice, 200L);
		this.ledger.addAnger(this.alice, 12);
		assertFalse(this.ledger.rollbackSupplement(this.alice, 205L, PAIR_WINDOW, 12), "超过窗口不再撤回");
		assertEquals(12, this.ledger.anger(this.alice));
	}

	@Test
	@DisplayName("撤回后不低于 0，且只撤回一次")
	void rollbackDoesNotGoNegative() {
		this.ledger.markSupplement(this.alice, 0L);
		this.ledger.addAnger(this.alice, 5);
		assertTrue(this.ledger.rollbackSupplement(this.alice, 1L, PAIR_WINDOW, 12));
		assertEquals(0, this.ledger.anger(this.alice));
		assertFalse(this.ledger.rollbackSupplement(this.alice, 2L, PAIR_WINDOW, 12), "同一次补充只撤回一次");
	}

	@Test
	@DisplayName("小噪音按类型、按玩家分别限频")
	void minorNoiseCooldownPerKindAndPlayer() {
		assertTrue(this.ledger.allowMinorNoise(AngerLedger.Noise.WALK, this.alice, 0L, 100));
		assertFalse(this.ledger.allowMinorNoise(AngerLedger.Noise.WALK, this.alice, 99L, 100), "冷却内");
		assertTrue(this.ledger.allowMinorNoise(AngerLedger.Noise.WALK, this.alice, 100L, 100), "到点放行");
		assertTrue(this.ledger.allowMinorNoise(AngerLedger.Noise.JUMP, this.alice, 100L, 100), "不同类型互不影响");
		assertTrue(this.ledger.allowMinorNoise(AngerLedger.Noise.WALK, this.bob, 100L, 100), "不同玩家互不影响");
	}

	@Test
	@DisplayName("大噪音不做限频，调用即错误")
	void loudNoiseHasNoCooldown() {
		assertThrows(IllegalArgumentException.class,
				() -> this.ledger.allowMinorNoise(AngerLedger.Noise.LOUD, this.alice, 0L, 100));
	}

	@Test
	@DisplayName(" prune 清理过期时间戳，不误删新条目")
	void pruneDropsStaleMemory() {
		this.ledger.markEventHeard(this.alice, 0L);
		this.ledger.markEventHeard(this.bob, 99L);
		this.ledger.prune(150L, MEMORY_TICKS);
		assertFalse(this.ledger.wasEventHeardRecently(this.alice, 150L, Integer.MAX_VALUE));
		assertTrue(this.ledger.wasEventHeardRecently(this.bob, 150L, Integer.MAX_VALUE), "99 + 51 仍在窗口内");
	}

	@Test
	@DisplayName("离线玩家不再保留愤怒")
	void retainPlayersDropsOffline() {
		this.ledger.addAnger(this.alice, 30);
		this.ledger.addAnger(this.bob, 40);
		this.ledger.retainPlayers(uuid -> uuid.equals(this.alice));
		assertEquals(30, this.ledger.anger(this.alice));
		assertEquals(0, this.ledger.anger(this.bob));
		assertEquals(List.of(this.alice), this.ledger.angeredPlayers());
	}

	@Test
	@DisplayName("退回普通形态时愤怒与配对记忆一起清空")
	void clearHearingResetsEverything() {
		this.ledger.addAnger(this.alice, 100);
		this.ledger.markEventHeard(this.alice, 0L);
		this.ledger.markSupplement(this.alice, 0L);
		this.ledger.clearHearing();
		assertEquals(0, this.ledger.anger(this.alice));
		assertFalse(this.ledger.wasEventHeardRecently(this.alice, 0L, PAIR_WINDOW));
		assertFalse(this.ledger.rollbackSupplement(this.alice, 0L, PAIR_WINDOW, 12));
	}
}
