package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.sql.*;
import java.util.*;

/** Three protected reward IDs per player and run. Never moves physical inventory items. */
public final class AbyssSafePouch implements Listener {
    private static final String TITLE = "深淵の迷宮 - 死亡保護スロット";
    private final AbyssPlugin plugin;
    private final String jdbc;
    public AbyssSafePouch(AbyssPlugin plugin, String jdbc) {
        this.plugin=plugin;this.jdbc=jdbc;
        try(Connection c=DriverManager.getConnection(jdbc);Statement st=c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS abyss_safe_pouch (run_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, slot INTEGER NOT NULL CHECK(slot BETWEEN 0 AND 2), item_id TEXT NOT NULL, PRIMARY KEY(run_id,owner_uuid,slot), UNIQUE(run_id,owner_uuid,item_id))");
        } catch(SQLException e){throw new IllegalStateException("Death-safe pouch initialization failed",e);}
    }
    private ItemStack icon(Material material,String name,List<String> lore){
        ItemStack item=new ItemStack(material);ItemMeta m=item.getItemMeta();
        m.setDisplayName(name);m.setLore(lore);item.setItemMeta(m);return item;
    }
    private record Reward(String itemId,String definitionId) {}
    private Map<Integer,String> protectedItems(Connection c,UUID runId,UUID player) throws SQLException {
        Map<Integer,String> result=new HashMap<>();
        try(PreparedStatement q=c.prepareStatement("SELECT slot,item_id FROM abyss_safe_pouch WHERE run_id=? AND owner_uuid=?")) {
            q.setString(1,runId.toString());q.setString(2,player.toString());
            try(ResultSet rows=q.executeQuery()){while(rows.next())result.put(rows.getInt(1),rows.getString(2));}
        }
        return result;
    }
    private List<Reward> rewards(Connection c,UUID runId,UUID player) throws SQLException{
        List<Reward> result=new ArrayList<>();
        try(PreparedStatement q=c.prepareStatement("SELECT item_id,definition_id FROM abyss_pending WHERE run_id=? AND owner_uuid=? ORDER BY rowid")) {
            q.setString(1,runId.toString());q.setString(2,player.toString());
            try(ResultSet rows=q.executeQuery()){while(rows.next())result.add(new Reward(rows.getString(1),rows.getString(2)));}
        }
        return result;
    }
    private String name(String def){
        AbyssEquipment.Definition definition=AbyssEquipment.definition(def);
        return definition==null?def:definition.name();
    }
    private Material material(String def){
        AbyssEquipment.Definition definition=AbyssEquipment.definition(def);
        if(definition==null)return Material.PAPER;
        // The visual icon is not a physical copy of the reward.
        return switch(definition.slot()){
            case "weapon"->Material.IRON_SWORD;case "head"->Material.IRON_HELMET;
            case "chest"->Material.IRON_CHESTPLATE;case "legs"->Material.IRON_LEGGINGS;
            case "feet"->Material.IRON_BOOTS;case "accessory","offhand"->Material.AMETHYST_SHARD;
            default->Material.PAPER;
        };
    }
    public void open(Player p){
        DungeonRun run=plugin.runFor(p.getUniqueId());
        if(run==null){p.sendMessage("§c死亡保護スロットは攻略中のみ使用できます。");return;}
        try(Connection c=DriverManager.getConnection(jdbc)){
            Map<Integer,String> saved=protectedItems(c,run.id(),p.getUniqueId());
            List<Reward> pending=rewards(c,run.id(),p.getUniqueId());
            Inventory gui=Bukkit.createInventory(null,54,TITLE);
            Map<String,Integer> byId=new HashMap<>();
            for(var e:saved.entrySet())byId.put(e.getValue(),e.getKey());
            for(int i=0;i<Math.min(pending.size(),45);i++){
                Reward reward=pending.get(i);
                Integer selected=byId.get(reward.itemId());
                gui.setItem(i,icon(material(reward.definitionId()),
                        (selected==null?"§f":"§a[保護 "+(selected+1)+"] ")+name(reward.definitionId()),
                        List.of(selected==null?"§7クリックで死亡保護枠に登録":"§aクリックで保護解除",
                                "§8今回獲得した未確定報酬")));
            }
            for(int i=0;i<3;i++){
                String selected=saved.get(i);
                Reward reward=null;
                for(Reward candidate:pending)if(candidate.itemId().equals(selected)){reward=candidate;break;}
                gui.setItem(47+i,reward==null
                    ?icon(Material.GRAY_STAINED_GLASS_PANE,"§7保護枠 "+(i+1)+" / 空き",
                            List.of("§7上の未確定報酬をクリックして登録"))
                    :icon(Material.LIME_STAINED_GLASS_PANE,"§a保護枠 "+(i+1)+": "+name(reward.definitionId()),
                            List.of("§a全滅しても倉庫に保存されます","§7クリックで保護解除")));
            }
            gui.setItem(53,icon(Material.ARROW,"§eメインメニューに戻る",List.of()));
            if(pending.size()>45)p.sendMessage("§e報酬が45個を超えています。先頭45個だけ表示しています。");
            p.openInventory(gui);
        }catch(SQLException ex){plugin.getLogger().warning("Safe pouch view failed: "+ex.getMessage());p.sendMessage("§c保護スロットを読み込めませんでした。");}
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(!TITLE.equals(e.getView().getTitle()))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        if(e.getRawSlot()==53){Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())plugin.openMainMenu(p);});return;}
        DungeonRun run=plugin.runFor(p.getUniqueId());
        if(run==null){p.closeInventory();return;}
        int clicked=e.getRawSlot();
        if(clicked>=45 && clicked<=46 || clicked>=50 && clicked<=52)return;
        try(Connection c=DriverManager.getConnection(jdbc)){
            c.setAutoCommit(false);
            try{
                Map<Integer,String> saved=protectedItems(c,run.id(),p.getUniqueId());
                List<Reward> pending=rewards(c,run.id(),p.getUniqueId());
                String target=null;
                if(clicked>=0 && clicked<Math.min(pending.size(),45))target=pending.get(clicked).itemId();
                else if(clicked>=47 && clicked<=49)target=saved.get(clicked-47);
                if(target==null){c.rollback();return;}
                Integer existing=null;
                for(var entry:saved.entrySet())if(entry.getValue().equals(target)){existing=entry.getKey();break;}
                if(existing!=null){
                    try(PreparedStatement del=c.prepareStatement("DELETE FROM abyss_safe_pouch WHERE run_id=? AND owner_uuid=? AND item_id=?")){
                        del.setString(1,run.id().toString());del.setString(2,p.getUniqueId().toString());del.setString(3,target);del.executeUpdate();
                    }
                    p.sendMessage("§e死亡保護を解除しました。");
                }else{
                    int free=-1;for(int i=0;i<3;i++)if(!saved.containsKey(i)){free=i;break;}
                    if(free<0){p.sendMessage("§c保護枠は3個までです。先にほかの報酬を解除してください。");c.rollback();return;}
                    try(PreparedStatement add=c.prepareStatement("INSERT INTO abyss_safe_pouch(run_id,owner_uuid,slot,item_id) SELECT ?,?,?,item_id FROM abyss_pending WHERE run_id=? AND owner_uuid=? AND item_id=?")){
                        add.setString(1,run.id().toString());add.setString(2,p.getUniqueId().toString());add.setInt(3,free);
                        add.setString(4,run.id().toString());add.setString(5,p.getUniqueId().toString());add.setString(6,target);
                        if(add.executeUpdate()!=1){c.rollback();p.sendMessage("§c報酬が見つかりません。");return;}
                    }
                    p.sendMessage("§a死亡保護枠 "+(free+1)+" に登録しました。");
                }
                c.commit();
            }catch(Exception ex){c.rollback();throw ex;}
            Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline()&&plugin.runFor(p.getUniqueId())==run)open(p);});
        }catch(Exception ex){plugin.getLogger().warning("Safe pouch update failed: "+ex.getMessage());p.sendMessage("§c死亡保護の変更に失敗しました。");}
    }

    /**
     * Must be executed by the existing single-thread database executor.
     * One transaction preserves only registered, owned, pending items then
     * discards all other rewards. Failure rolls back everything.
     */
    public int secureAndDiscard(UUID runId,UUID owner) throws SQLException{
        try(Connection c=DriverManager.getConnection(jdbc)){
            c.setAutoCommit(false);
            try{
                int saved;
                try(PreparedStatement insert=c.prepareStatement(
                        "INSERT OR IGNORE INTO abyss_inventory(item_id,owner_uuid,definition_id,acquired_at) "+
                        "SELECT pending.item_id,pending.owner_uuid,pending.definition_id,? "+
                        "FROM abyss_pending pending JOIN abyss_safe_pouch safe "+
                        "ON safe.item_id=pending.item_id AND safe.run_id=pending.run_id AND safe.owner_uuid=pending.owner_uuid "+
                        "WHERE pending.run_id=? AND pending.owner_uuid=?")){
                    insert.setLong(1,System.currentTimeMillis());insert.setString(2,runId.toString());insert.setString(3,owner.toString());
                    saved=insert.executeUpdate();
                }
                try(PreparedStatement delete=c.prepareStatement("DELETE FROM abyss_pending WHERE run_id=? AND owner_uuid=?")){
                    delete.setString(1,runId.toString());delete.setString(2,owner.toString());delete.executeUpdate();
                }
                try(PreparedStatement clean=c.prepareStatement("DELETE FROM abyss_safe_pouch WHERE run_id=? AND owner_uuid=?")){
                    clean.setString(1,runId.toString());clean.setString(2,owner.toString());clean.executeUpdate();
                }
                c.commit();return saved;
            }catch(SQLException ex){c.rollback();throw ex;}
        }
    }
}
