package dev.abyss;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.*;

/** Separate survival inventory and Abyss lobby/dungeon inventory; GUI items are not physical rewards. */
public final class AbyssInventory implements Listener {
    private static final String MAIN = "深淵ダンジョン - メインメニュー";
    private static final String DIFFICULTY = "深淵ダンジョン - 難易度選択";
    private static final String STORE = "深淵ダンジョン - 専用倉庫";
    private final AbyssPlugin plugin;
    private AbyssEquipment equipment;
    private AbyssMagic magic;
    public void setMagic(AbyssMagic magic) { this.magic=magic; }
    public void setEquipment(AbyssEquipment equipment) { this.equipment=equipment; }
    private final String jdbcUrl, lobby, dungeon;
    private final NamespacedKey menuKey;
    private final NamespacedKey consumableKey;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID,Integer> warehousePages = new HashMap<>();
    private final Map<UUID,String> warehouseCategories = new HashMap<>();
    private static final String[] CATEGORIES = {"weapon", "armor", "accessory", "consumable", "material"};
    private static final String[] CATEGORY_LABELS = {"武器", "防具", "アクセサリー", "消費アイテム", "素材・その他"};
    private static String category(String id) {
        AbyssEquipment.Definition d=AbyssEquipment.definition(id);
        if(d==null)return "material";
        return switch(d.slot()){case "weapon"->"weapon";case "offhand"->"accessory";case "head","chest","legs","feet"->"armor";case "accessory"->"accessory";case "consumable"->"consumable";default->"material";};
    }
    private static double strength(String id) {
        AbyssEquipment.Definition d=AbyssEquipment.definition(id);
        if(d==null)return 0;
        return switch(category(id)){case "weapon"->d.attack();case "armor"->d.defense();case "accessory"->d.attack()+d.defense()+d.health()+d.magic()+d.mana()*0.1;default->0;};
    }
    private record StoredItem(String itemId,String definitionId) {}
    private final Map<UUID,String> pendingDeletes = new HashMap<>();

    public AbyssInventory(AbyssPlugin plugin, String jdbcUrl, String lobby, String dungeon) {
        this.plugin = plugin; this.jdbcUrl = jdbcUrl; this.lobby = lobby; this.dungeon = dungeon;
        menuKey = new NamespacedKey(plugin, "navigation_item");
        consumableKey = new NamespacedKey(plugin, "consumable_slot");
        try (Connection c = DriverManager.getConnection(jdbcUrl); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS abyss_inventory_snapshots (uuid TEXT PRIMARY KEY, inventory_data TEXT NOT NULL, active INTEGER NOT NULL)");
        } catch (SQLException e) { throw new IllegalStateException("Cannot initialize inventory isolation", e); }
    }

