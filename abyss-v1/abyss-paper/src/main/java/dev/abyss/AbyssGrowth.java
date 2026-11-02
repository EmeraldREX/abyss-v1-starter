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
    public int pending(UUID id){return pending.getOrDefault(id,0);}
    /** Do not interrupt combat, floor generation, or an existing menu. */
    public boolean canChoose(Player p){
        var run=plugin.runFor(p.getUniqueId());
        return run!=null && run.objectiveDone() && run.remaining()==0;
    }
    public void notifyPending(Player p) {
        int count=pending(p.getUniqueId());
        if(count>0)p.sendMessage("§eレベルアップ強化 "+count+" 回分が選択可能です。メインメニューの成長・強化を開いてください。");
    }
    public void afterFloor(Player p) {
        if(!p.isOnline()||!canChoose(p)||pending(p.getUniqueId())<=0)return;
        if(p.getOpenInventory().getTopInventory().getType()!=org.bukkit.event.inventory.InventoryType.CRAFTING){notifyPending(p);return;}
        open(p);
    }
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
        // Accumulate levels while mobs are alive. Show all choices only after
        // the current floor's objective is complete; never interrupt combat.
        p.sendMessage("§a成長ポイント +"+count+" §7(フロアクリア後に選択できます)");
    }
    public void open(Player p){
        UUID id=p.getUniqueId();
        boolean inRun=plugin.isInRun(id);
        if(!opening.add(id))return;
        try{
            Inventory inv=Bukkit.createInventory(null,27,TITLE);
            int points=pending(id);
            int level=inRun?plugin.stats().progress(id)[0]:1;
            make(inv,4,Material.EXPERIENCE_BOTTLE,"§e成長ステータス",
                "§7攻略レベル: "+level,
                "§7未使用ポイント: "+points,
                "§7攻撃力 +"+(int)bonus(id,"attack")+" / 防御力 +"+(int)bonus(id,"defense"),
                "§7HP +"+(int)bonus(id,"hp")+" / 魔力 +"+(int)bonus(id,"magic"),
                "§7スキル熟練度: Lv."+mastery(id),
                "§7この強化は今回の攻略のみ有効");
            if(canChoose(p) && points>0){
                String focus=focus(id);
                make(inv,11,Material.IRON_SWORD,"§c"+label(focus)+"を強化",
                    "§7"+label(focus)+" +"+("magic".equals(focus)?4:3),
                    "§7職業に合わせた基本能力の強化");
                make(inv,13,Material.GOLDEN_APPLE,"§a生存力を強化","§7最大HP +10 / 防御力 +1","§7耐久型ビルド向け");
                make(inv,15,Material.ENCHANTED_BOOK,"§d職業スキルを強化",
                    "§7スキル威力・回復効果 +12%","§7FキースキルのCDを0.5秒短縮");
            }else{
                make(inv,13,Material.BARRIER,!inRun?"§7冒険開始後に成長できます":points>0?"§c戦闘終了後に選択できます":"§7選択可能な強化なし",
                    "§7未使用ポイントは次回のフロアクリア時まで保持されます");
            }
            make(inv,22,Material.ARROW,"§eメインメニューに戻る","§7選択を保留して戻れます");
            p.openInventory(inv);
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
        int slot=e.getRawSlot();
        if(slot==22){
            Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())plugin.openMainMenu(p);});
            return;
        }
        if(slot!=11&&slot!=13&&slot!=15)return;
        UUID id=p.getUniqueId();
        if(!canChoose(p)||pending(id)<=0)return;
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
        if(remaining<=0){
            pending.remove(id);p.closeInventory();
            Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())plugin.openPendingPerk(p);});
        } else Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline()&&canChoose(p))open(p);});
    }
    @EventHandler public void close(org.bukkit.event.inventory.InventoryCloseEvent e){
        if(!TITLE.equals(e.getView().getTitle())||!(e.getPlayer() instanceof Player p))return;
        // Permit closing the GUI; pending points are retained and reopened via /abyss growth.
    }
}
