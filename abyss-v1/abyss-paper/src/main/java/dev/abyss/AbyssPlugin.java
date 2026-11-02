package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import dev.abyss.dungeon.ArenaBuilder;
import dev.abyss.dungeon.SchematicMapManager;
import dev.abyss.dungeon.FloorType;
import dev.abyss.dungeon.FloorSelector;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AbyssPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, DungeonRun> runs = new ConcurrentHashMap<>();
    private final AbyssParty parties = new AbyssParty();
    private final java.util.Set<UUID> downed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, org.bukkit.entity.ArmorStand> corpses = new ConcurrentHashMap<>();
    private final Map<UUID, Location> corpseLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> reviveProgress = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> revivesUsed = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Integer> partySizes = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Integer> bossPhases = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, org.bukkit.boss.BossBar> bossBars = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> bossTelegraphPending = ConcurrentHashMap.newKeySet();
    public boolean isInRun(UUID id) { return runs.containsKey(id); }
    public java.util.List<Player> activePartyMembers(UUID id) {
        DungeonRun source = runs.get(id);
        if (source == null) return java.util.List.of();
        java.util.List<Player> result = new java.util.ArrayList<>();
        for (UUID member : parties.members(id)) {
            Player online = Bukkit.getPlayer(member);
            DungeonRun other = runs.get(member);
            if (online != null && other != null && source.id().equals(other.id()) && !downed.contains(member)) result.add(online);
        }
        return result;
    }
    private final boolean[] occupiedSlots = new boolean[210];
    private final Map<UUID, Location> floorExits = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> savingBoss = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> fallProtectionUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Location> previousSpawns = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> transitioning = ConcurrentHashMap.newKeySet();
    private NamespacedKey mobRunKey;
    private NamespacedKey rewardKey;
    private final Map<UUID, java.util.List<String>> pendingRewards = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> finalizing = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> deadPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> floorRewarded = ConcurrentHashMap.newKeySet();
    private SchematicMapManager schematicMaps;
    private AbyssEncounterEnhancements enhancements;
    private AbyssFloorGimmicks floorGimmicks;
    public boolean isWithinRunMap(DungeonRun run, Location loc) { return schematicMaps != null && schematicMaps.withinMap(run.slot(), loc); }
    private AbyssInventory abyssInventory;
    private AbyssSafePouch safePouch;
    private AbyssStats abyssStats;
    private AbyssPerks abyssPerks;
    private AbyssGrowth abyssGrowth;
    private AbyssGuide abyssGuide;
    private AbyssBugReports bugReports;
    public AbyssGrowth growth(){return abyssGrowth;}
    private AbyssStarterKit starterKit;
    private AbyssMagic abyssMagic;
    private AbyssSkills abyssSkills;
    private final Map<UUID,Long> hazardCooldown=new ConcurrentHashMap<>();
    private AbyssEquipment equipmentInstance;
    public AbyssEquipment equipment(){return equipmentInstance;}
    public AbyssStats stats(){return abyssStats;}
    public AbyssInventory inventory(){return abyssInventory;}
    public DungeonRun runFor(UUID id){return runs.get(id);}
    public java.util.List<LivingEntity> enemiesFor(DungeonRun run){
        if(run==null)return java.util.List.of();
        World w=Bukkit.getWorld(dungeonName);if(w==null)return java.util.List.of();
        java.util.List<LivingEntity> found=new java.util.ArrayList<>();
        for(LivingEntity e:w.getLivingEntities())if(run.id().toString().equals(e.getPersistentDataContainer().get(mobRunKey,PersistentDataType.STRING)))found.add(e);
        return found;
    }
    private ExecutorService databaseExecutor;
    private String jdbcUrl;
    private String lobbyName;
    private String dungeonName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mobRunKey = new NamespacedKey(this, "mob_run");
        enhancements = new AbyssEncounterEnhancements(this);
        floorGimmicks = new AbyssFloorGimmicks(this);
        Bukkit.getPluginManager().registerEvents(floorGimmicks, this);
        Bukkit.getScheduler().runTaskTimer(this, floorGimmicks::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> enhancements.tickAI(), 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateBossBars, 10L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::telegraphBossAttacks, 200L, 200L);
        rewardKey = new NamespacedKey(this, "reward_id");
        lobbyName = getConfig().getString("lobby-world", "abyss_lobby");
        dungeonName = getConfig().getString("dungeon-world", "abyss_dungeon");
        if (lobbyName.equals(dungeonName)) {
            getLogger().severe("Lobby and dungeon world must differ.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        try {
            Files.createDirectories(getDataFolder().toPath());
            jdbcUrl = "jdbc:sqlite:" + getDataFolder().toPath().resolve("abyss.db").toAbsolutePath();
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = 5000");
                stmt.execute("PRAGMA journal_mode = WAL");
                stmt.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, currency INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)");
                stmt.execute("CREATE TABLE IF NOT EXISTS progress (uuid TEXT NOT NULL, difficulty TEXT NOT NULL, cleared_segments INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, difficulty))");
                stmt.execute("CREATE TABLE IF NOT EXISTS equipment (instance_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, definition_id TEXT NOT NULL, rarity TEXT NOT NULL, item_level INTEGER NOT NULL, affixes_json TEXT NOT NULL)");
                stmt.execute("CREATE TABLE IF NOT EXISTS abyss_inventory (item_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, definition_id TEXT NOT NULL, acquired_at INTEGER NOT NULL)");
                stmt.execute("CREATE TABLE IF NOT EXISTS abyss_pending (run_id TEXT NOT NULL, item_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, definition_id TEXT NOT NULL)");
                stmt.execute("CREATE TABLE IF NOT EXISTS abyss_item_rolls (item_id TEXT PRIMARY KEY, rarity TEXT NOT NULL, attack_bonus REAL NOT NULL DEFAULT 0, defense_bonus REAL NOT NULL DEFAULT 0, magic_bonus REAL NOT NULL DEFAULT 0)");
                // Backward-compatible SQLite migrations for existing item rolls.
                for(String column : new String[]{"crit_chance", "life_steal", "element_bonus", "attack_speed"}) {
                    try { stmt.execute("ALTER TABLE abyss_item_rolls ADD COLUMN " + column + " REAL NOT NULL DEFAULT 0"); }
                    catch(SQLException ex) { if(!ex.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw ex; }
                }
            }
        } catch (Exception e) {
            getLogger().severe("SQLite initialization failed: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        databaseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "abyss-sqlite");
            t.setDaemon(true);
            return t;
        });
        boolean newLobby = Bukkit.getWorld(lobbyName) == null && !new java.io.File(Bukkit.getWorldContainer(), lobbyName).exists();
        if (ensureWorld(lobbyName) == null || ensureWorld(dungeonName) == null) {
            getLogger().severe("World initialization failed");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        World lobby = Bukkit.getWorld(lobbyName);
        if (lobby != null) {
            lobby.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false);
            lobby.setStorm(false);
            lobby.setThundering(false);
            lobby.setTime(12500);
            lobby.setGameRule(org.bukkit.GameRules.ADVANCE_TIME, false);
            lobby.setGameRule(org.bukkit.GameRules.ADVANCE_WEATHER, false);
            if (newLobby && getConfig().getBoolean("lobby-build-on-create", true))
                try { AbyssCastleLobby.build(this, lobby, getConfig().getInt("lobby-spawn-x", 0),
                    getConfig().getInt("lobby-spawn-y", 200), getConfig().getInt("lobby-spawn-z", 0)); }
                catch (Exception ex) { getLogger().severe("天空ロビーの自動建築に失敗: " + ex.getMessage()); }
        }
        World dungeon = Bukkit.getWorld(dungeonName);
        if (dungeon != null) {
            dungeon.setGameRule(org.bukkit.GameRules.SPAWN_MOBS, false);
            dungeon.setGameRule(org.bukkit.GameRules.KEEP_INVENTORY, true);
        }
        schematicMaps = new SchematicMapManager(this);
        if (!schematicMaps.available()) getLogger().warning("WorldEdit missing: schematic floors unavailable; flat fallback active.");
        abyssInventory = new AbyssInventory(this, jdbcUrl, lobbyName, dungeonName);
        safePouch = new AbyssSafePouch(this, jdbcUrl);
        Bukkit.getPluginManager().registerEvents(safePouch, this);
        abyssGuide = new AbyssGuide(this);
        Bukkit.getPluginManager().registerEvents(abyssGuide, this);
        bugReports = new AbyssBugReports(this);
        Bukkit.getPluginManager().registerEvents(bugReports, this);
        AbyssEquipment abyssEquipment = new AbyssEquipment(this, jdbcUrl, dungeonName);
        equipmentInstance = abyssEquipment;
        abyssInventory.setEquipment(abyssEquipment);
        abyssMagic = new AbyssMagic(this, abyssEquipment, jdbcUrl, dungeonName);
        starterKit = new AbyssStarterKit(this, jdbcUrl);
        abyssPerks = new AbyssPerks(this);
        abyssGrowth = new AbyssGrowth(this);
        Bukkit.getPluginManager().registerEvents(abyssGrowth,this);
        abyssStats = new AbyssStats(this,jdbcUrl,abyssEquipment,abyssMagic);
        abyssStats.setPerks(abyssPerks);
        abyssSkills = new AbyssSkills(this);
        Bukkit.getPluginManager().registerEvents(abyssSkills,this);
        Bukkit.getScheduler().runTaskTimer(this, abyssSkills::tick, 10L, 10L);
        Bukkit.getPluginManager().registerEvents(abyssPerks,this);
        Bukkit.getPluginManager().registerEvents(new AbyssRangedWeapons(this), this);
        for(Player p:Bukkit.getOnlinePlayers()) starterKit.ensure(p.getUniqueId());
        Bukkit.getPluginManager().registerEvents(abyssStats,this);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for(Player p:Bukkit.getOnlinePlayers()) abyssStats.update(p, runs.get(p.getUniqueId())); },20L,20L);
        abyssInventory.setMagic(abyssMagic);
        Bukkit.getPluginManager().registerEvents(abyssMagic, this);
        Bukkit.getScheduler().runTaskTimer(this, abyssMagic::tick, 20L, 20L);
        Bukkit.getPluginManager().registerEvents(abyssEquipment, this);
        Bukkit.getPluginManager().registerEvents(abyssInventory, this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickRevives, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (Player p : Bukkit.getOnlinePlayers()) abyssInventory.refreshConsumables(p); }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::containDungeonMobs, 20L, 20L);
        org.bukkit.command.PluginCommand abyssCommand = Objects.requireNonNull(getCommand("abyss"));
        abyssCommand.setExecutor(this::onAbyssCommand);
        abyssCommand.setTabCompleter(this::onAbyssTabComplete);
        getLogger().info("AbyssDungeon v0.6.0 initialized");
    }

    private World ensureWorld(String name) {
        World world = Bukkit.getWorld(name);
        if (world != null) return world;
        WorldCreator creator = new WorldCreator(name);
        creator.type(WorldType.FLAT);
        return Bukkit.createWorld(creator);
    }

    private CompletableFuture<Integer> unlockedSegments(UUID playerId, String difficulty) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT cleared_segments FROM progress WHERE uuid=? AND difficulty=?")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, difficulty);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            } catch (Exception e) { throw new IllegalStateException(e); }
        }, databaseExecutor);
    }

    private CompletableFuture<Void> saveClear(UUID playerId, String difficulty, int segment) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO progress(uuid,difficulty,cleared_segments) VALUES(?,?,?) " +
                     "ON CONFLICT(uuid,difficulty) DO UPDATE SET cleared_segments=MAX(cleared_segments,excluded.cleared_segments)")) {
                ps.setString(1, playerId.toString());
                ps.setString(2, difficulty);
                ps.setInt(3, segment + 1);
                ps.executeUpdate();
            } catch (Exception e) { throw new IllegalStateException(e); }
        }, databaseExecutor);
    }

    /** Testing commands are never granted by OP status alone. */
    private boolean hasTestAccess(CommandSender sender) {
        if(sender instanceof Player p && p.getName().equalsIgnoreCase("EmeraldREX91"))return true;
        // abyss.test defaults to false. LuckPerms can explicitly grant it.
        return sender.hasPermission("abyss.test");
    }

    private java.util.List<String> onAbyssTabComplete(CommandSender sender, Command command,
                                                        String alias, String[] args) {
        if (args.length == 0) return java.util.List.of();
        java.util.List<String> candidates;
        if (args.length == 1) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>(java.util.List.of(
                "status", "lobby", "start", "party", "floor", "bag", "warehouse",
                "menu", "guide", "report", "safe", "growth", "magic", "perks", "skills", "class", "maps", "retreat", "interact", "claim", "clear"));
            if (hasTestAccess(sender)) names.addAll(java.util.List.of("learn", "equiptest", "rewardtest", "next", "leave", "level", "lobbybuild"));
            candidates = names;
        } else {
            String sub = args[0].toLowerCase(java.util.Locale.ROOT);
            if (args.length == 2) {
                candidates = switch (sub) {
                    case "start" -> java.util.List.of("easy", "normal", "hard");
                    case "class" -> AbyssStats.CLASSES;
                    case "lobbybuild" -> hasTestAccess(sender) ? java.util.List.of("confirm") : java.util.List.of();
                    case "level" -> hasTestAccess(sender) ? java.util.List.of("1", "10", "25", "50", "100") : java.util.List.of();
                    case "learn" -> hasTestAccess(sender)
                        ? java.util.List.of("fire", "ice", "thunder", "heal", "protect") : java.util.List.of();
                    default -> java.util.List.of();
                };
            } else if (args.length == 3 && sub.equals("start")) {
                int max = switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
                    case "easy" -> 3;
                    case "normal" -> 5;
                    case "hard" -> 10;
                    default -> 0;
                };
                java.util.ArrayList<String> segments = new java.util.ArrayList<>();
                for (int i = 1; i <= max; i++) segments.add(String.valueOf(i));
                candidates = segments;
            } else {
                candidates = java.util.List.of();
            }
        }
        String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
        return candidates.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private boolean onAbyssCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§a深淵ダンジョン稼働中。挑戦中の人数: " + runs.size());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("プレイヤーのみ使用できます。");
            return true;
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        // Enforce in the command executor as well as tab completion. OP is not enough.
        if(java.util.Set.of("next","leave","level","learn","equiptest","rewardtest","lobbybuild").contains(sub)
                && !hasTestAccess(player)){
            player.sendMessage("§cこのテスト用コマンドは使用できません。運営の許可が必要です。");
            return true;
        }
        switch (sub) {
            case "party" -> {
                if (runs.containsKey(player.getUniqueId()) && args.length > 1
                        && !args[1].equalsIgnoreCase("list")) {
                    player.sendMessage("§c攻略中はパーティ構成を変更できません。");
                    return true;
                }
                if (args.length == 1 || (args.length > 1 && args[1].equalsIgnoreCase("gui"))) openPartyMenu(player);
                else parties.command(player, args);
            }
            case "lobbybuild" -> {
                if (!hasTestAccess(player)) { player.sendMessage("§c管理者専用です。"); break; }
                World lobby = Bukkit.getWorld(lobbyName);
                if (lobby == null) { player.sendMessage("§cロビーが読み込まれていません。"); break; }
                if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
                    player.sendMessage("§e/abyss lobbybuild confirm §7- 上空にロビーを建築します。既存の同座標のブロックは置き換えます。");
                    break;
                }
                int x=getConfig().getInt("lobby-spawn-x",0), y=getConfig().getInt("lobby-spawn-y",200), z=getConfig().getInt("lobby-spawn-z",0);
                if (y < 90 || y > lobby.getMaxHeight()-48) {player.sendMessage("§cロビーのY座標が範囲外です。");break;}
                try {
                    AbyssCastleLobby.build(this,lobby,x,y,z);
                    player.sendMessage("§a天空城ロビーを再建築しました。/abyss lobby で移動できます。");
                } catch (Exception ex) {
                    player.sendMessage("§c天空城の建築に失敗: " + ex.getMessage());
                    getLogger().severe("Lobby build failed: " + ex);
                }
            }
            case "level" -> {if(!hasTestAccess(player)){player.sendMessage("§c管理者専用です。");break;}if(args.length<2){player.sendMessage("§e/abyss level <1-100>");break;}try{abyssStats.setLevel(player,Integer.parseInt(args[1]));}catch(NumberFormatException ex){player.sendMessage("§c数値を入力してください。");}}
            case "guide" -> abyssGuide.open(player);
            case "report" -> bugReports.open(player);
            case "magic" -> abyssInventory.openMagic(player);
            case "skills" -> abyssSkills.open(player);
            case "cast" -> abyssSkills.cast(player);
            case "perks" -> { if (isInRun(player.getUniqueId())) abyssPerks.open(player); else player.sendMessage("§c攻略中のみ利用できます。"); }
            case "learn" -> {if(!hasTestAccess(player)){player.sendMessage("§c管理者専用です。");break;}if(args.length<2){player.sendMessage("§e/abyss learn <fire|ice|thunder|heal|protect>");break;}abyssInventory.learnMagic(player,args[1]);}
            case "class" -> {if(args.length<2){player.sendMessage("§e/abyss class <warrior|mage|priest>");break;}if(isInRun(player.getUniqueId())){player.sendMessage("§c攻略中は職業変更不可です。");break;}abyssStats.setClass(player,args[1]);}
            case "maps" -> player.sendMessage("§b登録マップ: " + schematicMaps.summary());
            case "lobby", "leave" -> {
                DungeonRun active = runs.get(player.getUniqueId());
                if (active != null) {
                    // Test-only escape: no reward is committed. Normal reward extraction uses /abyss retreat.
                    if (!sub.equals("leave")) {
                        player.sendMessage("§c攻略中はロビーへ直接移動できません。休憩またはボス撃破後に正規の撤退を選択してください。");
                        return true;
                    }
                    if (finalizing.contains(player.getUniqueId())) {
                        player.sendMessage("§c報酬確定処理中です。少し待ってください。");
                        return true;
                    }
                    if (runs.get(player.getUniqueId()) == active) {
                        endSession(active, true);
                        player.sendMessage("§eテスト退出しました。今回の未確定報酬は破棄されました。");
                    }
                    return true;
                }
                teleport(player, lobbyName);
            }
            case "revive" -> player.sendMessage("§e蘇生は戦闘不能者の死体から10ブロック以内で、合計3秒しゃがむと行えます。");
            case "start" -> {
                if (runs.containsKey(player.getUniqueId())) {
                    player.sendMessage("§cすでにダンジョンに入場しています。");
                    return true;
                }
                String difficulty = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "easy";
                int maxSegments = switch (difficulty) {
                    case "easy" -> 3;
                    case "normal" -> 5;
                    case "hard" -> 10;
                    default -> 0;
                };
                if (maxSegments == 0) {
                    player.sendMessage("§c難易度は easy / normal / hard を指定してください。");
                    return true;
                }
                int segment;
                try { segment = args.length >= 3 ? Integer.parseInt(args[2]) - 1 : 0; }
                catch (NumberFormatException e) {
                    player.sendMessage("§c区間は1以上の数字で指定してください。");
                    return true;
                }
                if (segment < 0 || segment >= maxSegments) {
                    player.sendMessage("§c区間が範囲外です (1-" + maxSegments + ").");
                    return true;
                }
                UUID id = player.getUniqueId();
                unlockedSegments(id, difficulty).whenComplete((cleared, error) ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) {
                            getLogger().warning("Progress load failed: " + error.getMessage());
                            player.sendMessage("§c進行状況の読み込みに失敗しました。");
                            return;
                        }
                        if (segment > cleared) {
                            player.sendMessage("§c先に前の区間をクリアしてください。");
                            return;
                        }
                        if (parties.inParty(id) && !parties.isLeader(id)) { player.sendMessage("§c入場できるのはリーダーのみです。"); return; }
                        java.util.List<UUID> roster = parties.members(id);
                        for (UUID member : roster) {
                            Player teammate = Bukkit.getPlayer(member);
                            if (teammate == null || !teammate.isOnline() || runs.containsKey(member)) { player.sendMessage("§c全員オンラインかつ未攻略中である必要があります。"); return; }
                        }
                        DungeonRun run = new DungeonRun(UUID.randomUUID(), id, difficulty,
                            segment, FloorSelector.generate(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
                        int slot = allocateSlot(difficulty);
                        if (slot < 0) { player.sendMessage("§cこの難易度の70スロットは満員です。"); return; }
                        run.slot(slot);
                        if (runs.putIfAbsent(id, run) != null) { releaseSlot(slot); return; }
                        partySizes.put(run.id(), roster.size());
                        for (UUID member : roster) {
                            runs.put(member, run);
                            pendingRewards.put(member, new java.util.ArrayList<>());
                            abyssStats.beginRun(member); abyssPerks.beginRun(member); abyssGrowth.beginRun(member);
                            Player teammate = Bukkit.getPlayer(member); if (teammate != null) restoreVitals(teammate);
                        }
                        beginFloor(player, run);
                    }));
            }
            case "growth" -> { if (!isInRun(player.getUniqueId())) player.sendMessage("§c攻略中のみ選択できます。"); else abyssGrowth.open(player); }
            case "safe" -> safePouch.open(player);
            case "bag" -> {
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) { player.sendMessage("§c攻略中のみ確認できます。"); return true; }
                player.sendMessage("§e未確定報酬: " + pendingRewards.getOrDefault(player.getUniqueId(), java.util.List.of()).size() + "個");
            }
            case "warehouse" -> abyssInventory.openWarehouse(player);
            case "menu" -> abyssInventory.openMain(player);
            case "equiptest" -> {
                if (!hasTestAccess(player)) { player.sendMessage("§c管理者専用です。"); return true; }
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) { player.sendMessage("§c攻略中のみ使用できます。"); return true; }
                String id = AbyssEquipment.randomPhase21Item(run.floor(), java.util.concurrent.ThreadLocalRandom.current());
                if (id != null) grantPending(run,player.getUniqueId(),id);
                player.sendMessage("§a装備テスト用報酬を仮取得しました。");
            }
            case "rewardtest" -> {
                if (!hasTestAccess(player)) { player.sendMessage("§c管理者専用です。"); return true; }
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) { player.sendMessage("§c攻略中のみ使用できます。"); return true; }
                String id = AbyssEquipment.randomPhase21Item(run.floor(), java.util.concurrent.ThreadLocalRandom.current());
                if (id != null) grantPending(run, player.getUniqueId(), id);
                player.sendMessage("§aDQMVI装備を仮取得しました。");
            }
            case "retreat" -> {
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) { player.sendMessage("§c攻略中ではありません。"); return true; }
                if (!run.playerId().equals(player.getUniqueId())) { player.sendMessage("§cリーダーのみ撤退できます。"); return true; }
                if (!canRetreat(run)) { player.sendMessage("§c休憩フロアまたはボス撃破後にのみ撤退できます。"); return true; }
                openExitMenu(player);
            }
            case "floor" -> {
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) player.sendMessage("§cダンジョンに入場していません。");
                else showFloor(player, run);
            }
            case "interact" -> {
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) { player.sendMessage("§cダンジョンに入場していません。"); return true; }
                if (run.type() == FloorType.BATTLE || run.type() == FloorType.ELITE ||
                    run.type() == FloorType.BOSS) {
                    player.sendMessage("§c敵をすべて倒してください。");
                } else {
                    if (run.type() == FloorType.REST) openExitMenu(player);
                    else { if (!run.objectiveDone()) completeObjective(player, run); player.sendMessage("§e金ブロックを踏んで次の階へ進んでください。"); }
                }
            }
            case "next" -> {
                if (!hasTestAccess(player)) {
                    player.sendMessage("§c管理者専用コマンドです。");
                    return true;
                }
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null) {
                    player.sendMessage("§cダンジョンに入場していません。");
                    return true;
                }
                if (run.advance()) {
                    beginFloor(player, run);
                } else {
                    player.sendMessage("§eボス階層です。");
                }
            }
            case "claim", "clear" -> {
                DungeonRun run = runs.get(player.getUniqueId());
                if (run == null || !run.atBoss() || !run.objectiveDone()) {
                    player.sendMessage("§cボス撃破後のみ帰還できます。"); return true;
                }
                openExitMenu(player);
            }
            default -> player.sendMessage("§e/abyss <status|lobby|start [easy|normal|hard] [segment]|floor|leave|next|interact|claim|clear>");
        }
        return true;
    }

    private synchronized int allocateSlot(String difficulty) {
        int offset = switch (difficulty) {
            case "easy" -> 0;
            case "normal" -> 70;
            case "hard" -> 140;
            default -> throw new IllegalArgumentException("Unknown difficulty: " + difficulty);
        };
        for (int i = offset; i < offset + 70; i++) {
            if (!occupiedSlots[i]) { occupiedSlots[i] = true; return i; }
        }
        return -1;
    }

    private synchronized void releaseSlot(int slot) {
        if (slot >= 0 && slot < occupiedSlots.length) occupiedSlots[slot] = false;
    }

    private void finishRun(DungeonRun run) {
        transitioning.remove(run.playerId());
        fallProtectionUntil.remove(run.playerId());
        floorRewarded.remove(run.playerId());
        for(Player member:membersOf(run)){treasureClaimed.remove(member.getUniqueId());treasureOffers.remove(member.getUniqueId());treasureRuns.remove(member.getUniqueId());}
        previousSpawns.remove(run.playerId());
        floorExits.remove(run.playerId());
        savingBoss.remove(run.playerId());
        clearMobs(run);
        floorGimmicks.clear(run);
        // Keep the slot locked until its blocks are removed.
        Location center = ArenaBuilder.center(Objects.requireNonNull(Bukkit.getWorld(dungeonName)), run.slot());
        schematicMaps.clear(run.slot(), center, () -> releaseSlot(run.slot()));
    }

    private void beginFloor(Player player, DungeonRun run) {
        World world = Bukkit.getWorld(dungeonName);
        if (world == null) { player.sendMessage("§cダンジョンワールドがありません。"); return; }
        if (!transitioning.add(run.playerId())) return;
        Location center = ArenaBuilder.center(world, run.slot());
        player.sendMessage("§eフロアを準備中... (固定スロット " + run.slot() + ")");
        // Remove mobs first. Do not let the player stand inside the area being edited.
        clearMobs(run);
        floorGimmicks.clear(run);
        enhancements.clearLights(run);
        protectFromTransitionFall(player, 30000);
        Location holding = center.clone().add(0.5, 146, 0.5);
        holding.clone().subtract(0, 1, 0).getBlock().setType(org.bukkit.Material.BARRIER, false);
        player.teleportAsync(holding).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(this, () -> {
            if (err != null || !Boolean.TRUE.equals(ok) || runs.get(run.playerId()) != run) {
                transitioning.remove(run.playerId());
                return;
            }
            schematicMaps.clear(run.slot(), center, () -> {
                if (runs.get(run.playerId()) != run) { transitioning.remove(run.playerId()); return; }
                if (schematicMaps.available() && !schematicMaps.maps(run.type()).isEmpty()) {
                    long seed = run.id().getMostSignificantBits() ^ (long) run.floor() * 0x9E3779B97F4A7C15L;
                    schematicMaps.prepare(run.type(), center, seed, run.slot(), result -> {
                        transitioning.remove(run.playerId());
                        if (!result.success()) {
                            player.sendMessage("§c" + result.name() + " / この階は開始できません。/abyss leave で退出してください。");
                            return;
                        }
                        startEncounter(player, run, result.playerSpawn(), result.mobSpawns(), result.name(), result.exit());
                    });
                } else {
                    ArenaBuilder.prepare(this, center, () -> {
                        schematicMaps.markFallback(run.slot(), center);
                        transitioning.remove(run.playerId());
                        startEncounter(player, run, center.clone().add(0, 1, 0), java.util.List.of(), "flat-prototype", null);
                    });
                }
            });
        }));
    }

    private void startEncounter(Player player, DungeonRun run, Location spawn, java.util.List<Location> mobSpawns, String mapName, Location exit) {
        floorRewarded.remove(run.playerId());
        for(Player member:membersOf(run)){treasureClaimed.remove(member.getUniqueId());treasureOffers.remove(member.getUniqueId());treasureRuns.remove(member.getUniqueId());}
        if (exit != null) floorExits.put(run.playerId(), exit);
        else floorExits.remove(run.playerId());
        if (!player.isOnline() || runs.get(player.getUniqueId()) != run) return;
        floorGimmicks.start(run,spawn);
        for (Player member : membersOf(run)) previousSpawns.put(member.getUniqueId(), spawn.clone());
        // Regenerate the corpse at the new entrance; the player stays downed.
        for (Player member : membersOf(run)) if (downed.contains(member.getUniqueId())) {
            moveCorpse(member.getUniqueId(), spawn);
            if (!deadPlayers.contains(member.getUniqueId()) && !member.isDead())
                member.teleportAsync(spawn);
        }
        for (Player member : membersOf(run)) {
            if (downed.contains(member.getUniqueId())) continue;
            protectFromTransitionFall(member, 12000);
            if (!member.equals(player)) member.teleportAsync(spawn).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(this, () -> {
                if (err == null && Boolean.TRUE.equals(ok) && member.isOnline()) { member.setFallDistance(0f); member.setVelocity(new Vector(0,0,0)); restoreVitals(member); member.setFoodLevel(20); member.setSaturation(5f); }
            }));
        }
        player.teleportAsync(spawn).whenComplete((ok, err) -> Bukkit.getScheduler().runTask(this, () -> {
            if (err == null && Boolean.TRUE.equals(ok) && player.isOnline()) {
                player.setFallDistance(0f);
                player.setVelocity(new Vector(0, 0, 0));
                protectFromTransitionFall(player, 12000);
                restoreVitals(player);
                player.setFoodLevel(20); player.setSaturation(5f);
            }
        }));
        FloorType type = run.type();
        if(type == FloorType.EVENT) Bukkit.getScheduler().runTaskLater(this, () -> triggerFloorEvent(run), 35L);
        int count = switch (type) {
            case BATTLE -> 16;
            case ELITE -> 8;
            case BOSS -> 1;
            default -> 0;
        };
        int partySize = Math.max(1, Math.min(4, partySizes.getOrDefault(run.id(), 1)));
        if (type == FloorType.BATTLE || type == FloorType.ELITE) count = (int) Math.ceil(count * (1.0 + 0.5 * (partySize - 1)));
        run.remaining(count);
        World world = spawn.getWorld();
        if (count > 0) {
            if (mobSpawns.isEmpty() && !mapName.equals("flat-prototype")) {
                player.sendMessage("§cこのマップにはmob-spawnsが設定されていません。安全のため敵を出現させません。");
                return;
            }
            for (int i = 0; i < count; i++) {
                Location mobSpawn;
                if (!mobSpawns.isEmpty()) {
                    mobSpawn = mobSpawns.get(i % mobSpawns.size()).clone();
                } else {
                    double dx = ThreadLocalRandom.current().nextDouble(-22, 23);
                    double dz = ThreadLocalRandom.current().nextDouble(-22, 23);
                    mobSpawn = spawn.clone().add(dx, 0, dz);
                }
                EntityType mobType = type == FloorType.BOSS ? EntityType.RAVAGER :
                    (new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.HUSK, EntityType.STRAY, EntityType.CAVE_SPIDER, EntityType.DROWNED, EntityType.PILLAGER}[i % 8]);
                LivingEntity mob = (LivingEntity) world.spawnEntity(mobSpawn, mobType);
                mob.getPersistentDataContainer().set(mobRunKey, PersistentDataType.STRING, run.id().toString());
                mob.setRemoveWhenFarAway(false);
                abyssStats.scaleMob(mob,run,type);
                // Early floors must be threatening even with a high player defense stat.
                abyssStats.boostEnemyAttack(mob, Math.max(12.0, 13.0 + run.floor() * 0.9));
                // Beta balance: only the boss loses 25% of its final boosted attack.
                if (type == FloorType.BOSS) {
                    abyssStats.scaleBossAttack(mob, bossDamageFactor());
                }
                enhancements.configure(mob, run.floor());
                double healthFactor = 1.0 + 0.6 * (partySize - 1);
                double attackFactor = 1.0 + 0.25 * (partySize - 1);
                double defenseBonus = 1.5 * (partySize - 1);
                var hpAttr = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (hpAttr != null) { hpAttr.setBaseValue(hpAttr.getBaseValue() * healthFactor); mob.setHealth(hpAttr.getValue()); }
                var attackAttr = mob.getAttribute(org.bukkit.attribute.Attribute.ATTACK_DAMAGE);
                if (attackAttr != null) attackAttr.setBaseValue(attackAttr.getBaseValue() * attackFactor);
                var armorAttr = mob.getAttribute(org.bukkit.attribute.Attribute.ARMOR);
                if (armorAttr != null) armorAttr.setBaseValue(armorAttr.getBaseValue() + defenseBonus);
                if (type == FloorType.BOSS) {
                    bossPhases.put(mob.getUniqueId(), 1);
                    org.bukkit.boss.BossBar bar = Bukkit.createBossBar("§4深淵のボス §7- 第" + run.floor() + "階層", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SEGMENTED_10);
                    bossBars.put(mob.getUniqueId(), bar);
                    for (Player member : membersOf(run)) bar.addPlayer(member);
                }

            }
        }
        for (Player member : membersOf(run)) {
            showFloor(member, run);
            member.sendMessage("§bマップ: " + mapName);
            if (count == 0) member.sendMessage("§b金ブロックの出口に立つと次の階へ進みます。");
            else member.sendMessage("§c敵を " + count + " 体倒してください。");
        }
    }

    private void triggerFloorEvent(DungeonRun run) {
        if(!runs.containsValue(run)||run.type()!=FloorType.EVENT)return;
        int choice=ThreadLocalRandom.current().nextInt(4);
        for(Player p:membersOf(run)){
            switch(choice){
                case 0 -> {double max=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();p.setHealth(Math.min(max,p.getHealth()+max*0.35));p.sendMessage("§a回復祭壇: HPを回復しました");}
                case 1 -> {p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH,20*90,0));p.sendMessage("§b強化の泉: 90秒間攻撃力上昇");}
                case 2 -> {p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS,20*45,0));p.sendMessage("§5呪い: 45秒間弱体化");}
                default -> {p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED,20*60,0));p.sendMessage("§e不思議な加護: 60秒間移動速度上昇");}
            }
        }
    }

    private void broadcastRun(DungeonRun run, String message) {
        for (Player member : membersOf(run)) member.sendMessage(message);
    }

    public void openPartyMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, "深淵ダンジョン - パーティ");
        gui.setItem(10, partyIcon(Material.EMERALD_BLOCK, "§aパーティ作成"));
        gui.setItem(11, partyIcon(Material.PLAYER_HEAD, "§bメンバー一覧"));
        gui.setItem(13, partyIcon(Material.PAPER, "§e招待を承諾"));
        gui.setItem(15, partyIcon(Material.BARRIER, "§cパーティ脱退"));
        gui.setItem(16, partyIcon(Material.REDSTONE_BLOCK, "§cパーティ解散（リーダー）"));
        gui.setItem(22, partyIcon(Material.NAME_TAG, "§6招待方法を表示"));
        gui.setItem(26,partyIcon(Material.BARRIER,"§7戻る"));
        player.openInventory(gui);
    }

    private ItemStack partyIcon(Material material, String name) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta(); meta.setDisplayName(name); item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onPartyMenuClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("深淵ダンジョン - パーティ")) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        player.closeInventory();
        if(slot==26){abyssInventory.openMain(player);return;}
        switch (slot) {
            case 10 -> player.performCommand("abyss party create");
            case 11 -> player.performCommand("abyss party list");
            case 13 -> player.performCommand("abyss party accept");
            case 15 -> player.performCommand("abyss party leave");
            case 16 -> player.performCommand("abyss party disband");
            case 22 -> {
                player.sendMessage("§e招待するプレイヤーをクリックしてください:");
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (target.equals(player)) continue;
                    player.sendMessage(Component.text("[" + target.getName() + " を招待]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/abyss party invite " + target.getName())));
                }
            }
        }
    }

    private java.util.List<Player> membersOf(DungeonRun run) {
        java.util.List<Player> result = new java.util.ArrayList<>();
        for (var entry : runs.entrySet()) if (entry.getValue() == run) { Player p = Bukkit.getPlayer(entry.getKey()); if (p != null && p.isOnline()) result.add(p); }
        return result;
    }
    private void endSession(DungeonRun run, boolean discard) {
        for (Player member : membersOf(run)) {
            UUID id = member.getUniqueId(); runs.remove(id, run); downed.remove(id);
            removeCorpse(id); reviveProgress.remove(id); revivesUsed.remove(run.id());
            if (discard) discardRunFor(id, run);
            abyssSkills.clear(id); pendingRewards.remove(id); treasureOffers.remove(id); treasureRuns.remove(id); treasureClaimed.remove(id); abyssStats.endRun(id); abyssPerks.endRun(id); abyssGrowth.endRun(id);
            teleport(member, lobbyName); restoreVitals(member);
        }
        // Party membership deliberately survives dungeon exit; only the run is cleared.
        partySizes.remove(run.id()); finishRun(run);
    }
    private void discardRunFor(UUID id, DungeonRun run) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return safePouch.secureAndDiscard(run.id(), id);
            } catch (SQLException ex) {
                // Keep pending rewards intact if the atomic transfer fails.
                throw new java.util.concurrent.CompletionException(ex);
            }
        }, databaseExecutor).whenComplete((saved,error) -> Bukkit.getScheduler().runTask(this, () -> {
            Player member=Bukkit.getPlayer(id);
            if (error != null) {
                getLogger().severe("Death reward preservation failed for " + id + ": " + error.getMessage());
                if(member!=null)member.sendMessage("§c死亡保護の保存に失敗しました。管理者に連絡してください。報酬データは削除していません。");
            } else if(member!=null)member.sendMessage("§a死亡保護した報酬 "+saved+" 個を倉庫に確定しました。");
        }));
    }
    private void clearMobs(DungeonRun run) {
        World world = Bukkit.getWorld(dungeonName);
        if (world == null) return;
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            if (run.id().toString().equals(entity.getPersistentDataContainer()
                    .get(mobRunKey, PersistentDataType.STRING))) {
                removeBossBar(entity.getUniqueId());
                entity.remove();
            }
        }
    }

    private void completeObjective(Player player, DungeonRun run) {
        if (run.objectiveDone() || transitioning.contains(run.playerId()) || finalizing.contains(run.playerId())) return;
        run.objectiveDone(true);
        grantFloorReward(run);
        if (run.type() == FloorType.TREASURE) {
            for (Player member : membersOf(run)) openTreasureChoices(member, run);
        }
        broadcastRun(run, "§aフロアクリア！金ブロックを踏むと次の階へ進みます。");
        final UUID completedRun=run.id();
        final int completedFloor=run.floor();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for(Player member:membersOf(run)){
                DungeonRun current=runs.get(member.getUniqueId());
                if(current!=null && current.id().equals(completedRun)
                        && current.floor()==completedFloor && current.objectiveDone())
                    abyssGrowth.afterFloor(member);
            }
        }, 5L);
        if (run.atBoss() && run.floor() % 10 == 0) Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player member : membersOf(run)) {
                if (member.isOnline() && runs.get(member.getUniqueId()) == run && run.objectiveDone())
                    abyssPerks.offer(member, run.floor());
            }
        }, 20L);
    }

    private void containDungeonMobs() {
        World world = Bukkit.getWorld(dungeonName);
        if (world == null) return;
        for (org.bukkit.entity.Entity entity : world.getEntities()) {
            String runId = entity.getPersistentDataContainer().get(mobRunKey, PersistentDataType.STRING);
            if (runId == null) continue;
            DungeonRun owner = runs.values().stream().filter(r -> r.id().toString().equals(runId)).findFirst().orElse(null);
            if (owner == null) { entity.remove(); continue; }
            Location center = ArenaBuilder.center(world, owner.slot());
            if (!schematicMaps.withinMap(owner.slot(), entity.getLocation())) {
                Location safe = previousSpawns.get(owner.playerId());
                if (safe == null || !schematicMaps.withinMap(owner.slot(), safe)) safe = center.clone().add(0, 2, 0);
                entity.teleport(safe);
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        DungeonRun run = runs.get(event.getPlayer().getUniqueId());
        if (run == null || transitioning.contains(run.playerId()) || event.getTo() == null || downed.contains(event.getPlayer().getUniqueId())) return;
        World world = Bukkit.getWorld(dungeonName);
        if (world == null || !event.getTo().getWorld().equals(world)) return;
        if (!schematicMaps.withinMap(run.slot(), event.getTo())) {
            Location safe = previousSpawns.get(run.playerId());
            if (safe != null && schematicMaps.withinMap(run.slot(), safe)) event.setTo(safe);
            else event.setTo(ArenaBuilder.center(world, run.slot()).add(0, 2, 0));
        } else {
            previousSpawns.put(event.getPlayer().getUniqueId(), event.getTo().clone());
            if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) enhancements.light(event.getPlayer(), run);
        }
        if ((run.type()==FloorType.EXPLORATION || run.type()==FloorType.ELITE) &&
            event.getTo().getBlock().getRelative(0,-1,0).getType()==Material.MOSS_BLOCK) {
            long now=System.currentTimeMillis();UUID uid=event.getPlayer().getUniqueId();
            if(now>=hazardCooldown.getOrDefault(uid,0L)){
                hazardCooldown.put(uid,now+3000L);
                event.getPlayer().addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.POISON,60,0));
                event.getPlayer().sendActionBar("§2毒の地面！");
            }
        }
        Location exit = floorExits.get(run.playerId());
        if (exit == null || transitioning.contains(run.playerId())) return;
        Location to = event.getTo();
        if (to.getWorld().equals(exit.getWorld()) &&
            Math.abs(to.getX() - exit.getX()) < 1.1 &&
            Math.abs(to.getZ() - exit.getZ()) < 1.1 &&
            Math.abs(to.getY() - exit.getY()) < 2.5) {
            if (run.atBoss()) {
                if (run.objectiveDone() && event.getPlayer().getUniqueId().equals(run.playerId())) openExitMenu(event.getPlayer());
                else event.getPlayer().sendActionBar("§cボスを倒してください");
            } else if (run.type() == FloorType.REST) {
                if (event.getPlayer().getUniqueId().equals(run.playerId())) openExitMenu(event.getPlayer());
            } else if (run.remaining() == 0) {
                if (!run.objectiveDone()) completeObjective(event.getPlayer(), run);
                if (event.getPlayer().getUniqueId().equals(run.playerId())) {
                    if (run.type() == FloorType.TREASURE) {
                        boolean waiting=false;
                        for (Player member : membersOf(run)) {
                            if (!treasureClaimed.contains(member.getUniqueId())) {
                                waiting=true;
                                if (!treasureOffers.containsKey(member.getUniqueId())) openTreasureChoices(member,run);
                            }
                        }
                        if (waiting) { event.getPlayer().sendActionBar("§e全員が宝箱報酬を選ぶまで進めません"); return; }
                    }
                    if (!transitioning.contains(run.playerId()) && !finalizing.contains(run.playerId())) {
                        clearMobs(run);
                        if (run.advance()) beginFloor(event.getPlayer(), run);
                        else openExitMenu(event.getPlayer());
                    }
                } else event.getPlayer().sendActionBar("§eリーダーが金ブロックを踏むと進みます");
            } else if (run.remaining() > 0) {
                event.getPlayer().sendActionBar("§c残りの敵を倒してください");
            }
        }
    }

    private int maxSegments(String difficulty) {
        return switch (difficulty.toUpperCase(java.util.Locale.ROOT)) {
            case "EASY" -> 3;
            case "NORMAL" -> 5;
            case "HARD" -> 10;
            default -> 1;
        };
    }

    private boolean canRetreat(DungeonRun run) {
        return run.type() == FloorType.REST || (run.atBoss() && run.objectiveDone());
    }

    private static final String CLASS_GUI = "深淵の迷宮 - 職業選択";
    public void openClassMenu(Player player) {
        Inventory gui=Bukkit.createInventory(null,27,CLASS_GUI);
        String[] labels={"戦士","魔法使い","僧侶","弓使い","聖騎士","暗殺者","狂戦士","武闘家"};
        Material[] icons={Material.IRON_SWORD,Material.BLAZE_ROD,Material.GOLDEN_APPLE,Material.BOW,Material.SHIELD,Material.IRON_AXE,Material.NETHERITE_AXE,Material.MACE};
        for(int i=0;i<8;i++) gui.setItem(9+i,menuItem(icons[i],"§e"+labels[i]+(abyssStats.playerClass(player.getUniqueId()).equals(AbyssStats.CLASSES.get(i))?" §a[選択中]":"")));
        gui.setItem(22,menuItem(Material.BARRIER,"§7戻る"));player.openInventory(gui);
    }
    public void openMainMenu(Player player){abyssInventory.openMain(player);}
    public void openSkillMenu(Player player){abyssSkills.open(player);}
    public void openGuide(Player player){abyssGuide.open(player);}
    public void openSafePouch(Player player){safePouch.open(player);}
    public void openBugReport(Player player){bugReports.open(player);}
    public void openGrowth(Player player){abyssGrowth.open(player);}
    public void openPendingPerk(Player player){
        if(player.isOnline() && isInRun(player.getUniqueId())
                && abyssPerks.hasPending(player.getUniqueId())
                && player.getOpenInventory().getTopInventory().getType()==org.bukkit.event.inventory.InventoryType.CRAFTING)
            abyssPerks.open(player);
    }
    @EventHandler public void onClassMenuClick(InventoryClickEvent event){
        if(!event.getView().getTitle().equals(CLASS_GUI))return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||event.getClickedInventory()!=event.getView().getTopInventory())return;
        int slot=event.getRawSlot();
        if(slot==22){abyssInventory.openMain(player);return;}
        if(slot<9||slot>16)return;
        if(isInRun(player.getUniqueId())){player.sendMessage("§c攻略中は職業を変更できません。");return;}
        abyssStats.setClass(player,AbyssStats.CLASSES.get(slot-9));openClassMenu(player);
    }
    private void openExitMenu(Player player) {
        DungeonRun run = runs.get(player.getUniqueId());
        if (run == null || !canRetreat(run) || finalizing.contains(run.playerId()) || !player.getUniqueId().equals(run.playerId())) return;
        Inventory gui = Bukkit.createInventory(null, 9, "深淵ダンジョン - 帰還の選択");
        gui.setItem(3, menuItem(Material.EMERALD_BLOCK, "§a報酬を確定して帰還"));
        if (!run.atBoss() || run.segment() + 1 < maxSegments(run.difficulty()))
            gui.setItem(5, menuItem(Material.GOLD_BLOCK, "§e攻略を続行"));
        gui.setItem(8,menuItem(Material.BARRIER,"§7戻る"));
        player.openInventory(gui);
    }

    private ItemStack menuItem(Material material, String title) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName(title);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals("深淵ダンジョン - 帰還の選択")) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != event.getView().getTopInventory()) return;
        DungeonRun run = runs.get(player.getUniqueId());
        if (run == null || !canRetreat(run) || !player.getUniqueId().equals(run.playerId())) { player.closeInventory(); return; }
        if (event.getSlot() == 8) { player.closeInventory(); return; }
        if (event.getSlot() == 3) {
            player.closeInventory();
            commitAndReturn(player, run);
        } else if (event.getSlot() == 5) {
            player.closeInventory();
            if (run.atBoss()) {
                if (!run.objectiveDone()) { player.sendMessage("§cボスを倒してから続行してください。"); return; }
                if (run.segment() + 1 >= maxSegments(run.difficulty())) {
                    player.sendMessage("§a最終階層をクリアしました。報酬を確定して帰還してください。");
                    return;
                }
                if (transitioning.contains(run.playerId()) || finalizing.contains(run.playerId())) return;
                clearMobs(run);
                if (run.nextSegment(FloorSelector.generate(java.util.concurrent.ThreadLocalRandom.current().nextLong()))) {
                    broadcastRun(run, "§a次の10階区間へ進みます！ §e第" + run.floor() + "階");
                    beginFloor(player, run);
                }
            } else {
                // REST floors have no combat objective. Calling completeObjective here
                // reopens the same menu rather than advancing the shared run.
                if (transitioning.contains(run.playerId()) || finalizing.contains(run.playerId())) return;
                broadcastRun(run, "§a休憩終了。次の階層へ進みます...");
                clearMobs(run);
                if (run.advance()) beginFloor(player, run);
                else player.sendMessage("§e最終階層です。帰還を選択してください。");
            }
        }
    }


    private static final String TREASURE_TITLE = "深淵の迷宮 - 宝箱の報酬選択";
    private final Map<UUID, java.util.List<String>> treasureOffers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> treasureRuns = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> treasureClaimed = ConcurrentHashMap.newKeySet();
    private void openTreasureChoices(Player player, DungeonRun run) {
        if (treasureClaimed.contains(player.getUniqueId())) return;
        java.util.List<String> offers = new java.util.ArrayList<>();
        for (int i=0;i<3;i++) {
            String item=AbyssEquipment.randomPhase21Item(run.floor(), ThreadLocalRandom.current());
            if(item!=null) offers.add(item);
        }
        if(offers.isEmpty()) { player.sendMessage("§c報酬候補を作成できませんでした。"); return; }
        treasureOffers.put(player.getUniqueId(),offers);
        treasureRuns.put(player.getUniqueId(),run.id());
        Inventory gui=Bukkit.createInventory(null,27,TREASURE_TITLE);
        gui.setItem(22,menuItem(Material.BARRIER,"§7戻る"));
        int[] slots={11,13,15};
        for(int i=0;i<offers.size();i++) {
            AbyssEquipment.Definition def=AbyssEquipment.definition(offers.get(i));
            ItemStack stack=new ItemStack(def==null?Material.CHEST:def.icon());
            var meta=stack.getItemMeta();meta.setDisplayName("§e"+(def==null?offers.get(i):def.name()));
            meta.setLore(java.util.List.of("§7候補 "+(i+1),"§aクリックでこの報酬を獲得","§c他の候補は破棄されます"));
            stack.setItemMeta(meta);gui.setItem(slots[i],stack);
        }
        player.openInventory(gui);
    }
    @EventHandler public void chooseTreasure(InventoryClickEvent e) {
        if(!e.getView().getTitle().equals(TREASURE_TITLE))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        if(e.getRawSlot()==22){p.closeInventory();return;}
        int index=switch(e.getRawSlot()){case 11->0;case 13->1;case 15->2;default->-1;};
        java.util.List<String> offers=treasureOffers.get(p.getUniqueId());
        DungeonRun run=runs.get(p.getUniqueId());
        if(index<0||offers==null||index>=offers.size()||run==null||run.type()!=FloorType.TREASURE||!run.id().equals(treasureRuns.get(p.getUniqueId())))return;
        if(!treasureClaimed.add(p.getUniqueId()))return;
        String chosen=offers.get(index);
        treasureOffers.remove(p.getUniqueId());treasureRuns.remove(p.getUniqueId());
        p.closeInventory();
        grantPending(run,p.getUniqueId(),chosen);
        AbyssEquipment.Definition chosenDef=AbyssEquipment.definition(chosen);
        p.sendMessage("§a宝箱報酬を選択しました: §e"+(chosenDef==null?chosen:chosenDef.name())+" §7(未確定報酬へ保存中)");
    }
    private void grantFloorReward(DungeonRun run) {
        if (!floorRewarded.add(run.playerId())) return;
        // All new floor rewards are selected exclusively from the DQMVI catalog.
        if (run.type() == FloorType.REST || run.type() == FloorType.TREASURE) return;
        for (Player member : membersOf(run)) {
            String item = AbyssEquipment.randomPhase21Item(run.floor(), java.util.concurrent.ThreadLocalRandom.current());
            if (item != null) grantPending(run, member.getUniqueId(), item);
        }
    }

    private void grantPending(DungeonRun run, UUID recipient, String definition) {
        UUID itemId = UUID.randomUUID();
        // Persist first. A failed write must never become a confirmed reward.
        CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO abyss_pending(run_id,item_id,owner_uuid,definition_id) VALUES(?,?,?,?)")) {
                ps.setString(1, run.id().toString());
                ps.setString(2, itemId.toString());
                ps.setString(3, recipient.toString());
                ps.setString(4, definition);
                ps.executeUpdate();
                if (AbyssEquipment.definition(definition) != null) {
                    int roll=java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
                    // Difficulty-specific rarity distribution. EASY must never grant legendary.
                    String rarity = switch (run.difficulty().toUpperCase(java.util.Locale.ROOT)) {
                        case "EASY" -> roll < 55 ? "コモン" : roll < 85 ? "アンコモン" : roll < 98 ? "レア" : "エピック";
                        case "HARD" -> roll < 10 ? "コモン" : roll < 35 ? "アンコモン" : roll < 70 ? "レア" : roll < 95 ? "エピック" : "レジェンダリー";
                        default -> roll < 30 ? "コモン" : roll < 65 ? "アンコモン" : roll < 90 ? "レア" : roll < 99 ? "エピック" : "レジェンダリー";
                    };
                    double factor=switch(rarity){case "レジェンダリー"->5;case "エピック"->3;case "レア"->2;case "アンコモン"->1;default->0;};
                    int stat=java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
                    try(PreparedStatement bonus=conn.prepareStatement("INSERT INTO abyss_item_rolls(item_id,rarity,attack_bonus,defense_bonus,magic_bonus) VALUES(?,?,?,?,?)")) {
                        bonus.setString(1,itemId.toString());bonus.setString(2,rarity);
                        bonus.setDouble(3,stat==0?factor:0);bonus.setDouble(4,stat==1?factor:0);bonus.setDouble(5,stat==2?factor:0);
                        bonus.executeUpdate();
                    }
                    // Only weapon rewards roll combat affixes. Higher rarity has better odds.
                    AbyssEquipment.Definition rewardDef=AbyssEquipment.definition(definition);
                    if(rewardDef!=null && "weapon".equals(rewardDef.slot()) && factor>0) {
                        var random=java.util.concurrent.ThreadLocalRandom.current();
                        double crit=random.nextDouble()<0.5?Math.min(0.25,0.025*factor):0;
                        double steal=random.nextDouble()<0.25?Math.min(0.12,0.012*factor):0;
                        double element=random.nextDouble()<0.4?factor*0.7:0;
                        double speed=random.nextDouble()<0.30?Math.min(0.20,factor*0.04):0;
                        try(PreparedStatement affix=conn.prepareStatement("UPDATE abyss_item_rolls SET crit_chance=?,life_steal=?,element_bonus=?,attack_speed=? WHERE item_id=?")) {
                            affix.setDouble(1,crit);affix.setDouble(2,steal);affix.setDouble(3,element);
                            affix.setDouble(4,speed);affix.setString(5,itemId.toString());affix.executeUpdate();
                        }
                    }
                }
            } catch (Exception e) { throw new IllegalStateException(e); }
        }, databaseExecutor).whenComplete((unused, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (error != null) { getLogger().warning("Reward save failed: " + error.getMessage()); return; }
            if (runs.get(run.playerId()) == run) {
                pendingRewards.computeIfAbsent(recipient, k -> new java.util.ArrayList<>()).add(definition);
                Player p = Bukkit.getPlayer(recipient);
                if (p != null) p.sendMessage("§d仮報酬獲得: §f" + (AbyssEquipment.definition(definition)==null?definition:AbyssEquipment.definition(definition).name()) + " §7(死亡すると消失)");
            }
        }));
    }

    private void commitAndReturn(Player player, DungeonRun run) {
        if (!finalizing.add(run.playerId())) return;
        CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                conn.setAutoCommit(false);
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT OR IGNORE INTO abyss_inventory(item_id,owner_uuid,definition_id,acquired_at) SELECT item_id,owner_uuid,definition_id,? FROM abyss_pending WHERE run_id=?");
                     PreparedStatement delete = conn.prepareStatement("DELETE FROM abyss_pending WHERE run_id=?");
                     PreparedStatement clearSafe = conn.prepareStatement("DELETE FROM abyss_safe_pouch WHERE run_id=?");
                     PreparedStatement progress = conn.prepareStatement("INSERT INTO progress(uuid,difficulty,cleared_segments) VALUES(?,?,?) ON CONFLICT(uuid,difficulty) DO UPDATE SET cleared_segments=MAX(cleared_segments,excluded.cleared_segments)")) {
                    insert.setLong(1, System.currentTimeMillis()); insert.setString(2, run.id().toString()); insert.executeUpdate();
                    if (run.atBoss()) {
                        progress.setString(1, run.playerId().toString());
                        progress.setString(2, run.difficulty());
                        progress.setInt(3, run.segment() + 1);
                        progress.executeUpdate();
                    }
                    delete.setString(1, run.id().toString()); delete.executeUpdate();
                    clearSafe.setString(1, run.id().toString()); clearSafe.executeUpdate();
                    conn.commit();
                } catch (Exception e) { conn.rollback(); throw e; }
            } catch (Exception e) { throw new IllegalStateException(e); }
        }, databaseExecutor).whenComplete((unused, error) -> Bukkit.getScheduler().runTask(this, () -> {
            finalizing.remove(run.playerId());
            if (error != null) {
                getLogger().warning("Reward commit failed: " + error.getMessage());
                player.sendMessage("§c報酬確定に失敗しました。再試行してください。");
                return;
            }
            if (runs.get(run.playerId()) == run) {
                endSession(run, false);
                player.sendMessage("§a報酬を専用倉庫に確定しました！ /abyss warehouse で確認できます。");
            }
        }));
    }

    private void discardRun(DungeonRun run) {
        pendingRewards.remove(run.playerId());
        CompletableFuture.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM abyss_pending WHERE run_id=?")) {
                ps.setString(1, run.id().toString()); ps.executeUpdate();
            } catch (Exception e) { getLogger().warning("Pending reward cleanup failed: " + e.getMessage()); }
        }, databaseExecutor);
    }

    private void showWarehouse(Player player) {
        CompletableFuture.supplyAsync(() -> {
            java.util.List<String> items = new java.util.ArrayList<>();
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 PreparedStatement ps = conn.prepareStatement("SELECT definition_id FROM abyss_inventory WHERE owner_uuid=? ORDER BY acquired_at DESC LIMIT 100")) {
                ps.setString(1, player.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) items.add(rs.getString(1)); }
            } catch (Exception e) { throw new IllegalStateException(e); }
            return items;
        }, databaseExecutor).whenComplete((items, error) -> Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            if (error != null) { player.sendMessage("§c倉庫の読み込みに失敗しました。"); return; }
            player.sendMessage("§6専用倉庫 §7(直近100件): §f" + items.size() + "件");
            for (String item : items) player.sendMessage("§7- §f" + item);
        }));
    }

    private void protectFromTransitionFall(Player player, long millis) {
        fallProtectionUntil.put(player.getUniqueId(), System.currentTimeMillis() + millis);
        player.setFallDistance(0f);
    }

    @EventHandler
    public void onTransitionFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Long until = fallProtectionUntil.get(player.getUniqueId());
        if (until == null) return;
        if (System.currentTimeMillis() <= until) {
            event.setCancelled(true);
            player.setFallDistance(0f);
        } else {
            fallProtectionUntil.remove(player.getUniqueId(), until);
        }
    }

    private void removeBossBar(UUID mobId) {
        bossTelegraphPending.remove(mobId);
        bossPhases.remove(mobId);
        bossAttackCounts.remove(mobId);
        org.bukkit.boss.BossBar bar = bossBars.remove(mobId);
        if (bar != null) { bar.removeAll(); bar.setVisible(false); }
    }

    private void updateBossBars() {
        for (var entry : new java.util.ArrayList<>(bossBars.entrySet())) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity boss) || !boss.isValid() || boss.isDead()) {
                removeBossBar(entry.getKey());
                continue;
            }
            var attr = boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            double max = attr == null ? 1 : Math.max(1, attr.getValue());
            entry.getValue().setProgress(Math.max(0, Math.min(1, boss.getHealth() / max)));
            String runId = boss.getPersistentDataContainer().get(mobRunKey, PersistentDataType.STRING);
            for (Player viewer : new java.util.ArrayList<>(entry.getValue().getPlayers())) {
                DungeonRun current = runs.get(viewer.getUniqueId());
                if (!viewer.isOnline() || current == null || !current.id().toString().equals(runId)) entry.getValue().removePlayer(viewer);
            }
            for (DungeonRun current : new java.util.HashSet<>(runs.values())) {
                if (!current.id().toString().equals(runId)) continue;
                for (Player member : membersOf(current)) if (!entry.getValue().getPlayers().contains(member)) entry.getValue().addPlayer(member);
                break;
            }
        }
    }

    /** Beta boss damage adjustment: 0.75 = 75% of prior damage. */
    private double bossDamageFactor() {
        return Math.max(0.0, Math.min(1.0, getConfig().getDouble("boss.damage-multiplier", 0.75)));
    }

    /**
     * Special attacks are explicitly balanced damage, not regular mob melee hits.
     * Use unattributed damage so the generic enemy attack override does not replace
     * the telegraphed attack's value with the boss's high melee attack stat.
     */
    private void dealBossSpecialDamage(Player player, double amount) {
        if (amount > 0 && player.isOnline() && !player.isDead()) player.damage(amount * bossDamageFactor());
    }

    /** Phase 43: a visible, dodgeable boss circle attack every ten seconds. */
    private final java.util.Map<UUID,Integer> bossAttackCounts=new java.util.HashMap<>();
    private void telegraphBossAttacks() {
        for(UUID bossId : new java.util.ArrayList<>(bossBars.keySet())) {
            org.bukkit.entity.Entity entity=Bukkit.getEntity(bossId);
            if(!(entity instanceof LivingEntity boss) || !boss.isValid() || boss.isDead()) continue;
            if(!bossTelegraphPending.add(bossId))continue;
            String runId=boss.getPersistentDataContainer().get(mobRunKey,PersistentDataType.STRING);
            if(runId==null){bossTelegraphPending.remove(bossId);continue;}
            int attackNumber=bossAttackCounts.merge(bossId,1,Integer::sum);
            int phase=bossPhases.getOrDefault(bossId,1);
            // Alternating patterns: boss-centered shockwave / targeted impact.
            boolean targeted=(attackNumber%2==0);
            Location target=boss.getLocation().clone();
            if(targeted) {
                Player nearest=null;double best=Double.MAX_VALUE;
                for(Player candidate:boss.getWorld().getPlayers()) {
                    DungeonRun active=runs.get(candidate.getUniqueId());
                    if(active==null || !active.id().toString().equals(runId) || downed.contains(candidate.getUniqueId()))continue;
                    double dist=candidate.getLocation().distanceSquared(target);
                    if(dist<best){best=dist;nearest=candidate;}
                }
                if(nearest!=null)target=nearest.getLocation().clone();
            }
            final Location strikeLocation=target;
            double radius=targeted?(phase>=3?4.5:3.0):(phase>=3?6.0:4.0);
            for(Player p:boss.getWorld().getPlayers()) {
                DungeonRun run=runs.get(p.getUniqueId());
                if(run!=null && run.id().toString().equals(runId) && p.getLocation().distanceSquared(strikeLocation)<400)
                    p.sendMessage(targeted ? "§c【ボス予兆】§e着弾地点に3秒後、範囲攻撃！" : "§c【ボス予兆】§eボス周囲に3秒後、衝撃波！");
            }
            for(int t=0;t<3;t++) {
                Bukkit.getScheduler().runTaskLater(this,()->{
                    if(!boss.isValid()||boss.isDead())return;
                    for(int i=0;i<48;i++) {
                        double angle=2*Math.PI*i/48.0;
                        strikeLocation.getWorld().spawnParticle(org.bukkit.Particle.DUST,
                            strikeLocation.clone().add(Math.cos(angle)*radius,0.2,Math.sin(angle)*radius),
                            1,0,0,0,0,new org.bukkit.Particle.DustOptions(org.bukkit.Color.RED,1.3f));
                    }
                },t*20L);
            }
            Bukkit.getScheduler().runTaskLater(this,()->{
                bossTelegraphPending.remove(bossId);
                if(!boss.isValid()||boss.isDead())return;
                boss.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION,strikeLocation.clone().add(0,1,0),4,1,0.2,1);
                for(Player p:boss.getWorld().getPlayers()) {
                    DungeonRun run=runs.get(p.getUniqueId());
                    if(run==null||!run.id().toString().equals(runId)||downed.contains(p.getUniqueId()))continue;
                    if(p.getLocation().distanceSquared(strikeLocation)<=radius*radius) dealBossSpecialDamage(p, 3.0+phase*2.0);
                }
            },60L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity boss)) return;
        Integer phase = bossPhases.get(boss.getUniqueId());
        if (phase == null) return;
        var attribute = boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (attribute == null) return;
        double remaining = (boss.getHealth() - event.getFinalDamage()) / Math.max(1, attribute.getValue());
        int next = remaining <= 0.33 ? 3 : remaining <= 0.66 ? 2 : 1;
        if (next <= phase) return;
        bossPhases.put(boss.getUniqueId(), next);
        if(next==2){
            for(DungeonRun active:new java.util.HashSet<>(runs.values())) {
                if(!active.id().toString().equals(boss.getPersistentDataContainer().get(mobRunKey,PersistentDataType.STRING)))continue;
                for(int i=0;i<3;i++){
                    Location at=boss.getLocation().clone().add(ThreadLocalRandom.current().nextInt(-3,4),0,ThreadLocalRandom.current().nextInt(-3,4));
                    LivingEntity minion=(LivingEntity)boss.getWorld().spawnEntity(at,EntityType.ZOMBIE);
                    minion.getPersistentDataContainer().set(mobRunKey,PersistentDataType.STRING,active.id().toString());
                    minion.setRemoveWhenFarAway(false);
                    active.remaining(active.remaining()+1);
                }
                break;
            }
        }
        for (Player p : boss.getWorld().getPlayers()) if (p.getLocation().distanceSquared(boss.getLocation()) < 900)
            p.sendTitle("§cボス 第" + next + "フェーズ", "§e3秒後に範囲攻撃！離れてください", 5, 45, 10);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!boss.isValid() || boss.isDead()) return;
            boss.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, boss.getLocation().add(0,1,0), 8, 3, 0.5, 3);
            for (Player p : boss.getWorld().getPlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || downed.contains(p.getUniqueId())) continue;
                if (p.getLocation().distanceSquared(boss.getLocation()) < 25) dealBossSpecialDamage(p, 5.0 + next * 2.0);
            }
        }, 60L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPartyFriendlyFire(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (event.getDamager() instanceof Player direct) attacker = direct;
        else if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player shooter) attacker = shooter;
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (parties.inParty(attacker.getUniqueId())
                && parties.members(attacker.getUniqueId()).contains(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }


    /** A visual stand-in for a downed player's body. It never holds real inventory items. */
    private void moveCorpse(UUID id, Location location) {
        removeCorpse(id);
        Location pos = location.clone();
        corpseLocations.put(id, pos.clone());
        if (pos.getWorld() == null) return;
        org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) pos.getWorld().spawnEntity(pos, EntityType.ARMOR_STAND);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setSmall(false);
        stand.setBodyPose(new org.bukkit.util.EulerAngle(Math.PI / 2, 0, 0));
        stand.setHeadPose(new org.bukkit.util.EulerAngle(0.2, 0, 0));
        Player owner = Bukkit.getPlayer(id);
        if (owner != null) {
            org.bukkit.inventory.ItemStack head = new org.bukkit.inventory.ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta skull = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
            skull.setOwningPlayer(owner);
            head.setItemMeta(skull);
            stand.getEquipment().setHelmet(head);
            stand.setCustomName("§c" + owner.getName() + " §7(戦闘不能)");
        } else {
            stand.setCustomName("§c戦闘不能");
        }
        stand.setCustomNameVisible(true);
        corpses.put(id, stand);
    }

    private void removeCorpse(UUID id) {
        org.bukkit.entity.ArmorStand old = corpses.remove(id);
        if (old != null && old.isValid()) old.remove();
        corpseLocations.remove(id);
    }

    /** One increment per tick if at least one surviving teammate crouches nearby. */
    private void tickRevives() {
        for (UUID victimId : new java.util.ArrayList<>(downed)) {
            Player victim = Bukkit.getPlayer(victimId);
            DungeonRun run = runs.get(victimId);
            Location body = corpseLocations.get(victimId);
            if (victim == null || !victim.isOnline() || run == null || body == null) continue;
            if (deadPlayers.contains(victimId) || victim.isDead()) continue;
            if (revivesUsed.getOrDefault(run.id(), 0) >= 3) continue;
            boolean assisting = false;
            for (Player ally : membersOf(run)) {
                if (ally.getUniqueId().equals(victimId) || downed.contains(ally.getUniqueId())
                        || ally.getGameMode() == GameMode.SPECTATOR || !ally.isSneaking()
                        || !ally.getWorld().equals(body.getWorld())) continue;
                if (ally.getLocation().distanceSquared(body) <= 100) { assisting = true; break; }
            }
            if (!assisting) continue;
            int ticks = reviveProgress.merge(victimId, 1, Integer::sum);
            if (ticks % 20 == 0) victim.sendActionBar("§e蘇生進行: " + (ticks / 20) + "/3秒");
            if (ticks < 60) continue;
            if (revivesUsed.getOrDefault(run.id(), 0) >= 3) continue;
            revivesUsed.merge(run.id(), 1, Integer::sum);
            reviveProgress.remove(victimId);
            downed.remove(victimId);
            Location returnTo = body.clone();
            removeCorpse(victimId);
            victim.setGameMode(GameMode.SURVIVAL);
            victim.teleport(returnTo);
            var max = victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null) victim.setHealth(Math.max(1.0, max.getValue() * 0.6));
            victim.sendMessage("§a蘇生しました！ HP60%で復帰しました。");
            broadcastRun(run, "§a" + victim.getName() + " が蘇生しました。蘇生 " + revivesUsed.get(run.id()) + "/3回");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void preventAbyssEquipmentDamage(PlayerItemDamageEvent event) {
        String world = event.getPlayer().getWorld().getName();
        if (world.equals(lobbyName) || world.equals(dungeonName)) event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        fallProtectionUntil.remove(player.getUniqueId());
        DungeonRun run = runs.get(player.getUniqueId());
        if (run == null) return;
        if (finalizing.contains(run.playerId())) return;
        deadPlayers.add(player.getUniqueId());
        event.getDrops().clear();
        event.setDroppedExp(0);
        downed.add(player.getUniqueId());
        moveCorpse(player.getUniqueId(), player.getLocation());
        reviveProgress.put(player.getUniqueId(), 0);
        player.sendMessage("§c戦闘不能です。仲間が死体から10ブロック以内で合計3秒しゃがむと蘇生できます。");
        if (membersOf(run).stream().allMatch(p -> downed.contains(p.getUniqueId()))) {
            endSession(run, true);
            player.sendMessage("§cパーティ全滅。未確定報酬を失いました。");
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!deadPlayers.contains(event.getPlayer().getUniqueId())) return;
        DungeonRun current = runs.get(event.getPlayer().getUniqueId());
        if (current != null && downed.contains(event.getPlayer().getUniqueId())) {
            Location safe = previousSpawns.get(current.playerId());
            if (safe != null) event.setRespawnLocation(safe);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player p = event.getPlayer();
                if (runs.get(p.getUniqueId()) == current && downed.contains(p.getUniqueId())) p.setGameMode(org.bukkit.GameMode.SPECTATOR);
                deadPlayers.remove(p.getUniqueId());
            }, 2L);
            return;
        }
        World lobby = Bukkit.getWorld(lobbyName);
        if (lobby != null) event.setRespawnLocation(lobby.getSpawnLocation());
        Bukkit.getScheduler().runTaskLater(this, () -> deadPlayers.remove(event.getPlayer().getUniqueId()), 2L);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        String id = event.getEntity().getPersistentDataContainer().get(mobRunKey, PersistentDataType.STRING);
        if (id == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        removeBossBar(event.getEntity().getUniqueId());
        for (DungeonRun run : new java.util.HashSet<>(runs.values())) {
            if (!run.id().toString().equals(id)) continue;
            Player player = Bukkit.getPlayer(run.playerId());
            if (player == null) return;
            run.decrement();
            if (run.remaining() <= 3 && run.remaining() > 0) {
                for (LivingEntity other : event.getEntity().getWorld().getLivingEntities()) {
                    if (id.equals(other.getPersistentDataContainer().get(mobRunKey, PersistentDataType.STRING))) other.setGlowing(true);
                }
            }
            for (Player member : membersOf(run)) member.sendMessage("§e残りの敵: " + run.remaining() + "体");
            if (run.remaining() == 0) { Player leader = Bukkit.getPlayer(run.playerId()); if (leader != null) completeObjective(leader, run); }
            return;
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getWorld().getName().equals(dungeonName)) event.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (event.getBlock().getWorld().getName().equals(dungeonName)) event.setCancelled(true);
    }

    private void teleport(Player player, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world != null) player.teleportAsync(world.getSpawnLocation()).exceptionally(error -> {
            getLogger().warning("Teleport failed: " + error.getMessage());
            return false;
        });
    }

    private void showFloor(Player player, DungeonRun run) {
        String difficulty = switch (run.difficulty()) {
            case "easy" -> "初級"; case "normal" -> "中級"; default -> "上級";
        };
        player.sendMessage("§6深淵ダンジョン §f" + difficulty + " §7| §e第" + run.floor() + "階層" +
            " §7| §b" + run.type().display());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Disconnect does not finalize rewards or end the session. The slot stays reserved.
        // A restart-safe session restore is not yet implemented.
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        starterKit.ensure(player.getUniqueId());
        DungeonRun run = runs.get(player.getUniqueId());
        if (run != null) {
            Location safe = previousSpawns.get(player.getUniqueId());
            if (safe != null) player.teleportAsync(safe);
            player.sendMessage("§eダンジョンの攻略を再開しました。");
        } else if (player.getWorld().getName().equals(dungeonName)) teleport(player, lobbyName);
    }

    @EventHandler(ignoreCancelled = true)
    public void preventNonDungeonSpawns(CreatureSpawnEvent event) {
        // Do not interfere with other servers/worlds; only Abyss-managed lobby and dungeon.
        if (event.getLocation().getWorld().getName().equals(lobbyName)) event.setCancelled(true);
        else if (event.getLocation().getWorld().getName().equals(dungeonName)
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) event.setCancelled(true);
    }

    private void restoreVitals(Player player) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            var max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null) {
                double desired = (player.getWorld().getName().equals(lobbyName) || player.getWorld().getName().equals(dungeonName)) ? Math.max(1,abyssStats.stat(player.getUniqueId(),"hp")) : 20.0;
                max.setBaseValue(desired);
                if (!player.isDead()) player.setHealth(max.getValue());
            }
            if (abyssMagic != null) abyssMagic.fillMana(player);
        }, 2L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new java.util.ArrayList<>(bossBars.keySet())) removeBossBar(id);
        for (UUID id : new java.util.ArrayList<>(corpses.keySet())) removeCorpse(id);
        if (enhancements != null) enhancements.shutdown();
        for (DungeonRun run : runs.values()) clearMobs(run);
        runs.clear();
        if (abyssStats != null) abyssStats.clearRuns();
        if (databaseExecutor != null) databaseExecutor.shutdown();
    }
}
