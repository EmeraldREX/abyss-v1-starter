package dev.abyss.dungeon;
public enum FloorType {
    BATTLE("通常戦闘"), ELITE("強敵戦"), EXPLORATION("探索"),
    TREASURE("宝箱"), EVENT("イベント"), REST("休憩"), BOSS("ボス戦");
    private final String display;
    FloorType(String display) { this.display = display; }
    public String display() { return display; }
}
