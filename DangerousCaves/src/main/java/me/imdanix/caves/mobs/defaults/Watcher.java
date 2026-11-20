package me.imdanix.caves.mobs.defaults;

import me.imdanix.caves.mobs.CustomMob;
import me.imdanix.caves.mobs.MobBase;
import me.imdanix.caves.mobs.MobsManager;
import me.imdanix.caves.util.Locations;
import me.imdanix.caves.util.Materials;
import me.imdanix.caves.util.PmeBridge;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Mağara Gözü (config key {@code watcher}): a melee husk wearing the ProMobEngine model {@code cave_eye}, a hovering
 * eye whose tentacle lash is the model's ATTACK clip (the engine starts it from the husk's own arm swing).
 * <p>
 * The base always keeps the old watcher look, an invisible husk wearing the eye head: the engine hides body and
 * equipment from the players it draws the model for, and the rest (Bedrock, clients below 1.20.2, or everybody
 * when the engine or the model is missing) see the floating head.
 */
public class Watcher extends MobBase implements CustomMob.Ticking, Listener {
    private static final PotionEffect INVISIBILITY = new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false);
    private static final String DEFAULT_HEAD = "d2938f241d74433f72f5c39cb83a8e5ff7e177ba7cb42869db64e3079102bfc5";
    // A watcher whose ENTITY tick has not run for this long is gone (dead or unloaded)
    private static final int STALE_TICKS = 100;

    private final Plugin plugin;
    // "One per area": last known place of every live watcher, refreshed by its ENTITY tick and pruned lazily.
    // Replaces a 96-block cube entity scan on every spawn attempt
    private final Map<UUID, Spot> spots = new HashMap<>();
    private final Location scratch = new Location(null, 0, 0, 0);

    private ItemStack head;
    private double nearbyRadius;
    private double attackDamage;
    private String model;

    public Watcher(MobsManager mobs) {
        super(EntityType.HUSK, "watcher", 3, 20d, "&9Mağara Gözü");
        this.plugin = mobs.getPlugin();
    }

    @Override
    protected void configure(ConfigurationSection cfg) {
        head = Materials.getHeadFromURL(cfg.getString("head-url", DEFAULT_HEAD));
        nearbyRadius = Math.max(0, cfg.getDouble("nearby-limit-radius", 48));
        attackDamage = Math.max(0, cfg.getDouble("attack-damage", 4));
        model = cfg.getString("model", "cave_eye");
        // scare-cooldown and max-scare-distance belonged to the removed jumpscare and are ignored
    }

    @Override
    protected boolean randomizeData() {
        // No baby husks, chicken jockeys or random gear: the look is fixed
        return false;
    }

    @Override
    public boolean canSpawn(Location loc) {
        if (nearbyRadius <= 0) return true;
        double radiusSquared = nearbyRadius * nearbyRadius;
        World world = loc.getWorld();
        int now = Bukkit.getCurrentTick();
        Iterator<Spot> iterator = spots.values().iterator();
        while (iterator.hasNext()) {
            Spot spot = iterator.next();
            if (now - spot.tick > STALE_TICKS) {
                iterator.remove();
            } else if (spot.world == world && spot.distanceSquared(loc) <= radiusSquared) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void prepare(LivingEntity entity) {
        entity.setSilent(true);
        entity.setCanPickupItems(false);
        entity.addPotionEffect(INVISIBILITY);
        if (entity instanceof Ageable ageable) ageable.setAdult();

        EntityEquipment equipment = entity.getEquipment();
        equipment.clear();
        equipment.setHelmet(head.clone());
        equipment.setHelmetDropChance(0);
        setBaseAttribute(entity, Attribute.ATTACK_DAMAGE, attackDamage);

        if (PmeBridge.isAvailable()) PmeBridge.attach(entity, model);
        remember(entity);
    }

    @Override
    public void onLoad(LivingEntity entity) {
        remember(entity);
        if (!PmeBridge.isAvailable()) return;
        // The engine puts a model back from its own data a tick after the load. A watcher spawned while the
        // engine or the model was missing has none: give it one now that it is there
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (entity.isValid() && !PmeBridge.has(entity) && PmeBridge.hasModel(model)) PmeBridge.attach(entity, model);
        }, 2L);
    }

    @Override
    public void tick(LivingEntity entity) {
        remember(entity);
        // Restore the fallback look now and then (getHelmet copies the item, so not every ENTITY tick)
        if (entity.getTicksLived() % 20 < 4) ensureVisibleHead(entity);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        // A modelled watcher has the model's hurt sound; the squish is the fallback's voice
        if (isThis(entity) && !PmeBridge.has(entity))
            Locations.playSound(entity.getLocation(), Sound.ENTITY_SLIME_SQUISH, 1, 1.1f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        // A husk under water turns into a zombie, which would lose the model and the tags
        if (!isThis(event.getEntity())) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof Zombie zombie) zombie.setConversionTime(-1);
    }

    private void remember(LivingEntity entity) {
        entity.getLocation(scratch);
        spots.computeIfAbsent(entity.getUniqueId(), id -> new Spot()).update(scratch, Bukkit.getCurrentTick());
    }

    /**
     * Head is the only visible part of the fallback watcher, so restore it if something took it away
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

    private static final class Spot {
        private World world;
        private double x;
        private double y;
        private double z;
        private int tick;

        private void update(Location loc, int tick) {
            this.world = loc.getWorld();
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
            this.tick = tick;
        }

        private double distanceSquared(Location loc) {
            double dx = x - loc.getX();
            double dy = y - loc.getY();
            double dz = z - loc.getZ();
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
