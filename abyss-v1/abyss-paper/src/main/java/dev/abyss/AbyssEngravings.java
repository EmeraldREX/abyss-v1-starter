package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Engravings are persistent, detachable inventory records, not vanilla items. */
public final class AbyssEngravings implements Listener {
    private static final String EQUIPMENT_GUI="深淵の迷宮 - 刻印する装備";
    private static final String STONES_GUI="深淵の迷宮 - 刻印の着脱";
    private static final String PREFIX="engraving/";
    private static final String[] IDS={"power","guard","vitality","arcane","spirit","critical","fatal","fortitude","focus","haste",
            "vampire","fire","frost","lightning","poison","pierce","followup","chain","hunter","slayer",
            "regeneration","unyielding","reflect","fireproof","antidote","blessing","rescue","protector","vigor","laststand"};
    private static final String[] NAMES={"剛力","守護","生命","魔導","精神","会心","必殺","堅牢","集中","迅速",
            "吸血","炎撃","氷結","雷撃","毒牙","破甲","追撃","連鎖","狩人","討伐",
            "再生","不屈","反射","耐火","耐毒","加護","救済","守護者","活力","最後の砦"};
    private static final double[] BASE={8,8,10,8,12,4,12,5,8,5,3,10,10,8,12,10,5,12,10,8,1,15,5,40,40,12,15,3,5,12};
    private static final double[] QUALITY={0.375,0.625,0.875,1.125,1.5};
    private final AbyssPlugin plugin;
    private final String url;
    private record BonusCache(long until,Map<String,Double> percentages){}
    private final Map<UUID,BonusCache> cache=new HashMap<>();
    private final Map<UUID,String> selected=new HashMap<>();
    private final Map<UUID,Integer> gearPages=new HashMap<>(),stonePages=new HashMap<>();

