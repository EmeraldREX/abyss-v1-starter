package dev.abyss;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import java.sql.*;
import java.util.*;

/** Initial magic prototype: staff charging and two fixed test spells. */
public final class AbyssMagic implements Listener {
    private final AbyssPlugin plugin;
    private final AbyssEquipment equipment;
    private final String url, dungeon;
    private final NamespacedKey controlKey;
    private final NamespacedKey navigationKey;
    private final Map<UUID,Integer> charges=new HashMap<>();
    private final Map<UUID,Double> mana=new HashMap<>();
    private final Map<UUID,Long> lastRegen=new HashMap<>();
    private final Map<UUID,Long> lastCharge=new HashMap<>(), lastCast=new HashMap<>();
    public AbyssMagic(AbyssPlugin plugin,AbyssEquipment equipment,String url,String dungeon) {
        this.plugin=plugin;this.equipment=equipment;this.url=url;this.dungeon=dungeon;
        this.controlKey=new NamespacedKey(plugin,"combat_control");
        this.navigationKey=new NamespacedKey(plugin,"navigation_item");
        try(Connection c=DriverManager.getConnection(url);Statement st=c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS abyss_spells (owner_uuid TEXT NOT NULL, spell_id TEXT NOT NULL, PRIMARY KEY(owner_uuid,spell_id))");
            st.execute("CREATE TABLE IF NOT EXISTS abyss_spell_slots (owner_uuid TEXT NOT NULL, slot INTEGER NOT NULL, spell_id TEXT NOT NULL, PRIMARY KEY(owner_uuid,slot))");
        } catch(SQLException e){throw new IllegalStateException(e);}

    }
    public boolean isControl(ItemStack stack) {
        return stack!=null&&stack.hasItemMeta()&&stack.getItemMeta().getPersistentDataContainer().has(controlKey,PersistentDataType.STRING);
    }
    private ItemStack token(Material material,String name,String action,List<String> lore) {
        ItemStack item=new ItemStack(material);
        ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(lore);
        meta.getPersistentDataContainer().set(controlKey,PersistentDataType.STRING,action);
        item.setItemMeta(meta);return item;
    }
    public static final String SPELL_GUI="深淵ダンジョン - 魔法管理";
    private static final String[] SPELLS={"fire","ice","thunder","heal","protect"};
    private static String spellName(String id){if(id==null)return "未登録";return switch(id){case "fire"->"ファイア";case "ice"->"アイス";case "thunder"->"サンダー";case "heal"->"ヒール";case "protect"->"プロテクト";default->"未登録";};}
    private static Material spellIcon(String id){if(id==null)return Material.BARRIER;return switch(id){case "fire"->Material.BLAZE_POWDER;case "ice"->Material.SNOWBALL;case "thunder"->Material.LIGHTNING_ROD;case "heal"->Material.GHAST_TEAR;case "protect"->Material.SHIELD;default->Material.BARRIER;};}
    private static String spellEffect(String id){return switch(id){case "fire"->"単体攻撃・燃焼";case "ice"->"単体攻撃・鈍足";case "thunder"->"単体攻撃・弱体化";case "heal"->"HP回復";case "protect"->"8秒間被ダメージ30%軽減";default->"なし";};}
    private static int cost(String id){if(id==null)return 999;return switch(id){case "fire"->8;case "ice"->10;case "thunder"->14;case "heal"->6;case "protect"->12;default->999;};}
    // Class-specific spell permissions and potency; server-side checks also protect old saved slots.
    private String job(UUID id) { return plugin.stats()==null ? "warrior" : plugin.stats().playerClass(id); }
    private boolean canCast(UUID id,String spell) {
        return switch(job(id)) {
            case "mage" -> true;
            case "priest" -> List.of("heal","protect","ice").contains(spell);
            case "paladin" -> List.of("heal","protect").contains(spell);
            case "archer" -> List.of("ice","thunder").contains(spell);
            case "assassin" -> List.of("fire","ice").contains(spell);
            default -> false; // warrior, monk, berserker
        };
    }
    private double spellFactor(UUID id,String spell) {
        String cls=job(id);
        if("heal".equals(spell)) return switch(cls){case "priest"->1.65;case "paladin"->1.10;case "mage"->0.60;default->0.75;};
        return switch(cls){case "mage"->1.35;case "priest"->0.75;case "archer","assassin"->0.90;default->1.0;};
    }
    private final Map<UUID,Integer> selection=new HashMap<>();
    private final Map<UUID,Long> protection=new HashMap<>();
    private String slot(UUID id,int n){try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT spell_id FROM abyss_spell_slots WHERE owner_uuid=? AND slot=?")){ps.setString(1,id.toString());ps.setInt(2,n);try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}}catch(SQLException ex){plugin.getLogger().warning("Spell slot read failed: "+ex);return null;}}
    private boolean learned(UUID id,String spell){ if(Arrays.asList(SPELLS).contains(spell)) return true;try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("SELECT 1 FROM abyss_spells WHERE owner_uuid=? AND spell_id=?")){ps.setString(1,id.toString());ps.setString(2,spell);try(ResultSet rs=ps.executeQuery()){return rs.next();}}catch(SQLException ex){return false;}}
    public void learn(Player p,String id){if(!Arrays.asList(SPELLS).contains(id)){p.sendMessage("§c魔法IDが不正です。");return;}try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("INSERT OR IGNORE INTO abyss_spells(owner_uuid,spell_id) VALUES(?,?)")){ps.setString(1,p.getUniqueId().toString());ps.setString(2,id);ps.executeUpdate();p.sendMessage("§a習得: "+spellName(id));}catch(SQLException ex){p.sendMessage("§c習得保存に失敗しました。");}}
    public void open(Player p){org.bukkit.inventory.Inventory inv=Bukkit.createInventory(null,27,SPELL_GUI);for(int i=0;i<SPELLS.length;i++){String id=SPELLS[i];inv.setItem(i+10,token(spellIcon(id),(learned(p.getUniqueId(),id)?"§a":"§8")+spellName(id),"spell_menu",List.of("§9消費MP: "+cost(id),"§7効果: "+spellEffect(id),!canCast(p.getUniqueId(),id)?"§c現在の職業では使用不可":learned(p.getUniqueId(),id)?"§eクリックで選択中の枠に登録":"§a初期解放")));}inv.setItem(21,token(Material.BOOK,"§e魔法枠1: "+spellName(slot(p.getUniqueId(),1)),"spell_menu",List.of("§7クリックで登録先を選択")));inv.setItem(23,token(Material.BOOK,"§e魔法枠2: "+spellName(slot(p.getUniqueId(),2)),"spell_menu",List.of("§7クリックで登録先を選択")));inv.setItem(26,token(Material.ARROW,"§e戻る","spell_menu",List.of("§7メインメニューへ")));p.openInventory(inv);}
    @EventHandler public void menu(org.bukkit.event.inventory.InventoryClickEvent e){if(!e.getView().getTitle().equals(SPELL_GUI))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;if(plugin.isInRun(p.getUniqueId())){p.sendMessage("§c攻略中は魔法を変更できません。");return;}int n=e.getRawSlot();if(n==26){p.closeInventory();Bukkit.dispatchCommand(p,"abyss menu");return;}if(n==21||n==23){selection.put(p.getUniqueId(),n==21?1:2);open(p);return;}if(n<10||n>=15)return;String id=SPELLS[n-10];if(!canCast(p.getUniqueId(),id)){p.sendMessage("§c現在の職業ではこの魔法を使用できません。");return;}if(!learned(p.getUniqueId(),id)){p.sendMessage("§cこの魔法は使用できません: "+id);return;}int target=selection.getOrDefault(p.getUniqueId(),1);try(Connection c=DriverManager.getConnection(url);PreparedStatement ps=c.prepareStatement("INSERT INTO abyss_spell_slots(owner_uuid,slot,spell_id) VALUES(?,?,?) ON CONFLICT(owner_uuid,slot) DO UPDATE SET spell_id=excluded.spell_id")){ps.setString(1,p.getUniqueId().toString());ps.setInt(2,target);ps.setString(3,id);ps.executeUpdate();p.sendMessage("§a魔法枠"+target+"に"+spellName(id)+"を登録しました。");open(p);}catch(SQLException ex){p.sendMessage("§c登録に失敗しました。");}}
    public void fillMana(Player p){mana.put(p.getUniqueId(),maxMana(p.getUniqueId()));}
    public double mana(Player p){return currentMana(p.getUniqueId());}
    public double manaMax(Player p){return maxMana(p.getUniqueId());}
    private String weaponId(UUID player) {
        String item=equipment.equipped(player).get("weapon");
        if(item==null)return null;
        try(Connection c=DriverManager.getConnection(url);PreparedStatement p=c.prepareStatement("SELECT definition_id FROM abyss_inventory WHERE owner_uuid=? AND item_id=?")) {
            p.setString(1,player.toString());p.setString(2,item);
            try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}
        } catch(SQLException ex){plugin.getLogger().warning("Weapon read failed: "+ex);return null;}
    }
    private double maxMana(UUID id){return plugin.stats()==null?40.0+equipment.stat(id,"mana"):plugin.stats().stat(id,"mp");}
    private double currentMana(UUID id){return Math.min(maxMana(id),mana.computeIfAbsent(id,k->maxMana(id)));}
    private void showMana(Player p){p.sendActionBar("§9MP "+String.format(java.util.Locale.ROOT,"%.0f",currentMana(p.getUniqueId()))+"/"+String.format(java.util.Locale.ROOT,"%.0f",maxMana(p.getUniqueId()))+" §d魔力 +"+String.format(java.util.Locale.ROOT,"%.0f",equipment.stat(p.getUniqueId(),"magic")));}
    private boolean active(Player p){return p.getWorld().getName().equals(dungeon)&&plugin.isInRun(p.getUniqueId());}
    public void refresh(Player p) {
        if(!p.getWorld().getName().equals(dungeon)&&!p.getWorld().getName().equals(plugin.getConfig().getString("lobby-world","abyss_lobby")))return;
        boolean run=active(p);
        ItemStack off=p.getInventory().getItemInOffHand();
        if(run){
            String offId=equipment.equippedDefinition(p.getUniqueId(),"offhand");
            if(off!=null&&!off.getType().isAir()&&!isControl(off)){
                int free=p.getInventory().firstEmpty();
                if(free>=0){p.getInventory().setItem(free,off);p.getInventory().setItemInOffHand(null);}
                else {plugin.getLogger().warning("Cannot show offhand icon: inventory full for "+p.getName());offId=null;}
            }
            if(offId!=null){AbyssEquipment.Definition d=AbyssEquipment.definition(offId);if(d!=null)p.getInventory().setItemInOffHand(token(d.icon(),"§b"+d.name(),"offhand",List.of("§7オフハンド装備", "§7装備効果は常時適用")));}
            else if(isControl(p.getInventory().getItemInOffHand()))p.getInventory().setItemInOffHand(null);
        }else if(isControl(off))p.getInventory().setItemInOffHand(null);
        // Slot 4 belongs exclusively to AbyssInventory.refreshWeapon().
        // Do not treat its navigation_item=weapon display as a foreign item:
        // doing so displaced the equipped weapon into slot 9 every second.
        // Magic owns only the two spell control slots (5 and 6).
        for (int i=1; i<=2; i++) {
            int slot=i+4;
            ItemStack current=p.getInventory().getItem(slot);
            if(current!=null&&!current.getType().isAir()&&!isControl(current)) {
                int free=-1;
                for(int j=9;j<36;j++){ItemStack candidate=p.getInventory().getItem(j);if(candidate==null||candidate.getType().isAir()){free=j;break;}}
                if(free<0)continue;
                p.getInventory().setItem(free,current);p.getInventory().setItem(slot,null);
            }
            if(!run){if(isControl(p.getInventory().getItem(slot)))p.getInventory().setItem(slot,null);continue;}
            String spell=slot(p.getUniqueId(),i);
            ItemStack icon=token(spell==null?Material.BARRIER:spellIcon(spell),"§d"+spellName(spell)+" §7(右クリック)",
                    i==1?"spell_1":"spell_2",
                    List.of(spell==null?"§7ロビーの魔法管理で登録":"§9消費MP: "+cost(spell)));
            p.getInventory().setItem(slot,icon);
        }
    }
    @EventHandler public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND||!event.getAction().isRightClick())return;
        Player p=event.getPlayer();ItemStack item=event.getItem();
        if(item==null || !item.hasItemMeta() || !active(p))return;
        String action=item.getItemMeta().getPersistentDataContainer().get(controlKey,PersistentDataType.STRING);
        // Phase 49: weapon slot 4 is rendered by AbyssInventory, not AbyssMagic.
        // Only the equipped Abyss staff may use this tagged weapon display to charge.
        boolean displayedWeapon="weapon".equals(
                item.getItemMeta().getPersistentDataContainer().get(navigationKey,PersistentDataType.STRING));
        if(action==null && displayedWeapon && p.getInventory().getHeldItemSlot()==4
                && "abyss_staff".equals(weaponId(p.getUniqueId()))) action="weapon";
        if(action==null)return;
        int held=p.getInventory().getHeldItemSlot();
        if(("weapon".equals(action)&&held!=4)||("spell_1".equals(action)&&held!=5)||("spell_2".equals(action)&&held!=6))return;
        // Do not cancel normal right-clicks: the offhand shield needs them to start blocking.
        if("weapon".equals(action)&&!"abyss_staff".equals(weaponId(p.getUniqueId())))return;
        event.setCancelled(true);
        long now=System.currentTimeMillis();UUID id=p.getUniqueId();
        if("weapon".equals(action)) {
            if(!"abyss_staff".equals(weaponId(id)))return;
            if(now-lastCharge.getOrDefault(id,0L)<350L)return;
            lastCharge.put(id,now);
            int count=Math.min(3,charges.getOrDefault(id,0)+1);charges.put(id,count);
            p.sendActionBar("§d魔力チャージ "+count+"/3 §7| §e次の攻撃魔法 +"+(count*10)+"%");
            refresh(p);return;
        }
        long remainingCd=lastCast.getOrDefault(id,0L)+3000L-now;
        if(remainingCd>0){p.sendActionBar("§c魔法CD "+((remainingCd+999)/1000)+"秒");return;}
        int charge=charges.getOrDefault(id,0);
        String spell=slot(id,"spell_1".equals(action)?1:2);
        if(spell==null||!learned(id,spell)){p.sendActionBar("§c魔法が登録されていません");return;}
        if(!canCast(id,spell)){p.sendActionBar("§c現在の職業ではこの魔法を使用できません");return;}
        double cost=cost(spell);
        if(currentMana(id)<cost){p.sendActionBar("§cMPが足りません §9("+String.format(java.util.Locale.ROOT,"%.0f",currentMana(id))+"/"+String.format(java.util.Locale.ROOT,"%.0f",cost)+")");return;}
        double magicPower=equipment.stat(id,"magic");
        if(List.of("fire","ice","thunder").contains(spell)) {
            RayTraceResult ray=p.getWorld().rayTraceEntities(p.getEyeLocation(),p.getEyeLocation().getDirection(),12,0.6,entity->entity instanceof LivingEntity&&!entity.getUniqueId().equals(id));
            if(ray==null||!(ray.getHitEntity() instanceof LivingEntity target)){p.sendActionBar("§c射程内に対象がいません");return;}
            // Base damage and charge multiplier are temporary until the complete magic/stat system arrives.
            double damage=(switch(spell){case "ice"->7.0;case "thunder"->10.0;default->6.0;}+magicPower*0.35)*(1.0+charge*0.10)*spellFactor(id,spell);
            target.damage(damage,p);
            if("fire".equals(spell)) target.setFireTicks(Math.max(target.getFireTicks(),60));
            if("ice".equals(spell)) target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,80,1));
            if("thunder".equals(spell)) target.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WEAKNESS,80,0));
            p.sendActionBar("§c"+spellName(spell)+"！ §7威力 "+String.format(java.util.Locale.ROOT,"%.1f",damage)+" §d(チャージ消費)");
        } else if("heal".equals(spell)) {
            double max=p.getAttribute(Attribute.MAX_HEALTH).getValue();
            if(p.getHealth()>=max-0.01){p.sendActionBar("§eHPは満タンです");return;}
            // Charge increases healing too, but the full magic stat system is not implemented.
            double amount=(6.0+magicPower*0.20)*(1.0+charge*0.10)*spellFactor(id,spell);
            p.setHealth(Math.min(max,p.getHealth()+amount));
            p.sendActionBar("§aヒール！ §7回復 "+String.format(java.util.Locale.ROOT,"%.1f",amount));
        } else if("protect".equals(spell)) {protection.put(id,now+8000L);p.sendActionBar("§bプロテクト！ §7被ダメージ30%軽減 (8秒)");}
        else return;
        mana.put(id,currentMana(id)-cost);charges.remove(id);lastCast.put(id,now);refresh(p);showMana(p);
    }
    @EventHandler public void protect(org.bukkit.event.entity.EntityDamageEvent e){if(e.getEntity() instanceof Player p && active(p)&&System.currentTimeMillis()<protection.getOrDefault(p.getUniqueId(),0L))e.setDamage(e.getDamage()*0.7);}
    @EventHandler public void quit(PlayerQuitEvent event){clear(event.getPlayer().getUniqueId());}
    @EventHandler public void world(PlayerChangedWorldEvent event){clear(event.getPlayer().getUniqueId());}
    public void clear(UUID id){charges.remove(id);lastCharge.remove(id);lastCast.remove(id);mana.remove(id);lastRegen.remove(id);protection.remove(id);}
    public void tick(){for(Player p:Bukkit.getOnlinePlayers()){
        UUID id=p.getUniqueId();
        if(!active(p)){clear(id);refresh(p);continue;}
        long now=System.currentTimeMillis();
        long last=lastRegen.getOrDefault(id,now);
        if(now-last>=1000L){double steps=Math.min(5,(now-last)/1000L)*(plugin.mutations()==null?1:plugin.mutations().manaRegenMultiplier(id));mana.put(id,Math.min(maxMana(id),currentMana(id)+steps));lastRegen.put(id,now);}
        else lastRegen.putIfAbsent(id,now);
        refresh(p);
    }}
}
