package dev.abyss;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.sql.*;
import java.util.*;

/** Persistent virtual equipment: warehouse items never enter vanilla inventories. */
public final class AbyssEquipment implements Listener {
    public static final String TITLE = "深淵ダンジョン - 装備管理";
    private static final String[] SLOTS = {"weapon","offhand","head","chest","legs","feet","accessory1","accessory2","consumable1","consumable2","consumable3","consumable4"};
    private final String url, dungeon;
    private final AbyssPlugin plugin;
    // Shield guard stamina: 60 ticks continuous guard, 80 ticks cooldown.
    private final Map<UUID, Integer> guardTicks = new HashMap<>();
    private final Set<UUID> guardBroken = new HashSet<>();

    public AbyssEquipment(AbyssPlugin plugin, String url, String dungeon) {
        this.plugin=plugin; this.url=url; this.dungeon=dungeon;
        try(Connection c=DriverManager.getConnection(url); Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS abyss_loadout (owner_uuid TEXT NOT NULL, slot TEXT NOT NULL, item_id TEXT NOT NULL, PRIMARY KEY(owner_uuid,slot), UNIQUE(owner_uuid,item_id))");
        } catch(SQLException ex) { throw new IllegalStateException("Loadout database initialization failed",ex); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickShieldGuard, 1L, 5L);
    }
    public record Definition(String name, Material icon, String slot, double attack, double defense, double health, double magic, double mana, int rank) {
        public Definition(String name, Material icon, String slot, double attack, double defense, double health, double magic, double mana) {
            this(name, icon, slot, attack, defense, health, magic, mana, 1);
        }
    }
    private static final Map<String, Definition> PHASE21 = loadPhase21();
    private static Map<String, Definition> loadPhase21() {
        Map<String, Definition> definitions = new HashMap<>();
        try (java.io.InputStream in = AbyssEquipment.class.getResourceAsStream("/phase21_items.tsv")) {
            if (in == null) return definitions;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                reader.readLine(); // header
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] cols = line.split("\t", -1);
                    if (cols.length < 16) continue;
                    definitions.put(cols[0], new Definition(cols[1], Material.valueOf(cols[4]), cols[3],
                            Double.parseDouble(cols[5]), Double.parseDouble(cols[6]),
                            Double.parseDouble(cols[7]), Double.parseDouble(cols[8]), Double.parseDouble(cols[9]), Integer.parseInt(cols[15])));
                }
            }
        } catch (Exception e) { Bukkit.getLogger().warning("AbyssDungeon phase21 item catalog: " + e); }
        return definitions;
    }
    public static java.util.List<String> phase21Ids() { return java.util.List.copyOf(PHASE21.keySet()); }
    /** Rank draw is separate from rarity; excluded ranks can never be selected. */
    public static String randomPhase21Item(String difficulty, int floor, double mutationMultiplier, String job, java.util.Random rng) {
        double[][] weights = {
                {35,28,20,12,5,0,0,0,0,0},
                {10,14,18,18,16,12,8,4,0,0},
                {2,3,5,8,12,15,17,16,13,9}
        };
        int tier=switch(difficulty.toUpperCase(java.util.Locale.ROOT)) {case "EASY"->0;case "HARD"->2;default->1;};
        int cap=tier==0?5:tier==1?8:10;
        int segment=Math.max(0,(floor-1)/10);
        double highRankFactor=Math.pow(1.15,segment)*Math.max(0.01,mutationMultiplier);
        double total=0;
        double[] effective=new double[10];
        for(int i=0;i<cap;i++) { effective[i]=weights[tier][i]*Math.pow(highRankFactor,i/3.0);total+=effective[i]; }
        if(total<=0)return null;
        double draw=rng.nextDouble()*total;
        int rank=cap;
        for(int i=0;i<cap;i++) {draw-=effective[i];if(draw<0){rank=i+1;break;}}
        java.util.List<String> ids=new java.util.ArrayList<>();
        for(var entry:PHASE21.entrySet())if(entry.getValue().rank()==rank)ids.add(entry.getKey());
        if(ids.isEmpty())return null;
        // Spellbooks are a separate offhand family: give them a meaningful pool weight.
        double weightSum=0;
        for(String id:ids){Definition d=PHASE21.get(id);weightSum+=(d.slot().equals("offhand")?2.5:1.0)*proficiency(job,d);}
        double itemDraw=rng.nextDouble()*weightSum;
        for(String id:ids){Definition d=PHASE21.get(id);itemDraw-=(d.slot().equals("offhand")?2.5:1.0)*proficiency(job,d);if(itemDraw<0)return id;}
        return ids.get(ids.size()-1);
    }
    public static Definition definition(String id) {
        if (id == null) return null;
        Definition phase21 = PHASE21.get(id);
        if (phase21 != null) return phase21;
        return switch(id) {
            case "abyss_blade" -> new Definition("深淵の剣",Material.IRON_SWORD,"weapon",4,0,0,0,0);
            case "abyss_staff" -> new Definition("深淵の杖",Material.BLAZE_ROD,"weapon",2,0,0,12,15);
            case "abyss_shield" -> new Definition("深淵の盾",Material.SHIELD,"offhand",0,6,0,0,0);
            case "abyss_spellbook" -> new Definition("古代の魔導書",Material.ENCHANTED_BOOK,"offhand",0,0,0,8,20);
            case "abyss_helm" -> new Definition("深淵の兜",Material.IRON_HELMET,"head",0,2,0,0,0);
            case "abyss_chestplate" -> new Definition("深淵の鎧",Material.IRON_CHESTPLATE,"chest",0,4,0,0,0);
            case "abyss_leggings" -> new Definition("深淵の脚甲",Material.IRON_LEGGINGS,"legs",0,3,0,0,0);
            case "abyss_boots" -> new Definition("深淵の靴",Material.IRON_BOOTS,"feet",0,1,0,0,0);
            case "abyss_ring" -> new Definition("深淵の指輪",Material.AMETHYST_SHARD,"accessory",1,1,0,0,0);
            case "abyss_amulet" -> new Definition("生命の護符",Material.EMERALD,"accessory",0,0,4,0,0);
            case "abyss_potion" -> new Definition("深淵の回復薬",Material.POTION,"consumable",0,0,0,0,0);
            default -> null;
        };
    }
    public static ItemStack icon(String id, String itemId, boolean equipped) {
        Definition d=definition(id);
        ItemStack item=new ItemStack(d==null?Material.AMETHYST_SHARD:d.icon());
        ItemMeta m=item.getItemMeta(); m.setDisplayName((equipped?"§a[装備中] ":"§d")+(d==null?id:d.name()));
        List<String> lore=new ArrayList<>();
        if(d==null) lore.add("§7強化素材・未対応アイテム (装備不可)");
        else {
            lore.add("§7分類: "+d.slot());
            lore.add("§6装備ランク: R"+d.rank());
            if(d.attack()!=0) lore.add("§c物理攻撃力 +"+d.attack());
            if(d.defense()!=0) lore.add("§b防御力 +"+d.defense());
            if(d.magic()!=0) lore.add("§d魔力 +"+d.magic());
            if(d.mana()!=0) lore.add("§9最大MP +"+d.mana());
            if(d.health()!=0) lore.add("§a最大HP +"+d.health());
            lore.add(d.slot().equals("consumable") ? "§eクリックで持ち込み枠に登録・解除" : "§eクリックで装備・解除");
        }
        lore.add("§8ID: "+itemId);
        m.setLore(lore);item.setItemMeta(m);return item;
    }
    public Map<String,String> equipped(UUID uuid) {
        Map<String,String> result=new HashMap<>();
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT slot,item_id FROM abyss_loadout WHERE owner_uuid=?")) {
            ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){while(rs.next())result.put(rs.getString(1),rs.getString(2));}
        } catch(SQLException ex){plugin.getLogger().warning("Loadout read failed: "+ex);}
        return result;
    }
    public boolean toggle(Player p,String itemId) {
        if(plugin.isInRun(p.getUniqueId())) {p.sendMessage("§c攻略中は装備・持ち込み設定を変更できません。");return false;}
        try(Connection c=DriverManager.getConnection(url)) {
            c.setAutoCommit(false);
            String id=null;
            try(PreparedStatement ps=c.prepareStatement("SELECT definition_id FROM abyss_inventory WHERE owner_uuid=? AND item_id=?")) {
                ps.setString(1,p.getUniqueId().toString());ps.setString(2,itemId);
                try(ResultSet rs=ps.executeQuery()){if(rs.next())id=rs.getString(1);}
            }
            Definition d=definition(id);
            if(d==null){p.sendMessage("§cこのアイテムは装備できません。");c.rollback();return false;}
            String existing=null;
            try(PreparedStatement ps=c.prepareStatement("SELECT slot FROM abyss_loadout WHERE owner_uuid=? AND item_id=?")) {
                ps.setString(1,p.getUniqueId().toString());ps.setString(2,itemId);
                try(ResultSet rs=ps.executeQuery()){if(rs.next())existing=rs.getString(1);}
            }
            if(existing!=null) {
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM abyss_loadout WHERE owner_uuid=? AND item_id=?")) {
                    ps.setString(1,p.getUniqueId().toString());ps.setString(2,itemId);ps.executeUpdate();
                }
                p.sendMessage("§e装備を解除しました。");
            } else {
                String slot=d.slot();
                if(slot.equals("accessory")||slot.equals("consumable")) {
                    int count=slot.equals("accessory")?2:4;String prefix=slot;
                    Map<String,String> current=equipped(p.getUniqueId());
                    slot=null;for(int i=1;i<=count;i++){String s=prefix+i;if(!current.containsKey(s)){slot=s;break;}}
                    if(slot==null){p.sendMessage("§c空き枠がありません。先に装備を解除してください。");c.rollback();return false;}
                }
                try(PreparedStatement ps=c.prepareStatement("INSERT INTO abyss_loadout(owner_uuid,slot,item_id) VALUES(?,?,?) ON CONFLICT(owner_uuid,slot) DO UPDATE SET item_id=excluded.item_id")) {
                    ps.setString(1,p.getUniqueId().toString());ps.setString(2,slot);ps.setString(3,itemId);ps.executeUpdate();
                }
                p.sendMessage("§a装備を設定しました: §f"+d.name());
            }
            c.commit();return true;
        }catch(SQLException ex){plugin.getLogger().warning("Loadout update failed: "+ex);p.sendMessage("§c装備の保存に失敗しました。");return false;}
    }
    public void open(Player p) {
        Inventory inv=Bukkit.createInventory(null,27,TITLE);
        Map<String,String> eq=equipped(p.getUniqueId());
        for(int i=0;i<SLOTS.length;i++){
            String slot=SLOTS[i], itemId=eq.get(slot);
            String label=switch(slot){case "weapon"->"武器";case "offhand"->"オフハンド";case "head"->"頭";case "chest"->"胴";case "legs"->"脚";case "feet"->"足";default->slot.startsWith("accessory")?"装飾品 "+slot.substring(9):"消費アイテム "+slot.substring(10);};
            ItemStack item=new ItemStack(itemId==null?Material.GRAY_STAINED_GLASS_PANE:Material.LIME_STAINED_GLASS_PANE);
            ItemMeta m=item.getItemMeta();m.setDisplayName("§e"+label+(itemId==null?" §7(空)":" §a(設定済み)"));item.setItemMeta(m);inv.setItem(i,item);
        }
        ItemStack back=new ItemStack(Material.ENDER_CHEST);ItemMeta meta=back.getItemMeta();meta.setDisplayName("§b倉庫で装備を変更");back.setItemMeta(meta);inv.setItem(22,back);
        p.openInventory(inv);
    }
    private boolean isWeaponHeld(UUID uuid, String definitionId) {
        Player player = Bukkit.getPlayer(uuid);
        Definition def = definition(definitionId);
        if (player == null || def == null) return false;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() != def.icon() || !held.hasItemMeta()) return false;
        String actual = held.getItemMeta().getDisplayName();
        return actual != null && org.bukkit.ChatColor.stripColor(actual).contains(def.name());
    }
    /** Class proficiency applies to equipment contributions, not to innate class stats. */
    public static double proficiency(String job,Definition d){
        if(d==null)return 1;
        Material icon=d.icon();
        boolean armor=java.util.Set.of("head","chest","legs","feet").contains(d.slot());
        boolean book=icon==Material.ENCHANTED_BOOK && d.slot().equals("offhand");
        boolean weapon=d.slot().equals("weapon");
        boolean favored=switch(job){
            case "warrior"->armor || (weapon && (icon==Material.IRON_SWORD || icon==Material.IRON_AXE));
            case "mage"->book || (weapon && (icon==Material.STICK || icon==Material.BLAZE_ROD));
            case "priest"->book || icon==Material.SHIELD;
            case "archer"->weapon && icon==Material.BOW;
            case "paladin"->armor || icon==Material.SHIELD || (weapon && icon==Material.IRON_SWORD);
            case "assassin"->weapon && (icon==Material.IRON_HOE || icon==Material.LEAD);
            case "berserker"->weapon && (icon==Material.MACE || icon==Material.IRON_AXE);
            case "monk"->weapon && (icon==Material.IRON_SHOVEL || icon==Material.IRON_HOE);
            default->false;
        };
        return favored?1.25:1.0;
    }
    public double stat(UUID uuid,String stat) {
        double total=0;
        String job=plugin.stats().playerClass(uuid);
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT i.definition_id,COALESCE(r.attack_bonus,0),COALESCE(r.defense_bonus,0),COALESCE(r.magic_bonus,0) FROM abyss_loadout l JOIN abyss_inventory i ON i.item_id=l.item_id AND i.owner_uuid=l.owner_uuid LEFT JOIN abyss_item_rolls r ON r.item_id=i.item_id WHERE l.owner_uuid=?")) {
            ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){while(rs.next()){Definition d=definition(rs.getString(1));if(d!=null && (!"weapon".equals(d.slot()) || isWeaponHeld(uuid, rs.getString(1))))total+=proficiency(job,d)*switch(stat){case "magic"->d.magic()+rs.getDouble(4);case "mana"->d.mana();case "hp"->d.health();case "attack"->d.attack()+rs.getDouble(2);case "defense"->d.defense()+rs.getDouble(3);default->0;};}}
        }catch(SQLException ex){plugin.getLogger().warning("Loadout stats failed: "+ex);}
        return total;
    }
    public String equippedDefinition(UUID uuid,String slot) {
        String itemId=equipped(uuid).get(slot);if(itemId==null)return null;
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT definition_id FROM abyss_inventory WHERE owner_uuid=? AND item_id=?")){
            ps.setString(1,uuid.toString());ps.setString(2,itemId);try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}
        }catch(SQLException ex){plugin.getLogger().warning("Equipment lookup failed: "+ex);return null;}
    }
    public double bonus(UUID uuid,boolean attack) {
        double total=0;
        String job=plugin.stats().playerClass(uuid);
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT i.definition_id,COALESCE(r.attack_bonus,0),COALESCE(r.defense_bonus,0),COALESCE(r.magic_bonus,0) FROM abyss_loadout l JOIN abyss_inventory i ON i.item_id=l.item_id AND i.owner_uuid=l.owner_uuid LEFT JOIN abyss_item_rolls r ON r.item_id=i.item_id WHERE l.owner_uuid=?")) {
            ps.setString(1,uuid.toString());try(ResultSet rs=ps.executeQuery()){while(rs.next()){Definition d=definition(rs.getString(1));if(d!=null && (!"weapon".equals(d.slot()) || isWeaponHeld(uuid, rs.getString(1))))total+=proficiency(job,d)*(attack?d.attack()+rs.getDouble(2):d.defense()+rs.getDouble(3));}}
        }catch(SQLException ex){plugin.getLogger().warning("Loadout stats failed: "+ex);}
        return total;
    }
    private void tickShieldGuard() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!p.getWorld().getName().equals(dungeon) || !plugin.isInRun(id)) {
                guardTicks.remove(id);
                guardBroken.remove(id);
                continue;
            }
            ItemStack shield = p.getInventory().getItemInOffHand();
            if (shield.getType() != Material.SHIELD) {
                guardTicks.remove(id);
                guardBroken.remove(id);
                continue;
            }
            if (p.hasCooldown(Material.SHIELD)) {
                guardBroken.add(id);
                continue;
            }
            if (guardBroken.remove(id)) guardTicks.remove(id);
            if (p.isBlocking()) {
                int ticks = guardTicks.merge(id, 5, Integer::sum);
                if (ticks >= 60) {
                    guardTicks.remove(id);
                    guardBroken.add(id);
                    p.setCooldown(Material.SHIELD, 80);
                    p.sendActionBar(net.kyori.adventure.text.Component.text("§cガードブレイク！ 4秒間盾を使用できません"));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BREAK, 0.8f, 1.0f);
                }
            } else {
                guardTicks.computeIfPresent(id, (k, v) -> v <= 10 ? null : v - 10);
            }
        }
    }

    // Phase 42: persistent weapon affixes; the equipped weapon must be held in the main hand.
    @EventHandler(priority=org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled=true)
    public void onWeaponAffixHit(EntityDamageByEntityEvent event) {
        Player player;
        boolean arrowHit=false;
        if(event.getDamager() instanceof Player p) player=p;
        else if(event.getDamager() instanceof org.bukkit.entity.Arrow arrow
                && arrow.getShooter() instanceof Player p
                && arrow.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin,"short_range_bow"),org.bukkit.persistence.PersistentDataType.BYTE)) {
            player=p; arrowHit=true;
        } else return;
        if(!plugin.isInRun(player.getUniqueId()) || !(event.getEntity() instanceof LivingEntity target))return;
        String definitionId=equippedDefinition(player.getUniqueId(),"weapon");
        if(definitionId==null || !isWeaponHeld(player.getUniqueId(),definitionId))return;
        Definition weapon=definition(definitionId);
        if(weapon==null || (weapon.icon()==Material.BOW)!=arrowHit)return;
        String itemId=equipped(player.getUniqueId()).get("weapon");
        if(itemId==null)return;
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement(
                "SELECT crit_chance,life_steal,element_bonus,attack_speed FROM abyss_item_rolls WHERE item_id=?")) {
            ps.setString(1,itemId);
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next())return;
                double crit=rs.getDouble(1),steal=rs.getDouble(2),element=rs.getDouble(3),speed=rs.getDouble(4);
                double damage=event.getDamage();
                if(crit>0 && java.util.concurrent.ThreadLocalRandom.current().nextDouble()<crit){
                    damage*=1.5;
                    player.sendActionBar(net.kyori.adventure.text.Component.text("§6会心の一撃！"));
                }
                damage+=element;
                // Attack-speed affix provides a modest damage bonus on hits made during the
                // vanilla attack cooldown; it does not bypass Minecraft's hit immunity.
                if(!arrowHit && speed>0) damage*=1.0+speed;
                if(element>0){
                    target.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT,target.getLocation().add(0,1,0),12,0.3,0.4,0.3,0.01);
                    if(java.util.concurrent.ThreadLocalRandom.current().nextDouble()<0.20){
                        target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,40,0));
                    }
                }
                event.setDamage(damage);
                if(steal>0 && damage>0){
                    double heal=Math.min(5.0,damage*steal);
                    Bukkit.getScheduler().runTask(plugin,()->{
                        if(player.isOnline() && !player.isDead())player.setHealth(Math.min(player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue(),player.getHealth()+heal));
                    });
                }
            }
        }catch(SQLException ex){plugin.getLogger().warning("Weapon affix read failed: "+ex);}
    }

    @EventHandler public void onAttack(EntityDamageByEntityEvent e) {
        if(!(e.getDamager() instanceof Player p)||!p.getWorld().getName().equals(dungeon)||!(e.getEntity() instanceof LivingEntity))return;
        if(!plugin.isInRun(p.getUniqueId()))return;
        // AbyssStats.combat already includes equipment attack; do not apply twice.
    }
    @EventHandler public void onDefense(EntityDamageEvent e) {
        if(!(e.getEntity() instanceof Player p)||!p.getWorld().getName().equals(dungeon))return;
        if(!plugin.isInRun(p.getUniqueId()))return;
        if (!(e instanceof EntityDamageByEntityEvent)) e.setDamage(Math.max(0,e.getDamage()-bonus(p.getUniqueId(),false)*0.25));
    }
}
