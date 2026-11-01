package dev.abyss.dungeon;

import java.util.List;
import java.util.UUID;

public final class DungeonRun {
    private final UUID id;
    private final UUID playerId;
    private final String difficulty;
    private int segment;
    private List<FloorType> floors;
    private int index;
    private int remaining;
    private boolean objectiveDone;
    private int slot;

    public DungeonRun(UUID id, UUID playerId, String difficulty, int segment, List<FloorType> floors) {
        this.id = id;
        this.playerId = playerId;
        this.difficulty = difficulty;
        this.segment = segment;
        this.floors = List.copyOf(floors);
    }

    public UUID id() { return id; }
    public UUID playerId() { return playerId; }
    public String difficulty() { return difficulty; }
    public int segment() { return segment; }
    public int floor() { return segment * 10 + index + 1; }
    public FloorType type() { return floors.get(index); }
    public int remaining() { return remaining; }
    public boolean objectiveDone() { return objectiveDone; }
    public void objectiveDone(boolean value) { objectiveDone = value; }
    public void remaining(int value) { remaining = value; }
    public void decrement() { if (remaining > 0) remaining--; }
    public int slot() { return slot; }
    public void slot(int value) { slot = value; }
    public boolean atBoss() { return index == 9; }
    public boolean nextSegment(List<FloorType> nextFloors) {
        if (!atBoss() || !objectiveDone || nextFloors == null || nextFloors.size() != 10) return false;
        segment++;
        floors = List.copyOf(nextFloors);
        index = 0;
        remaining = 0;
        objectiveDone = false;
        return true;
    }
    public boolean advance() {
        if (index == 9) return false;
        index++;
        remaining = 0;
        objectiveDone = false;
        return true;
    }
}
