package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import dev.abyss.dungeon.FloorType;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.*;
import java.sql.*;
import java.util.*;

/** Configurable provisional class, enemy scaling and in-run HUD. */
public final class AbyssStats implements Listener {
    private final AbyssPlugin plugin;
    private final String url;
    private final AbyssEquipment equipment;
    private final AbyssMagic magic;
    private final Map<UUID,Scoreboard> boards=new HashMap<>();
    private final Map<UUID,Scoreboard> previousBoards=new HashMap<>();

    private boolean hudWorld(Player p){
        String world=p.getWorld().getName();
        return world.equals(plugin.getConfig().getString("lobby-world","abyss_lobby"))
            || world.equals(plugin.getConfig().getString("dungeon-world","abyss_dungeon"));
    }

    private void hideHud(Player p){
        UUID id=p.getUniqueId();
        Scoreboard own=boards.get(id);
        if(own!=null && p.getScoreboard()==own){
            Scoreboard previous=previousBoards.remove(id);
            p.setScoreboard(previous!=null?previous:Bukkit.getScoreboardManager().getMainScoreboard());
        } else {
            previousBoards.remove(id);
        }
    }

    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event){
        Player p=event.getPlayer();
        if(!hudWorld(p))hideHud(p);
        else Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())update(p,null);});
    }

    @EventHandler public void onQuit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();
        hideHud(event.getPlayer());
        boards.remove(id);previousBoards.remove(id);
    }
    private AbyssPerks perks;
    public void setPerks(AbyssPerks perks){this.perks=perks;}
    // Run-only XP/levels: never read the old persistent abyss_levels table.
    private final Map<UUID,int[]> runLevels = new HashMap<>();
    public void beginRun(UUID id){runLevels.put(id,new int[]{1,0});}
    public void endRun(UUID id){runLevels.remove(id);}
    public void clearRuns(){runLevels.clear();}
    private final NamespacedKey enemyAttack,enemyDefense;
    public AbyssStats(AbyssPlugin plugin,String url,AbyssEquipment equipment,AbyssMagic magic){
        this.plugin=plugin;this.url=url;this.equipment=equipment;this.magic=magic;
        enemyAttack=new NamespacedKey(plugin,"enemy_attack");enemyDefense=new NamespacedKey(plugin,"enemy_defense");
        try(Connection c=DriverManager.getConnection(url);Statement st=c.createStatement()){
            st.execute("CREATE TABLE IF NOT EXISTS abyss_classes(owner_uuid TEXT PRIMARY KEY, class_id TEXT NOT NULL)");
            // Legacy abyss_levels table is deliberately left untouched for safe migration.
        }catch(SQLException ex){throw new IllegalStateException(ex);}
        plugin.getConfig().addDefault("enemy-scaling.easy.hp",1.0);
        plugin.getConfig().addDefault("enemy-scaling.easy.attack",1.0);
        plugin.getConfig().addDefault("enemy-scaling.easy.defense",1.0);
        plugin.getConfig().addDefault("enemy-scaling.normal.hp",1.8);
        plugin.getConfig().addDefault("enemy-scaling.normal.attack",1.5);
        plugin.getConfig().addDefault("enemy-scaling.normal.defense",1.4);
        plugin.getConfig().addDefault("enemy-scaling.hard.hp",3.0);
        plugin.getConfig().addDefault("enemy-scaling.hard.attack",2.2);
        plugin.getConfig().addDefault("enemy-scaling.hard.defense",2.0);
        plugin.getConfig().addDefault("enemy-scaling.floor.hp",0.08);
        plugin.getConfig().addDefault("enemy-scaling.floor.attack",0.05);
        plugin.getConfig().addDefault("enemy-scaling.floor.defense",0.04);
        for(String cls:CLASSES) {
            for(String stat:List.of("hp","mp","attack","defense","magic")) {
                plugin.getConfig().addDefault("classes."+cls+".base."+stat, legacyBase(cls,stat));
                plugin.getConfig().addDefault("classes."+cls+".growth."+stat, switch(stat){case "hp"->(cls.equals("warrior")||cls.equals("paladin")||cls.equals("berserker"))?8:5;case "mp"->cls.equals("mage")?8:4;case "attack"->cls.equals("warrior")?2.5:1.0;case "defense"->cls.equals("warrior")?2:1;default->cls.equals("mage")?3:1.5;});
            }
        }
        for(String mob:List.of("zombie","skeleton","ravager","default")) {
            double factor=mob.equals("ravager")?3:mob.equals("skeleton")?0.8:1;
            plugin.getConfig().addDefault("enemies."+mob+".hp",20*factor);
            plugin.getConfig().addDefault("enemies."+mob+".attack",4*factor);
            plugin.getConfig().addDefault("enemies."+mob+".defense",2*factor);
            plugin.getConfig().addDefault("enemies."+mob+".xp",mob.equals("ravager")?100:mob.equals("skeleton")?12:15);
        }
        plugin.getConfig().addDefault("leveling.max-level",100);
        plugin.getConfig().addDefault("leveling.xp-base",100);
        plugin.getConfig().addDefault("leveling.xp-growth",1.25);
        plugin.getConfig().addDefault("leveling.effective-xp-base",65);
        plugin.getConfig().addDefault("leveling.effective-xp-growth",1.15);
        plugin.getConfig().options().copyDefaults(true);plugin.saveConfig();
    }
    public static final java.util.List<String> CLASSES=java.util.List.of("warrior","mage","priest","archer","paladin","assassin","berserker","monk");
    public String playerClass(UUID id){try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT class_id FROM abyss_classes WHERE owner_uuid=?")){ps.setString(1,id.toString());try(ResultSet r=ps.executeQuery()){return r.next()?r.getString(1):"warrior";}}catch(SQLException ex){return "warrior";}}
    public void setClass(Player p,String value){
        if(plugin.isInRun(p.getUniqueId())){p.sendMessage("§c攻略中は職業を変更できません。");return;}
        if(!CLASSES.contains(value)){p.sendMessage("§c職業: "+String.join(" / ",CLASSES));return;}
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("INSERT INTO abyss_classes(owner_uuid,class_id) VALUES(?,?) ON CONFLICT(owner_uuid) DO UPDATE SET class_id=excluded.class_id")){
            ps.setString(1,p.getUniqueId().toString());ps.setString(2,value);ps.executeUpdate();p.sendMessage("§a職業を設定: "+value);
        }catch(SQLException ex){p.sendMessage("§c保存に失敗しました。");}
    }
    private double legacyBase(String cls,String stat){return switch(cls){
        case "archer"->switch(stat){case "hp"->95;case "mp"->45;case "attack"->16;case "defense"->8;case "magic"->5;default->0;};
        case "paladin"->switch(stat){case "hp"->135;case "mp"->65;case "attack"->13;case "defense"->20;case "magic"->10;default->0;};
        case "assassin"->switch(stat){case "hp"->85;case "mp"->45;case "attack"->21;case "defense"->7;case "magic"->5;default->0;};
        case "berserker"->switch(stat){case "hp"->140;case "mp"->20;case "attack"->25;case "defense"->6;case "magic"->2;default->0;};
        case "monk"->switch(stat){case "hp"->115;case "mp"->60;case "attack"->15;case "defense"->13;case "magic"->11;default->0;};case "mage"->switch(stat){case "hp"->80;case "mp"->100;case "attack"->6;case "defense"->5;case "magic"->22;default->0;};case "priest"->switch(stat){case "hp"->100;case "mp"->80;case "attack"->10;case "defense"->10;case "magic"->16;default->0;};default->switch(stat){case "hp"->120;case "mp"->30;case "attack"->18;case "defense"->15;case "magic"->4;default->0;};};}
    private double base(String cls,String stat){return plugin.getConfig().getDouble("classes."+cls+".base."+stat,legacyBase(cls,stat));}
    private double growth(String cls,String stat){return plugin.getConfig().getDouble("classes."+cls+".growth."+stat,0);}
    public int[] progress(UUID id){
        int[] data=runLevels.get(id);
        return data==null?new int[]{1,0}:data.clone();
    }
    public int xpRequired(int level){return Math.max(1,(int)Math.round(plugin.getConfig().getDouble("leveling.effective-xp-base",65)*Math.pow(plugin.getConfig().getDouble("leveling.effective-xp-growth",1.15),Math.max(0,level-1))));}
    public void addXp(Player p,int amount){
        if(amount<=0||!plugin.isInRun(p.getUniqueId()))return;
        int[] data=runLevels.get(p.getUniqueId());
        if(data==null)return;
        int level=data[0],xp=data[1]+amount;
        int previousLevel=level;
        int max=plugin.getConfig().getInt("leveling.max-level",100);
        while(level<max&&xp>=xpRequired(level)){
            xp-=xpRequired(level);level++;p.sendMessage("§aラン内レベルアップ！ Lv."+level);
            if(plugin.perks()!=null)plugin.perks().offerLevel(p,level);
        }
        if(level>=max)xp=0;
        data[0]=level;data[1]=xp;
        if(level>previousLevel && plugin.growth()!=null)plugin.growth().levelUp(p,level-previousLevel);
    }
    public void setLevel(Player p,int level){
        int[] data=runLevels.get(p.getUniqueId());
        if(data==null){p.sendMessage("§cラン内レベルは攻略中のみ変更できます。");return;}
        int max=plugin.getConfig().getInt("leveling.max-level",100);
        if(level<1||level>max){p.sendMessage("§cレベル範囲: 1～"+max);return;}
        int previousLevel=data[0];
        data[0]=level;data[1]=0;
        if(level>previousLevel && plugin.perks()!=null)for(int reached=previousLevel+1;reached<=level;reached++)plugin.perks().offerLevel(p,reached);
        if(level>previousLevel && plugin.growth()!=null)plugin.growth().levelUp(p,level-previousLevel);
        p.sendMessage("§a今回の攻略レベルを "+level+" に設定しました。");
    }
    public double stat(UUID id,String stat){
        String cls=playerClass(id);
        double baseValue=base(cls,stat)+growth(cls,stat)*(progress(id)[0]-1)+equipment.stat(id,stat.equals("mp")?"mana":stat)
                +(perks==null?0:perks.bonus(id,stat))+(plugin.growth()==null?0:plugin.growth().bonus(id,stat));
        return baseValue*(1.0+(plugin.engravings()==null?0:plugin.engravings().stat(id,stat))/100.0);
    }
    @EventHandler public void onKill(EntityDeathEvent e){
        LivingEntity mob=e.getEntity();Player killer=mob.getKiller();
        if(killer==null||!plugin.isInRun(killer.getUniqueId())||!mob.getPersistentDataContainer().has(enemyAttack,org.bukkit.persistence.PersistentDataType.DOUBLE))return;
        String key=mob.getType().getKey().getKey();
        int xp=plugin.getConfig().getInt("enemies."+key+".xp",plugin.getConfig().getInt("enemies.default.xp",15));
        java.util.List<Player> recipients = plugin.activePartyMembers(killer.getUniqueId());
        if (recipients.isEmpty()) addXp(killer,xp);
        else for (Player member : recipients) addXp(member,xp);
    }
    public void scaleMob(LivingEntity mob,DungeonRun run,FloorType type){
        String difficulty=run.difficulty();int floor=run.floor();
        double elite=type==FloorType.BOSS?5.0:type==FloorType.ELITE?2.0:1.0;
        String key=mob.getType().getKey().getKey();
        double hp=enemyBase(key,"hp",20)*elite*mult(difficulty,"hp",floor);
        double attack=plugin.mutations().enemyAttack(run,enemyBase(key,"attack",4)*elite*mult(difficulty,"attack",floor));
        double defense=plugin.mutations().enemyDefense(run,enemyBase(key,"defense",2)*elite*mult(difficulty,"defense",floor));
        var attribute=mob.getAttribute(Attribute.MAX_HEALTH);
        if(attribute!=null){attribute.setBaseValue(hp);mob.setHealth(hp);}
        mob.getPersistentDataContainer().set(enemyAttack,org.bukkit.persistence.PersistentDataType.DOUBLE,attack);
        mob.getPersistentDataContainer().set(enemyDefense,org.bukkit.persistence.PersistentDataType.DOUBLE,defense);
    }
    public void boostEnemyAttack(LivingEntity mob, double minimum) {
        var data = mob.getPersistentDataContainer();
        Double current = data.get(enemyAttack, org.bukkit.persistence.PersistentDataType.DOUBLE);
        if (current != null) data.set(enemyAttack, org.bukkit.persistence.PersistentDataType.DOUBLE, Math.max(current * 1.7, minimum));
    }
    /** Apply beta boss-only damage tuning after generic enemy boosts. */
    public void scaleBossAttack(LivingEntity mob, double factor) {
        var data = mob.getPersistentDataContainer();
        Double current = data.get(enemyAttack, org.bukkit.persistence.PersistentDataType.DOUBLE);
        if(current != null)
            data.set(enemyAttack, org.bukkit.persistence.PersistentDataType.DOUBLE,
                    Math.max(0.0, current * factor));
    }
    private double enemyBase(String type,String stat,double fallback){return plugin.getConfig().getDouble("enemies."+type+"."+stat,plugin.getConfig().getDouble("enemies.default."+stat,fallback));}
    private double mult(String difficulty,String stat,int floor){return plugin.getConfig().getDouble("enemy-scaling."+difficulty+"."+stat,1.0)*(1.0+plugin.getConfig().getDouble("enemy-scaling.floor."+stat,0.0)*Math.max(0,floor-1));}
    @EventHandler public void combat(EntityDamageByEntityEvent e){
        if(e.getDamager() instanceof LivingEntity mob && e.getEntity() instanceof Player p && mob.getPersistentDataContainer().has(enemyAttack,org.bukkit.persistence.PersistentDataType.DOUBLE)){
            double attack=mob.getPersistentDataContainer().get(enemyAttack,org.bukkit.persistence.PersistentDataType.DOUBLE);
            double defense=stat(p.getUniqueId(),"defense");
            e.setDamage(Math.max(1,attack*100.0/(100.0+defense)));
        } else if(e.getDamager() instanceof Player p && e.getEntity() instanceof LivingEntity mob && mob.getPersistentDataContainer().has(enemyDefense,org.bukkit.persistence.PersistentDataType.DOUBLE)){
            if(p.getInventory().getItemInMainHand().getType()==Material.BOW){e.setDamage(1.0);return;}
            double defense=mob.getPersistentDataContainer().get(enemyDefense,org.bukkit.persistence.PersistentDataType.DOUBLE);
            e.setDamage(Math.max(1,(stat(p.getUniqueId(),"attack"))*100.0/(100.0+defense)));
        } else if(e.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player p
                && arrow.getPersistentDataContainer().has(new NamespacedKey(plugin,"short_range_bow"),org.bukkit.persistence.PersistentDataType.BYTE)
                && e.getEntity() instanceof LivingEntity mob && plugin.isInRun(p.getUniqueId())){
            Double defense=mob.getPersistentDataContainer().get(enemyDefense,org.bukkit.persistence.PersistentDataType.DOUBLE);
            double effectiveDefense=defense==null?0:defense;
            e.setDamage(Math.max(1,stat(p.getUniqueId(),"attack")*100.0/(100.0+effectiveDefense)));
        }
    }
    public void update(Player p,DungeonRun run){
        UUID id=p.getUniqueId();
        // Abyss lobby and dungeon use Abyss HP; ordinary worlds keep vanilla HP.
        var hpAttr=p.getAttribute(Attribute.MAX_HEALTH);
        if(hpAttr!=null){
            double wanted=hudWorld(p)?Math.max(1,stat(id,"hp")*(plugin.mutations()==null?1:plugin.mutations().hpMultiplier(plugin.runOf(id)))):20.0;
            if(Math.abs(hpAttr.getBaseValue()-wanted)>0.01){
                hpAttr.setBaseValue(wanted);
                if(!p.isDead())p.setHealth(Math.min(p.getHealth(),hpAttr.getValue()));
            }
        }
        if(!hudWorld(p)){hideHud(p);return;}
        Scoreboard board=boards.computeIfAbsent(id,k->Bukkit.getScoreboardManager().getNewScoreboard());
        Objective obj=board.getObjective("abyss");if(obj==null)obj=board.registerNewObjective("abyss",Criteria.DUMMY,"§5§l深淵の迷宮");obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        for(String entry:board.getEntries())board.resetScores(entry);
        String[] lines={"§7場所: §f"+(run==null?"ロビー/通常":"ダンジョン"),
            "§7難易度: §f"+(run==null?"--":run.difficulty()),"§7階層: §e"+(run==null?"--":run.floor()),
            "§cHP: §f"+(int)Math.ceil(p.getHealth())+"/"+(hpAttr==null?20:(int)Math.ceil(hpAttr.getValue())),
            "§9MP: §f"+(int)magic.mana(p)+"/"+(int)magic.manaMax(p),
            "§6攻撃: §f"+(int)stat(id,"attack"),"§b防御: §f"+(int)stat(id,"defense"),
            "§d魔力: §f"+(int)stat(id,"magic"),"§a職業: §f"+playerClass(id),
            "§eLv: §f"+progress(id)[0]+(run==null?"":" §7XP "+progress(id)[1]+"/"+xpRequired(progress(id)[0]))};
        for(int i=0;i<lines.length;i++)obj.getScore(lines[i]).setScore(lines.length-i);
        if(p.getScoreboard()!=board){
            previousBoards.put(id,p.getScoreboard());
            p.setScoreboard(board);
        }
    }
}
