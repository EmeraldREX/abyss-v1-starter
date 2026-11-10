package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Run-scoped mutation selection. No physical items or vanilla inventory slots are modified. */
public final class AbyssMutations implements Listener {
    public enum Kind {
        RAGE("狂暴化", "敵の攻撃力+30%", 15, Material.IRON_SWORD),
        ARMOR("鉄壁", "敵の防御力+40%", 15, Material.IRON_CHESTPLATE),
        GIANT("巨大化", "敵の最大HP+50%", 15, Material.SLIME_BLOCK),
        SWIFT("疾風", "敵の移動速度+25%", 15, Material.FEATHER),
        VAMPIRE("吸血", "敵が与えたダメージの10%を回復", 20, Material.REDSTONE),
        REGEN("再生", "敵が5秒ごとに最大HPの2%を回復", 20, Material.GHAST_TEAR),
        WEAK_HEAL("治癒阻害", "プレイヤー回復量-50%", 20, Material.FERMENTED_SPIDER_EYE),
        LOW_MANA("魔力枯渇", "MP自然回復速度-50%", 15, Material.LAPIS_LAZULI),
        FRAGILE("脆弱", "プレイヤー被ダメージ+25%", 20, Material.GLASS),
        SLOW("呪縛", "プレイヤー移動速度-15%", 10, Material.COBWEB),
        LOW_HP("生命減衰", "プレイヤー最大HP-20%", 20, Material.WITHER_ROSE),
        ELITE("精鋭化", "一部の通常敵が強化される", 25, Material.NETHERITE_SWORD),
        SWARM("増殖", "通常敵出現数+30%", 20, Material.SPAWNER),
        BLESSING("深淵の祝福", "高ランク装備の抽選重み上昇", 0, Material.ENCHANTED_BOOK),
        CHAOS("混沌", "60秒ごとに別の敵強化が発動", 30, Material.NETHER_STAR);

        final String name, description;
        final int rewardBonus;
        final Material icon;
        Kind(String name, String description, int rewardBonus, Material icon) {
            this.name=name;this.description=description;this.rewardBonus=rewardBonus;this.icon=icon;
        }
    }

    private static final String PICK_TITLE="深淵の迷宮 - 変異選択";
    private static final String CONFIRM_TITLE="深淵の迷宮 - 変異確定";
    private final AbyssPlugin plugin;
    private final String jdbcUrl;
    private final Map<UUID, Selection> selections=new HashMap<>();
    private final Map<UUID, EnumSet<Kind>> active=new HashMap<>();
    private final Map<UUID, UUID> mobRuns=new HashMap<>();
    private final Map<UUID, Long> lastChaos=new HashMap<>();
    private final Map<UUID, Kind> chaosCurrent=new HashMap<>();

    private static final class Selection {
        final DungeonRun run;
        final EnumSet<Kind> chosen=EnumSet.noneOf(Kind.class);
        final List<Kind> offers=new ArrayList<>();
        boolean confirming;
        Selection(DungeonRun run){this.run=run;}
    }

    public AbyssMutations(AbyssPlugin plugin,String jdbcUrl) {
        this.plugin=plugin;this.jdbcUrl=jdbcUrl;
        try(Connection c=DriverManager.getConnection(jdbcUrl);var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS abyss_run_mutations (run_id TEXT PRIMARY KEY, leader_uuid TEXT NOT NULL, mutations TEXT NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0)");
        } catch(SQLException ex){throw new IllegalStateException("Mutation DB initialization failed",ex);}
        Bukkit.getScheduler().runTaskTimer(plugin,this::tick,100L,100L);
    }