    public AbyssEngravings(AbyssPlugin plugin,String jdbcUrl) {
        this.plugin=plugin;url=jdbcUrl;
        try(Connection c=DriverManager.getConnection(url); Statement s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS abyss_engraving_slots (equipment_item_id TEXT NOT NULL,slot_index INTEGER NOT NULL,stone_item_id TEXT NOT NULL UNIQUE,PRIMARY KEY(equipment_item_id,slot_index))");
        } catch(SQLException e){throw new IllegalStateException("Engraving storage initialization failed",e);}
        Bukkit.getScheduler().runTaskTimer(plugin,this::regeneration,100L,100L);
    }
    public static boolean isStone(String id){return id!=null && id.startsWith(PREFIX) && parse(id)!=null;}
    private record Stone(int type,int quality){}
    private static Stone parse(String id){
        if(id==null||!id.startsWith(PREFIX))return null;
        String[] v=id.substring(PREFIX.length()).split("/");
        if(v.length!=2)return null;
        try {int q=Integer.parseInt(v[1]);int t=Arrays.asList(IDS).indexOf(v[0]);return t>=0&&q>=1&&q<=5?new Stone(t,q):null;}
        catch(NumberFormatException ex){return null;}
    }
    private static String roman(int quality){return new String[]{"I","II","III","IV","V"}[quality-1];}
    public static String display(String id){Stone s=parse(id);return s==null?id:NAMES[s.type()]+"の刻印 "+roman(s.quality());}
    public boolean isAttached(String stoneId){
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT 1 FROM abyss_engraving_slots WHERE stone_item_id=?")){
            ps.setString(1,stoneId);try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }catch(SQLException ex){plugin.getLogger().warning("Engraving attachment check failed: "+ex.getMessage());return true;}
    }
    public boolean hasAttachments(String gearId){
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT 1 FROM abyss_engraving_slots WHERE equipment_item_id=?")){
            ps.setString(1,gearId);try(ResultSet rs=ps.executeQuery()){return rs.next();}
        }catch(SQLException ex){plugin.getLogger().warning("Engraving gear check failed: "+ex.getMessage());return true;}
    }
    /** A separate difficulty cap is applied before a high-quality roll. */
    public static String randomStone(String difficulty,int floor,double rewardFactor) {
        int cap=switch(difficulty.toUpperCase(Locale.ROOT)){case "EASY"->3;case "HARD"->5;default->4;};
        double shift=Math.min(2.4,1.0+floor/85.0)*Math.max(0.25,Math.min(2.0,rewardFactor));
        double total=0;double[] weights=new double[cap];
        for(int i=0;i<cap;i++){weights[i]=(new double[]{60,25,10,4,1}[i])*Math.pow(shift,i);total+=weights[i];}
        double r=ThreadLocalRandom.current().nextDouble(total);int quality=1;
        for(int i=0;i<cap;i++){r-=weights[i];if(r<0){quality=i+1;break;}}
        return PREFIX+IDS[ThreadLocalRandom.current().nextInt(IDS.length)]+"/"+quality;
    }
    private static int capacity(String rarity){return switch(rarity){case "レジェンダリー"->5;case "エピック"->4;case "レア"->3;case "アンコモン"->2;default->1;};}
    private record Gear(String itemId,String definitionId,String rarity){}
    private List<Gear> equipment(UUID id){
        List<Gear> list=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement(
                "SELECT i.item_id,i.definition_id,COALESCE(r.rarity,'コモン') FROM abyss_inventory i LEFT JOIN abyss_item_rolls r ON i.item_id=r.item_id WHERE i.owner_uuid=? ORDER BY i.acquired_at DESC,i.item_id")) {
            ps.setString(1,id.toString());try(ResultSet rs=ps.executeQuery()){
                while(rs.next()){AbyssEquipment.Definition d=AbyssEquipment.definition(rs.getString(2));
                    if(d!=null&&!d.slot().startsWith("consumable"))list.add(new Gear(rs.getString(1),rs.getString(2),rs.getString(3)));}
            }
        }catch(SQLException e){plugin.getLogger().warning("Engraving gear list failed: "+e.getMessage());}
        return list;
    }
    private record SavedStone(String itemId,Stone stone){}
    private List<SavedStone> freeStones(UUID owner){
        List<SavedStone> list=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement(
                "SELECT i.item_id,i.definition_id FROM abyss_inventory i LEFT JOIN abyss_engraving_slots s ON s.stone_item_id=i.item_id WHERE i.owner_uuid=? AND i.definition_id LIKE 'engraving/%' AND s.stone_item_id IS NULL ORDER BY i.acquired_at DESC")){
            ps.setString(1,owner.toString());try(ResultSet rs=ps.executeQuery())while(rs.next()){
                Stone stone=parse(rs.getString(2));if(stone!=null)list.add(new SavedStone(rs.getString(1),stone));
            }
        }catch(SQLException e){plugin.getLogger().warning("Engraving stone list failed: "+e.getMessage());}
        return list;
    }
    private ItemStack icon(Material material,String title,String... lore){
        ItemStack stack=new ItemStack(material);ItemMeta m=stack.getItemMeta();m.setDisplayName(title);m.setLore(Arrays.asList(lore));stack.setItemMeta(m);return stack;
    }
    public void open(Player p){openGear(p,0);}
    private void openGear(Player p,int page){
        if(plugin.isInRun(p.getUniqueId())){p.sendMessage("§c攻略中は刻印を変更できません。");return;}
        List<Gear> list=equipment(p.getUniqueId());page=Math.max(0,Math.min(page,Math.max(0,(list.size()-1)/45)));gearPages.put(p.getUniqueId(),page);
        Inventory inv=Bukkit.createInventory(null,54,EQUIPMENT_GUI);
        for(int i=page*45;i<Math.min(list.size(),page*45+45);i++){
            Gear g=list.get(i);AbyssEquipment.Definition d=AbyssEquipment.definition(g.definitionId());
            ItemStack item=icon(d.icon(),"§e"+d.name(),"§7レアリティ: "+g.rarity(),"§d刻印枠: "+capacity(g.rarity()),"§aクリックして刻印を編集");
            inv.setItem(i-page*45,item);
        }
        inv.setItem(45,icon(Material.ARROW,"§e前ページ"));inv.setItem(49,icon(Material.BARRIER,"§cメニューに戻る"));inv.setItem(53,icon(Material.ARROW,"§e次ページ"));p.openInventory(inv);
    }
    private void openStones(Player p,int page){
        String id=selected.get(p.getUniqueId());Gear gear=equipment(p.getUniqueId()).stream().filter(g->g.itemId().equals(id)).findFirst().orElse(null);
        if(gear==null){openGear(p,0);return;}
        List<SavedStone> free=freeStones(p.getUniqueId());page=Math.max(0,Math.min(page,Math.max(0,(free.size()-1)/27)));stonePages.put(p.getUniqueId(),page);
        Inventory inv=Bukkit.createInventory(null,54,STONES_GUI);
        AbyssEquipment.Definition d=AbyssEquipment.definition(gear.definitionId());
        inv.setItem(4,icon(d.icon(),"§e"+d.name(),"§7"+gear.rarity()+" / 刻印枠 "+capacity(gear.rarity())));
        Map<Integer,Stone> mounted=mountedStones(id);
        for(int i=0;i<capacity(gear.rarity());i++){
            Stone s=mounted.get(i);
            inv.setItem(10+i,s==null?icon(Material.GRAY_STAINED_GLASS_PANE,"§7空き刻印枠 "+(i+1)):
                    icon(Material.ENCHANTED_BOOK,"§d"+NAMES[s.type()]+" "+roman(s.quality()),"§7効果量: "+String.format(Locale.ROOT,"%.1f",value(s))+"%","§cクリックで刻印石を取り外す"));
        }
        for(int i=page*27;i<Math.min(free.size(),page*27+27);i++){
            SavedStone stone=free.get(i);Stone s=stone.stone();inv.setItem(18+i-page*27,
                icon(Material.AMETHYST_SHARD,"§d"+NAMES[s.type()]+" "+roman(s.quality()),"§7効果量: "+String.format(Locale.ROOT,"%.1f",value(s))+"%","§aクリックで空き刻印枠へ装着"));
        }
        inv.setItem(45,icon(Material.ARROW,"§e前ページ"));inv.setItem(49,icon(Material.BARRIER,"§c装備一覧へ"));inv.setItem(53,icon(Material.ARROW,"§e次ページ"));p.openInventory(inv);
    }
    private Map<Integer,Stone> mountedStones(String gearId){
        Map<Integer,Stone> stones=new HashMap<>();
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement(
                "SELECT s.slot_index,i.definition_id FROM abyss_engraving_slots s JOIN abyss_inventory i ON i.item_id=s.stone_item_id WHERE s.equipment_item_id=?")){
            ps.setString(1,gearId);try(ResultSet rs=ps.executeQuery())while(rs.next()){
                Stone s=parse(rs.getString(2));if(s!=null)stones.put(rs.getInt(1),s);
            }
        }catch(SQLException e){plugin.getLogger().warning("Engraving slots failed: "+e.getMessage());}
        return stones;
    }
    /** A transaction protects against consuming one stone twice. */
    private void change(Player p,String gearId,String stoneId,int removeSlot){
        if(plugin.isInRun(p.getUniqueId())){p.sendMessage("§c攻略中は刻印を変更できません。");return;}
        UUID owner=p.getUniqueId();
        try(Connection c=DriverManager.getConnection(url)){
            c.setAutoCommit(false);
            try {
                Gear gear=equipment(owner).stream().filter(g->g.itemId().equals(gearId)).findFirst().orElse(null);
                if(gear==null)throw new SQLException("Gear not owned");
                if(removeSlot>=0){
                    try(PreparedStatement ps=c.prepareStatement("DELETE FROM abyss_engraving_slots WHERE equipment_item_id=? AND slot_index=?")){
                        ps.setString(1,gearId);ps.setInt(2,removeSlot);ps.executeUpdate();
                    }
                }else{
                    Stone selectedStone=null;
                    try(PreparedStatement ps=c.prepareStatement("SELECT definition_id FROM abyss_inventory WHERE item_id=? AND owner_uuid=?")){
                        ps.setString(1,stoneId);ps.setString(2,owner.toString());try(ResultSet rs=ps.executeQuery())if(rs.next())selectedStone=parse(rs.getString(1));
                    }
                    if(selectedStone==null)throw new SQLException("Stone not owned");
                    int free=-1;Set<Integer> used=new HashSet<>();Set<Integer> sameTypes=new HashSet<>();
                    try(PreparedStatement ps=c.prepareStatement("SELECT s.slot_index,i.definition_id FROM abyss_engraving_slots s JOIN abyss_inventory i ON s.stone_item_id=i.item_id WHERE s.equipment_item_id=?")){
                        ps.setString(1,gearId);try(ResultSet rs=ps.executeQuery())while(rs.next()){
                            used.add(rs.getInt(1));Stone v=parse(rs.getString(2));if(v!=null)sameTypes.add(v.type());
                        }
                    }
                    if(sameTypes.contains(selectedStone.type()))throw new SQLException("同種の刻印は1装備に1個までです");
                    for(int i=0;i<capacity(gear.rarity());i++)if(!used.contains(i)){free=i;break;}
                    if(free<0)throw new SQLException("刻印枠が埋まっています");
                    try(PreparedStatement ps=c.prepareStatement("INSERT INTO abyss_engraving_slots(equipment_item_id,slot_index,stone_item_id) SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM abyss_engraving_slots WHERE stone_item_id=?)")){
                        ps.setString(1,gearId);ps.setInt(2,free);ps.setString(3,stoneId);ps.setString(4,stoneId);
                        if(ps.executeUpdate()!=1)throw new SQLException("刻印石はすでに装着されています");
                    }
                }
                c.commit();
            }catch(Exception ex){c.rollback();throw ex;}
        }catch(Exception ex){p.sendMessage("§c刻印変更に失敗: "+ex.getMessage());return;}
        p.sendMessage(removeSlot>=0?"§a刻印石を倉庫に戻しました。":"§a刻印石を装着しました。");
        cache.remove(owner);
        openStones(p,stonePages.getOrDefault(owner,0));
    }
    @EventHandler public void click(InventoryClickEvent e){
        String title=e.getView().getTitle();if(!EQUIPMENT_GUI.equals(title)&&!STONES_GUI.equals(title))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        if(plugin.isInRun(p.getUniqueId())){p.closeInventory();return;}
        UUID owner=p.getUniqueId();int slot=e.getRawSlot();
        if(EQUIPMENT_GUI.equals(title)){
            int page=gearPages.getOrDefault(owner,0);
            if(slot==49){plugin.openMainMenu(p);return;}
            if(slot==45){openGear(p,page-1);return;}if(slot==53){openGear(p,page+1);return;}
            int index=page*45+slot;List<Gear> list=equipment(owner);
            if(slot>=0&&slot<45&&index<list.size()){
                selected.put(owner,list.get(index).itemId());openStones(p,0);
            }
        }else{
            if(slot==49){openGear(p,gearPages.getOrDefault(owner,0));return;}
            int page=stonePages.getOrDefault(owner,0);
            if(slot==45){openStones(p,page-1);return;}if(slot==53){openStones(p,page+1);return;}
            String gearId=selected.get(owner);if(gearId==null)return;
            if(slot>=10&&slot<15){if(mountedStones(gearId).containsKey(slot-10))change(p,gearId,null,slot-10);return;}
            if(slot>=18&&slot<45){List<SavedStone> list=freeStones(owner);int index=page*27+slot-18;
                if(index<list.size())change(p,gearId,list.get(index).itemId(),-1);
            }
        }
    }
    private static double value(Stone s){return BASE[s.type()]*QUALITY[s.quality()-1];}
    /** Sum a percent modifier from equipped items only; reusable stones cannot double count. */
    public double percent(UUID owner,String type){
        if(!Arrays.asList(IDS).contains(type))return 0;
        BonusCache old=cache.get(owner);
        long now=System.currentTimeMillis();
        if(old!=null&&old.until()>now)return old.percentages().getOrDefault(type,0.0);
        Map<String,Double> amounts=new HashMap<>();
        try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement(
                "SELECT stone.definition_id,l.slot,gear.definition_id FROM abyss_engraving_slots s JOIN abyss_inventory gear ON gear.item_id=s.equipment_item_id JOIN abyss_loadout l ON l.item_id=gear.item_id AND l.owner_uuid=gear.owner_uuid JOIN abyss_inventory stone ON stone.item_id=s.stone_item_id AND stone.owner_uuid=gear.owner_uuid WHERE gear.owner_uuid=?")){
            ps.setString(1,owner.toString());try(ResultSet rs=ps.executeQuery())while(rs.next()){
                if("weapon".equals(rs.getString(2)) && plugin.equipment()!=null && !plugin.equipment().isWeaponHeld(owner,rs.getString(3)))continue;
                Stone stone=parse(rs.getString(1));if(stone!=null)amounts.merge(IDS[stone.type()],value(stone),Double::sum);
            }
        }catch(SQLException ex){plugin.getLogger().warning("Engraving bonus failed: "+ex.getMessage());}
        cache.put(owner,new BonusCache(now+1500L,amounts));
        return amounts.getOrDefault(type,0.0);
    }
    public double stat(UUID owner,String stat){return switch(stat){
        case "attack"->percent(owner,"power");case "defense"->percent(owner,"guard");
        case "hp"->percent(owner,"vitality");case "magic"->percent(owner,"arcane");
        case "mp"->percent(owner,"spirit");default->0;
    };}
    private boolean inRun(Player p){return plugin.isInRun(p.getUniqueId());}
    private double value(Player p,String type){return percent(p.getUniqueId(),type);}
    private double reduction(Player p){
        double max=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double total=value(p,"fortitude")+(p.getHealth()<=max*0.2?value(p,"laststand"):0)
                +(p.getHealth()<=max*0.3?value(p,"unyielding"):0);
        double allyShield=0;
        for(Player ally:plugin.activePartyMembers(p.getUniqueId())){
            if(ally.getUniqueId().equals(p.getUniqueId()) || !ally.getWorld().equals(p.getWorld()) || ally.getLocation().distanceSquared(p.getLocation())>64)continue;
            allyShield=Math.max(allyShield,value(ally,"protector"));
        }
        return Math.min(35.0,total+allyShield);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onAttack(EntityDamageByEntityEvent e){
        Player p=e.getDamager() instanceof Player player?player:null;
        if(p==null && e.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player shooter)p=shooter;
        if(p!=null && inRun(p) && e.getEntity() instanceof org.bukkit.entity.LivingEntity victim && !(victim instanceof Player)){
            double damage=e.getDamage();ThreadLocalRandom rng=ThreadLocalRandom.current();
            double crit=value(p,"critical");if(rng.nextDouble(100)<crit)damage*=1.5+value(p,"fatal")/100.0;
            damage*=1+value(p,"pierce")/200.0;
            if(rng.nextDouble(100)<value(p,"lightning"))damage*=1.25;
            if(rng.nextDouble(100)<value(p,"followup"))damage*=1.3;
            DungeonRun run=plugin.runFor(p.getUniqueId());
            if(run!=null)damage*=1+(run.atBoss()?value(p,"slayer"):value(p,"hunter"))/100.0;
            if(p.getHealth()>=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()*0.5)damage*=1+value(p,"vigor")/100.0;
            e.setDamage(Math.max(0,damage));
            if(rng.nextDouble(100)<value(p,"fire"))victim.setFireTicks(Math.max(victim.getFireTicks(),80));
            if(rng.nextDouble(100)<value(p,"frost"))victim.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,80,0));
            if(rng.nextDouble(100)<value(p,"poison"))victim.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.POISON,80,0));
            double steal=value(p,"vampire");
            if(steal>0){var hp=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);if(hp!=null)p.setHealth(Math.min(hp.getValue(),p.getHealth()+Math.min(5.0,e.getDamage()*steal/100.0)));}
            double chain=value(p,"chain");
            if(chain>0 && victim.getHealth()<=e.getFinalDamage()){
                for(org.bukkit.entity.Entity nearby:victim.getNearbyEntities(4,3,4)){
                    if(nearby instanceof org.bukkit.entity.Monster monster && nearby!=victim)
                        monster.damage(Math.min(8.0,e.getDamage()*chain/100.0));
                }
            }
        }
        if(e.getEntity() instanceof Player defender && inRun(defender)){
            e.setDamage(e.getDamage()*(1-reduction(defender)/100.0));
            if(e.getDamager() instanceof org.bukkit.entity.LivingEntity attacker && !(attacker instanceof Player)){
                double reflect=value(defender,"reflect");
                if(reflect>0)attacker.damage(Math.min(5,e.getDamage()*reflect/100.0));
            }
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHazard(EntityDamageEvent e){
        if(e instanceof EntityDamageByEntityEvent || !(e.getEntity() instanceof Player p) || !inRun(p))return;
        double factor=1.0-reduction(p)/100.0;
        if(e.getCause()==EntityDamageEvent.DamageCause.FIRE||e.getCause()==EntityDamageEvent.DamageCause.FIRE_TICK||e.getCause()==EntityDamageEvent.DamageCause.LAVA)
            factor*=1-Math.min(80,value(p,"fireproof"))/100.0;
        if(e.getCause()==EntityDamageEvent.DamageCause.POISON)factor*=1-Math.min(80,value(p,"antidote"))/100.0;
        e.setDamage(e.getDamage()*factor);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHeal(EntityRegainHealthEvent e){
        if(e.getEntity() instanceof Player p && inRun(p))
            e.setAmount(e.getAmount()*(1+Math.max(value(p,"blessing"),value(p,"rescue"))/100.0));
    }
    private void regeneration(){
        for(Player p:Bukkit.getOnlinePlayers())if(inRun(p) && !p.isDead()){
            double regen=value(p,"regeneration");var attr=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if(attr!=null && regen>0 && p.getHealth()>0)p.setHealth(Math.min(attr.getValue(),p.getHealth()+attr.getValue()*regen/100.0));
        }
    }
}
