package dev.abyss;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import java.util.*;

/** Main-thread party roster and invitations. Dungeon sessions are not yet integrated. */
public final class AbyssParty {
    private static final int MAX = 4;
    private final Map<UUID, Party> byMember = new HashMap<>();
    private final Map<UUID, Invitation> invitations = new HashMap<>();
    private static final long INVITE_MS = 60_000;
    private static final class Party {
        UUID leader;
        final LinkedHashSet<UUID> members = new LinkedHashSet<>();
        Party(UUID leader) { this.leader = leader; members.add(leader); }
    }
    private record Invitation(UUID leader, long expiresAt) {}
    public boolean inParty(UUID id) { return byMember.containsKey(id); }
    public boolean isLeader(UUID id) { Party p = byMember.get(id); return p != null && p.leader.equals(id); }
    public List<UUID> members(UUID id) { Party p = byMember.get(id); return p == null ? List.of(id) : List.copyOf(p.members); }
    public void command(Player player, String[] args) {
        if (args.length < 2) { player.sendMessage("§e/abyss party <create|invite|accept|leave|kick|list|disband>"); return; }
        UUID id = player.getUniqueId();
        String action = args[1].toLowerCase(Locale.ROOT);
        Party party = byMember.get(id);
        switch (action) {
            case "create" -> {
                if (party != null) { player.sendMessage("§cすでにパーティに所属しています。"); return; }
                Party created = new Party(id); byMember.put(id, created);
                player.sendMessage("§aパーティを作成しました。");
            }
            case "invite" -> {
                if (party == null || !party.leader.equals(id)) { player.sendMessage("§cリーダーのみ招待できます。"); return; }
                if (args.length < 3) { player.sendMessage("§e/abyss party invite <プレイヤー>"); return; }
                if (party.members.size() >= MAX) { player.sendMessage("§cパーティは最大4人です。"); return; }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null || !target.isOnline() || target.getUniqueId().equals(id)) { player.sendMessage("§c招待先が見つかりません。"); return; }
                if (byMember.containsKey(target.getUniqueId())) { player.sendMessage("§c相手はすでにパーティに所属しています。"); return; }
                invitations.put(target.getUniqueId(), new Invitation(id, System.currentTimeMillis() + INVITE_MS));
                player.sendMessage("§a" + target.getName() + " を招待しました。");
                target.sendMessage("§e" + player.getName() + " からパーティ招待が届きました（60秒間有効）。");
                target.sendMessage(Component.text("[クリックして参加]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/abyss party accept")));
            }
            case "accept" -> {
                if (party != null) { player.sendMessage("§cすでにパーティに所属しています。"); return; }
                Invitation invite = invitations.remove(id);
                if (invite == null || invite.expiresAt < System.currentTimeMillis()) { player.sendMessage("§c有効な招待がありません。"); return; }
                Party destination = byMember.get(invite.leader);
                if (destination == null || !destination.leader.equals(invite.leader) || destination.members.size() >= MAX) { player.sendMessage("§c招待元のパーティに参加できません。"); return; }
                destination.members.add(id); byMember.put(id, destination);
                announce(destination, "§a" + player.getName() + " が参加しました。");
            }
            case "leave" -> {
                if (party == null) { player.sendMessage("§cパーティに所属していません。"); return; }
                party.members.remove(id); byMember.remove(id); player.sendMessage("§eパーティを脱退しました。");
                if (party.members.isEmpty()) return;
                if (party.leader.equals(id)) party.leader = party.members.iterator().next();
                announce(party, "§e" + player.getName() + " が脱退しました。リーダー: " + name(party.leader));
            }
            case "kick" -> {
                if (party == null || !party.leader.equals(id)) { player.sendMessage("§cリーダーのみ追放できます。"); return; }
                if (args.length < 3) { player.sendMessage("§e/abyss party kick <プレイヤー>"); return; }
                Player target = Bukkit.getPlayerExact(args[2]);
                if (target == null || target.getUniqueId().equals(id) || !party.members.remove(target.getUniqueId())) { player.sendMessage("§c対象メンバーが見つかりません。"); return; }
                byMember.remove(target.getUniqueId()); target.sendMessage("§cパーティから除外されました。");
                announce(party, "§e" + target.getName() + " を除外しました。");
            }
            case "disband" -> {
                if (party == null || !party.leader.equals(id)) { player.sendMessage("§cリーダーのみ解散できます。"); return; }
                announce(party, "§eパーティが解散されました。");
                for (UUID member : party.members) byMember.remove(member);
            }
            case "list" -> {
                if (party == null) { player.sendMessage("§eパーティ未所属（ソロ）"); return; }
                player.sendMessage("§bパーティ " + party.members.size() + "/4 §7リーダー: " + name(party.leader));
                for (UUID member : party.members) player.sendMessage("§7- " + name(member));
            }
            default -> player.sendMessage("§e/abyss party <create|invite|accept|leave|kick|list|disband>");
        }
    }
    private static String name(UUID id) { Player p = Bukkit.getPlayer(id); return p == null ? id.toString() : p.getName(); }
    private static void announce(Party party, String message) { for (UUID id : party.members) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(message); } }
}
