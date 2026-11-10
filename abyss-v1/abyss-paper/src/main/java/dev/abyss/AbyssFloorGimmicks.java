package dev.abyss;

import dev.abyss.dungeon.DungeonRun;
import dev.abyss.dungeon.FloorType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

/** Phase 49: non-destructive floor hazards and a recoverable exploration secret. */
public final class AbyssFloorGimmicks implements Listener {
    private final AbyssPlugin plugin;
    private record BlockBackup(Location location, BlockData original) {}
    private static final class FloorState {
        final UUID runId;
        final int floor;
        final Location spawn;
        final List<BlockBackup> doorway=new ArrayList<>();
        boolean revealed;
        int ticks;
        FloorState(DungeonRun run,Location spawn){
            this.runId=run.id();this.floor=run.floor();this.spawn=spawn.clone();
        }
    }
    private final Map<UUID,FloorState> states=new HashMap<>();
    public AbyssFloorGimmicks(AbyssPlugin plugin){this.plugin=plugin;}
    public void clear(DungeonRun run){
        FloorState state=states.remove(run.id());
        if(state==null)return;
        for(BlockBackup original:state.doorway){
            Block block=original.location().getBlock();
            // Restore only our marker or opened passage; never overwrite unrelated edits.
            if(block.getType()==Material.CRACKED_STONE_BRICKS || block.getType()==Material.AIR)
                block.setBlockData(original.original(),false);
        }
    }
    public void start(DungeonRun run,Location spawn){
        clear(run);
        if(!Set.of(FloorType.BATTLE,FloorType.ELITE,FloorType.EXPLORATION).contains(run.type()))return;
        FloorState state=new FloorState(run,spawn);
        states.put(run.id(),state);
        if(run.type()==FloorType.EXPLORATION)installSecret(run,state);
    }
    private boolean safe(DungeonRun run,Location loc){
        return loc.getWorld()!=null && plugin.isWithinRunMap(run,loc);
    }
    private void installSecret(DungeonRun run,FloorState state){
        World w=state.spawn.getWorld();
        if(w==null)return;
        int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        // Find a real, two-block-high wall with walkable space on both sides.
        for(int radius=7;radius<=22;radius++){
            for(int[] dir:directions){
                int x=state.spawn.getBlockX()+dir[0]*radius;
                int z=state.spawn.getBlockZ()+dir[1]*radius;
                for(int dy=-2;dy<=2;dy++){
                    int y=state.spawn.getBlockY()+dy;
                    Location center=new Location(w,x,y,z);
                    if(!safe(run,center))continue;
                    Block lower=w.getBlockAt(x,y,z),upper=w.getBlockAt(x,y+1,z);
                    if(!lower.getType().isSolid() || !upper.getType().isSolid())continue;
                    // Do not alter containers, spawners, exits or unusual block states.
                    if(!Set.of(Material.STONE_BRICKS,Material.DEEPSLATE_BRICKS,
                        Material.TUFF_BRICKS,Material.COBBLESTONE,Material.STONE,
                        Material.ANDESITE,Material.DEEPSLATE).contains(lower.getType()))continue;
                    if(upper.getType()!=lower.getType())continue;
                    int px=-dir[0],pz=-dir[1];
                    Location a=new Location(w,x+px,y,z+pz),b=new Location(w,x-px,y,z-pz);
                    if(!safe(run,a)||!safe(run,b))continue;
                    if(!w.getBlockAt(a).isPassable()||!w.getBlockAt(a.clone().add(0,1,0)).isPassable()
                      ||!w.getBlockAt(b).isPassable()||!w.getBlockAt(b.clone().add(0,1,0)).isPassable())continue;
                    if(!a.clone().add(0,-1,0).getBlock().getType().isSolid()
                      ||!b.clone().add(0,-1,0).getBlock().getType().isSolid())continue;
                    state.doorway.add(new BlockBackup(lower.getLocation(),lower.getBlockData().clone()));
                    state.doorway.add(new BlockBackup(upper.getLocation(),upper.getBlockData().clone()));
                    lower.setType(Material.CRACKED_STONE_BRICKS,false);
                    upper.setType(Material.CRACKED_STONE_BRICKS,false);
                    return;
                }
            }
        }
        plugin.getLogger().info("Phase49: no safe hidden passage in "+run.id()+" floor "+run.floor());
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void discover(PlayerInteractEvent e){
        if(e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        Player p=e.getPlayer();
        DungeonRun run=plugin.runFor(p.getUniqueId());
        if(run==null || run.type()!=FloorType.EXPLORATION)return;
        FloorState state=states.get(run.id());
        if(state==null||state.floor!=run.floor()||state.revealed)return;
        boolean matched=state.doorway.stream().anyMatch(b->b.location().equals(e.getClickedBlock().getLocation()));
        if(!matched || p.getLocation().distanceSquared(e.getClickedBlock().getLocation())>36)return;
        e.setCancelled(true);
        state.revealed=true;
        for(BlockBackup backup:state.doorway)backup.location().getBlock().setType(Material.AIR,false);
        p.sendMessage("§6隠された通路を発見した！");
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,e.getClickedBlock().getLocation().add(.5,1,.5),30,.7,.8,.7,.04);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,120,0));
    }
    /** Called each second, strictly on the server thread. */
    public void tick(){
        for(FloorState state:new ArrayList<>(states.values())){
            DungeonRun run=null;
            List<Player> members=new ArrayList<>();
            for(Player p:Bukkit.getOnlinePlayers()){
                DungeonRun current=plugin.runFor(p.getUniqueId());
                if(current!=null && current.id().equals(state.runId) && current.floor()==state.floor
                  && safe(current,p.getLocation())) {run=current;members.add(p);}
            }
            if(run==null){continue;}
            if(++state.ticks<8)continue; // grace period after teleport
            if(run.type()==FloorType.EXPLORATION && !state.revealed){
                for(BlockBackup backup:state.doorway){
                    backup.location().getWorld().spawnParticle(Particle.END_ROD,
                        backup.location().clone().add(.5,.5,.5),2,.25,.25,.25,.005);
                }
            }
            // Moving obstacle: a sweeping visible hazard band that applies a brief slow.
            double offset=Math.sin(state.ticks*0.35)*8.0;
            Location center=state.spawn.clone().add(offset,0,0);
            for(int n=-6;n<=6;n+=2){
                Location point=center.clone().add(0,0,n);
                if(safe(run,point))point.getWorld().spawnParticle(Particle.CRIT,point.add(0,1,0),3,.15,.4,.15,.01);
            }
            for(Player p:members){
                if(p.getLocation().distanceSquared(center)>200)continue;
                if(Math.abs(p.getLocation().getX()-center.getX())<1.1
                        && Math.abs(p.getLocation().getZ()-center.getZ())<6.5){
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,45,0));
                    p.sendActionBar("§e移動障害物に足を取られた！");
                }
            }
        }
    }
}
