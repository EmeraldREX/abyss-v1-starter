package dev.abyss;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import java.util.*;
public final class AbyssRangedWeapons implements Listener {
 private final AbyssPlugin plugin;
 private final NamespacedKey ranged;
 private final Map<UUID,Long> cooldown=new HashMap<>();
 public AbyssRangedWeapons(AbyssPlugin plugin){this.plugin=plugin;this.ranged=new NamespacedKey(plugin,"short_range_bow");}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void onUse(PlayerInteractEvent e){
  if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND)return;
  if(e.getAction()!=Action.RIGHT_CLICK_AIR&&e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
  Player p=e.getPlayer();if(!plugin.isInRun(p.getUniqueId()))return;
  ItemStack held=p.getInventory().getItemInMainHand();if(held.getType()!=Material.BOW)return;
  long now=System.currentTimeMillis();if(now-cooldown.getOrDefault(p.getUniqueId(),0L)<650)return;
  cooldown.put(p.getUniqueId(),now);e.setCancelled(true);
  Arrow a=p.launchProjectile(Arrow.class);a.setVelocity(p.getEyeLocation().getDirection().multiply(2.6));
  a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
  a.getPersistentDataContainer().set(ranged,PersistentDataType.BYTE,(byte)1);
  Location origin=a.getLocation().clone();
  new org.bukkit.scheduler.BukkitRunnable(){public void run(){if(!a.isValid()||a.isDead()){cancel();return;}if(!a.getWorld().equals(origin.getWorld())||a.getLocation().distanceSquared(origin)>=225){a.remove();cancel();}}}.runTaskTimer(plugin,1L,1L);
 }
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void onBowMelee(EntityDamageByEntityEvent e){
  if(e.getDamager() instanceof Player p && plugin.isInRun(p.getUniqueId())
    && p.getInventory().getItemInMainHand().getType()==Material.BOW
    && e.getEntity() instanceof LivingEntity){e.setDamage(1.0);}
 }
 @EventHandler public void onHit(ProjectileHitEvent e){if(e.getEntity().getPersistentDataContainer().has(ranged,PersistentDataType.BYTE))Bukkit.getScheduler().runTask(plugin,()->e.getEntity().remove());}
}
