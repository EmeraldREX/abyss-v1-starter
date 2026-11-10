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
    private record Choice(String stat, String name, String rarity, int value) {}
    private record Option(String name,String stat,int base) {}
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
    public double skillFactor(UUID id){return 1.0+bonus(id,"skill_power")/100.0;}
    public long skillCooldownReduction(UUID id){return (long)(bonus(id,"skill_cooldown")*100.0);}
    /** Each class owns a different perk catalog, including upgrades to its own F-key skills. */
    private List<Option> catalog(String job){return switch(job){
        case "warrior"->List.of(new Option("剣術の極意","attack",7),new Option("鋼の守り","defense",7),new Option("戦士の体力","hp",24),new Option("薙ぎ払いの熟練","skill_power",12),new Option("闘争の呼吸","skill_cooldown",5));
        case "mage"->List.of(new Option("魔導の深奥","magic",9),new Option("魔力の器","mp",25),new Option("秘術の集中","skill_power",15),new Option("詠唱短縮","skill_cooldown",6),new Option("魔術師の生命","hp",16));
        case "priest"->List.of(new Option("聖なる祈り","magic",7),new Option("守護の光","defense",6),new Option("慈愛の生命","hp",20),new Option("治癒術の昇華","skill_power",16),new Option("迅速な祈祷","skill_cooldown",6));
        case "archer"->List.of(new Option("精密射撃","attack",8),new Option("射手の耐久","hp",16),new Option("軽装の守り","defense",5),new Option("連射の極意","skill_power",15),new Option("早撃ち","skill_cooldown",7));
        case "paladin"->List.of(new Option("聖騎士の盾","defense",9),new Option("不屈の命","hp",25),new Option("聖剣術","attack",6),new Option("聖盾の強化","skill_power",14),new Option("守護の連携","skill_cooldown",5));
        case "assassin"->List.of(new Option("急所看破","attack",9),new Option("影の護身","defense",5),new Option("軽やかな生命","hp",14),new Option("暗殺術の極意","skill_power",17),new Option("影の連撃","skill_cooldown",7));
        case "berserker"->List.of(new Option("狂戦士の闘気","attack",10),new Option("血戦の体力","hp",24),new Option("戦場の守り","defense",4),new Option("狂乱の深化","skill_power",18),new Option("暴走の連鎖","skill_cooldown",6));
        case "monk"->List.of(new Option("拳法の極意","attack",8),new Option("気の防壁","defense",7),new Option("鍛錬の身体","hp",20),new Option("気功の増幅","skill_power",14),new Option("流水の呼吸","skill_cooldown",6));
        default->List.of();
    };}
    private String statLabel(String stat){return switch(stat){
        case "attack"->"攻撃力";case "defense"->"防御力";case "hp"->"最大HP";
        case "magic"->"魔力";case "mp"->"最大MP";case "skill_power"->"職業スキルの威力・回復量";
        case "skill_cooldown"->"職業スキルのクールタイム";default->stat;
    };}
    private String effect(String stat,int value){return stat.equals("skill_power")?statLabel(stat)+" +"+value+"%"
            :stat.equals("skill_cooldown")?statLabel(stat)+" -"+String.format(java.util.Locale.ROOT,"%.1f",value/10.0)+"秒"
            :statLabel(stat)+" +"+value;}
    public boolean hasPending(UUID id){return offers.containsKey(id) && choices.containsKey(id);}
    private boolean canChoose(Player p){
        return plugin.isInRun(p.getUniqueId()) && plugin.growth()!=null && plugin.growth().canChoose(p);
    }
    /** The entitlement is queued during combat; the GUI is only offered after floor clear. */
    public void afterFloor(Player p){
        if(!p.isOnline() || !canChoose(p) || !hasPending(p.getUniqueId()))return;
        if(plugin.growth().pending(p.getUniqueId())>0){p.sendMessage("§e成長ポイントを選んだ後、未選択パークを確認できます。");return;}
        if(p.getOpenInventory().getTopInventory().getType()==org.bukkit.event.inventory.InventoryType.CRAFTING)open(p);
        else p.sendMessage("§e未選択パークがあります。/abyss perks から選択できます。");
    }
    public void offer(Player p,int floor){
        UUID id=p.getUniqueId();if(claimed.getOrDefault(id,Set.of()).contains(floor))return;
        if(offers.containsKey(id) && offers.get(id)!=floor){
            Deque<Integer> pending=queued.computeIfAbsent(id,k->new ArrayDeque<>());
            if(!pending.contains(floor))pending.addLast(floor);
            return;
        }
        if(!offers.containsKey(id)) {
            offers.put(id,floor);
            List<Option> pool=new ArrayList<>(catalog(plugin.stats().playerClass(id)));
            Collections.shuffle(pool);
            Map<Integer,Choice> offer=new HashMap<>();
            int[] slots={11,13,15};
            for(int i=0;i<3;i++) {
                int roll=java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
                String rarity=roll<5?"伝説":roll<25?"レア":"通常";
                int factor=rarity.equals("伝説")?4:rarity.equals("レア")?2:1;
                Option option=pool.get(i);
                offer.put(slots[i],new Choice(option.stat(),option.name(),rarity,option.base()*factor));
            }
            choices.put(id,offer);
        }
        // Give the level-up selection priority and never overwrite another GUI.
        if(!canChoose(p)){
            p.sendMessage("§eパーク候補を獲得しました。フロアクリア後に選択できます。");
        }else if(plugin.growth()!=null && plugin.growth().pending(id)>0){
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
        UUID id=p.getUniqueId();if(!canChoose(p)){p.sendMessage("§c戦闘中はパークを選択できません。フロアクリア後に開いてください。");return;}
        Map<Integer,Choice> offer=choices.get(id);
        if(offer==null){p.sendMessage("§7現在選択できるパークはありません。");return;}
        Inventory inv=Bukkit.createInventory(null,27,TITLE);
        for(var entry:offer.entrySet()){
            Choice c=entry.getValue();
            Material material=switch(c.stat()){case "attack"->Material.IRON_SWORD;case "defense"->Material.SHIELD;case "hp"->Material.GOLDEN_APPLE;case "magic"->Material.BLAZE_ROD;case "skill_power"->Material.NETHER_STAR;case "skill_cooldown"->Material.CLOCK;default->Material.LAPIS_LAZULI;};
            ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();
            meta.setDisplayName("§e["+c.rarity()+"] §f"+c.name());
            meta.setLore(List.of("§b効果: §f"+effect(c.stat(),c.value()),"§7今回の攻略中のみ有効","§7同じ能力の強化は累積します","§aクリックで獲得"));
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
        if(choice==null||!canChoose(p))return;
        Integer floor=offers.remove(id);if(floor==null)return;
        choices.remove(id);claimed.computeIfAbsent(id,k->new HashSet<>()).add(floor);
        perks.computeIfAbsent(id,k->new HashMap<>()).merge(choice.stat(),choice.value(),Integer::sum);
        p.closeInventory();p.sendMessage("§aパーク獲得: "+choice.rarity()+" "+choice.name()+" §7("+effect(choice.stat(),choice.value())+")");
        Deque<Integer> pending=queued.get(id);
        if(pending!=null && !pending.isEmpty()){
            int next=pending.removeFirst();
            Bukkit.getScheduler().runTask(plugin,()->offer(p,next));
        }
    }
}
