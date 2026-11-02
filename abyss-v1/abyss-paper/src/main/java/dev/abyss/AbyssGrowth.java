package dev.abyss;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.*;

/** Phase 50: run-only level-up build choices. No database persistence. */
public final class AbyssGrowth implements Listener {
    private static final String TITLE="深淵の迷宮 - レベルアップ強化";
    private final AbyssPlugin plugin;
    private final Map<UUID,Integer> pending=new HashMap<>();
    private final Map<UUID,Map<String,Integer>> bonuses=new HashMap<>();
    private final Map<UUID,Integer> mastery=new HashMap<>();
    private final Set<UUID> opening=new HashSet<>();
    public AbyssGrowth(AbyssPlugin plugin){this.plugin=plugin;}
    public void beginRun(UUID id){pending.remove(id);bonuses.put(id,new HashMap<>());mastery.remove(id);opening.remove(id);}
    public void endRun(UUID id){pending.remove(id);bonuses.remove(id);mastery.remove(id);opening.remove(id);}
    public double bonus(UUID id,String stat){return bonuses.getOrDefault(id,Map.of()).getOrDefault(stat,0);}
    public int mastery(UUID id){return mastery.getOrDefault(id,0);}
    public double skillFactor(UUID id){return 1.0+0.12*mastery(id);}
    public long skillCooldown(UUID id){return Math.max(6000L,12000L-500L*mastery(id));}
    private String focus(UUID id){
        return switch(plugin.stats().playerClass(id)){
            case "mage","priest" -> "magic";
            case "paladin" -> "defense";
            case "archer","assassin","berserker","warrior","monk" -> "attack";
            default -> "attack";
        };
    }
    private String label(String stat){return switch(stat){
        case "hp"->"最大HP";case "mp"->"最大MP";case "magic"->"魔力";
        case "defense"->"防御力";default->"攻撃力";
    };}
    public void levelUp(Player p,int count){
        if(count<=0||!plugin.isInRun(p.getUniqueId()))return;
        UUID id=p.getUniqueId();
        pending.merge(id,count,Integer::sum);
        Bukkit.getScheduler().runTask(plugin,()->{
            if(p.isOnline()&&plugin.isInRun(id))open(p);
        });
    }
    public void open(Player p){
        UUID id=p.getUniqueId();
        if(!plugin.isInRun(id)||pending.getOrDefault(id,0)<=0)return;
        if(!opening.add(id))return;
        try{
            Inventory inv=Bukkit.createInventory(null,27,TITLE);
            String focus=focus(id);
            make(inv,11,Material.IRON_SWORD,"§c"+label(focus)+"を強化",
                "§7"+label(focus)+" +"+("attack".equals(focus)?3:"magic".equals(focus)?4:3),
                "§7職業に合わせた基本能力の強化");
            make(inv,13,Material.GOLDEN_APPLE,"§a生存力を強化","§7最大HP +10 / 防御力 +1","§7耐久型ビルド向け");
            make(inv,15,Material.ENCHANTED_BOOK,"§d職業スキルを強化",
                "§7スキル威力・回復効果 +12%","§7FキースキルのCDを0.5秒短縮");
            p.openInventory(inv);
            p.sendMessage("§eレベルアップ強化を選択してください。残り "+pending.get(id)+" 回");
        } finally {opening.remove(id);}
    }
    private void make(Inventory inv,int slot,Material mat,String name,String... lore){
        ItemStack item=new ItemStack(mat);ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(name);meta.setLore(List.of(lore));item.setItemMeta(meta);inv.setItem(slot,item);
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(!TITLE.equals(e.getView().getTitle()))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory()!=e.getView().getTopInventory())return;
        int slot=e.getRawSlot();if(slot!=11&&slot!=13&&slot!=15)return;
        UUID id=p.getUniqueId();
        if(!plugin.isInRun(id)||pending.getOrDefault(id,0)<=0)return;
        if(slot==11){
            String focus=focus(id);int val="magic".equals(focus)?4:3;
            bonuses.computeIfAbsent(id,k->new HashMap<>()).merge(focus,val,Integer::sum);
            p.sendMessage("§a"+label(focus)+" +"+val);
        }else if(slot==13){
            Map<String,Integer> map=bonuses.computeIfAbsent(id,k->new HashMap<>());
            map.merge("hp",10,Integer::sum);map.merge("defense",1,Integer::sum);
            p.sendMessage("§a最大HP +10 / 防御力 +1");
        }else{
            mastery.merge(id,1,Integer::sum);
            p.sendMessage("§d職業スキル強化 Lv."+mastery(id));
        }
        int remaining=pending.merge(id,-1,Integer::sum);
        if(remaining<=0){pending.remove(id);p.closeInventory();}
        else Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline()&&plugin.isInRun(id))open(p);});
    }
    @EventHandler public void close(org.bukkit.event.inventory.InventoryCloseEvent e){
        if(!TITLE.equals(e.getView().getTitle())||!(e.getPlayer() instanceof Player p))return;
        // Permit closing the GUI; pending points are retained and reopened via /abyss growth.
    }
}
