package me.imdanix.caves.mobs.defaults;

import me.imdanix.caves.mobs.CustomMob;
import me.imdanix.caves.mobs.MobBase;
import me.imdanix.caves.util.Locations;
import me.imdanix.caves.util.Materials;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public class Watcher extends MobBase implements CustomMob.Ticking, Listener {
    private static final PotionEffect INVISIBILITY = new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false);
    private static final PotionEffect SLOW = new PotionEffect(PotionEffectType.SLOWNESS, 30, 4);
    private static final PotionEffect BLINDNESS = new PotionEffect(PotionEffectType.BLINDNESS, 80, 2);
    private static final Vector ZERO_VECTOR = new Vector(0, 0, 0);
    // Distances in front of the player to try, closest last so the scare still happens in tight tunnels
    private static final double[] TELEPORT_DISTANCES = {1.5, 1.2};

    // Last jumpscare time per watcher; weak keys so removed entities don't leak
    private final Map<UUID, Long> lastScare = new WeakHashMap<>();

    private ItemStack head;
    private long scareCooldown;
    private double nearbyRadius;
    private double maxScareDistance;

    public Watcher() {
        super(EntityType.HUSK, "watcher", 3, 15d);
    }

    @Override
    protected void configure(ConfigurationSection cfg) {
        head = Materials.getHeadFromURL(cfg.getString("head-url", "d2938f241d74433f72f5c39cb83a8e5ff7e177ba7cb42869db64e3079102bfc5"));
        scareCooldown = Math.max(0, Math.round(cfg.getDouble("scare-cooldown", 2) * 1000));
        nearbyRadius = Math.max(0, cfg.getDouble("nearby-limit-radius", 48));
        maxScareDistance = Math.max(2, cfg.getDouble("max-scare-distance", 24));
    }

    @Override
    public boolean canSpawn(Location loc) {
        if (nearbyRadius <= 0) return true;
        // One watcher per area is creepy, a crowd of them is just annoying
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, nearbyRadius, nearbyRadius, nearbyRadius,
                e -> e.getType() == EntityType.HUSK)) {
            if (isThis(entity)) return false;
        }
        return true;
    }

    @Override
    public void prepare(LivingEntity entity) {
        entity.setSilent(true);
        entity.setCanPickupItems(false);
        entity.addPotionEffect(INVISIBILITY);
        // Baby husks and chicken jockeys make the only visible part (the head) tiny or hidden
        if (entity instanceof Ageable ageable) ageable.setAdult();
        if (entity.isInsideVehicle()) entity.leaveVehicle();

        EntityEquipment equipment = entity.getEquipment();
        equipment.setHelmet(head.clone());  equipment.setHelmetDropChance(0);
        equipment.setChestplate(null);
        equipment.setLeggings(null);
        equipment.setBoots(null);
        equipment.setItemInMainHand(null);
        equipment.setItemInOffHand(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (isThis(event.getEntity()))
            Locations.playSound(event.getEntity().getLocation(), Sound.ENTITY_SLIME_SQUISH, 1, 1.1f);
    }

    @Override
    public void tick(LivingEntity entity) {
        ensureVisibleHead(entity);
        if (!(((Monster) entity).getTarget() instanceof Player target)) return;
        if (target.getWorld() != entity.getWorld() ||
                target.getLocation().distanceSquared(entity.getLocation()) > maxScareDistance * maxScareDistance) return;
        if (Locations.isLookingAt(target, entity) && target.hasLineOfSight(entity)) return;

        long now = System.currentTimeMillis();
        Long last = lastScare.get(entity.getUniqueId());
        if (last != null && now - last < scareCooldown) return;

        Location loc = findScareLocation(target);
        if (loc == null) return;
        if (!entity.teleport(loc)) return;
        lastScare.put(entity.getUniqueId(), now);

        target.setVelocity(ZERO_VECTOR);
        target.addPotionEffect(SLOW);
        target.addPotionEffect(BLINDNESS);
        Locations.playSound(target.getEyeLocation(), Sound.ENTITY_GHAST_HURT, 1, 2);
    }

    /**
     * Head is the only visible part of the watcher, so restore it if something took it away
     */
    private void ensureVisibleHead(LivingEntity entity) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) return;
        ItemStack helmet = equipment.getHelmet();
        if (helmet == null || helmet.getType() != Material.PLAYER_HEAD) {
            equipment.setHelmet(head.clone());
            equipment.setHelmetDropChance(0);
        }
        if (entity instanceof Ageable ageable && !ageable.isAdult()) ageable.setAdult();
    }

    /**
     * Find a free spot right in front of the player, facing them.
     * Old logic used raw look direction, so the watcher often ended up inside walls/floor
     * (or behind the camera when looking up/down) and became fully invisible.
     */
    private static Location findScareLocation(Player target) {
        Location base = target.getLocation();
        Vector forward = base.getDirection().setY(0);
        if (forward.lengthSquared() < 1.0E-4) {
            // Looking straight up/down - fall back to body yaw
            double yaw = Math.toRadians(base.getYaw());
            forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        }
        forward.normalize();

        for (double distance : TELEPORT_DISTANCES) {
            Location loc = base.clone().add(forward.clone().multiply(distance));
            if (!isFree(loc)) continue;
            Location eyeLevel = loc.clone().add(0, 1.6, 0);
            if (!target.hasLineOfSight(eyeLevel)) continue;
            loc.setDirection(base.toVector().subtract(loc.toVector()));
            loc.setPitch(0);
            return loc;
        }
        return null;
    }

    private static boolean isFree(Location loc) {
        Block feet = loc.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid();
    }
}
