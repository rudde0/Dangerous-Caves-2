package com.github.evillootlye.caves.caverns;

import com.github.evillootlye.caves.Dynamics;
import com.github.evillootlye.caves.configuration.Configurable;
import com.github.evillootlye.caves.utils.MaterialUtils;
import com.github.evillootlye.caves.utils.Rnd;
import com.github.evillootlye.caves.utils.Utils;
import com.github.evillootlye.caves.utils.bounds.Bound;
import com.github.evillootlye.caves.utils.bounds.DualBound;
import com.github.evillootlye.caves.utils.bounds.SingularBound;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Switch;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;

public class CavesAging implements Dynamics.Tickable, Configurable {
    private static final BlockFace[] FACES = { BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST };
    private final Set<Bound> skippedChunks;
    private final Set<String> worlds;

    private int radius;
    private int y;
    private double chance;
    private double agingChance;

    public CavesAging() {
        skippedChunks = new HashSet<>();
        worlds = new HashSet<>();
    }

    @Override
    public void reload(ConfigurationSection cfg) {
        radius = cfg.getInt("radius", 3);
        y = Math.min(254, cfg.getInt("y-max", 80));
        chance = cfg.getDouble("chance", 50) / 100;
        agingChance = cfg.getDouble("change-chance", 25) / 100;
        skippedChunks.clear();
        for(String str : cfg.getStringList("skip-chunks")) {
            String[] xzStr = str.split(" ");
            String[] firstCoords = xzStr[0].split(",");
            if(xzStr.length > 1) {
                String[] secondCoords = xzStr[1].split(",");
                try {
                    DualBound bound = new DualBound(
                            Integer.valueOf(firstCoords[0]), Integer.valueOf(secondCoords[0]),
                            Integer.valueOf(firstCoords[1]), Integer.valueOf(secondCoords[1])
                    );
                    skippedChunks.add(bound);
                } catch (NumberFormatException ignored) {}
            } else {
                try {
                    SingularBound bound = new SingularBound(
                            Integer.valueOf(firstCoords[0]), Integer.valueOf(firstCoords[1])
                    );
                    skippedChunks.add(bound);
                } catch (NumberFormatException ignored) {}
            }
        }
        worlds.clear();
        Utils.fillWorlds(cfg.getStringList("worlds"), worlds);
    }

    @Override
    public void tick() {
        if(chance <= 0) return;
        for(World world : Bukkit.getWorlds()) {
            if(!worlds.contains(world.getName())) continue;
            for(Player player : world.getPlayers()) {
                if(Rnd.nextDouble() > chance) continue;
                Chunk start = player.getChunk();
                for(int x = start.getX() - radius; x <= start.getX() + radius; x++)
                for(int z = start.getZ() - radius; z <= start.getZ() + radius; z++)
                    if(isAllowed(x, z)) proceedChunk(world.getChunkAt(x, z));
            }
        }
    }

    private boolean isAllowed(int x, int z) {
        for(Bound bound : skippedChunks)
            if(bound.isInside(x, z)) return false;
        return true;
    }

    private void proceedChunk(Chunk chunk) {
        for(int x = 0; x < 16; x++) for(int z = 0; z < 16; z++) for(int y = 2; y <= this.y; y++) {
            Block block = chunk.getBlock(x, y, z);
            if(block.getLightFromSky() > 0) break;
            Material type = block.getType();
            if((MaterialUtils.CAVE.contains(type) || type == Material.COBBLESTONE_WALL) && Rnd.nextDouble() < agingChance) {
                switch(Rnd.nextInt(3)) {
                    case 0: block.setType(Material.COBBLESTONE); break;
                    case 1: block.setType(Material.ANDESITE); break;
                    default:
                        Block downBlock = block.getRelative(BlockFace.DOWN);
                        if(downBlock.getType() == Material.AIR && Rnd.nextBoolean())
                            downBlock.setType(Material.COBBLESTONE_WALL);
                }
                if(Rnd.nextDouble() > 0.125) {
                    for(BlockFace face : FACES) {
                        Block relBlock = block.getRelative(face);
                        if (relBlock.getType() == Material.AIR) {
                            relBlock.setType(Material.VINE);
                            MultipleFacing facing = (MultipleFacing) relBlock.getBlockData();
                            facing.setFace(face.getOppositeFace(), true);
                            relBlock.setBlockData(facing);
                        }
                    }
                }
                Block upBlock = block.getRelative(BlockFace.UP);
                if(upBlock.getType() == Material.AIR){
                    if(Rnd.nextDouble() > 0.111) {
                        upBlock.setType(Rnd.nextBoolean() ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM);
                    }
                    if(Rnd.nextDouble() > 0.667) {
                        upBlock.setType(Material.STONE_BUTTON);
                        Switch button = (Switch) upBlock.getBlockData();
                        button.setFace(Switch.Face.FLOOR);
                        upBlock.setBlockData(button);
                    }
                }
            }
        }
    }

    @Override
    public Dynamics.TickLevel getTickLevel() {
        return Dynamics.TickLevel.WORLD;
    }
}
