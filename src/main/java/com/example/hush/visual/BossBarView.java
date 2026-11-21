package com.example.hush.visual;

import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Boss 血条：只发给正在追踪实体的玩家；只有 Boss 形态才显示，阶段 4 换成紫色「（狂暴）」。
 * <p>刷新按固定间隔节流，值没变就不发包。只写血条状态，不读实体其余状态。
 */
public final class BossBarView {
	/** 每 tick 都刷新会不停发包，10 tick（0.5 秒）足够跟手。 */
	private static final int UPDATE_INTERVAL_TICKS = 10;

	private static final String BERSERK_SUFFIX = "（狂暴）";

	private final ServerBossBar bar;

	/** 缓存上一次的可见状态：与原版 {@code isVisible()} 对比后再发包。 */
	private boolean visible;

	public BossBarView(Text title) {
		this.bar = new ServerBossBar(title, BossBar.Color.RED, BossBar.Style.PROGRESS);
		this.visible = this.bar.isVisible();
	}

	/** 玩家开始追踪本实体时把血条加给他。 */
	public void track(ServerPlayerEntity player) {
		this.bar.addPlayer(player);
	}

	/** 玩家离开范围或维度时摘掉。 */
	public void untrack(ServerPlayerEntity player) {
		this.bar.removePlayer(player);
	}

	/** 只隐藏，不动玩家列表（退回普通形态时用）。 */
	public void hide() {
		this.visible = false;
		this.bar.setVisible(false);
	}

	/** 隐藏并清空玩家列表（死亡时用，别在屏幕上留一条空血条）。 */
	public void clear() {
		this.visible = false;
		this.bar.setVisible(false);
		this.bar.clearPlayers();
	}

	/**
	 * 按间隔刷新血条。
	 *
	 * @param shouldShow 当前是否该显示（Boss 形态且存活、未移除）
	 * @param age        实体 age，用来节流
	 * @param percent    当前血量比例
	 * @param berserk    阶段 4：紫色 + 名称后缀
	 * @param title      实体显示名
	 */
	public void update(boolean shouldShow, long age, float percent, boolean berserk, Text title) {
		if (this.visible != shouldShow) {
			this.visible = shouldShow;
			this.bar.setVisible(shouldShow);
		}
		if (!shouldShow || age % UPDATE_INTERVAL_TICKS != 0) {
			return;
		}
		if (percent != this.bar.getPercent()) {
			this.bar.setPercent(percent);
		}
		Text name = berserk ? Text.literal(title.getString() + BERSERK_SUFFIX) : title;
		if (!this.bar.getName().equals(name)) {
			this.bar.setName(name);
		}
		BossBar.Color color = berserk ? BossBar.Color.PURPLE : BossBar.Color.RED;
		if (this.bar.getColor() != color) {
			this.bar.setColor(color);
		}
	}
}
