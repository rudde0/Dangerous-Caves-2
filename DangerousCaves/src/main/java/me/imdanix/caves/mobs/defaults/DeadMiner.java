package me.imdanix.caves.mobs.defaults;

import me.imdanix.caves.mobs.CustomMob;
import me.imdanix.caves.mobs.MobBase;
import me.imdanix.caves.regions.ActionType;
import me.imdanix.caves.regions.Regions;
import me.imdanix.caves.util.Locations;
import me.imdanix.caves.util.Materials;
import me.imdanix.caves.util.random.Rng;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeadMiner extends MobBase implements CustomMob.Ticking, Listener {
    private boolean requiresTarget;
    private boolean torches;
    private boolean redTorches;
    private double dropChance;
    private ItemStack head;
    private List<Material> items;
    private PotionEffect cooldownEffect;

    public DeadMiner() {
        super(EntityType.ZOMBIE, "dead-miner", 10, 22d);
        items = Collections.emptyList();
    }

    @Override
    protected void configure(ConfigurationSection cfg) {
        requiresTarget = cfg.getBoolean("requires-target", true);
        torches = cfg.getBoolean("place-torches", true);
        redTorches = cfg.getBoolean("redstone-torches", false);
        dropChance = cfg.getDouble("drop-chance", 16.67) / 100;
        head = Materials.getHeadFromURL(cfg.getString("head-url", "31937bcd5beaaa34244913f277505e29d2e6fb35f2e23ca4afa2b6768e398d73"));

        items = new ArrayList<>(Materials.getSet(cfg.getStringList("drop-items")));

        int cooldown = cfg.getInt("torches-cooldown", 12);
        if (cooldown <= 0) {
            cooldownEffect = null;
        } else {
            cooldownEffect = new PotionEffect(PotionEffectType.NAUSEA, cooldown*20, 0, true, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!isThis(event.getEntity()) || event.getDamage() < 1) return;
        LivingEntity entity = (LivingEntity) event.getEntity();
        if (dropChance > 0 && !items.isEmpty() && Rng.chance(dropChance))
            entity.getWorld().dropItemNaturally(
                    entity.getLocation(),
                    new ItemStack(Rng.randomElement(items))
            );
    }

    @Override
    public void prepare(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        equipment.setHelmet(head); equipment.setHelmetDropChance(0);
        equipment.setItemInMainHand(new ItemStack(Rng.nextBoolean() ? Material.IRON_PICKAXE : Material.STONE_PICKAXE));
        if (Rng.nextBoolean()) {
            equipment.setChestplate(new ItemStack(Rng.nextBoolean() ? Material.CHAINMAIL_CHESTPLATE : Material.LEATHER_CHESTPLATE));
        }
        if (Rng.nextBoolean()) {
            equipment.setBoots(new ItemStack(Rng.nextBoolean() ? Material.CHAINMAIL_BOOTS : Material.LEATHER_BOOTS));
        }
        if (torches) equipment.setItemInOffHand(new ItemStack(redTorches ? Material.REDSTONE_TORCH : Material.TORCH));
        entity.setCanPickupItems(false);
    }

    @Override
    public void tick(LivingEntity entity) {
        if (!torches || entity.hasPotionEffect(PotionEffectType.NAUSEA)) return;
        Block block = entity.getLocation().getBlock();

        if (block.getLightLevel() > 0 || (requiresTarget && ((Monster)entity).getTarget() == null) ||
                !Regions.INSTANCE.isAllowed(ActionType.ENTITY, block.getLocation()))
            return;

        if (block.getType().isAir() && Materials.isCave(block.getRelative(BlockFace.DOWN).getType())) {
            block.setType(redTorches ? Material.REDSTONE_TORCH : Material.TORCH, false);
            Locations.playSound(block.getLocation(), Sound.BLOCK_WOOD_PLACE, 1, 1);
            if (cooldownEffect != null) {
                entity.addPotionEffect(cooldownEffect);
            }
        }
    }
}
