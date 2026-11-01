package dev.abyss.dungeon;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Flat-world 128x128 arenas; no external schematics required. */
public final class ArenaBuilder {
    private ArenaBuilder() {}
    public static final int SIZE = 128;
    public static Location center(World world, int slot) {
        int x = (slot % 15) * 384;
        int z = (slot / 15) * 384;
        return new Location(world, x + .5, 65, z + .5);
    }
    public static void prepare(JavaPlugin plugin, Location center, Runnable done) {
        World world = center.getWorld();
        int x = center.getBlockX(), z = center.getBlockZ(), y = center.getBlockY();
        // Only the perimeter is edited. The flat-world terrain is the floor.
        // Spread edits over ticks to avoid a large single-tick block update.
        final int[] cursor = {0};
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
            for (int i = 0; i < 80 && cursor[0] < SIZE * 4; i++, cursor[0]++) {
                int n = cursor[0], dx, dz;
                if (n < SIZE) { dx = n - 64; dz = -64; }
                else if (n < SIZE * 2) { dx = n - SIZE - 64; dz = 63; }
                else if (n < SIZE * 3) { dx = -64; dz = n - SIZE * 2 - 64; }
                else { dx = 63; dz = n - SIZE * 3 - 64; }
                for (int h = 0; h < 3; h++) world.getBlockAt(x + dx, y + h, z + dz).setType(Material.BARRIER, false);
            }
            if (cursor[0] >= SIZE * 4) { cancel(); done.run(); }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
    public static boolean within(Location location, Location center) {
        return location.getWorld().equals(center.getWorld()) &&
            Math.abs(location.getBlockX() - center.getBlockX()) < 62 &&
            Math.abs(location.getBlockZ() - center.getBlockZ()) < 62;
    }
}
