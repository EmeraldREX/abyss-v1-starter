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

/** In-game public beta manual. All navigation uses GUI rather than commands. */
public final class AbyssGuide implements Listener {
    private static final String TITLE="深淵の迷宮 - 遊び方・仕様 v0.6.0";
    private final AbyssPlugin plugin;
    private static final String[] SECTIONS={
        "まず最初に","入場とパーティ","フロア進行","戦闘と操作","装備と倉庫",
        "魔法・職業・スキル","レベルと成長","報酬・撤退・死亡","フロアギミック","ベータ版の注意事項"
    };
    private static final String[][] PAGES={
        {"§7この本はゲーム内の遊び方・基本仕様をまとめています。",
         "§e① ロビーでメニューアイテムを右クリック",
         "§e② 職業・装備・魔法を準備",
         "§e③ パーティを組むかソロで難易度を選択",
         "§e④ フロアを攻略し、報酬を確定して撤退",
         "§7ベータ版: v0.6.0"},
        {"§7参加人数: 1～4人。ロビーのメニューからパーティ管理。",
         "§7難易度: EASY 30階 / NORMAL 50階 / HARD 100階。",
         "§7攻略は10階単位の区間で管理。",
         "§7パーティの入場操作はリーダーが行います。",
         "§7攻略中はパーティ編成を変更できません。"},
        {"§7フロア全体が戦闘・強敵・探索・休息・宝箱・イベント・ボスのいずれか。",
         "§7戦闘では敵をすべて倒すとクリア。",
         "§7指定の出口（金ブロック）から次の階へ進行。",
         "§710階区切りで報酬・撤退の選択ができます。",
         "§7休憩・ボス後には撤退を選択できます。"},
        {"§7ホットバー5番目は装備中武器の表示枠。",
         "§7Fキー: 装備武器を持って職業スキル。",
         "§7スニーク＋右クリックでもスキルを使用できます。",
         "§7魔法スロットはホットバー6・7番目。",
         "§7弓や盾などの操作は装備に応じて異なります。",
         "§7戦闘中は装備変更できません。"},
        {"§7武器・防具・アクセサリー・消費アイテムは専用倉庫で管理。",
         "§7倉庫は分類・ページ送り・能力順ソートに対応。",
         "§7武器は5番目、防具は通常防具欄に表示用コピーが出ます。",
         "§7倉庫装備と通常サバイバルの持ち物は分離。",
         "§7装備を持ち越して次の挑戦に使用できます。",
         "§c倉庫の削除操作は元に戻せないため注意。"},
        {"§7職業はロビーのメニューから変更。攻略中は変更不可。",
         "§7魔法: ファイア・アイス・サンダー・ヒール・プロテクト。",
         "§7MPとクールタイムを消費し、職業で使用制限があります。",
         "§7職業スキルはFキーで発動、クールタイムあり。",
         "§7装備・魔法は攻略前に設定してください。"},
        {"§7レベル・経験値は今回の攻略中だけ有効。",
         "§7敵を倒すと経験値を獲得してレベルアップ。",
         "§7強化の3択: 職業特化 / 生存力 / スキル熟練度。",
         "§7戦闘中には選択画面を割り込ませません。",
         "§7クリア後に未選択の強化をまとめて選べます。",
         "§7メニュー内の「成長」からも確認可能。",
         "§7終了時にレベル・一時強化をリセット。"},
        {"§7ダンジョン内の未確定報酬は撤退時に確定します。",
         "§7死亡・全滅時は今回の未確定報酬を失います。",
         "§7確定した専用装備は倉庫に保存して次回も使えます。",
         "§7通常ワールド用のアイテムは報酬に含めません。",
         "§7意図しない切断やサーバー停止には注意。"},
        {"§7一部のフロアに落石予兆・回避可能な範囲攻撃。",
         "§7移動する危険地帯に触れると短時間鈍足。",
         "§7探索フロアでは亀裂のある壁を探してください。",
         "§7隠し通路は右クリックで見つかることがあります。",
         "§7ギミックはマップ条件に応じて出現します。"},
        {"§6現在はベータ版です。性能・確率は調整される可能性があります。",
         "§7進行前に職業・装備・スキルを確認してください。",
         "§7通常ワールドと専用ワールドの持ち物は別管理。",
         "§7コマンドは運営が制限しています。",
         "§7メニューを利用して遊んでください。",
         "§c不具合を見つけたら管理者へ報告してください。"}
    };
    public AbyssGuide(AbyssPlugin plugin){this.plugin=plugin;}
    public void open(Player p){open(p,0);}
    private ItemStack icon(Material mat,String title,String... lines){
        ItemStack it=new ItemStack(mat);ItemMeta meta=it.getItemMeta();
        meta.setDisplayName(title);meta.setLore(List.of(lines));it.setItemMeta(meta);return it;
    }
    public void open(Player p,int page){
        int index=Math.max(0,Math.min(SECTIONS.length-1,page));
        Inventory inv=Bukkit.createInventory(null,54,TITLE);
        for(int i=0;i<SECTIONS.length;i++){
            inv.setItem(i,icon(i==index?Material.WRITTEN_BOOK:Material.BOOK,
                (i==index?"§a▶ ":"§e")+SECTIONS[i],"§7クリックで該当する説明を表示"));
        }
        inv.setItem(22,icon(Material.KNOWLEDGE_BOOK,"§6"+SECTIONS[index],PAGES[index]));
        inv.setItem(45,icon(Material.ARROW,"§e前のページ"));
        inv.setItem(49,icon(Material.BARRIER,"§7メインメニューに戻る"));
        inv.setItem(53,icon(Material.ARROW,"§e次のページ"));
        p.openInventory(inv);
    }
    @EventHandler public void click(InventoryClickEvent e){
        if(!TITLE.equals(e.getView().getTitle()))return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p)||e.getClickedInventory()!=e.getView().getTopInventory())return;
        int raw=e.getRawSlot();
        if(raw==49){Bukkit.getScheduler().runTask(plugin,()->plugin.openMainMenu(p));return;}
        if(raw>=0&&raw<SECTIONS.length){int page=raw;Bukkit.getScheduler().runTask(plugin,()->open(p,page));return;}
        if(raw==45||raw==53){
            int page=0;
            ItemStack selected=e.getView().getTopInventory().getItem(22);
            if(selected!=null&&selected.hasItemMeta()){
                String name=org.bukkit.ChatColor.stripColor(selected.getItemMeta().getDisplayName());
                for(int i=0;i<SECTIONS.length;i++)if(name.equals(SECTIONS[i])){page=i;break;}
            }
            int target=page+(raw==45?-1:1);
            Bukkit.getScheduler().runTask(plugin,()->open(p,target));
        }
    }
}
