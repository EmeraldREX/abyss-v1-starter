package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import dev.abyss.dungeon.FloorType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Interactive exploration clue and event choice instead of automatic free floors. */
public final class AbyssFloorActivities implements Listener {
    private static final String EXPLORE="深淵の迷宮 - 探索の分岐";
    private static final String EVENT="深淵の迷宮 - 遭遇イベント";
    private final AbyssPlugin plugin;
    private record State(DungeonRun run,int correct) {}
    private final Map<UUID,State> current=new HashMap<>();
    public AbyssFloorActivities(AbyssPlugin plugin){this.plugin=plugin;}
    public boolean pending(DungeonRun run){return current.containsKey(run.id());}
    public void start(DungeonRun run,Player leader){
        if(run.type()!=FloorType.EXPLORATION && run.type()!=FloorType.EVENT)return;
        current.put(run.id(),new State(run,ThreadLocalRandom.current().nextInt(3)));
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(plugin.runOf(leader.getUniqueId())==run && !run.objectiveDone())open(leader,run);
        },35L);
    }
    private ItemStack item(Material material,String name,String description){
        ItemStack item=new ItemStack(material);ItemMeta m=item.getItemMeta();
        m.setDisplayName(name);m.setLore(List.of("§7"+description,"§aクリックで選択"));item.setItemMeta(m);return item;
    }
    public void open(Player leader,DungeonRun run){
        State state=current.get(run.id());
        if(state==null || !leader.getUniqueId().equals(run.playerId()))return;
        boolean explore=run.type()==FloorType.EXPLORATION;
        Inventory gui=Bukkit.createInventory(null,27,explore?EXPLORE:EVENT);
        if(explore){
            String[] clues={"石壁の刻印","湿った足跡","微かな風音"};
            gui.setItem(4,item(Material.MAP,"§e見つけた手掛かり: "+clues[state.correct()],"手掛かりに合う道を選んでください"));
            gui.setItem(11,item(Material.CHISELED_STONE_BRICKS,"§6刻印の道","石壁に古い印が残る"));
            gui.setItem(13,item(Material.MOSS_BLOCK,"§2足跡の道","湿った地面に足跡が続く"));
            gui.setItem(15,item(Material.FEATHER,"§b風の道","奥から風が吹き込む"));
        }else{
            gui.setItem(11,item(Material.GOLDEN_APPLE,"§a生命の祭壇","パーティ全員のHPを最大値の30%回復"));
            gui.setItem(13,item(Material.LAPIS_LAZULI,"§9魔力の泉","パーティ全員のMPを最大まで回復"));
            gui.setItem(15,item(Material.CHEST,"§6封印された宝箱","追加装備を獲得。ただし25%でHPが10%減る"));
        }
        leader.openInventory(gui);
    }
    @EventHandler public void click(InventoryClickEvent e){
        String title=e.getView().getTitle();if(!title.equals(EXPLORE)&&!title.equals(EVENT))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        DungeonRun run=plugin.runOf(p.getUniqueId());
        if(run==null||!run.playerId().equals(p.getUniqueId()))return;
        State state=current.get(run.id());if(state==null)return;
        int choice=switch(e.getRawSlot()){case 11->0;case 13->1;case 15->2;default->-1;};
        if(choice<0)return;
        current.remove(run.id());
        p.closeInventory();
        if(run.type()==FloorType.EXPLORATION){
            if(choice==state.correct()){
                plugin.activityMessage(run,"§a正しい道を発見！ 探索報酬が追加されました。");
                plugin.activityReward(run);
            }else{
                plugin.activityMessage(run,"§c行き止まりでした！ 毒の罠が発動しました。");
                for(Player member:plugin.activityMembers(run))member.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.POISON,100,0));
            }
        }else{
            if(choice==0){
                for(Player member:plugin.activityMembers(run)){
                    var max=member.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                    if(max!=null&&!member.isDead())member.setHealth(Math.min(max.getValue(),member.getHealth()+max.getValue()*0.30));
                }
                plugin.activityMessage(run,"§a生命の祭壇がパーティを癒しました。");
            }else if(choice==1){
                plugin.activityFillMana(run);
                plugin.activityMessage(run,"§9魔力の泉でMPが回復しました。");
            }else{
                plugin.activityReward(run);
                if(ThreadLocalRandom.current().nextDouble()<0.25){
                    for(Player member:plugin.activityMembers(run)){
                        var max=member.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                        if(max!=null&&!member.isDead())member.setHealth(Math.max(1,member.getHealth()-max.getValue()*0.10));
                    }
                    plugin.activityMessage(run,"§c宝箱の呪いでHPが減少しました！");
                }else plugin.activityMessage(run,"§6封印された宝箱から追加報酬を獲得しました。");
            }
        }
        plugin.finishActivity(p,run);
    }
    @EventHandler public void close(InventoryCloseEvent e){
        if(!e.getView().getTitle().equals(EXPLORE)&&!e.getView().getTitle().equals(EVENT))return;
        if(!(e.getPlayer() instanceof Player p))return;
        DungeonRun run=plugin.runOf(p.getUniqueId());
        if(run!=null&&pending(run))Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(p.isOnline()&&pending(run))open(p,run);
        },3L);
    }
    public void end(DungeonRun run){current.remove(run.id());}
}
