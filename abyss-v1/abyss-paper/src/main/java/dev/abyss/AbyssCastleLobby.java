package dev.abyss;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/** Large floating cathedral lobby: a real Sponge schematic bundled with the plugin. */
public final class AbyssCastleLobby {
    private AbyssCastleLobby() {}

    public static void build(JavaPlugin plugin, World world, int x, int y, int z) throws Exception {
        if (!org.bukkit.Bukkit.getPluginManager().isPluginEnabled("WorldEdit"))
            throw new IllegalStateException("WorldEditが必要です");
        File file = new File(plugin.getDataFolder(), "lobby/sky_castle_lobby.schem");
        file.getParentFile().mkdirs();
        // Prefer the schematic supplied by the server owner. Never overwrite it
        // with a bundled resource when rebuilding the lobby.
        if (!file.isFile()) {
            try (InputStream in = plugin.getResource("sky_castle_lobby.schem")) {
                if (in == null)
                    throw new IllegalStateException("ロビーのschemが見つかりません: " + file.getAbsolutePath());
                Files.copy(in, file.toPath());
            }
        }
        if (!file.canRead() || file.length() == 0)
            throw new IllegalStateException("ロビーのschemを読み取れません: " + file.getAbsolutePath());
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) throw new IllegalStateException("WorldEditがschem形式を認識できません");
        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(Files.newInputStream(file.toPath()))) {
            clipboard = reader.read();
        }
        // Schematic min corner: x-75, y-40, z-62. The gold spawn marker is at (0, y+1, z+13).
        BlockVector3 min = BlockVector3.at(x - 75, y - 40, z - 62);
        var weWorld = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance().newEditSession(weWorld)) {
            // Explicit destructive rebuild requested: remove old structures within the build envelope.
            CuboidRegion region = new CuboidRegion(weWorld,
                BlockVector3.at(x - 78, y - 41, z - 65),
                BlockVector3.at(x + 78, y + 63, z + 65));
            session.setBlocks(region, BlockTypes.AIR.getDefaultState());
            Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                .to(min).ignoreAirBlocks(false).build());
        }
        world.setSpawnLocation(x, y + 2, z + 13, 180);
        world.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false);
        world.setTime(12500);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(org.bukkit.GameRules.ADVANCE_TIME, false);
        world.setGameRule(org.bukkit.GameRules.ADVANCE_WEATHER, false);
    }
}
