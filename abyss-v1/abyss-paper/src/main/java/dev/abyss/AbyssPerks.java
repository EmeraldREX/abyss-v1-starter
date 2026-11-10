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

/** Per-run randomized perks. All offers and bonuses are private to the player. */
public final class AbyssPerks implements Listener {
    private static final String TITLE="深淵の迷宮 - パーク選択";
    private record Choice(String stat, String rarity, int value) {}
    private final AbyssPlugin plugin;
    private final Map<UUID,Map<String,Integer>> perks=new HashMap<>();
    private final Map<UUID,Map<Integer,Choice>> choices=new HashMap<>();
    private final Map<UUID,Integer> offers=new HashMap<>();
    private final Map<UUID,Deque<Integer>> queued=new HashMap<>();
    private final Map<UUID,Set<Integer>> claimed=new HashMap<>();
    public AbyssPerks(AbyssPlugin plugin){this.plugin=plugin;}
    public void beginRun(UUID id){perks.put(id,new HashMap<>());choices.remove(id);offers.remove(id);queued.remove(id);claimed.put(id,new HashSet<>());}
    public void endRun(UUID id){perks.remove(id);choices.remove(id);offers.remove(id);queued.remove(id);claimed.remove(id);}
    public double bonus(UUID id,String stat){return perks.getOrDefault(id,Map.of()).getOrDefault(stat,0);}
    public boolean hasPending(UUID id){return offers.containsKey(id) && choices.containsKey(id);}
    public void offer(Player p,int floor){
        UUID id=p.getUniqueId();if(claimed.getOrDefault(id,Set.of()).contains(floor))return;
        if(offers.containsKey(id) && offers.get(id)!=floor){
            Deque<Integer> pending=queued.computeIfAbsent(id,k->new ArrayDeque<>());
            if(!pending.contains(floor))pending.addLast(floor);
            return;
        }
        if(!offers.containsKey(id)) {
            offers.put(id,floor);
            List<String> pool=new ArrayList<>(List.of("attack","defense","hp","magic","mp"));
            Collections.shuffle(pool);
            Map<Integer,Choice> offer=new HashMap<>();
            int[] slots={11,13,15};
            for(int i=0;i<3;i++) {
                int roll=java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
                String rarity=roll<5?"伝説":roll<25?"レア":"通常";
                int factor=rarity.equals("伝説")?4:rarity.equals("レア")?2:1;
                String stat=pool.get(i);
                int base=switch(stat){case "hp"->20;case "mp"->10;case "magic"->4;default->5;};
                offer.put(slots[i],new Choice(stat,rarity,base*factor));
            }
            choices.put(id,offer);
        }
        // Give the level-up selection priority and never overwrite another GUI.
        if(plugin.growth()!=null && plugin.growth().pending(id)>0){
            p.sendMessage("§eボス報酬パークは成長ポイントの選択後に確認できます。");
        }else if(p.getOpenInventory().getTopInventory().getType()==org.bukkit.event.inventory.InventoryType.CRAFTING){
            open(p);
        }else{
            p.sendMessage("§e未選択パークがあります。/abyss perks から選択してください。");
        }
    }
    /** Negative keys keep level milestones separate from boss-floor rewards. */
    public void offerLevel(Player p,int level){if(level>0 && level%5==0)offer(p,-level);}
    public void open(Player p){
        UUID id=p.getUniqueId();if(!plugin.isInRun(id))return;
        Map<Integer,Choice> offer=choices.get(id);
        if(offer==null){p.sendMessage("§7現在選択できるパークはありません。");return;}
        Inventory inv=Bukkit.createInventory(null,27,TITLE);
        for(var entry:offer.entrySet()){
            Choice c=entry.getValue();
            Material material=switch(c.stat()){case "attack"->Material.IRON_SWORD;case "defense"->Material.SHIELD;case "hp"->Material.GOLDEN_APPLE;case "magic"->Material.BLAZE_ROD;default->Material.LAPIS_LAZULI;};
            String name=switch(c.stat()){case "attack"->"剛力";case "defense"->"堅守";case "hp"->"生命";case "magic"->"魔導";default->"魔力容量";};
            ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();
            meta.setDisplayName("§e["+c.rarity()+"] §f"+name+" +"+c.value());
            meta.setLore(List.of("§7今回の攻略中のみ有効","§7同じパークの効果は累積します","§aクリックで獲得"));
            item.setItemMeta(meta);inv.setItem(entry.getKey(),item);
        }
        ItemStack back=new ItemStack(Material.BARRIER);ItemMeta bm=back.getItemMeta();bm.setDisplayName("§7戻る");back.setItemMeta(bm);inv.setItem(22,back);
        p.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(!e.getView().getTitle().equals(TITLE))return;e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        if(e.getRawSlot()==22){plugin.openMainMenu(p);return;}
        UUID id=p.getUniqueId();Map<Integer,Choice> offer=choices.get(id);
        Choice choice=offer==null?null:offer.get(e.getRawSlot());
        if(choice==null||!plugin.isInRun(id))return;
        Integer floor=offers.remove(id);if(floor==null)return;
        choices.remove(id);claimed.computeIfAbsent(id,k->new HashSet<>()).add(floor);
        perks.computeIfAbsent(id,k->new HashMap<>()).merge(choice.stat(),choice.value(),Integer::sum);
        p.closeInventory();p.sendMessage("§aパーク獲得: "+choice.rarity()+" "+choice.stat()+" +"+choice.value());
        Deque<Integer> pending=queued.get(id);
        if(pending!=null && !pending.isEmpty()){
            int next=pending.removeFirst();
            Bukkit.getScheduler().runTask(plugin,()->offer(p,next));
        }
    }
}
