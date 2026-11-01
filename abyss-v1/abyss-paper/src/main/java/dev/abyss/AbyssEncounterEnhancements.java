package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import java.util.*;

/** Dungeon-only lighting and per-mob randomized combat archetypes. */
public final class AbyssEncounterEnhancements {
    private final AbyssPlugin plugin;
    private final NamespacedKey variantKey;
    private final Map<UUID, Set<Location>> lights = new HashMap<>();
    public AbyssEncounterEnhancements(AbyssPlugin plugin) {
        this.plugin = plugin;
        this.variantKey = new NamespacedKey(plugin, "enemy_variant");
    }
    public void light(Player player, DungeonRun run) {
        Location at = player.getLocation();
        if (at.getWorld() == null || at.getBlock().getLightLevel() > 5) return;
        // Only place lights inside the run's schematic bounds and in empty headroom.
        Block block = at.getBlock().getRelative(0, 1, 0);
        if (block.getType() != Material.AIR || block.getLightLevel() > 5) return;
        if (!plugin.isWithinRunMap(run, block.getLocation())) return;
        block.setType(Material.LIGHT, false);
        if (block.getBlockData() instanceof Levelled level) {
            level.setLevel(10);
            block.setBlockData(level, false);
        }
        lights.computeIfAbsent(run.id(), unused -> new HashSet<>()).add(block.getLocation());
    }
    public void clearLights(DungeonRun run) {
        Set<Location> set = lights.remove(run.id());
        if (set == null) return;
        for (Location loc : set) if (loc.getChunk().isLoaded() && loc.getBlock().getType() == Material.LIGHT)
            loc.getBlock().setType(Material.AIR, false);
    }
    private final Map<UUID, LivingEntity> active = new HashMap<>();
    private long ticks;
    private final Map<UUID, Long> spawnedAt = new HashMap<>();

