package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Beta bug-report UI. Text entered after opening the GUI is intercepted and
 * never broadcast as public chat. Discord delivery is asynchronous.
 */
public final class AbyssBugReports implements Listener {
    private static final String ENTRY_TITLE = "深淵の迷宮 - 不具合報告";
    private static final String CONFIRM_TITLE = "深淵の迷宮 - 報告の確認";
    private static final int MAX_LENGTH = 800;
    private final AbyssPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final Set<UUID> awaitingChat = ConcurrentHashMap.newKeySet();
    private final Set<UUID> sending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> drafts = new HashMap<>();
    private final Map<UUID, Long> lastAttempt = new HashMap<>();

    public AbyssBugReports(AbyssPlugin plugin) { this.plugin = plugin; }

    private ItemStack icon(Material type, String name, List<String> lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean configured() {
        return validWebhook(plugin.getConfig().getString("bug-reports.webhook-url", ""));
    }

    private static boolean validWebhook(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("discord.com") || host.equalsIgnoreCase("discordapp.com"))
                    && uri.getPath() != null
                    && uri.getPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[^/]+")
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void open(Player p) {
        if (!configured()) {
            p.sendMessage("§c不具合報告先が設定されていません。運営に連絡してください。");
            return;
        }
        if (drafts.containsKey(p.getUniqueId())) {
            confirm(p); // Keep the report editable/retryable after delivery failures.
            return;
        }
        Inventory gui = Bukkit.createInventory(null, 27, ENTRY_TITLE);
        gui.setItem(11, icon(Material.WRITABLE_BOOK, "§a不具合を報告する",
                List.of("§7クリックするとチャット入力待ちになります",
                        "§7入力内容は全体チャットへ表示されません",
                        "§7最大800文字 / 送信前に確認できます")));
        gui.setItem(13, icon(Material.CLOCK, "§e入力方法",
                List.of("§7GUIを閉じたら不具合の内容をチャットへ入力",
                        "§7「cancel」で入力をキャンセル",
                        "§7同じプレイヤーは2分に1回まで送信可能")));
        gui.setItem(15, icon(Material.ARROW, "§7メインメニューへ戻る", List.of()));
        p.openInventory(gui);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!ENTRY_TITLE.equals(title) && !CONFIRM_TITLE.equals(title)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)
                || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (ENTRY_TITLE.equals(title)) {
            if (slot == 15) {
                Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) plugin.openMainMenu(p); });
            } else if (slot == 11) {
                if (!configured()) { p.sendMessage("§cWebhookが設定されていません。"); return; }
                if (sending.contains(p.getUniqueId())) { p.sendMessage("§e前の報告を送信中です。"); return; }
                awaitingChat.add(p.getUniqueId());
                drafts.remove(p.getUniqueId());
                p.closeInventory();
                p.sendMessage("§a不具合内容をチャットに入力してください。§7(800文字まで・cancelで取消)");
                p.sendMessage("§7この入力はほかのプレイヤーには表示されません。");
            }
        } else if (slot == 11) {
            send(p);
        } else if (slot == 13) {
            awaitingChat.add(p.getUniqueId());
            drafts.remove(p.getUniqueId());
            p.closeInventory();
            p.sendMessage("§e内容を再入力してください。(cancelで取消)");
        } else if (slot == 15) {
            drafts.remove(p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) plugin.openMainMenu(p); });
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!awaitingChat.contains(id)) return;
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        // Never access Bukkit inventories/GUI on the async chat thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !awaitingChat.remove(id)) return;
            String value = message.trim();
            if (value.equalsIgnoreCase("cancel")) {
                drafts.remove(id);
                player.sendMessage("§7不具合報告を取り消しました。");
                return;
            }
            if (value.isBlank() || value.length() > MAX_LENGTH) {
                player.sendMessage("§c報告内容は1～800文字で入力してください。再入力してください。");
                awaitingChat.add(id);
                return;
            }
            drafts.put(id, value);
            confirm(player);
        });
    }

    private void confirm(Player p) {
        String draft = drafts.get(p.getUniqueId());
        if (draft == null) return;
        Inventory gui = Bukkit.createInventory(null, 27, CONFIRM_TITLE);
        gui.setItem(11, icon(Material.LIME_CONCRETE, "§aDiscordへ送信",
                List.of("§7クリックすると指定の報告チャンネルへ送信します")));
        List<String> preview = new ArrayList<>();
        preview.add("§7内容（全体チャットには表示されません）");
        for (int i = 0; i < Math.min(draft.length(), 240); i += 32)
            preview.add("§f" + draft.substring(i, Math.min(draft.length(), i + 32)));
        if (draft.length() > 240) preview.add("§7...（残りは送信時に含まれます）");
        gui.setItem(4, icon(Material.PAPER, "§e報告内容の確認", preview));
        gui.setItem(13, icon(Material.WRITABLE_BOOK, "§e書き直す", List.of()));
        gui.setItem(15, icon(Material.BARRIER, "§c送信せず破棄", List.of()));
        p.openInventory(gui);
    }

    private static String json(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private void send(Player p) {
        UUID id = p.getUniqueId();
        String draft = drafts.get(id);
        if (draft == null) { p.sendMessage("§c先に内容を入力してください。"); return; }
        if (!configured()) { p.sendMessage("§cWebhookが設定されていません。"); return; }
        long now = System.currentTimeMillis();
        long interval = 120_000L;
        long elapsed = now - lastAttempt.getOrDefault(id, 0L);
        if (elapsed < interval) {
            p.sendMessage("§e次の送信まで " + ((interval - elapsed + 999) / 1000) + " 秒です。");
            return;
        }
        if (!sending.add(id)) { p.sendMessage("§eすでに送信中です。"); return; }
        lastAttempt.put(id, now);
        DungeonRun run = plugin.runFor(id);
        String context = run == null ? "ロビー・その他" :
                "第" + run.floor() + "階 / " + run.type() + " / " + run.difficulty();
        String world = p.getWorld().getName();
        String playerName = p.getName();
        String when = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String body = "{\"username\":\"AbyssDungeon Bug Reports\","
                + "\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{"
                + "\"title\":\"深淵の迷宮・不具合報告\","
                + "\"color\":15158332,"
                + "\"fields\":["
                + "{\"name\":\"報告者\",\"value\":" + json(playerName + " (" + id + ")") + "},"
                + "{\"name\":\"発生場所\",\"value\":" + json(world + " / " + context) + "},"
                + "{\"name\":\"報告内容\",\"value\":" + json(draft) + "}],"
                + "\"timestamp\":" + json(when)
                + "}]}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(plugin.getConfig()
                    .getString("bug-reports.webhook-url", "").trim()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException ex) {
            sending.remove(id);
            p.sendMessage("§c報告の送信設定が不正です。");
            return;
        }
        p.closeInventory();
        p.sendMessage("§e不具合報告をDiscordに送信しています...");
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    sending.remove(id);
                    if (error == null && response != null && response.statusCode() >= 200
                            && response.statusCode() < 300) {
                        drafts.remove(id);
                        if (p.isOnline()) p.sendMessage("§a不具合報告を送信しました。ご協力ありがとうございます。");
                    } else {
                        // Never log the webhook URL or token.
                        int status = response == null ? 0 : response.statusCode();
                        plugin.getLogger().warning("Bug report webhook failed (HTTP " + status + ").");
                        if (p.isOnline()) p.sendMessage("§c報告を送信できませんでした。"
                                + "内容は保持されています。しばらくして再試行してください。");
                    }
                }));
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        awaitingChat.remove(id);
        drafts.remove(id);
        sending.remove(id);
    }
}
