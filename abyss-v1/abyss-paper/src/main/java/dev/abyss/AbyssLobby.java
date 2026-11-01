package dev.abyss;

import org.bukkit.*;

/** Sky-island lobby. Builds only on explicit request or when the world is new. */
public final class AbyssLobby {
    private AbyssLobby() {}

    public static void build(World world, int x, int y, int z) {
        // Clear the sky construction volume without touching terrain below.
        for (int dx=-35;dx<=35;dx++) for(int dz=-25;dz<=25;dz++)
            for(int dy=-2;dy<=24;dy++) world.getBlockAt(x+dx,y+dy,z+dz).setType(Material.AIR,false);
        // Main floating island, tapering underside and solid walking surface.
        island(world,x,y,z,18,14);
        island(world,x-27,y,z,7,7);
        island(world,x+27,y,z,7,7);
        for(int sx:new int[]{-1,1}) {
            for(int dx=18;dx<=24;dx++) for(int dz=-2;dz<=2;dz++)
                set(world,x+sx*dx,y-1,z+dz,Material.STONE_BRICKS);
            for(int dx=18;dx<=24;dx++) for(int dz:new int[]{-3,3})
                set(world,x+sx*dx,y,z+dz,Material.POLISHED_DEEPSLATE_WALL);
        }
        // Central ceremonial path and portal facade.
        for(int dz=-11;dz<=11;dz++) for(int dx=-2;dx<=2;dx++)
            set(world,x+dx,y-1,z+dz,(Math.abs(dx)==2?Material.POLISHED_BLACKSTONE_BRICKS:Material.POLISHED_ANDESITE));
        for(int dx=-4;dx<=4;dx++) for(int dy=0;dy<=11;dy++) {
            if(Math.abs(dx)>=3 || dy==11) set(world,x+dx,y+dy,z-11,Material.POLISHED_BLACKSTONE_BRICKS);
            else if(dy>=1) set(world,x+dx,y+dy,z-11,Material.CRYING_OBSIDIAN);
        }
        for(int dx:new int[]{-13,13}) for(int dz:new int[]{-9,9}) {
            pillar(world,x+dx,y,z+dz,8);
        }
        for(int dx:new int[]{-27,27}) {
            for(int dz:new int[]{-4,4}) pillar(world,x+dx,y,z+dz,6);
            for(int dy=1;dy<=6;dy++) for(int dz=-2;dz<=2;dz++)
                if(Math.abs(dz)==2||dy==6) set(world,x+dx,y+dy,z+0,Material.POLISHED_BLACKSTONE_BRICKS);
        }
        for(int dx=-13;dx<=13;dx+=4) for(int dz:new int[]{-12,12}) {
            set(world,x+dx,y,z+dz,Material.DEEPSLATE_BRICK_WALL);
            set(world,x+dx,y+1,z+dz,Material.LANTERN);
        }
        set(world,x,y-1,z,Material.GOLD_BLOCK);
        world.setSpawnLocation(x,y,z,0);
        world.setGameRule(GameRules.SPAWN_MOBS,false);
        world.setTime(6000);
    }
    private static void island(World w,int x,int y,int z,int rx,int rz) {
        for(int dx=-rx;dx<=rx;dx++) for(int dz=-rz;dz<=rz;dz++) {
            double dist=(double)dx*dx/(rx*rx)+(double)dz*dz/(rz*rz);
            if(dist>1)continue;
            set(w,x+dx,y-1,z+dz,dist>.84?Material.POLISHED_DEEPSLATE:Material.STONE_BRICKS);
            int depth=Math.max(1,(int)Math.round((1-dist)*7));
            for(int d=2;d<=depth;d++)set(w,x+dx,y-d,z+dz,d%3==0?Material.DEEPSLATE_BRICKS:Material.COBBLED_DEEPSLATE);
        }
    }
    private static void pillar(World w,int x,int y,int z,int h) {
        for(int dy=0;dy<h;dy++)set(w,x,y+dy,z,Material.POLISHED_BLACKSTONE_BRICKS);
        set(w,x,y+h,z,Material.SOUL_LANTERN);
    }
    private static void set(World w,int x,int y,int z,Material m){w.getBlockAt(x,y,z).setType(m,false);}
}