    public boolean isSelecting(UUID leader){return selections.containsKey(leader);}
    public void begin(DungeonRun run,Player leader) {
        Selection selection=new Selection(run);
        selections.put(run.playerId(),selection);
        nextOffers(selection);
        save(run,selection.chosen,false);
        open(leader,selection);
    }
    private int count(DungeonRun run) {
        return switch(run.difficulty().toUpperCase(java.util.Locale.ROOT)){case "EASY"->1;case "NORMAL"->2;default->3;};
    }
    private boolean incompatible(Set<Kind> chosen,Kind next){
        return (chosen.contains(Kind.GIANT)&&next==Kind.REGEN)||(chosen.contains(Kind.REGEN)&&next==Kind.GIANT)
                ||(chosen.contains(Kind.WEAK_HEAL)&&next==Kind.LOW_HP)||(chosen.contains(Kind.LOW_HP)&&next==Kind.WEAK_HEAL)
                ||(chosen.contains(Kind.SWIFT)&&next==Kind.SWARM)||(chosen.contains(Kind.SWARM)&&next==Kind.SWIFT);
    }
    private void nextOffers(Selection s){
        s.offers.clear();
        List<Kind> pool=new ArrayList<>(Arrays.asList(Kind.values()));
        pool.removeIf(k->s.chosen.contains(k)||incompatible(s.chosen,k));
        java.util.Collections.shuffle(pool,ThreadLocalRandom.current());
        s.offers.addAll(pool.subList(0,Math.min(3,pool.size())));
    }
    private ItemStack item(Material material,String name,List<String> lore) {
        ItemStack i=new ItemStack(material);
        ItemMeta m=i.getItemMeta();m.setDisplayName(name);m.setLore(lore);i.setItemMeta(m);return i;
    }
    private void open(Player leader,Selection s){
        if(!leader.isOnline() || !leader.getUniqueId().equals(s.run.playerId()))return;
        Inventory inv=Bukkit.createInventory(null,27,s.confirming?CONFIRM_TITLE:PICK_TITLE);
        if(s.confirming){
            inv.setItem(11,item(Material.LIME_CONCRETE,"§aこの変異で攻略開始",List.of("§7選択は攻略終了まで変更できません")));
            inv.setItem(15,item(Material.PAPER,"§e選択内容",s.chosen.stream().map(k->"§7- "+k.name).toList()));
        } else {
            int[] slots={11,13,15};
            for(int n=0;n<s.offers.size();n++){
                Kind k=s.offers.get(n);
                inv.setItem(slots[n],item(k.icon,"§d"+k.name,List.of("§7"+k.description,"§a追加報酬率 +"+k.rewardBonus+"%","§eクリックで選択")));
            }
            inv.setItem(22,item(Material.BOOK,"§e変異 "+(s.chosen.size()+1)+" / "+count(s.run),List.of("§7リーダーが選択します")));
        }
        leader.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent e){
        String title=e.getView().getTitle();
        if(!title.equals(PICK_TITLE)&&!title.equals(CONFIRM_TITLE))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        Selection s=selections.get(p.getUniqueId());
        if(s==null || !plugin.isInRun(p.getUniqueId())){p.closeInventory();return;}
        int slot=e.getRawSlot();
        if(s.confirming){
            if(slot!=11)return;
            try{save(s.run,s.chosen,true);}catch(IllegalStateException ex){p.sendMessage("§c変異の保存に失敗しました。");return;}
            active.put(s.run.id(),EnumSet.copyOf(s.chosen));
            selections.remove(p.getUniqueId());
            p.closeInventory();
            plugin.startSelectedRun(p,s.run);
            return;
        }
        int index=switch(slot){case 11->0;case 13->1;case 15->2;default->-1;};
        if(index<0||index>=s.offers.size())return;
        s.chosen.add(s.offers.get(index));
        try{save(s.run,s.chosen,false);}catch(IllegalStateException ex){p.sendMessage("§c変異選択を保存できませんでした。");s.chosen.remove(s.offers.get(index));return;}
        if(s.chosen.size()==count(s.run))s.confirming=true;
        else nextOffers(s);
        open(p,s);
    }
    @EventHandler public void close(InventoryCloseEvent e){
        if(!e.getView().getTitle().equals(PICK_TITLE)&&!e.getView().getTitle().equals(CONFIRM_TITLE))return;
        if(!(e.getPlayer() instanceof Player p))return;
        Selection s=selections.get(p.getUniqueId());
        if(s!=null)Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!p.isOnline()||selections.get(p.getUniqueId())!=s)return;
            String current=p.getOpenInventory().getTitle();
            if(!current.equals(PICK_TITLE)&&!current.equals(CONFIRM_TITLE))open(p,s);
        },2L);
    }
    @EventHandler public void join(PlayerJoinEvent e){
        Selection s=selections.get(e.getPlayer().getUniqueId());
        if(s!=null)Bukkit.getScheduler().runTaskLater(plugin,()->open(e.getPlayer(),s),20L);
    }
    private void save(DungeonRun run,Set<Kind> kinds,boolean confirmed){
        String serialized=String.join(",",kinds.stream().map(Enum::name).toList());
        try(Connection c=DriverManager.getConnection(jdbcUrl);PreparedStatement p=c.prepareStatement(
                "INSERT INTO abyss_run_mutations(run_id,leader_uuid,mutations,confirmed) VALUES(?,?,?,?) ON CONFLICT(run_id) DO UPDATE SET mutations=excluded.mutations,confirmed=excluded.confirmed")){
            p.setString(1,run.id().toString());p.setString(2,run.playerId().toString());p.setString(3,serialized);p.setInt(4,confirmed?1:0);p.executeUpdate();
        }catch(SQLException ex){throw new IllegalStateException(ex);}
    }
    public boolean has(DungeonRun run,Kind kind){
        return run!=null && active.getOrDefault(run.id(),EnumSet.noneOf(Kind.class)).contains(kind);
    }
    public boolean hasPlayer(UUID player,Kind kind){return has(plugin.runOf(player),kind);}
    public double rewardChance(DungeonRun run){
        EnumSet<Kind> set=active.get(run.id());
        if(set==null)return 0;
        return Math.min(0.80,set.stream().mapToInt(k->k.rewardBonus).sum()/100.0);
    }
    public double rankMultiplier(DungeonRun run){return has(run,Kind.BLESSING)?1.5:1.0;}
    public int extraMobs(DungeonRun run,int count){return has(run,Kind.SWARM)?(int)Math.ceil(count*0.3):0;}
    public double hpMultiplier(DungeonRun run){return has(run,Kind.LOW_HP)?0.8:1.0;}
    public double manaRegenMultiplier(UUID player){return hasPlayer(player,Kind.LOW_MANA)?0.5:1.0;}
    public void applyMob(LivingEntity mob,DungeonRun run){
        mobRuns.put(mob.getUniqueId(),run.id());
        var hp=mob.getAttribute(Attribute.MAX_HEALTH);
        if(hp!=null && has(run,Kind.GIANT)){hp.setBaseValue(hp.getBaseValue()*1.5);mob.setHealth(hp.getValue());}
        var speed=mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if(speed!=null && has(run,Kind.SWIFT))speed.setBaseValue(speed.getBaseValue()*1.25);
        if(has(run,Kind.ELITE)&&run.floor()%10!=0&&ThreadLocalRandom.current().nextDouble()<0.2){
            if(hp!=null){hp.setBaseValue(hp.getBaseValue()*1.5);mob.setHealth(hp.getValue());}
            mob.setCustomName("§5精鋭 "+mob.getType().name());mob.setCustomNameVisible(true);
        }
    }
    public double enemyAttack(DungeonRun run,double attack){return has(run,Kind.RAGE)?attack*1.3:attack;}
    public double enemyDefense(DungeonRun run,double defense){return has(run,Kind.ARMOR)?defense*1.4:defense;}
    @EventHandler public void damage(EntityDamageByEntityEvent event){
        if(!(event.getEntity() instanceof LivingEntity))return;
        if(event.getEntity() instanceof Player victim){
            DungeonRun run=plugin.runOf(victim.getUniqueId());
            if(run==null)return;
            if(!(event.getDamager() instanceof LivingEntity attacker)||!run.id().equals(mobRuns.get(attacker.getUniqueId())))return;
            if(has(run,Kind.FRAGILE))event.setDamage(event.getDamage()*1.25);
            if(has(run,Kind.VAMPIRE)){
                double heal=event.getFinalDamage()*0.1;
                Bukkit.getScheduler().runTask(plugin,()->{
                    if(attacker.isValid()&&!attacker.isDead())attacker.setHealth(Math.min(attacker.getAttribute(Attribute.MAX_HEALTH).getValue(),attacker.getHealth()+heal));
                });
            }
        }
    }
    @EventHandler public void heal(EntityRegainHealthEvent event){
        if(event.getEntity() instanceof Player p && hasPlayer(p.getUniqueId(),Kind.WEAK_HEAL))event.setAmount(event.getAmount()*0.5);
    }
    @EventHandler public void death(EntityDeathEvent event){mobRuns.remove(event.getEntity().getUniqueId());lastChaos.remove(event.getEntity().getUniqueId());chaosCurrent.remove(event.getEntity().getUniqueId());}
    private void tick(){
        long now=System.currentTimeMillis();
        for(var entry:new ArrayList<>(mobRuns.entrySet())){
            LivingEntity mob=entry.getKey()==null?null:(Bukkit.getEntity(entry.getKey()) instanceof LivingEntity living?living:null);
            if(mob==null||!mob.isValid()){mobRuns.remove(entry.getKey());continue;}
            EnumSet<Kind> set=active.get(entry.getValue());
            if(set==null)continue;
            var max=mob.getAttribute(Attribute.MAX_HEALTH);
            if(max==null)continue;
            if(set.contains(Kind.REGEN))mob.setHealth(Math.min(max.getValue(),mob.getHealth()+max.getValue()*0.02));
            if(set.contains(Kind.CHAOS)){
                long last=lastChaos.getOrDefault(entry.getKey(),0L);
                if(now-last>=60000L){
                    lastChaos.put(entry.getKey(),now);
                    Kind[] options={Kind.RAGE,Kind.ARMOR,Kind.REGEN,Kind.SWIFT};
                    Kind choice=options[ThreadLocalRandom.current().nextInt(options.length)];
                    chaosCurrent.put(entry.getKey(),choice);
                    org.bukkit.potion.PotionEffectType potion=switch(choice){
                        case RAGE->org.bukkit.potion.PotionEffectType.STRENGTH;
                        case ARMOR->org.bukkit.potion.PotionEffectType.RESISTANCE;
                        case REGEN->org.bukkit.potion.PotionEffectType.REGENERATION;
                        default->org.bukkit.potion.PotionEffectType.SPEED;
                    };
                    mob.addPotionEffect(new org.bukkit.potion.PotionEffect(potion,1200,0,true,true));
                }
            }
        }
        for(Player p:Bukkit.getOnlinePlayers()){
            if(hasPlayer(p.getUniqueId(),Kind.SLOW) && plugin.isInRun(p.getUniqueId())){
                p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,120,0,true,false,false));
            }
        }
    }
    public void end(DungeonRun run){
        selections.remove(run.playerId());active.remove(run.id());
        mobRuns.entrySet().removeIf(e->{
            if(!e.getValue().equals(run.id()))return false;
            lastChaos.remove(e.getKey());chaosCurrent.remove(e.getKey());return true;
        });
        try(Connection c=DriverManager.getConnection(jdbcUrl);PreparedStatement p=c.prepareStatement("DELETE FROM abyss_run_mutations WHERE run_id=?")){
            p.setString(1,run.id().toString());p.executeUpdate();
        }catch(SQLException ex){plugin.getLogger().warning("Mutation cleanup failed: "+ex.getMessage());}
    }
}
