package com.example.firstmob.registry;

import com.example.firstmob.Firstmob;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * 姚永寿自己的音效事件：声音文件复用原版（羊驼口水、监守者声波），
 * 但字幕换成"姚永寿：xxx"，不再显示"羊驼""监守者"。
 */
public final class ModSounds {
	public static final SoundEvent YAO_AMBIENT = register("entity.yao_yongshou.ambient");
	public static final SoundEvent YAO_HURT = register("entity.yao_yongshou.hurt");
	public static final SoundEvent YAO_DEATH = register("entity.yao_yongshou.death");
	public static final SoundEvent YAO_SPIT = register("entity.yao_yongshou.spit");
	public static final SoundEvent SONIC_CHARGE = register("entity.yao_yongshou.sonic_charge");
	public static final SoundEvent SONIC_BOOM = register("entity.yao_yongshou.sonic_boom");

	private static SoundEvent register(String path) {
		Identifier id = Firstmob.id(path);
		return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
	}

	/** 由入口调用，确保类加载时完成注册。 */
	public static void register() {
		// 静态字段已完成注册
	}

	private ModSounds() {
	}
}