    private boolean special(String world) { return lobby.equals(world) || dungeon.equals(world); }
    private boolean isProtected(ItemStack item) {
        return isMenu(item) || (magic != null && magic.isControl(item)) || item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(consumableKey, PersistentDataType.INTEGER);
    }
    private boolean isMenu(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.STRING);
    }
    private ItemStack item(Material type, String title, String id) {
        ItemStack stack = new ItemStack(type);
        var meta = stack.getItemMeta(); meta.setDisplayName(title);
        if (id != null) meta.getPersistentDataContainer().set(menuKey, PersistentDataType.STRING, id);
        stack.setItemMeta(meta); return stack;
    }
    private String encode(Player player) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(player.getInventory().getStorageContents());
            out.writeObject(player.getInventory().getArmorContents());
            out.writeObject(player.getInventory().getItemInOffHand());
            out.writeInt(player.getInventory().getHeldItemSlot());
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    private void restore(Player player, String data) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            ItemStack[] storage = (ItemStack[]) in.readObject();
            ItemStack[] armor = (ItemStack[]) in.readObject();
            ItemStack offhand = (ItemStack) in.readObject();
            int held = in.readInt();
            player.getInventory().clear();
            player.getInventory().setStorageContents(storage);
            player.getInventory().setArmorContents(armor);
            player.getInventory().setItemInOffHand(offhand);
            player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, held)));
        }
    }
    private record Snapshot(String data, boolean active) {}
    private Snapshot snapshot(UUID id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement p = c.prepareStatement("SELECT inventory_data, active FROM abyss_inventory_snapshots WHERE uuid=?")) {
            p.setString(1, id.toString());
            try (ResultSet r = p.executeQuery()) { return r.next() ? new Snapshot(r.getString(1), r.getInt(2) != 0) : null; }
        }
    }
    private void store(UUID id, String data, boolean active) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl);
             PreparedStatement p = c.prepareStatement("INSERT INTO abyss_inventory_snapshots(uuid,inventory_data,active) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET inventory_data=excluded.inventory_data,active=excluded.active")) {
            p.setString(1,id.toString()); p.setString(2,data); p.setInt(3,active ? 1 : 0); p.executeUpdate();
        }
    }
    private void clear(Player p) {
        p.getInventory().clear(); p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
    }
    /** Move a foreign physical item out of a reserved UI slot without destroying it. */
    private boolean freeUiSlot(Player p, int slot) {
        ItemStack current = p.getInventory().getItem(slot);
        if (current == null || current.getType().isAir() || isProtected(current)) return true;
        if (isVisualEquipment(current)) { p.getInventory().setItem(slot,null); return true; }
        // Keep UI slots free and preserve the original stack as-is.
        for (int i = 9; i < 36; i++) {
            ItemStack candidate = p.getInventory().getItem(i);
            if (candidate == null || candidate.getType().isAir()) {
                plugin.getLogger().info("[AbyssWeaponDiag] displaced player=" + p.getUniqueId()
                        + " from=" + slot + " to=" + i + " type=" + current.getType()
                        + " menuWeapon=" + isMenuWeapon(current));
                p.getInventory().setItem(i, current);
                p.getInventory().setItem(slot, null);
                return true;
            }
        }
        plugin.getLogger().warning("No free inventory slot for displaced Abyss UI item: " + p.getUniqueId() + " slot=" + slot);
        p.sendMessage("§cインベントリに空きがないため、専用メニューを配置できません。空きを作ってください。");
        return false;
    }
    /** Phase 48: equipment shown in vanilla armor slots is a tagged visual copy only. */
    private ItemStack displayEquipment(String itemId, String definitionId, String tag) {
        AbyssEquipment.Definition def=AbyssEquipment.definition(definitionId);
        if(def==null)return null;
        ItemStack display=AbyssEquipment.icon(definitionId,itemId,true);
        appendRollLore(display,itemId);
        var meta=display.getItemMeta();
        meta.getPersistentDataContainer().set(menuKey,PersistentDataType.STRING,tag);
        display.setItemMeta(meta);
        return display;
    }
    private boolean isVisualEquipment(ItemStack stack) {
        if(stack==null || !stack.hasItemMeta())return false;
        String tag=stack.getItemMeta().getPersistentDataContainer().get(menuKey,PersistentDataType.STRING);
        return "weapon".equals(tag) || "armor_display".equals(tag);
    }
    private boolean isLegacyEquippedDisplay(ItemStack stack, String itemId) {
        if (stack==null || !stack.hasItemMeta() || itemId==null) return false;
        var meta=stack.getItemMeta();
        // Only match a uniquely identified equipment display, never a generic item.
        if (!meta.hasLore()) return false;
        boolean idMatch=false, displayMarker=false;
        for (String line:meta.getLore()) {
            String plain=org.bukkit.ChatColor.stripColor(line);
            if (plain.equals("ID: "+itemId)) idMatch=true;
            if (plain.contains("クリックで装備・解除") || plain.contains("装備中の武器")) displayMarker=true;
        }
        return idMatch && displayMarker;
    }
    /** Diagnostic only: log weapon identity without modifying inventory contents. */
    private void logWeaponSlots(Player p, String stage) {
        StringBuilder detail = new StringBuilder();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = p.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            boolean tagged = isMenuWeapon(stack);
            if (slot != 4 && slot != 9 && !tagged) continue;
            String nav = stack.hasItemMeta()
                    ? stack.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING) : null;
            detail.append(" [slot=").append(slot)
                    .append(",type=").append(stack.getType())
                    .append(",amount=").append(stack.getAmount())
                    .append(",nav=").append(nav)
                    .append(",visual=").append(isVisualEquipment(stack))
                    .append(",name=").append(stack.hasItemMeta() && stack.getItemMeta().hasDisplayName()
                            ? stack.getItemMeta().getDisplayName() : "-")
                    .append("]");
        }
        plugin.getLogger().info("[AbyssWeaponDiag] player=" + p.getUniqueId()
                + " stage=" + stage + " equipped="
                + (equipment == null ? "-" : equipment.equipped(p.getUniqueId()).get("weapon"))
                + detail);
    }

    public void refreshWeapon(Player p) {
        if (!special(p.getWorld().getName()) || equipment==null)return;
        logWeaponSlots(p, "refresh-before");
        for(int slot=0;slot<36;slot++)if(slot!=4 && isMenuWeapon(p.getInventory().getItem(slot)))p.getInventory().setItem(slot,null);
        // Slot 4 is reserved exclusively for the equipped Abyss weapon in special worlds.
        // Do not displace its previous contents to slot 9: that produced a duplicate
        // weapon in the first main-inventory slot after equipment refresh.
        // Remove a legacy copy left in slot 9 by older builds only if it matches
        // the equipped weapon (identified by the plugin's item ID tag).
        // Remove only legacy *visual* copies of the currently equipped weapon.
        // Older builds may have displaced these into storage slot 9.
        String equippedId=equipment.equipped(p.getUniqueId()).get("weapon");
        for (int slot=9;slot<36;slot++) {
            ItemStack candidate=p.getInventory().getItem(slot);
            if (candidate==null || candidate.getType().isAir()) continue;
            if (isMenuWeapon(candidate) || (equippedId!=null && isLegacyEquippedDisplay(candidate,equippedId))) {
                plugin.getLogger().info("Abyss visual weapon cleanup: player="+p.getUniqueId()+" slot="+slot+" equippedId="+equippedId);
                p.getInventory().setItem(slot,null);
            }
        }
        ItemStack current=p.getInventory().getItem(4);
        if(current!=null && !current.getType().isAir() && !isMenuWeapon(current)) {
            // The old slot-4 display is replaced below, not moved into storage.
            p.getInventory().setItem(4,null);
        }
        String itemId=equipment.equipped(p.getUniqueId()).get("weapon");
        String definitionId=equipment.equippedDefinition(p.getUniqueId(),"weapon");
        // Abyss worlds never use the 27 main-storage slots. Clear stale
        // visual weapons regardless of lore formatting, item ID, or version.
        p.getInventory().setItem(4,itemId==null?null:displayEquipment(itemId,definitionId,"weapon"));
        refreshArmor(p);
        logWeaponSlots(p, "refresh-after");
    }
    public void refreshArmor(Player p) {
        if (!special(p.getWorld().getName()) || equipment==null)return;
        Map<String,String> eq=equipment.equipped(p.getUniqueId());
        String[] slots={"feet","legs","chest","head"};
        ItemStack[] current=p.getInventory().getArmorContents();
        for(int i=0;i<4;i++) {
            if(current[i]!=null && !current[i].getType().isAir() && !isVisualEquipment(current[i]))continue;
            String itemId=eq.get(slots[i]);
            current[i]=itemId==null?null:displayEquipment(itemId,equipment.equippedDefinition(p.getUniqueId(),slots[i]),"armor_display");
        }
        p.getInventory().setArmorContents(current);
    }
    public boolean isMenuWeapon(ItemStack stack) {
        return stack!=null && stack.hasItemMeta() && "weapon".equals(
                stack.getItemMeta().getPersistentDataContainer().get(menuKey,PersistentDataType.STRING));
    }
    private void grant(Player p) {
        if (!special(p.getWorld().getName())) return;
        refreshWeapon(p);
        if (freeUiSlot(p, 7)) p.getInventory().setItem(7, item(Material.ENDER_CHEST,"§b深淵の倉庫 §7(右クリック)","warehouse"));
        if (freeUiSlot(p, 8)) p.getInventory().setItem(8, item(Material.NETHER_STAR,"§6深淵の案内書 §7(右クリック)","main"));
        refreshConsumables(p);
    }
    private void switchToAbyss(Player p) throws Exception {
        Snapshot existing = snapshot(p.getUniqueId());
        if (existing != null && existing.active()) { grant(p); return; }
        // Never persist generated Abyss weapons in the normal-world snapshot.
        for(int slot=0;slot<36;slot++) {
            if(isMenuWeapon(p.getInventory().getItem(slot))) p.getInventory().setItem(slot,null);
        }
        // Write snapshot before touching the player's inventory.
        store(p.getUniqueId(), encode(p), true);
        clear(p); grant(p);
    }
    private void switchToNormal(Player p) throws Exception {
        Snapshot existing = snapshot(p.getUniqueId());
        if (existing == null || !existing.active()) return;
        // Keep snapshot active until restoration is successful.
        // Remove temporary Abyss items before restoring the normal inventory.
        clear(p);
        restore(p, existing.data());
        store(p.getUniqueId(), existing.data(), false);
    }
    private void sync(Player p, boolean targetSpecial) {
        if (!busy.add(p.getUniqueId())) return;
        try { if (targetSpecial) switchToAbyss(p); else switchToNormal(p); }
        catch (Exception e) { plugin.getLogger().severe("Inventory isolation failed for " + p.getUniqueId() + ": " + e); p.sendMessage("§cインベントリの切り替えに失敗しました。管理者へ連絡してください。"); }
        finally { busy.remove(p.getUniqueId()); }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) { sync(e.getPlayer(), special(e.getPlayer().getWorld().getName())); }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) sync(p, special(p.getWorld().getName())); });
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) sync(p, special(p.getWorld().getName())); }, 2L);
    }
    @EventHandler
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!special(p.getWorld().getName()) || !e.getAction().isRightClick()) return;
        ItemStack held = e.getItem();
        // The equipped shield is a protected Abyss control item.  Cancelling either
        // the main-hand weapon click or the off-hand shield click prevents vanilla
        // Minecraft from entering its blocking animation.  Keep inventory protection
        // (click/drop/swap) but allow the native shield use interaction.
        ItemStack offhand = p.getInventory().getItemInOffHand();
        boolean shieldEquipped = offhand != null && offhand.getType() == Material.SHIELD
                && magic != null && magic.isControl(offhand);
        if (shieldEquipped && magic != null && magic.isControl(held)
                && held.getType() != Material.ENDER_CHEST
                && held.getType() != Material.NETHER_STAR
                && !held.getItemMeta().getPersistentDataContainer().has(consumableKey, PersistentDataType.INTEGER)) {
            return;
        }
        if (isMenuWeapon(held)) return; // allow native bow draw and weapon interactions
        if (!isProtected(held)) return;
        if (held.getItemMeta().getPersistentDataContainer().has(consumableKey, PersistentDataType.INTEGER)) {
            e.setCancelled(true);
            if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) useConsumable(p, held);
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        String action = held.getItemMeta().getPersistentDataContainer().get(menuKey, PersistentDataType.STRING);
        if ("main".equals(action)) openMain(p);
        else if ("warehouse".equals(action)) openWarehouse(p);
    }
    public void openMain(Player p) {
        if (!special(p.getWorld().getName())) { p.sendMessage("§c専用ワールドでのみ使用できます。"); return; }
        Inventory gui = Bukkit.createInventory(null, 54, MAIN);
        gui.setItem(10, item(Material.IRON_SWORD,"§eダンジョン入場 §7(/abyss start)",null));
        gui.setItem(12, item(Material.ENDER_CHEST,"§b専用倉庫",null));
        gui.setItem(14, item(Material.SHIELD,"§a装備管理",null));
        gui.setItem(16, item(Material.ENCHANTED_BOOK,"§d魔法管理",null));
        gui.setItem(26, item(Material.PLAYER_HEAD,"§bパーティ管理",null));
        gui.setItem(28, item(Material.POTION,"§d消費アイテム設定 §7(装備管理へ)",null));
        gui.setItem(30, item(Material.PAPER,"§e未確定報酬 §7(/abyss bag)",null));
        gui.setItem(32, item(Material.GOLD_BLOCK,"§6撤退・報酬確定 §7(/abyss retreat)",null));
        gui.setItem(36, item(Material.VILLAGER_SPAWN_EGG,"§a職業選択",null));
        gui.setItem(38, item(Material.NETHER_STAR,"§6職業スキル",null));
        int points=plugin.growth()==null?0:plugin.growth().pending(p.getUniqueId());
        gui.setItem(40, item(Material.EXPERIENCE_BOTTLE,"§a成長・強化 §7(未使用 "+points+" 回)",null));
        gui.setItem(41, item(Material.ENDER_EYE,"§b死亡保護スロット §7(最大3個)",null));
        gui.setItem(42, item(Material.BOOK,"§b冒険ガイド §7(仕様・遊び方)",null));
        gui.setItem(44, item(Material.PAPER,"§c不具合を報告 §7(Discordへ送信)",null));
        gui.setItem(34, item(Material.COMPASS,"§bロビーへ戻る §7(/abyss lobby)",null));
        p.openInventory(gui);
    }
    public void openWarehouse(Player p) { openWarehouse(p, warehousePages.getOrDefault(p.getUniqueId(), 0)); }
    private void chooseCategory(Player p,String category) {
        warehouseCategories.put(p.getUniqueId(),category);
        openWarehouse(p,0);
    }
    public void openWarehouse(Player p, int page) {
        if (!special(p.getWorld().getName())) { p.sendMessage("§c専用ワールドでのみ使用できます。"); return; }
        String selected=warehouseCategories.getOrDefault(p.getUniqueId(),"weapon");
        List<StoredItem> matches=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(jdbcUrl);
            PreparedStatement ps=c.prepareStatement("SELECT item_id,definition_id FROM abyss_inventory WHERE owner_uuid=?")) {
            ps.setString(1,p.getUniqueId().toString());
            try(ResultSet rs=ps.executeQuery()) {
                while(rs.next()) {
                    String id=rs.getString(2);
                    if(category(id).equals(selected)) matches.add(new StoredItem(rs.getString(1),id));
                }
            }
        }catch(SQLException ex){p.sendMessage("§c倉庫の読み込みに失敗しました。");plugin.getLogger().warning("Warehouse GUI: "+ex);return;}
        // Stable descending strength sort. Item UUID is a deterministic tie-breaker.
        matches.sort(Comparator.comparingDouble((StoredItem i)->strength(i.definitionId())).reversed()
            .thenComparing(StoredItem::definitionId).thenComparing(StoredItem::itemId));
        int count=matches.size();
        int maxPage=Math.max(0,(count-1)/45);
        page=Math.max(0,Math.min(page,maxPage));
        warehousePages.put(p.getUniqueId(),page);
        Inventory gui=Bukkit.createInventory(null,54,STORE);
        Set<String> equippedIds=equipment==null?Set.of():new HashSet<>(equipment.equipped(p.getUniqueId()).values());
        for(int i=page*45;i<Math.min(count,(page+1)*45);i++) {
            StoredItem entry=matches.get(i);
            ItemStack icon=AbyssEquipment.icon(entry.definitionId(),entry.itemId(),equippedIds.contains(entry.itemId()));
            appendRollLore(icon, entry.itemId());
            var meta=icon.getItemMeta();var lore=meta.getLore()==null?new ArrayList<String>():new ArrayList<>(meta.getLore());
            lore.add("§7左クリック: 装備 / 右クリック: 削除確認");meta.setLore(lore);icon.setItemMeta(meta);
            gui.setItem(i-page*45,icon);
        }
        // Bottom row: previous, category selector, page status, next, return.
        if(page>0)gui.setItem(45,item(Material.ARROW,"§e前のページ",null));
        Material[] icons={Material.IRON_SWORD,Material.IRON_CHESTPLATE,Material.AMETHYST_SHARD,Material.POTION,Material.CHEST};
        for(int i=0;i<CATEGORIES.length;i++) {
            gui.setItem(46+i,item(icons[i],(selected.equals(CATEGORIES[i])?"§a▶ ":"§e")+CATEGORY_LABELS[i],null));
        }
        gui.setItem(51,item(Material.PAPER,"§b"+(page+1)+" / "+(maxPage+1)+" ページ §7("+count+"件)",null));
        gui.setItem(52,item(Material.BARRIER,"§cメインメニューへ",null));
        if(page<maxPage)gui.setItem(53,item(Material.ARROW,"§e次のページ",null));
        p.openInventory(gui);
    }
    /** Show saved rarity and affixes for every warehouse item, including legacy items. */
    public void appendRollLore(ItemStack icon, String itemId) {
        List<String> lines=new ArrayList<>();
        try(Connection conn=DriverManager.getConnection(jdbcUrl);
            PreparedStatement ps=conn.prepareStatement("SELECT rarity,attack_bonus,defense_bonus,magic_bonus,crit_chance,life_steal,element_bonus,attack_speed FROM abyss_item_rolls WHERE item_id=?")) {
            ps.setString(1,itemId);
            try(ResultSet rs=ps.executeQuery()) {
                if(rs.next()) {
                    String rarity=rs.getString(1);
                    String color=switch(rarity){case "レジェンダリー"->"§6";case "エピック"->"§5";case "レア"->"§9";case "アンコモン"->"§a";default->"§f";};
                    lines.add("§7レアリティ: "+color+rarity);
                    if(rs.getDouble(2)>0)lines.add("§c追加攻撃力: +"+rs.getDouble(2));
                    if(rs.getDouble(3)>0)lines.add("§b追加防御力: +"+rs.getDouble(3));
                    if(rs.getDouble(4)>0)lines.add("§d追加魔力: +"+rs.getDouble(4));
                    if(rs.getDouble(5)>0)lines.add(String.format(java.util.Locale.ROOT,"§eクリティカル確率: %.1f%%",rs.getDouble(5)*100));
                    if(rs.getDouble(6)>0)lines.add(String.format(java.util.Locale.ROOT,"§c吸血: %.1f%%",rs.getDouble(6)*100));
                    if(rs.getDouble(7)>0)lines.add(String.format(java.util.Locale.ROOT,"§6属性追加ダメージ: +%.1f §7(鈍足20%%)",rs.getDouble(7)));
                    if(rs.getDouble(8)>0)lines.add(String.format(java.util.Locale.ROOT,"§b迅速: 与ダメージ +%.1f%%",rs.getDouble(8)*100));
                    if(rs.getDouble(5)==0 && rs.getDouble(6)==0 && rs.getDouble(7)==0 && rs.getDouble(8)==0)lines.add("§8特殊効果: なし");
                } else lines.add("§7レアリティ: §8未設定（旧アイテム）");
            }
        } catch(SQLException ex) { plugin.getLogger().warning("Item roll lore: "+ex.getMessage()); lines.add("§cレアリティ情報を取得できません"); }
        var meta=icon.getItemMeta();
        if(meta==null)return;
        var lore=meta.getLore()==null?new ArrayList<String>():new ArrayList<>(meta.getLore());
        int idPos=lore.size();
        for(int i=0;i<lore.size();i++)if(lore.get(i).startsWith("§8ID: ")){idPos=i;break;}
        lore.addAll(idPos,lines);meta.setLore(lore);icon.setItemMeta(meta);
    }
    private void confirmDelete(Player p,String itemId) {
        if(plugin.isInRun(p.getUniqueId())) {p.sendMessage("§c攻略中は倉庫アイテムを削除できません。");return;}
        if(equipment!=null && equipment.equipped(p.getUniqueId()).containsValue(itemId)) {
            p.sendMessage("§c装備中のアイテムは削除できません。先に装備解除してください。");return;
        }
        pendingDeletes.put(p.getUniqueId(),itemId);
        Inventory gui=Bukkit.createInventory(null,9,"深淵ダンジョン - 削除確認");
        gui.setItem(3,item(Material.RED_CONCRETE,"§c完全に削除する",null));
        gui.setItem(5,item(Material.LIME_CONCRETE,"§aキャンセル",null));
        p.openInventory(gui);
    }
    private void deleteConfirmed(Player p) {
        String id=pendingDeletes.remove(p.getUniqueId());
        if(id==null || plugin.isInRun(p.getUniqueId()))return;
        try(Connection c=DriverManager.getConnection(jdbcUrl)){
            c.setAutoCommit(false);
            try(PreparedStatement ps=c.prepareStatement("DELETE FROM abyss_inventory WHERE owner_uuid=? AND item_id=? AND NOT EXISTS (SELECT 1 FROM abyss_loadout WHERE owner_uuid=? AND item_id=?)")){
                ps.setString(1,p.getUniqueId().toString());ps.setString(2,id);
                ps.setString(3,p.getUniqueId().toString());ps.setString(4,id);
                int n=ps.executeUpdate();c.commit();
                p.sendMessage(n==1?"§aアイテムを削除しました。":"§c削除できませんでした。装備状態を確認してください。");
            }catch(Exception ex){c.rollback();throw ex;}
        }catch(Exception ex){plugin.getLogger().warning("Warehouse delete: "+ex);p.sendMessage("§c削除に失敗しました。");}
    }
    public void openDifficulty(Player p) {
        Inventory gui=Bukkit.createInventory(null,27,DIFFICULTY);
        gui.setItem(11,item(Material.LIME_WOOL,"§a初級 §7(30階)",null));
        gui.setItem(13,item(Material.YELLOW_WOOL,"§e中級 §7(50階)",null));
        gui.setItem(15,item(Material.RED_WOOL,"§c上級 §7(100階)",null));
        gui.setItem(22,item(Material.BARRIER,"§7戻る",null));
        p.openInventory(gui);
    }
    public void openMagic(Player p){if(magic!=null)magic.open(p);}
    public void learnMagic(Player p,String id){if(magic!=null)magic.learn(p,id);}
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title=e.getView().getTitle();
        if(title.equals(DIFFICULTY)) {
            e.setCancelled(true);
            if(e.getClickedInventory()!=e.getView().getTopInventory())return;
            int slot=e.getRawSlot();
            if(slot==22){openMain(p);return;}
            String mode=switch(slot){case 11->"easy";case 13->"normal";case 15->"hard";default->null;};
            if(mode!=null){p.closeInventory();p.performCommand("abyss start "+mode);}
            return;
        }
        if(title.equals("深淵ダンジョン - 削除確認")) {
            e.setCancelled(true);
            if(e.getClickedInventory()!=e.getView().getTopInventory())return;
            if(e.getRawSlot()==3){deleteConfirmed(p);openWarehouse(p);}
            else if(e.getRawSlot()==5){pendingDeletes.remove(p.getUniqueId());openWarehouse(p);}
            return;
        }
        if (title.equals(AbyssEquipment.TITLE)) {
            e.setCancelled(true);
            if (e.getClickedInventory()==e.getView().getTopInventory() && e.getRawSlot()==22) {
                if(plugin.isInRun(p.getUniqueId()))p.sendMessage("§c攻略中は装備を変更できません。");
                else openWarehouse(p);
            }
            return;
        }
        if (title.equals(MAIN) || title.equals(STORE)) {
            e.setCancelled(true);
            if (e.getClickedInventory()!=e.getView().getTopInventory()) return;
            int slot=e.getRawSlot();
            if (title.equals(MAIN)) {
                if (slot==10) { openDifficulty(p); }
                if (slot==12) openWarehouse(p);
                if (slot==14 && equipment!=null) equipment.open(p);
                if (slot==16) {
                    if (magic==null) {
                        p.sendMessage("§c魔法管理が初期化されていません。管理者に連絡してください。");
                    } else {
                        // Opening a different inventory during InventoryClickEvent can conflict with other listeners.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!p.isOnline()) return;
                            try { magic.open(p); }
                            catch (Exception ex) {
                                plugin.getLogger().severe("Magic GUI open failed for " + p.getName() + ": " + ex);
                                ex.printStackTrace();
                                p.sendMessage("§c魔法管理を開けませんでした。サーバーログを確認してください。");
                            }
                        });
                    }
                }
                if (slot==26) plugin.openPartyMenu(p);
                if (slot==28 && equipment!=null) equipment.open(p);
                if (slot==30) { p.closeInventory(); p.performCommand("abyss bag"); }
                if (slot==32) { p.closeInventory(); p.performCommand("abyss retreat"); }
                if (slot==36) plugin.openClassMenu(p);
                if (slot==38) plugin.openSkillMenu(p);
                if (slot==40) Bukkit.getScheduler().runTask(plugin,()->{
                    if(p.isOnline())plugin.openGrowth(p);
                });
                if (slot==41) Bukkit.getScheduler().runTask(plugin,()->{
                    if(p.isOnline())plugin.openSafePouch(p);
                });
                if (slot==42) Bukkit.getScheduler().runTask(plugin,()->{
                    if(p.isOnline())plugin.openGuide(p);
                });
                if (slot==44) Bukkit.getScheduler().runTask(plugin,()->{
                    if(p.isOnline())plugin.openBugReport(p);
                });
                if (slot==34) { p.closeInventory(); p.performCommand("abyss lobby"); }
            } else if (slot==52) openMain(p);
            else if (slot>=46 && slot<=50) chooseCategory(p,CATEGORIES[slot-46]);
            else if (slot==45) openWarehouse(p,warehousePages.getOrDefault(p.getUniqueId(),0)-1);
            else if (slot==53) openWarehouse(p,warehousePages.getOrDefault(p.getUniqueId(),0)+1);
            else if (slot>=0 && slot<45 && equipment!=null && e.getCurrentItem()!=null && e.getCurrentItem().hasItemMeta()) {
                var lore=e.getCurrentItem().getItemMeta().getLore();
                if(lore!=null) for(String line:lore) if(line.startsWith("§8ID: ")) {
                    String itemId=line.substring("§8ID: ".length());
                    if(e.isRightClick())confirmDelete(p,itemId);
                    else if(plugin.isInRun(p.getUniqueId()))p.sendMessage("§c攻略中は装備・持ち込み設定を変更できません。");
                    else {
                        // InventoryClickEvent is still being processed here. Defer all
                        // player-inventory writes and GUI replacement until the next tick.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!p.isOnline() || !special(p.getWorld().getName())) return;
                            equipment.toggle(p,itemId);
                            refreshWeapon(p);
                            openWarehouse(p);
                            logWeaponSlots(p, "warehouse-reopen");
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (p.isOnline() && special(p.getWorld().getName()))
                                    logWeaponSlots(p, "warehouse-next-tick");
                            });
                        });
                    }
                    break;
                }
            }
            return;
        }
        // Phase 50: slot 4 belongs to the plugin even when empty. Prevent
        // mouse clicks, hotbar-number swaps, and offhand swaps from overwriting it.
        // Do not touch the normal storage slots (9-35).
        if (!special(p.getWorld().getName())) return;
        if ((e.getClickedInventory()==p.getInventory() && e.getSlot()==4)
                || e.getHotbarButton()==4) {
            e.setCancelled(true); return;
        }
        if (isProtected(e.getCurrentItem()) || isProtected(e.getCursor())) {
            e.setCancelled(true); return;
        }
        if (e.getHotbarButton() >= 0 && isProtected(p.getInventory().getItem(e.getHotbarButton()))) {
            e.setCancelled(true); return;
        }
        if (e.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                && isProtected(p.getInventory().getItemInOffHand())) e.setCancelled(true);
    }
    @EventHandler public void onDrag(InventoryDragEvent e) {
        String title = e.getView().getTitle();
        if (title.equals(MAIN) || title.equals(STORE) || title.equals(AbyssEquipment.TITLE)
                || title.equals("深淵ダンジョン - 削除確認")) { e.setCancelled(true); return; }
        if (!(e.getWhoClicked() instanceof Player p) || !special(p.getWorld().getName())) return;
        if (isProtected(e.getOldCursor())) { e.setCancelled(true); return; }
        for (int raw : e.getRawSlots()) {
            if ((raw >= 0 && raw < e.getView().countSlots()
                    && e.getView().getInventory(raw)==p.getInventory()
                    && e.getView().convertSlot(raw)==4)
                    || isProtected(e.getView().getItem(raw))) {
                e.setCancelled(true); return;
            }
        }
    }

    /** Slots 0-3 are visual controls only; permanent items remain in SQLite. */
    public void refreshConsumables(Player p) {
        if (!special(p.getWorld().getName()) || equipment==null) return;
        boolean inRun=plugin.isInRun(p.getUniqueId());
        Map<String,String> loadout=equipment.equipped(p.getUniqueId());
        for(int i=0;i<4;i++) {
            ItemStack current=p.getInventory().getItem(i);
            if (current != null && !current.getType().isAir() && !isProtected(current)) {
                if (!freeUiSlot(p, i)) continue;
                current = p.getInventory().getItem(i);
            }
            String itemId=loadout.get("consumable"+(i+1));
            if (!inRun || itemId==null) {
                if (current!=null && current.hasItemMeta() && current.getItemMeta().getPersistentDataContainer().has(consumableKey, PersistentDataType.INTEGER)) p.getInventory().setItem(i,null);
                continue;
            }
            ItemStack token=item(Material.POTION,"§d深淵の回復薬 §7(右クリックで使用)",null);
            var meta=token.getItemMeta();
            meta.getPersistentDataContainer().set(consumableKey,PersistentDataType.INTEGER,i+1);
            meta.setLore(java.util.List.of("§aHPを8回復", "§c使用すると倉庫から1個消費", "§8持ち込み枠 "+(i+1)));
            token.setItemMeta(meta);
            p.getInventory().setItem(i,token);
        }
    }
    private void useConsumable(Player p, ItemStack token) {
        if (!plugin.isInRun(p.getUniqueId()) || !dungeon.equals(p.getWorld().getName())) {
            p.sendMessage("§c攻略中のみ使用できます。"); return;
        }
        Integer slot=token.getItemMeta().getPersistentDataContainer().get(consumableKey,PersistentDataType.INTEGER);
        if(slot==null || slot<1 || slot>4 || p.getInventory().getHeldItemSlot()!=slot-1) return;
        if (p.getHealth() >= p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()-0.01) {
            p.sendMessage("§eHPが満タンです。"); return;
        }
        // Verify ownership and loadout in the same transaction; consuming twice cannot duplicate effects.
        try(Connection c=DriverManager.getConnection(jdbcUrl)) {
            c.setAutoCommit(false);
            String id=null;
            try(PreparedStatement q=c.prepareStatement("SELECT l.item_id FROM abyss_loadout l JOIN abyss_inventory i ON i.item_id=l.item_id AND i.owner_uuid=l.owner_uuid WHERE l.owner_uuid=? AND l.slot=? AND i.definition_id='abyss_potion'")) {
                q.setString(1,p.getUniqueId().toString());q.setString(2,"consumable"+slot);
                try(ResultSet r=q.executeQuery()){if(r.next())id=r.getString(1);}
            }
            if(id==null) {c.rollback();refreshConsumables(p);return;}
            try(PreparedStatement del=c.prepareStatement("DELETE FROM abyss_inventory WHERE owner_uuid=? AND item_id=?")) {
                del.setString(1,p.getUniqueId().toString());del.setString(2,id);
                if(del.executeUpdate()!=1) {c.rollback();return;}
            }
            try(PreparedStatement del=c.prepareStatement("DELETE FROM abyss_loadout WHERE owner_uuid=? AND item_id=?")) {
                del.setString(1,p.getUniqueId().toString());del.setString(2,id);del.executeUpdate();
            }
            c.commit();
            p.setHealth(Math.min(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),p.getHealth()+8));
            p.sendMessage("§a深淵の回復薬を使用しました。HP +8");
            refreshConsumables(p);
        } catch(Exception ex) {plugin.getLogger().warning("Potion consume failed: "+ex);p.sendMessage("§c使用処理に失敗しました。");}
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) {
        if(isProtected(e.getItemDrop().getItemStack()))e.setCancelled(true);
    }
    @EventHandler public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if(isProtected(e.getMainHandItem()) || isProtected(e.getOffHandItem()))e.setCancelled(true);
    }
    @EventHandler public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if(e.getEntity() instanceof Player p && special(p.getWorld().getName()))e.setCancelled(true);
    }
}
