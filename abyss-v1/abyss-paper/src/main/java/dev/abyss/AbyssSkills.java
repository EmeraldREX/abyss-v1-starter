package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import java.util.*;

/** Run-only sneak + right click skills using the equipped weapon. */
public final class AbyssSkills implements Listener {
    private final AbyssPlugin plugin;
    private final Map<UUID,Long> cooldown=new HashMap<>();
    private final Map<UUID,Integer> selected=new HashMap<>();
    private static final String TITLE="深淵の迷宮 - スキル選択";
    public AbyssSkills(AbyssPlugin plugin){this.plugin=plugin;}
    public void clear(UUID id){cooldown.remove(id); /* selected skill persists between runs */}
    private String job(Player p){return plugin.stats().playerClass(p.getUniqueId());}
    private String[] names(Player p){return switch(job(p)){
        case "warrior"->new String[]{"薙ぎ払い","鉄壁"};
        case "mage"->new String[]{"魔力爆発","魔力集中"};
        case "priest"->new String[]{"聖なる癒し","祝福"};
        case "archer"->new String[]{"連射","狙撃"};
        case "paladin"->new String[]{"聖盾","聖撃"};
        case "assassin"->new String[]{"急所突き","影走り"};
        case "berserker"->new String[]{"狂乱撃","血の渇望"};
        default->new String[]{"連撃","気功"};
    };}
    private List<String> skillLore(Player p,int index) {
        String cls=job(p);
        String detail;
        if(index==0) detail=switch(cls){
            case "priest"->"自分のHPを10回復";
            case "paladin"->"自分に吸収HPを8付与";
            case "mage"->"周囲8ブロックの敵に魔力範囲攻撃";
            case "archer"->"周囲8ブロックの敵を最大3体攻撃";
            case "assassin"->"周囲8ブロックの敵1体に強攻撃";
            default->"周囲8ブロックの敵を最大5体攻撃";
        };else detail=switch(cls){
            case "priest","monk"->"5秒間、再生能力を付与";
            case "warrior","paladin"->"5秒間、耐性を付与";
            case "assassin"->"5秒間、移動速度上昇";
            case "mage"->"4秒間、攻撃力上昇";
            case "archer"->"周囲8ブロックの敵に範囲攻撃";
            default->"5秒間、攻撃力上昇";
        };
        return List.of("§b効果: §f"+detail,"§7武器を5番スロットに持ちFキーで発動", "§7クールタイム: 12秒", "§7攻略中のみ使用可能");
    }
    public void open(Player p){
        
        Inventory inv=Bukkit.createInventory(null,27,TITLE);String[] names=names(p);
        for(int i=0;i<2;i++){
            ItemStack item=new ItemStack(i==0?Material.IRON_SWORD:Material.NETHER_STAR);
            ItemMeta meta=item.getItemMeta();meta.setDisplayName("§e"+names[i]);
            meta.setLore(skillLore(p,i));item.setItemMeta(meta);inv.setItem(i==0?11:15,item);
        }
        inv.setItem(22,backItem());
        p.openInventory(inv);
    }
    private ItemStack backItem(){ItemStack it=new ItemStack(Material.BARRIER);ItemMeta m=it.getItemMeta();m.setDisplayName("§7戻る");it.setItemMeta(m);return it;}
    @EventHandler public void click(InventoryClickEvent e){
        if(!e.getView().getTitle().equals(TITLE))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p))return;
        if(e.getRawSlot()==22){plugin.openMainMenu(p);return;}
        int index=e.getRawSlot()==11?0:e.getRawSlot()==15?1:-1;
        if(index<0)return;
        selected.put(p.getUniqueId(),index);p.closeInventory();p.sendMessage("§a職業スキル: "+names(p)[index]);
    }
    /** Primary activation: swap-hand key (F). Event is cancelled so the shield/offhand is preserved. */
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p=e.getPlayer();
        if (!plugin.isInRun(p.getUniqueId())) return;
        if (!hasEquippedWeapon(p)) return;
        e.setCancelled(true);
        cast(p);
    }
    /** Compatibility activation for sneak+right-click, including on interactable blocks. */
    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=false)
    public void activate(PlayerInteractEvent e) {
        Player p=e.getPlayer();
        if(e.getHand()!=EquipmentSlot.HAND || !p.isSneaking())return;
        if(e.getAction()!=Action.RIGHT_CLICK_AIR && e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        if(!plugin.isInRun(p.getUniqueId()) || !hasEquippedWeapon(p))return;
        e.setCancelled(true);
        cast(p);
    }
    private boolean hasEquippedWeapon(Player p) {
        if(p.getInventory().getHeldItemSlot()!=4)return false;
        ItemStack weapon=p.getInventory().getItemInMainHand();
        if(weapon==null || weapon.getType().isAir())return false;
        // Inventory slot 5 is exclusively populated by the equipped weapon.
        // Do not rely solely on the PDC marker: older weapon copies can lack it.
        String id=plugin.equipment().equippedDefinition(p.getUniqueId(),"weapon");
        return id!=null && dev.abyss.AbyssEquipment.definition(id)!=null;
    }
    public void cast(Player p) {
        DungeonRun run=plugin.runFor(p.getUniqueId());
        if(run==null){p.sendActionBar("§cスキルは攻略中のみ使用できます");return;}
        if(!hasEquippedWeapon(p)){p.sendMessage("§cホットバー5番目に装備武器を持ってください。");return;}
        long now=System.currentTimeMillis();UUID id=p.getUniqueId();long left=cooldown.getOrDefault(id,0L)-now;
        if(left>0){p.sendActionBar("§cスキルCD: "+((left+999)/1000)+"秒");return;}
        int index=selected.getOrDefault(id,0);String cls=job(p);String name=names(p)[index];
        List<LivingEntity> nearby=new ArrayList<>();
        for(LivingEntity mob:plugin.enemiesFor(run))if(mob.getLocation().distanceSquared(p.getLocation())<=64)nearby.add(mob);
        double power=plugin.stats().stat(id,"attack");
        if(index==0){
            if(cls.equals("priest")){double max=p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();p.setHealth(Math.min(max,p.getHealth()+10));}
            else if(cls.equals("paladin")){p.setAbsorptionAmount(Math.min(20,p.getAbsorptionAmount()+8));}
            else if(cls.equals("mage")){for(LivingEntity mob:nearby)mob.damage(7+plugin.stats().stat(id,"magic")*0.4,p);}
            else {int limit=cls.equals("archer")?3:cls.equals("assassin")?1:5;for(int i=0;i<Math.min(limit,nearby.size());i++)nearby.get(i).damage(Math.max(2,power*(cls.equals("assassin")?1.3:0.5)),p);}
        } else {
            if(cls.equals("priest")||cls.equals("monk")){p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION,100,1));}
            else if(cls.equals("warrior")||cls.equals("paladin")){p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE,100,0));}
            else if(cls.equals("assassin")){p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED,100,1));}
            else if(cls.equals("mage")){p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH,80,0));}
            else if(cls.equals("archer")){for(LivingEntity mob:nearby)mob.damage(Math.max(2,power*0.65),p);}
            else {p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.STRENGTH,100,0));}
        }
        cooldown.put(id,now+12000L);p.sendActionBar("§a"+name+"！ §7CD 12秒");
        p.sendMessage("§aスキル発動: §e"+name+" §7("+skillLore(p,index).get(0).replace("§b効果: §f", "")+")");
        p.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT, p.getLocation().add(0,1,0), 28, 0.7,0.7,0.7,0.1);
    }
    public void tick(){long now=System.currentTimeMillis();for(Player p:Bukkit.getOnlinePlayers()){
        if(!plugin.isInRun(p.getUniqueId())
                || !hasEquippedWeapon(p))continue;
        long left=cooldown.getOrDefault(p.getUniqueId(),0L)-now;
        p.sendActionBar(left>0?"§e"+names(p)[selected.getOrDefault(p.getUniqueId(),0)]+" §7CD "+((left+999)/1000)+"秒":"§aFキー: "+names(p)[selected.getOrDefault(p.getUniqueId(),0)]+" §7使用可能");
    }}
}