    /** Called on the main server thread once per second; no async Bukkit entity access. */
    public void tickAI() {
        ticks++;
        Iterator<Map.Entry<UUID, LivingEntity>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            LivingEntity mob = it.next().getValue();
            if (!mob.isValid() || mob.isDead()) { spawnedAt.remove(mob.getUniqueId()); it.remove(); continue; }
            if (!(mob instanceof Mob hostile)) continue;
            // Gradual aggro: 0-30s 8 blocks, 30-60s 16, 60-90s 28, then 48.
            long age = ticks - spawnedAt.getOrDefault(mob.getUniqueId(), ticks);
            double range = age < 30 ? 8 : age < 60 ? 16 : age < 90 ? 28 : 48;
            Player nearest = null;
            double best = range * range;
            for (Player candidate : mob.getWorld().getPlayers()) {
                if (!plugin.isInRun(candidate.getUniqueId()) || candidate.isDead() || candidate.getGameMode() == GameMode.SPECTATOR) continue;
                double d = mob.getLocation().distanceSquared(candidate.getLocation());
                if (d < best) { nearest = candidate; best = d; }
            }
            if (nearest == null) hostile.setTarget(null);
            else hostile.setTarget(nearest);
            if (!(hostile.getTarget() instanceof Player target) || !target.isOnline()
                || !target.getWorld().equals(mob.getWorld()) || mob.getLocation().distanceSquared(target.getLocation()) > 256) continue;
            Integer v = mob.getPersistentDataContainer().get(variantKey, PersistentDataType.INTEGER);
            if (v == null) continue;
            // Different intervals prevent every mob using its ability on the same tick.
            if ((ticks + Math.floorMod(mob.getUniqueId().hashCode(), 13)) % (4 + v) != 0) continue;
            double distance = Math.sqrt(mob.getLocation().distanceSquared(target.getLocation()));
            if (mob instanceof Spider || mob instanceof CaveSpider) {
                switch (v) {
                    case 0 -> { if (distance < 12 && distance > 2) leap(mob, target, 1.05); }
                    case 1 -> { if (distance < 5) { target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1)); leap(mob, target, .7); }; }
                    case 2 -> { if (distance < 5) { target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 90, 1)); leap(mob, target, .6); }; }
                }
            } else if (mob instanceof Skeleton || mob instanceof Stray) {
                switch (v) {
                    case 0 -> { if (distance < 12) fireArrow(mob, target, 2.4); }
                    case 1 -> { if (distance < 12) { fireArrow(mob, target, 2.1); fireArrow(mob, target, 2.3); } }
                    case 2 -> { if (distance < 9) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0)); }
                    case 3 -> { if (distance < 6) retreat(mob, target); }
                    case 4 -> { if (distance < 13) fireArrow(mob, target, 3.0); }
                }
            } else if (mob instanceof Zombie || mob instanceof Husk || mob instanceof Drowned) {
                switch (v) {
                    case 0 -> { if (distance < 12 && distance > 2) leap(mob, target, .85); }
                    case 1 -> { if (distance < 4) target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0)); }
                    case 2 -> { if (distance < 13) leap(mob, target, 1.25); }
                    case 3 -> { if (distance < 3) target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 70, 0)); }
                    case 4 -> { if (distance < 7) { mob.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1)); mob.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 0)); }; }
                }
            } else if (mob instanceof Pillager) {
                switch (v) {
                    case 0 -> { if (distance < 12) fireArrow(mob, target, 1.1); }
                    case 1 -> { if (distance < 6) retreat(mob, target); }
                    case 2 -> { if (distance < 12) fireArrow(mob, target, 1.45); }
                    case 3 -> { if (distance < 5) target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 55, 0)); }
                    case 4 -> { if (distance < 10) { fireArrow(mob, target, .95); fireArrow(mob, target, .95); } }
                }
            }
        }
    }
    private void fireArrow(LivingEntity source, Player target, double speed) {
        Vector direction = target.getEyeLocation().toVector().subtract(source.getEyeLocation().toVector());
        if (direction.lengthSquared() < .01) return;
        Arrow arrow = source.getWorld().spawnArrow(source.getEyeLocation(), direction.normalize(), (float)speed, 6f);
        arrow.setShooter(source);
    }
    private void leap(LivingEntity mob, Player target, double strength) {
        Vector v = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
        if (v.lengthSquared() < .01) return;
        mob.setVelocity(v.normalize().multiply(strength).setY(.30));
    }
    private void retreat(LivingEntity mob, Player target) {
        Vector v = mob.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
        if (v.lengthSquared() < .01) return;
        mob.setVelocity(v.normalize().multiply(.42).setY(.18));
    }
    public void shutdown() { active.clear(); spawnedAt.clear(); lights.clear(); }

    public void configure(LivingEntity mob, int floor) {
        if (!(mob instanceof Mob)) return;
        int variants = mob instanceof Spider || mob instanceof CaveSpider ? 3 : 5;
        int variant = java.util.concurrent.ThreadLocalRandom.current().nextInt(variants);
        mob.getPersistentDataContainer().set(variantKey, PersistentDataType.INTEGER, variant);
        active.put(mob.getUniqueId(), mob);
        spawnedAt.put(mob.getUniqueId(), ticks);
        String[] titles = {"強襲", "重装", "俊敏", "凶暴", "精鋭"};
        double[] hp = {1, 1.7, .8, 1.2, 1.45};
        double[] attack = {1.25, 1.05, .9, 1.6, 1.3};
        double[] defense = {1, 1.8, .7, .85, 1.35};
        double[] speed = {1.8, 1.5, 3.0, 2.2, 2.5};
        var max = mob.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) { max.setBaseValue(Math.max(1, max.getBaseValue() * hp[variant])); mob.setHealth(max.getValue()); }
        var damage = mob.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(Math.max(1, damage.getBaseValue() * attack[variant]));
        var armor = mob.getAttribute(Attribute.ARMOR);
        if (armor != null) armor.setBaseValue(Math.min(30, armor.getBaseValue() + 2 * defense[variant]));
        var movement = mob.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) movement.setBaseValue(Math.min(.85, movement.getBaseValue() * speed[variant]));
        mob.setCustomName("§6" + titles[variant] + " §f" + mob.getType().name() + " §7Lv" + floor);
        mob.setCustomNameVisible(true);
        if (variant == 3 && mob instanceof Zombie zombie) zombie.setBaby(false);
    }
}
