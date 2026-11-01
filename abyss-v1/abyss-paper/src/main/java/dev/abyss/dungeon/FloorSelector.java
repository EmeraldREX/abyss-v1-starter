package dev.abyss.dungeon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class FloorSelector {
    private FloorSelector() {}

    public static List<FloorType> generate(long seed) {
        Random random = new Random(seed);
        List<FloorType> floors = new ArrayList<>(List.of(
            FloorType.BATTLE, FloorType.BATTLE, FloorType.ELITE,
            FloorType.EXPLORATION, FloorType.TREASURE, FloorType.REST
        ));
        FloorType[] extras = { FloorType.BATTLE, FloorType.EXPLORATION,
            FloorType.EVENT, FloorType.BATTLE };
        while (floors.size() < 9) {
            floors.add(extras[random.nextInt(extras.length)]);
        }
        Collections.shuffle(floors, random);
        floors.add(FloorType.BOSS);
        return List.copyOf(floors);
    }
}
