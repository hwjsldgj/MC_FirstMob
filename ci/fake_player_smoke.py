#!/usr/bin/env python3
"""假玩家冒烟：用 Carpet 的 /player 造一个真实的服务端玩家，驱动模组的战斗流程并断言。

运行前提：
  - 无头服务端已启动，RCON 可用（run/server.properties 里 enable-rcon=true）
  - run/mods 里有 fabric-carpet（先跑 ci/fetch_carpet.py）
  - 服务端 JVM 打开 -Dhush.debug=true（CI 里用 HUSH_DEBUG=1 让 Gradle 传递）

用法：
    python3 ci/fake_player_smoke.py --report build/smoke-report.txt

退出码 0 表示全部断言通过；失败时打印原因并以 1 退出，同时停服。
"""
import argparse
import re
import sys
import time

from rcon import Rcon

SURFACE_Y = -59.0
ENTITY = "@e[type=hush:yao_yongshou,limit=1,tag=hush_smoke]"
PLAYER = "Smoke"


class Smoke:
    def __init__(self, args):
        self.args = args
        self.rcon = None
        self.transcript = []
        self.results = []
        self.notes = []

    # ---------------------------------------------------------------- 基础

    def command(self, command: str) -> str:
        started = time.time()
        try:
            out = self.rcon.command(command)
        except OSError as exc:
            out = f"<rcon error: {exc}>"
        self.transcript.append((command, out))
        print(f"> {command}\n{out}".rstrip())
        if time.time() - started > 5:
            self.notes.append(f"slow command {time.time() - started:.1f}s: {command}")
        return out

    def check(self, name: str, ok: bool, detail: str) -> bool:
        self.results.append((name, ok, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}: {detail}")
        return ok

    def wait_for(self, name: str, predicate, timeout: float, interval: float = 1.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            value = predicate()
            if value:
                return self.check(name, True, str(value))
            time.sleep(interval)
        return self.check(name, False, f"timeout after {timeout:.0f}s")

    # ---------------------------------------------------------------- 取值

    def entity_number(self, field: str) -> float:
        out = self.command(f"data get entity {ENTITY} {field}")
        match = re.search(r"data:\s*(-?[\d.]+)", out)
        return float(match.group(1)) if match else float("nan")

    def entity_flag(self, field: str) -> bool:
        out = self.command(f"data get entity {ENTITY} {field}")
        return "1b" in out

    def entity_pos(self):
        out = self.command(f"data get entity {ENTITY} Pos")
        numbers = re.findall(r"-?\d+\.\d+", out)
        return tuple(float(n) for n in numbers[:3]) if len(numbers) >= 3 else None

    def attribute(self, name: str) -> float:
        out = self.command(f"attribute {ENTITY} minecraft:generic.{name} get")
        match = re.search(r"is\s+(-?[\d.]+)", out)
        return float(match.group(1)) if match else float("nan")

    def has_effect(self, effect: str) -> bool:
        # 不猜 NBT 路径（不同版本键名不同），直接取整份实体 NBT 找效果名
        out = self.command(f"data get entity {ENTITY}")
        return effect in out

    # ---------------------------------------------------------------- 场景

    def run(self) -> int:
        self.rcon = Rcon(self.args.host, self.args.port, self.args.password)
        try:
            self.setup()
            self.scenario_freeze()
            self.scenario_phases()
            self.scenario_combat()
            self.scenario_out_of_combat()
        finally:
            self.command("player Smoke stop")
            self.command("player Smoke kill")
            self.command("kill " + ENTITY)
            self.report()
            self.command("stop")
            self.rcon.close()
        return 0 if all(ok for _, ok, _ in self.results) else 1

    def setup(self) -> None:
        for command in (
            "gamerule doMobSpawning false",
            "gamerule doDaylightCycle false",
            "gamerule sendCommandFeedback true",
            "time set noon",
            "difficulty normal",
            "kill " + ENTITY,
            "player Smoke kill",
        ):
            self.command(command)
        self.command(
            f"execute positioned 0 {SURFACE_Y + 1} 0 run summon hush:yao_yongshou ~ ~ ~ "
            '{NoAI:0b,PersistenceRequired:1b,Tags:["hush_smoke"]}'
        )
        self.check("entity summoned", "Summoned" in self.transcript[-1][1], self.transcript[-1][1])
        # 假玩家放在 10 格外：既进入「贴太近要后退」区间，又在注视范围内
        self.command(f"player {PLAYER} spawn at 0.5 {SURFACE_Y + 1} 10.5 facing 0 0")
        self.command(f"effect give {PLAYER} minecraft:resistance 600 4 true")
        self.command(f"effect give {PLAYER} minecraft:saturation 600 4 true")
        self.wait_for("fake player online", self.player_online, timeout=30)

    def player_online(self) -> bool:
        return PLAYER in self.command("list")

    def scenario_freeze(self) -> None:
        """10 格内被注视：不应继续后退，位置基本不变。"""
        self.command(f"player {PLAYER} look at 0.5 {SURFACE_Y + 1.5} 0.5")
        time.sleep(2)
        before = self.entity_pos()
        time.sleep(4)
        after = self.entity_pos()
        if before is None or after is None:
            self.check("gaze freeze", False, "position unreadable")
            return
        moved = sum((a - b) ** 2 for a, b in zip(before, after)) ** 0.5
        self.check("gaze freeze (no back-off)", moved < 0.5, f"moved {moved:.2f} blocks in 4s")

    def scenario_phases(self) -> None:
        # 直接改 Health，避开护甲减伤（Boss 阶段护甲 15~20，/damage 30 实际只掉十几点）
        self.command(f"data modify entity {ENTITY} Health set value 70f")  # <80%
        self.wait_for("boss form at 70%", lambda: self.entity_flag("BossForm"), timeout=5)
        self.wait_for("phase 2 armor 10", lambda: self.attribute("armor") == 10.0, timeout=5)

        self.command(f"data modify entity {ENTITY} Health set value 55f")  # <60%
        self.wait_for("phase 3 armor 15", lambda: self.attribute("armor") == 15.0, timeout=5)

        self.command(f"data modify entity {ENTITY} Health set value 25f")  # <30%
        self.wait_for("phase 4 armor 20", lambda: self.attribute("armor") == 20.0, timeout=5)
        self.wait_for("boss scale 2", lambda: self.attribute("scale") == 2.0, timeout=5)
        self.wait_for("phase 4 resistance", lambda: self.has_effect("minecraft:resistance"), timeout=5)
        self.wait_for("phase 4 strength", lambda: self.has_effect("minecraft:strength"), timeout=5)

    def scenario_combat(self) -> None:
        """Boss 形态下玩家在 6~16 格：应吐口水；用口水实体落地作为「真的打了」的证据。"""
        before = self.entity_number("Health")
        self.command(f"player {PLAYER} look at 0.5 {SURFACE_Y + 1.5} 0.5")
        self.wait_for("boss spit at player", self.spit_spawned, timeout=25)
        after = self.entity_number("Health")
        self.check("health readable after combat", after == after, f"{before} -> {after}")

    def spit_spawned(self) -> bool:
        out = self.command("data get entity @e[type=hush:yao_spit,limit=1] Pos")
        return "has the following" in out

    def scenario_out_of_combat(self) -> None:
        self.wait_for("out-of-combat regeneration", lambda: self.has_effect("minecraft:regeneration"), timeout=40)

    # ---------------------------------------------------------------- 报告

    def report(self) -> None:
        lines = [
            "# 假玩家冒烟报告",
            f"生成时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
            f"服务端日志: {self.args.log}",
            "",
            "## 断言",
        ]
        for name, ok, detail in self.results:
            lines.append(f"- {'PASS' if ok else 'FAIL'} {name}: {detail}")
        if self.notes:
            lines += ["", "## 卡顿记录"] + [f"- {note}" for note in self.notes]
        lines += ["", "## 服务端调试日志（[Hush] 行）"]
        lines += extract_debug_lines(self.args.log)
        lines += ["", "## 命令回放"]
        for command, out in self.transcript:
            lines.append(f"> {command}")
            if out.strip():
                lines.append(out.strip())
        with open(self.args.report, "w", encoding="utf-8") as handle:
            handle.write("\n".join(lines) + "\n")
        print(f"report: {self.args.report}")


def extract_debug_lines(path: str, limit: int = 200) -> list:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            lines = [line.rstrip() for line in handle if "[Hush]" in line or "HUSH_" in line]
    except OSError as exc:
        return [f"- 读取日志失败: {exc}"]
    if len(lines) > limit:
        lines = lines[-limit:]
    return [f"- {line}" for line in lines]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="hush-smoke")
    parser.add_argument("--log", default="run/logs/latest.log")
    parser.add_argument("--report", default="build/smoke-report.txt")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    smoke = Smoke(args)
    try:
        return smoke.run()
    except OSError as exc:
        print(f"rcon: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
