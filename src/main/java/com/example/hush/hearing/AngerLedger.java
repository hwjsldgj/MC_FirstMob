package com.example.hush.hearing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 每个玩家的愤怒值与听觉记忆：纯 Java，不引用任何 Minecraft 类型，可直接在 JUnit 里验证。
 * <p>
 * 实体只负责「解析玩家、算距离、决定行为」，所有计数、限频、配对窗口、衰减都交到这里：
 * <ul>
 * <li>愤怒值 0~100，超过上限截断；</li>
 * <li>音效补充与 {@code GameEvent} 的配对窗口：同一动作只算一次；</li>
 * <li>脚步 / 落地 / 受击三类小噪音分别限频；</li>
 * <li>固定间隔全体衰减，顺带清理过期的记忆条目。</li>
 * </ul>
 * 行为参数（每次加多少、间隔多久）仍由实体按 SPEC 传入，这里不写死玩法数值。
 */
public final class AngerLedger {
	/** 愤怒上限，与 SPEC §4.2 一致。 */
	public static final int ANGER_MAX = 100;

	/** 声音分类：脚步、落地、受击是小噪音，其余按大噪音计。 */
	public enum Noise {
		WALK,
		JUMP,
		HURT,
		LOUD
	}

	private final Map<UUID, Integer> anger = new HashMap<>();
	/** 最近一次振动通道收到该玩家 GameEvent 的 tick。 */
	private final Map<UUID, Long> lastGameEvent = new HashMap<>();
	/** 最近一次按音效补充计入愤怒的 tick。 */
	private final Map<UUID, Long> lastSupplement = new HashMap<>();
	/** 三类小噪音各自的上一次计数 tick。 */
	private final Map<UUID, Long> lastWalk = new HashMap<>();
	private final Map<UUID, Long> lastJump = new HashMap<>();
	private final Map<UUID, Long> lastHurt = new HashMap<>();
	private int decayTimer;

	/** 当前愤怒值，没有记录时为 0。 */
	public int anger(UUID player) {
		return this.anger.getOrDefault(player, 0);
	}

	/** 加愤怒，返回加完后的值（0~{@link #ANGER_MAX}）。 */
	public int addAnger(UUID player, int amount) {
		int next = Math.clamp(this.anger(player) + amount, 0, ANGER_MAX);
		this.anger.put(player, next);
		return next;
	}

	/** 直接设定愤怒值，越界截断；声波换目标时用。 */
	public void setAnger(UUID player, int value) {
		this.anger.put(player, Math.clamp(value, 0, ANGER_MAX));
	}

	/** 清空愤怒与配对记忆；退回普通形态时调用，避免残留。 */
	public void clearHearing() {
		this.anger.clear();
		this.lastGameEvent.clear();
		this.lastSupplement.clear();
	}

	/** 记录一次振动通道收到的 GameEvent。 */
	public void markEventHeard(UUID player, long now) {
		this.lastGameEvent.put(player, now);
	}

	/** 该玩家最近是否刚被振动通道接收过（用于排除重复计数）。 */
	public boolean wasEventHeardRecently(UUID player, long now, int windowTicks) {
		Long last = this.lastGameEvent.get(player);
		return last != null && now - last.longValue() <= windowTicks;
	}

	/** 记录一次音效补充。 */
	public void markSupplement(UUID player, long now) {
		this.lastSupplement.put(player, now);
	}

	/**
	 * 音效先到、GameEvent 后到：撤回刚才那次补充的愤怒。
	 *
	 * @return true 表示确实撤回了一次（在窗口内）
	 */
	public boolean rollbackSupplement(UUID player, long now, int windowTicks, int amount) {
		Long last = this.lastSupplement.remove(player);
		if (last == null || now - last.longValue() > windowTicks) {
			return false;
		}
		this.anger.computeIfPresent(player, (uuid, value) -> Math.max(0, value - amount));
		return true;
	}

	/**
	 * 小噪音限频：冷却内返回 false，否则记录本次并返回 true。
	 * 大噪音没有限频，传 {@link Noise#LOUD} 会抛异常，调用方需自己跳过。
	 */
	public boolean allowMinorNoise(Noise noise, UUID player, long now, int cooldownTicks) {
		Map<UUID, Long> ticks = switch (noise) {
			case WALK -> this.lastWalk;
			case JUMP -> this.lastJump;
			case HURT -> this.lastHurt;
			case LOUD -> throw new IllegalArgumentException("大噪音不做限频");
		};
		Long last = ticks.get(player);
		if (last != null && now - last.longValue() < cooldownTicks) {
			return false;
		}
		ticks.put(player, now);
		return true;
	}

	/**
	 * 按固定间隔衰减：每 {@code intervalTicks} 全体 -1，1 分的条目直接移除。
	 *
	 * @return 本 tick 是否触发了衰减
	 */
	public boolean decayIfDue(long now, int intervalTicks, int memoryTicks) {
		if (++this.decayTimer < intervalTicks) {
			return false;
		}
		this.decayTimer = 0;
		this.anger.entrySet().removeIf(entry -> entry.getValue() <= 1);
		this.anger.replaceAll((uuid, value) -> value - 1);
		this.prune(now, memoryTicks);
		return true;
	}

	/** 清掉超过记忆窗口的时间戳，避免长跑服务器上按玩家无限增长。 */
	public void prune(long now, int memoryTicks) {
		pruneMap(this.lastGameEvent, now, memoryTicks);
		pruneMap(this.lastSupplement, now, memoryTicks);
		pruneMap(this.lastWalk, now, memoryTicks);
		pruneMap(this.lastJump, now, memoryTicks);
		pruneMap(this.lastHurt, now, memoryTicks);
	}

	/** 移除已不在世界里的玩家条目；离线玩家的愤怒不应无限保留。 */
	public void retainPlayers(Predicate<UUID> present) {
		this.anger.keySet().removeIf(uuid -> !present.test(uuid));
	}

	/** 愤怒大于 0 的玩家快照，供实体解析「主嫌」。 */
	public List<UUID> angeredPlayers() {
		List<UUID> players = new ArrayList<>();
		for (Map.Entry<UUID, Integer> entry : this.anger.entrySet()) {
			if (entry.getValue() > 0) {
				players.add(entry.getKey());
			}
		}
		return players;
	}

	private static void pruneMap(Map<UUID, Long> ticks, long now, int memoryTicks) {
		ticks.entrySet().removeIf(entry -> now - entry.getValue().longValue() > memoryTicks);
	}
}
