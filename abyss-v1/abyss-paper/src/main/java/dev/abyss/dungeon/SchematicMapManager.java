package dev.abyss.dungeon;

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
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Schematic catalog: files are under plugins/AbyssDungeon/schematics/<floor-type>/*.schem.
 *  Each .schem can have an optional adjacent .yml for spawn offsets.
 */
public final class SchematicMapManager {
    private final JavaPlugin plugin;
    private final File root;
    private final boolean worldEditAvailable;
    private final File slotFile;
    private final YamlConfiguration slotData;
    private final java.util.Set<Integer> clearing = new java.util.HashSet<>();

    public SchematicMapManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "schematics");
        this.worldEditAvailable = Bukkit.getPluginManager().isPluginEnabled("WorldEdit");
        this.slotFile = new File(plugin.getDataFolder(), "arena-slots.yml");
        this.slotData = YamlConfiguration.loadConfiguration(slotFile);
        for (FloorType type : FloorType.values()) {
            new File(root, type.name().toLowerCase(Locale.ROOT)).mkdirs();
        }
        installTerrainTemplates();
    }

    /** Install generated terrain templates without overwriting user-provided schematics. */
    private void installTerrainTemplates() {
        for (FloorType type : new FloorType[]{FloorType.BATTLE, FloorType.ELITE, FloorType.EXPLORATION, FloorType.BOSS}) {
            String dir = type.name().toLowerCase(Locale.ROOT);
            for (int i = 1; i <= 10; i++) {
                String stem = String.format(Locale.ROOT, "terrain_%02d", i);
                for (String ext : new String[]{".schem", ".yml"}) {
                    String resource = "test-schematics/" + dir + "/" + stem + ext;
                    File target = new File(new File(root, dir), stem + ext);
                    if (target.exists()) continue;
                    try (java.io.InputStream in = plugin.getResource(resource)) {
                        if (in == null) { plugin.getLogger().warning("Missing terrain template: " + resource); continue; }
                        Files.copy(in, target.toPath());
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Cannot install terrain template " + resource + ": " + ex);
                    }
                }
            }
        }
    }

    public boolean available() { return worldEditAvailable; }

    public List<File> maps(FloorType type) {
        File dir = new File(root, type.name().toLowerCase(Locale.ROOT));
        File[] files = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".schem"));
        if (files == null) return List.of();
        List<File> list = new ArrayList<>(List.of(files));
        list.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (FloorType type : FloorType.values()) {
            if (!sb.isEmpty()) sb.append(" / ");
            sb.append(type.name()).append(':').append(maps(type).size());
        }
        return sb.toString();
    }

    /** True only inside the actual pasted schematic (one-block safety inset). */
    public boolean withinMap(int slot, Location loc) {
        java.util.List<Integer> b = slotData.getIntegerList("slots." + slot + ".bounds");
        if (b.size() != 6 || slotData.getBoolean("slots." + slot + ".fallback"))
            return ArenaBuilder.within(loc, ArenaBuilder.center(loc.getWorld(), slot));
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x > b.get(0) && x < b.get(3) && z > b.get(2) && z < b.get(5)
            && y >= b.get(1) + 1 && y <= b.get(4) - 1;
    }

    public void markFallback(int slot, Location center) {
        // Fallback builds only a 128x128 barrier perimeter, three blocks high.
        recordBounds(slot, center.getBlockX()-64, center.getBlockY(), center.getBlockZ()-64,
            center.getBlockX()+63, center.getBlockY()+2, center.getBlockZ()+63, true);
    }

    private void recordBounds(int slot, int x1, int y1, int z1, int x2, int y2, int z2, boolean fallback) {
        String key = "slots." + slot;
        slotData.set(key + ".bounds", java.util.List.of(x1, y1, z1, x2, y2, z2));
        slotData.set(key + ".fallback", fallback);
        saveSlots();
    }

    private void saveSlots() {
        try { slotData.save(slotFile); }
        catch (Exception ex) { plugin.getLogger().severe("Cannot persist arena slot bounds: " + ex); }
    }

    public void clear(int slot, Location center, Runnable done) {
        if (!clearing.add(slot)) { plugin.getLogger().warning("Slot " + slot + " already clearing"); return; }
        try {
            String key = "slots." + slot;
            java.util.List<Integer> bounds = slotData.getIntegerList(key + ".bounds");
            if (bounds.size() == 6) {
                if (slotData.getBoolean(key + ".fallback")) {
                    int x1 = bounds.get(0), y = bounds.get(1), z1 = bounds.get(2);
                    int x2 = bounds.get(3), z2 = bounds.get(5);
                    for (int h=0; h<3; h++) {
                        for (int x=x1; x<=x2; x++) {
                            center.getWorld().getBlockAt(x,y+h,z1).setType(org.bukkit.Material.AIR,false);
                            center.getWorld().getBlockAt(x,y+h,z2).setType(org.bukkit.Material.AIR,false);
                        }
                        for (int z=z1; z<=z2; z++) {
                            center.getWorld().getBlockAt(x1,y+h,z).setType(org.bukkit.Material.AIR,false);
                            center.getWorld().getBlockAt(x2,y+h,z).setType(org.bukkit.Material.AIR,false);
                        }
                    }
                } else if (worldEditAvailable) {
                    CuboidRegion region = new CuboidRegion(
                        BlockVector3.at(bounds.get(0),bounds.get(1),bounds.get(2)),
                        BlockVector3.at(bounds.get(3),bounds.get(4),bounds.get(5)));
                    try (EditSession edit = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(center.getWorld()))) {
                        edit.setBlocks(region, BlockTypes.AIR.getDefaultState());
                    }
                } else {
                    plugin.getLogger().severe("WorldEdit unavailable; cannot clear schematic slot " + slot);
                    return; // never release an uncleared slot
                }
                slotData.set(key, null);
                saveSlots();
            }
            done.run();
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not clear slot " + slot + ": " + ex);
            // Do not release the slot when cleanup fails.
        } finally { clearing.remove(slot); }
    }

    public void prepare(FloorType type, Location destination, long seed, int slot, Consumer<MapResult> callback) {
        if (!worldEditAvailable) {
            callback.accept(MapResult.failure("WorldEditが見つかりません。"));
            return;
        }
        // Peaceful floors use compact, brightly lit procedural rooms instead of
        // the large combat schematics. Their full bounds are persisted for cleanup.
        if (type == FloorType.REST || type == FloorType.TREASURE || type == FloorType.EVENT) {
            try {
                callback.accept(buildPeacefulRoom(type, destination, seed, slot));
            } catch (Exception ex) {
                plugin.getLogger().severe("Peaceful room generation failed: " + ex);
                callback.accept(MapResult.failure("非戦闘フロアの生成に失敗しました"));
            }
            return;
        }
        List<File> candidates = maps(type);
        // Use the new 10 terrain bases for combat maps; legacy flat schematics remain on disk.
        List<File> terrain = candidates.stream().filter(f -> f.getName().startsWith("terrain_")).toList();
        if (!terrain.isEmpty()) candidates = terrain;
        if (candidates.isEmpty()) {
            callback.accept(MapResult.failure(type + " の .schem がありません。"));
            return;
        }
        File selected = candidates.get(new Random(seed).nextInt(candidates.size()));
        Path path = selected.toPath();
        CompletableFuture.supplyAsync(() -> {
            try {
                ClipboardFormat format = ClipboardFormats.findByFile(selected);
                if (format == null) throw new IllegalArgumentException("Unsupported schematic format: " + selected.getName());
                try (ClipboardReader reader = format.getReader(Files.newInputStream(path))) {
                    return reader.read();
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Cannot read " + selected.getName(), ex);
            }
        }).whenComplete((clipboard, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().warning("Schematic load failed: " + error);
                callback.accept(MapResult.failure("読み込み失敗: " + selected.getName()));
                return;
            }
            try {
                if (clipboard.getDimensions().x() > 192 || clipboard.getDimensions().z() > 192 || clipboard.getDimensions().y() > 64) {
                    callback.accept(MapResult.failure("マップが192x192x64の上限を超えています: " + selected.getName()));
                    return;
                }
                // WorldEdit clipboard origin is pasted at destination. Create schematics with //copy at player spawn.
                World world = destination.getWorld();
                BlockVector3 at = BlockVector3.at(destination.getBlockX(), destination.getBlockY(), destination.getBlockZ());
                try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
                    Operations.complete(new ClipboardHolder(clipboard).createPaste(session)
                        .to(at).ignoreAirBlocks(false).build());
                }
                BlockVector3 min = clipboard.getRegion().getMinimumPoint().subtract(clipboard.getOrigin()).add(at);
                BlockVector3 max = clipboard.getRegion().getMaximumPoint().subtract(clipboard.getOrigin()).add(at);
                recordBounds(slot, min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), false);
                // Deterministic, low-impact floor dressing. Do not touch exit, player spawn,
                // mob spawn locations or walls. Small rooms receive fewer details.
                decorateFloor(world, min, max, type, seed);
                buildTerraces(world, min, max, type, seed);
                YamlConfiguration meta = metadata(selected);
                boolean generatedTerrain = selected.getName().startsWith("terrain_");
                Location spawn;
                Location exit;
                List<Location> mobSpawns = new ArrayList<>();
                if (generatedTerrain) {
                    // Terrain heights vary: never use fixed Y offsets from the template.
                    int cx = (min.x() + max.x()) / 2;
                    int cz = (min.z() + max.z()) / 2;
                    spawn = safeSurface(world, cx, cz, min.y(), max.y());
                    exit = safeSurface(world, cx, cz + 5, min.y(), max.y());
                    if (spawn == null || exit == null) {
                        callback.accept(MapResult.failure("安全なスポーン・出口が見つかりません: " + selected.getName()));
                        return;
                    }
                    clearHeadroom(spawn);
                    clearHeadroom(exit);
                    // The exit marker must be physically present, not just metadata.
                    exit.clone().add(0, -1, 0).getBlock().setType(org.bukkit.Material.GOLD_BLOCK, false);
                    Random spawnRandom = new Random(seed ^ 0x4E554C4CL);
                    int attempts = 0;
                    while (mobSpawns.size() < 32 && attempts++ < 600) {
                        int sx = min.x() + 5 + spawnRandom.nextInt(Math.max(1, max.x() - min.x() - 9));
                        int sz = min.z() + 5 + spawnRandom.nextInt(Math.max(1, max.z() - min.z() - 9));
                        if (Math.abs(sx - cx) < 9 && Math.abs(sz - cz) < 9) continue;
                        Location candidate = safeSurface(world, sx, sz, min.y(), max.y());
                        if (candidate != null && candidate.distanceSquared(spawn) > 100
                                && candidate.distanceSquared(exit) > 25) mobSpawns.add(candidate);
                    }
                    if (mobSpawns.isEmpty()) {
                        callback.accept(MapResult.failure("敵の安全な出現地点がありません: " + selected.getName()));
                        return;
                    }
                } else {
                    spawn = destination.clone().add(meta.getDouble("player-spawn.x", 0),
                        meta.getDouble("player-spawn.y", 1), meta.getDouble("player-spawn.z", 0));
                    for (var entry : meta.getMapList("mob-spawns")) {
                        Object x = entry.get("x"), y = entry.get("y"), z = entry.get("z");
                        if (x instanceof Number xx && y instanceof Number yy && z instanceof Number zz) {
                            Location candidate = destination.clone().add(xx.doubleValue(), yy.doubleValue(), zz.doubleValue());
                            if (withinMap(slot, candidate) && candidate.getBlock().isPassable()
                                && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                                && candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                                mobSpawns.add(candidate);
                            } else plugin.getLogger().warning("Ignoring unsafe mob spawn in " + selected.getName() + ": " + candidate);
                        }
                    }
                    exit = destination.clone().add(meta.getDouble("exit.x", 0),
                        meta.getDouble("exit.y", 1), meta.getDouble("exit.z", 0));
                }
                plugin.getLogger().info("Map " + selected.getName() + " spawn=" + spawn + " exit=" + exit + " mobSpawns=" + mobSpawns.size());
                callback.accept(new MapResult(true, selected.getName(), spawn, List.copyOf(mobSpawns), exit));
            } catch (Exception ex) {
                plugin.getLogger().severe("Schematic paste failed: " + selected.getName() + ": " + ex);
                callback.accept(MapResult.failure("配置失敗: " + selected.getName()));
            }
        }));
    }

    /** Compact non-combat rooms with permanent lighting and unobstructed walkways. */
    private MapResult buildPeacefulRoom(FloorType type, Location destination, long seed, int slot) {
        World world = destination.getWorld();
        int radius = type == FloorType.REST ? 10 : type == FloorType.TREASURE ? 8 : 9;
        int x = destination.getBlockX(), z = destination.getBlockZ();
        int floorY = destination.getBlockY() - 1;
        int ceilingY = floorY + 7;
        Random random = new Random(seed);
        org.bukkit.Material floor = type == FloorType.REST ? org.bukkit.Material.SMOOTH_STONE
            : type == FloorType.TREASURE ? org.bukkit.Material.POLISHED_DEEPSLATE
            : org.bukkit.Material.POLISHED_ANDESITE;
        org.bukkit.Material wall = type == FloorType.REST ? org.bukkit.Material.STONE_BRICKS
            : type == FloorType.TREASURE ? org.bukkit.Material.DEEPSLATE_BRICKS
            : org.bukkit.Material.TUFF_BRICKS;
        // WorldEdit writes the room in bulk, including air inside. All blocks are
        // inside the recorded region, so the next floor can clear them completely.
        try (EditSession edit = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            CuboidRegion volume = new CuboidRegion(BlockVector3.at(x-radius, floorY, z-radius),
                BlockVector3.at(x+radius, ceilingY, z+radius));
            edit.setBlocks(volume, BlockTypes.AIR.getDefaultState());
            edit.setBlocks(new CuboidRegion(BlockVector3.at(x-radius,floorY,z-radius),
                BlockVector3.at(x+radius,floorY,z+radius)), com.sk89q.worldedit.world.block.BlockTypes.get("minecraft:" + floor.name().toLowerCase(Locale.ROOT)).getDefaultState());
            edit.setBlocks(new CuboidRegion(BlockVector3.at(x-radius,ceilingY,z-radius),
                BlockVector3.at(x+radius,ceilingY,z+radius)), com.sk89q.worldedit.world.block.BlockTypes.get("minecraft:" + wall.name().toLowerCase(Locale.ROOT)).getDefaultState());
            for (int dx=-radius; dx<=radius; dx++) for (int dz=-radius; dz<=radius; dz++) {
                if (Math.abs(dx)==radius || Math.abs(dz)==radius) {
                    for (int dy=1;dy<7;dy++) edit.setBlock(BlockVector3.at(x+dx,floorY+dy,z+dz),
                        com.sk89q.worldedit.world.block.BlockTypes.get("minecraft:" + wall.name().toLowerCase(Locale.ROOT)).getDefaultState());
                }
            }
        } catch (com.sk89q.worldedit.MaxChangedBlocksException ex) {
            throw new IllegalStateException("Peaceful room block limit exceeded", ex);
        }
        // Evenly spaced ceiling lights ensure that corners are bright too.
        for (int dx=-radius+3; dx<=radius-3; dx+=5) {
            for (int dz=-radius+3; dz<=radius-3; dz+=5) {
                world.getBlockAt(x+dx, ceilingY, z+dz).setType(org.bukkit.Material.SEA_LANTERN, false);
            }
        }
        // Sparse edge-only decoration; leave the middle and exit accessible.
        for (int i=0;i<Math.min(8,radius);i++) {
            int dx = random.nextBoolean() ? radius-2 : -radius+2;
            int dz = -radius+3+random.nextInt(2*radius-5);
            world.getBlockAt(x+dx,floorY+1,z+dz).setType(
                type == FloorType.REST ? org.bukkit.Material.POTTED_FERN : org.bukkit.Material.CHISELED_STONE_BRICKS, false);
        }
        recordBounds(slot,x-radius,floorY,z-radius,x+radius,ceilingY,z+radius,false);
        Location spawn = new Location(world,x+0.5,floorY+1,z+0.5);
        Location exit = new Location(world,x+0.5,floorY+1,z+4.5);
        // Always create a physical, walkable exit marker in peaceful rooms.
        world.getBlockAt(x, floorY, z+4).setType(org.bukkit.Material.GOLD_BLOCK, false);
        world.getBlockAt(x, floorY+1, z+4).setType(org.bukkit.Material.AIR, false);
        world.getBlockAt(x, floorY+2, z+4).setType(org.bukkit.Material.AIR, false);
        plugin.getLogger().info("Compact bright " + type + " room: " + (radius*2+1) + "x" + (radius*2+1));
        return new MapResult(true, "compact-" + type.name().toLowerCase(Locale.ROOT), spawn, List.of(), exit);
    }

    /** Decorates only within pasted schematic bounds. No world-height lookups: sky worlds
     *  have unrelated blocks above/below the arena and previously prevented decoration. */
    /** Find a solid walkable surface strictly inside the pasted schematic bounds. */
    private Location safeSurface(World world, int x, int z, int minY, int maxY) {
        for (int y = maxY - 2; y >= minY + 1; y--) {
            if (world.getBlockAt(x, y - 1, z).getType().isSolid()
                && world.getBlockAt(x, y, z).isPassable()
                && world.getBlockAt(x, y + 1, z).isPassable()) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private void clearHeadroom(Location location) {
        location.getBlock().setType(org.bukkit.Material.AIR, false);
        location.clone().add(0, 1, 0).getBlock().setType(org.bukkit.Material.AIR, false);
    }

    private void decorateFloor(World world, BlockVector3 min, BlockVector3 max, FloorType type, long seed) {
        Random random = new Random(seed ^ 0x6A09E667F3BCC909L);
        int width = max.x() - min.x() + 1, depth = max.z() - min.z() + 1;
        boolean compact = type == FloorType.REST || type == FloorType.TREASURE || type == FloorType.EVENT;
        int count = compact ? Math.min(14, width * depth / 70) : Math.min(210, width * depth / 35);
        org.bukkit.Material[] surface = {org.bukkit.Material.MOSS_BLOCK, org.bukkit.Material.CRACKED_STONE_BRICKS,
            org.bukkit.Material.MOSSY_STONE_BRICKS, org.bukkit.Material.COBBLED_DEEPSLATE,
            org.bukkit.Material.TUFF, org.bukkit.Material.POLISHED_ANDESITE, org.bukkit.Material.COARSE_DIRT};
        org.bukkit.Material[] columns = {org.bukkit.Material.STONE_BRICKS, org.bukkit.Material.DEEPSLATE_BRICKS,
            org.bukkit.Material.MOSSY_COBBLESTONE, org.bukkit.Material.TUFF_BRICKS};
        int placed = 0;
        for (int attempt = 0; attempt < count * 12 && placed < count; attempt++) {
            if (width < 12 || depth < 12) break;
            int x = min.x() + 4 + random.nextInt(width - 8);
            int z = min.z() + 4 + random.nextInt(depth - 8);
            // Protect central player spawn / exits and primary transit corridor.
            if (Math.abs(x - (min.x() + max.x()) / 2) < 8 && Math.abs(z - (min.z() + max.z()) / 2) < 8) continue;
            for (int y = max.y() - 3; y >= min.y() + 2; y--) {
                Block floor = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (!floor.getType().isSolid() || !feet.getType().isAir() || !head.getType().isAir()) continue;
                if (!world.getBlockAt(x, y + 2, z).getType().isAir()) continue;
                int roll = random.nextInt(100);
                if (compact || roll < 48) {
                    // Replace one existing ground block: noticeable color variation, no collision change.
                    floor.setType(surface[random.nextInt(surface.length)], false);
                } else if (roll < 78) {
                    // Short, walkable raised terrain patches. Avoid existing walls and narrow paths.
                    if (openAround(world, x, y, z)) {
                        feet.setType(surface[random.nextInt(surface.length)], false);
                    } else continue;
                } else {
                    // Distinct low pillars / cover for combat. Never on small noncombat floors.
                    if (!openAround(world, x, y, z)) continue;
                    org.bukkit.Material mat = columns[random.nextInt(columns.length)];
                    feet.setType(mat, false);
                    if (random.nextBoolean()) head.setType(mat, false);
                }
                placed++;
                break;
            }
        }
        plugin.getLogger().info("Abyss floor decoration: " + type + " " + width + "x" + depth + ", placed=" + placed);
    }

    /** Build broad stepped terraces, not isolated one-block decorations.
     *  Every terrace is self-contained with climbable one-block steps on four sides.
     *  Original floor is never removed; occupied rooms and the central spawn are protected. */
    private void buildTerraces(World world, BlockVector3 min, BlockVector3 max, FloorType type, long seed) {
        if (type != FloorType.BATTLE && type != FloorType.ELITE && type != FloorType.BOSS && type != FloorType.EXPLORATION) return;
        Random random = new Random(seed ^ 0x56C8A47FL);
        int built = 0;
        int width = max.x() - min.x() + 1, depth = max.z() - min.z() + 1;
        if (width < 40 || depth < 40) return;
        org.bukkit.Material[] materials = {org.bukkit.Material.TUFF_BRICKS, org.bukkit.Material.STONE_BRICKS,
            org.bukkit.Material.POLISHED_ANDESITE, org.bukkit.Material.DEEPSLATE_TILES};
        for (int attempt = 0; attempt < 75 && built < Math.min(9, width * depth / 900); attempt++) {
            int cx = min.x() + 13 + random.nextInt(width - 26);
            int cz = min.z() + 13 + random.nextInt(depth - 26);
            if (Math.abs(cx - (min.x() + max.x()) / 2) < 15 && Math.abs(cz - (min.z() + max.z()) / 2) < 15) continue;
            int baseY = -1;
            // Scan only inside the schematic and require a 2-block-tall open interior.
            for (int y = max.y() - 9; y >= min.y() + 1; y--) {
                if (world.getBlockAt(cx, y-1, cz).getType().isSolid()
                    && world.getBlockAt(cx, y, cz).isEmpty() && world.getBlockAt(cx, y+6, cz).isEmpty()) { baseY = y; break; }
            }
            if (baseY < 0) continue;
            boolean clear = true;
            for (int dx=-8; dx<=8 && clear; dx+=2) for (int dz=-8; dz<=8; dz+=2) {
                if (!world.getBlockAt(cx+dx,baseY-1,cz+dz).getType().isSolid()) { clear=false; break; }
                for (int dy=0; dy<=6; dy++) if (!world.getBlockAt(cx+dx,baseY+dy,cz+dz).isEmpty()) { clear=false; break; }
                if (!clear) break;
            }
            if (!clear) continue;
            org.bukkit.Material material = materials[random.nextInt(materials.length)];
            // Concentric 17x17, 13x13, 9x9, 5x5 platforms: height up to 4 blocks.
            for (int dx=-8; dx<=8; dx++) for (int dz=-8; dz<=8; dz++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                int height = dist <= 2 ? 4 : dist <= 4 ? 3 : dist <= 6 ? 2 : 1;
                for (int dy=0; dy<height; dy++) world.getBlockAt(cx+dx,baseY+dy,cz+dz).setType(material,false);
            }
            built++;
        }
        plugin.getLogger().info("Abyss terrain terraces built=" + built + " type=" + type);
    }

    private boolean openAround(World world, int x, int y, int z) {
        int clear = 0;
        for (int[] offset : new int[][] {{1,0},{-1,0},{0,1},{0,-1}}) {
            if (world.getBlockAt(x + offset[0], y, z + offset[1]).getType().isAir()
                && world.getBlockAt(x + offset[0], y + 1, z + offset[1]).getType().isAir()) clear++;
        }
        return clear >= 4;
    }

    private YamlConfiguration metadata(File schematic) {
        String name = schematic.getName();
        File yaml = new File(schematic.getParentFile(), name.substring(0, name.length() - 6) + ".yml");
        return YamlConfiguration.loadConfiguration(yaml);
    }

    public record MapResult(boolean success, String name, Location playerSpawn, List<Location> mobSpawns, Location exit) {
        public static MapResult failure(String reason) { return new MapResult(false, reason, null, List.of(), null); }
    }
}
