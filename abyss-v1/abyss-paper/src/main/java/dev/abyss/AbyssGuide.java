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
import java.util.List;

/** Beta v0.6.0 player-facing in-game rulebook. No inventory items are granted. */
public final class AbyssGuide implements Listener {
    public static final String TITLE = "深淵の迷宮 - 冒険ガイド";
    private static final String DETAIL = "深淵の迷宮 - 解説：";
    private final AbyssPlugin plugin;
    private static final String[] TOPICS = {
        "はじめかた", "攻略の流れ", "フロアの種類", "戦闘と操作",
        "装備と倉庫", "魔法と職業", "レベルと成長", "報酬と撤退",
        "パーティ", "注意事項"
    };
    private static final Material[] ICONS = {
        Material.COMPASS,Material.MAP,Material.GRASS_BLOCK,Material.IRON_SWORD,
        Material.ENDER_CHEST,Material.ENCHANTED_BOOK,Material.EXPERIENCE_BOTTLE,
        Material.EMERALD,Material.PLAYER_HEAD,Material.BARRIER
    };
    private static final List<List<String>> DESCRIPTIONS = List.of(
        List.of("§7ロビーで専用メニューを開く",
            "§7職業を選択し、倉庫で装備を設定する",
            "§7必要なら魔法2枠とパーティを設定",
            "§7難易度を選び、ダンジョンに入場",
            "§e/abyss menu からもメニューを開けます"),
        List.of("§7ダンジョンは10階ごとの区間制",
            "§7EASY: 30階 / NORMAL: 50階 / HARD: 100階",
            "§7階層ごとに目標が変わる",
            "§7敵を倒す、またはイベント目標を達成する",
            "§7出口の金ブロックから次へ進む"),
        List.of("§7戦闘: 通常の敵を討伐",
            "§7強敵: 強化された敵との戦闘",
            "§7ボス: 強い敵と予兆攻撃に注意",
            "§7探索: 迷路、隠し通路などを探索",
            "§7休憩: 休息・撤退の判断",
            "§7宝箱/イベント: フロア固有の選択や報酬"),
        List.of("§7武器はホットバーの5番目",
            "§7職業スキルは武器を選んでFキー",
            "§7しゃがみ＋右クリックでもスキル発動",
            "§7スキルにはクールタイムがある",
            "§7ボスの予兆から離れて攻撃を避ける",
            "§7落石・移動障害物・毒の地面に注意"),
        List.of("§7専用倉庫で武器・防具・装飾品を管理",
            "§7装備品は倉庫で保管され次回に持ち越せる",
            "§7通常ワールドと専用インベントリは分離",
            "§7攻略中は装備の変更ができない",
            "§7武器と防具の表示アイテムは複製報酬ではない",
            "§7倉庫では右クリックで削除確認"),
        List.of("§7職業ごとに基礎能力と使用魔法が異なる",
            "§7魔法メニューで2つの使用枠を設定",
            "§7魔法使用にはMPとクールタイムが必要",
            "§7魔法枠はホットバー6・7番目",
            "§7ファイア、アイス、サンダー、",
            "§7ヒール、プロテクトを利用可能（職業制限あり）"),
        List.of("§7レベルと経験値は攻略ごとにリセット",
            "§7敵を倒すと経験値を獲得",
            "§7レベルアップごとに強化選択権を獲得",
            "§7戦闘終了後にまとめて選択",
            "§7職業能力・生存力・スキル熟練度の3択",
            "§eメインメニューの成長・強化から確認可能"),
        List.of("§7攻略中に得た報酬は未確定",
            "§7死亡すると未確定報酬を失う",
            "§7休憩フロアまたはボス撃破後に撤退可能",
            "§7正規の撤退で報酬を倉庫に確定する",
            "§7次回は保管した装備を再利用できる",
            "§cテスト用の強制退出は報酬を確定しない"),
        List.of("§7パーティ人数は1～4人",
            "§7ロビーからパーティメニューを操作",
            "§7パーティ全員がオンラインで入場",
            "§7リーダーが入場・次階層・撤退を操作",
            "§7経験値は攻略メンバーに共有される"),
        List.of("§6ベータ版 v0.6.0",
            "§7予期しない不具合やバランス変動があります",
            "§7ログアウトで強制退出したことにはなりません",
            "§7アイテムを失った場合は管理者に報告",
            "§cテスト用コマンドは運営または権限保持者のみ",
            "§7通常の冒険では出口・撤退メニューを使用")
    );
    public AbyssGuide(AbyssPlugin plugin){this.plugin=plugin;}
    private ItemStack icon(Material mat, String name, List<String> lore) {
        ItemStack item=new ItemStack(mat);
        ItemMeta meta=item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    public void open(Player p) {
        Inventory inv=Bukkit.createInventory(null,54,TITLE);
        for(int i=0;i<TOPICS.length;i++) {
            int slot=(i/5)*9+2+(i%5);
            inv.setItem(slot,icon(ICONS[i],"§b"+TOPICS[i],
                List.of("§7クリックして詳しい説明を見る")));
        }
        inv.setItem(49,icon(Material.ARROW,"§eメインメニューへ戻る",List.of()));
        p.openInventory(inv);
    }
    private void detail(Player p,int index) {
        Inventory inv=Bukkit.createInventory(null,27,DETAIL+index);
        inv.setItem(13,icon(Material.BOOK,"§6"+TOPICS[index],DESCRIPTIONS.get(index)));
        inv.setItem(22,icon(Material.ARROW,"§e項目一覧へ戻る",List.of()));
        p.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        String title=event.getView().getTitle();
        boolean index=TITLE.equals(title),isDetail=title.startsWith(DETAIL);
        if(!index&&!isDetail)return;
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player p)
            ||event.getClickedInventory()!=event.getView().getTopInventory())return;
        int slot=event.getRawSlot();
        if(index) {
            if(slot==49) {
                Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())plugin.openMainMenu(p);});
                return;
            }
            for(int i=0;i<TOPICS.length;i++)if(slot==(i/5)*9+2+(i%5)){
                final int chapter=i;
                Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())detail(p,chapter);});
                return;
            }
        } else if(slot==22) {
            Bukkit.getScheduler().runTask(plugin,()->{if(p.isOnline())open(p);});
        }
    }
}
