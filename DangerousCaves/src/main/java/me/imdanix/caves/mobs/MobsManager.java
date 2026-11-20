package me.imdanix.caves.mobs;

import me.imdanix.caves.configuration.Configurable;
import me.imdanix.caves.configuration.Configuration;
import me.imdanix.caves.ticks.Dynamics;
import me.imdanix.caves.ticks.TickLevel;
import me.imdanix.caves.ticks.Tickable;
import me.imdanix.caves.util.Locations;
import me.imdanix.caves.util.Manager;
import me.imdanix.caves.util.PlayerAttackedEvent;
import me.imdanix.caves.util.TagHelper;
import me.imdanix.caves.util.Utils;
import me.imdanix.caves.util.random.Rng;
import me.imdanix.caves.util.random.WeightedPool;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Manages custom mob spawning and registering
 */
// TODO: Split into Manager and Registry
public class MobsManager implements Manager<CustomMob>, Listener, Tickable, Configurable {
    private static final Pattern CUSTOM_TYPE_PATTERN = Pattern.compile("[a-z\\d_-]+");

    // Ticking mobs keep a reference to their entity: one validity check per ENTITY tick instead of
    // a UUID lookup through every world. Invalid ones are looked up once (cross-world travel keeps the UUID)
    private final Map<CustomMob.Ticking, Map<UUID, LivingEntity>> tickingEntities = new HashMap<>();
    private final MetadataValue MARKER;
    // Natural spawn replacement lives in its own listener, so a reload can switch it alone
    private final Listener spawnListener;

    private final Plugin plugin;
    private final Configuration config;
    private final Dynamics dynamics;

    private final Map<String, CustomMob> mobs;
    private final Set<String> worlds;
    private boolean disabled;
    private WeightedPool<CustomMob> mobsPool;

    private Set<EntityType> replaceTypes;
    private int yMin;
    private int yMax;
    private int spawnTries;
    private double chance;
    private boolean blockRename;
    private boolean metadata;
    private Predicate<Location> lightCheck;

    public MobsManager(Plugin plugin, Configuration config, Dynamics dynamics) {
        MARKER = new FixedMetadataValue(plugin, new Object());
        this.plugin = plugin;
        this.config = config;
        this.dynamics = dynamics;
        mobs = new HashMap<>();
        worlds = new HashSet<>();
        mobsPool = new WeightedPool<>();
        spawnListener = new Listener() {
            @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
            public void onSpawn(CreatureSpawnEvent event) {
                if (MobsManager.this.onSpawn(event.getEntityType(), event.getLocation(), event.getSpawnReason()))
                    event.setCancelled(true);
            }
        };
    }

    @Override
    public void reload(ConfigurationSection cfg) {
        chance = cfg.getDouble("try-chance", 50) / 100;
        blockRename = cfg.getBoolean("restrict-rename", false);
        yMin = cfg.getInt("y-min", -64);
        yMax = cfg.getInt("y-max", 64);
        spawnTries = Math.max(1, cfg.getInt("spawn-tries", 3));

        int maxLight = cfg.getInt("max-light-level", 16);
        if (maxLight < 16) {
            lightCheck = (l) -> l.getBlock().getLightLevel() <= Math.max(0, maxLight);
        } else {
            lightCheck = (l) -> true;
        }

        Utils.fillWorlds("mobs.worlds", cfg.getStringList("worlds"), worlds);

        List<String> replaceMobs = cfg.isList("replace-mobs") ?
                                   cfg.getStringList("replace-mobs") :
                                   Arrays.asList("ZOMBIE", "HUSK", "SKELETON", "STRAY", "CREEPER",
                                                                                        "SPIDER", "WITCH", "ENDERMAN");
        replaceTypes = Utils.getEnumSet(EntityType.class, replaceMobs);

        disabled = !cfg.getBoolean("enabled", true);
        recalculateChances();

        metadata = cfg.getBoolean("add-metadata", false);
        // Only the spawn listener is switched here; the rest of this listener is registered once on enable
        // (registering `this` again duplicated the attack/load/interact handlers on every reload)
        HandlerList.unregisterAll(spawnListener);
        if (!disabled) {
            Bukkit.getPluginManager().registerEvents(spawnListener, plugin);
        }
    }

    /**
     * Recalculate mobs spawn chances
     */
    private void recalculateChances() {
        mobsPool = new WeightedPool<>();
        mobs.values().forEach(m -> mobsPool.add(m, m.getWeight()));
        if (mobsPool.isEmpty()) {
            disabled = true;
        }
    }

    /**
     * Register a new custom mob
     * @param mob Mob to register
     */
    @Override
    public boolean register(CustomMob mob) {
        if (!CUSTOM_TYPE_PATTERN.matcher(mob.getCustomType()).matches() || mobs.containsKey(mob.getCustomType())) {
            return false;
        }
        mobs.put(mob.getCustomType(), mob);
        // Configure first: the weight is read from the config
        if (mob instanceof Configurable configurable)
            config.register(configurable);
        mobsPool.add(mob, mob.getWeight());
        if (mob instanceof Listener listener)
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        if (mob instanceof Tickable tickable)
            dynamics.register(tickable);
        if (mob instanceof CustomMob.Ticking ticking)
            tickingEntities.put(ticking, new HashMap<>());
        return true;
    }

    @Override
    public void tick() {
        for (Map.Entry<CustomMob.Ticking, Map<UUID, LivingEntity>> entry : tickingEntities.entrySet()) {
            CustomMob.Ticking mob = entry.getKey();
            Iterator<Map.Entry<UUID, LivingEntity>> it = entry.getValue().entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, LivingEntity> tracked = it.next();
                LivingEntity entity = tracked.getValue();
                if (!entity.isValid()) {
                    // Unloaded or dead; a mob that changed worlds is the same UUID in another entity object
                    if (entity.isDead() || !(Bukkit.getEntity(tracked.getKey()) instanceof LivingEntity moved)
                            || !moved.isValid()) {
                        it.remove();
                        continue;
                    }
                    tracked.setValue(entity = moved);
                }
                mob.tick(entity);
            }
        }
    }

    @Override
    public TickLevel getTickLevel() {
        return TickLevel.ENTITY;
    }

    public CustomMob getMob(String type) {
        return mobs.get(type);
    }

    /**
     * Try to summon a new mob
     * @param type Type of mob to summon
     * @param loc Where to summon
     * @return Is summoning was successful
     */
    public LivingEntity spawn(String type, Location loc) {
        CustomMob mob = mobs.get(type);
        if (mob == null) return null;
        return spawn(mob, loc);
    }

    /**
     * Try to summon a new mob
     * @param mob Type of mob to summon
     * @param loc Where to summon
     * @return Is summoning was successful
     */
    public LivingEntity spawn(CustomMob mob, Location loc) {
        LivingEntity entity = mob.spawn(loc);
        // A mob may refuse the spawn after the fact (Ore Mole without its model removes itself)
        if (entity == null || entity.isDead()) return null;
        TagHelper.setTag(entity, mob.getCustomType());
        if (metadata) entity.setMetadata("DangerousCaves", MARKER);
        if (mob instanceof CustomMob.Ticking ticking) {
            handleTicking(entity, ticking);
        }
        entity.setRemoveWhenFarAway(true);
        return entity;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            // The scoreboard tag is a plain set lookup, cheaper than a PDC read on every loaded entity
            if (!(entity instanceof LivingEntity livingEntity)
                    || !entity.getScoreboardTags().contains(TagHelper.SCOREBOARD_TAG)) continue;
            String type = TagHelper.getTag(livingEntity);
            if (type != null) {
                if (metadata) entity.setMetadata("DangerousCaves", MARKER);
                CustomMob mob = mobs.get(type);
                if (mob == null) continue;
                if (mob instanceof CustomMob.Ticking ticking) {
                    handleTicking(livingEntity, ticking);
                }
                mob.onLoad(livingEntity);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityAttack(EntityDamageByEntityEvent event) {
        // Only DC mobs have listeners for PlayerAttackedEvent; skip PvP and vanilla mobs
        if (event.getEntityType() == EntityType.PLAYER && event.getDamager() instanceof LivingEntity
                && event.getDamager().getScoreboardTags().contains(TagHelper.SCOREBOARD_TAG)) {
            PlayerAttackedEvent pEvent = new PlayerAttackedEvent((Player) event.getEntity(), (LivingEntity) event.getDamager(), event.getDamage());
            Bukkit.getPluginManager().callEvent(pEvent);
            event.setDamage(pEvent.getDamage());
            event.setCancelled(pEvent.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!blockRename) return;
        Material item = event.getHand() == EquipmentSlot.HAND ?
                event.getPlayer().getInventory().getItemInMainHand().getType() :
                event.getPlayer().getInventory().getItemInOffHand().getType();
        if (item == Material.NAME_TAG && event.getRightClicked() instanceof LivingEntity living
            && TagHelper.isTagged(living)) {
            event.setCancelled(true);
        }
    }

    private boolean onSpawn(EntityType type, Location loc, CreatureSpawnEvent.SpawnReason reason) {
        if (disabled || reason != CreatureSpawnEvent.SpawnReason.NATURAL ||
                !replaceTypes.contains(type) ||
                loc.getBlockY() > yMax ||
                loc.getBlockY() < yMin ||
                !worlds.contains(loc.getWorld().getName()) ||
                !Rng.chance(chance) ||
                !lightCheck.test(loc) ||
                !Locations.isCave(loc))
            return false;
        // Re-roll when the picked mob rejects the location, otherwise mobs with a
        // restrictive canSpawn() (i.e. Smoke Demon) waste the whole spawn attempt
        for (int i = 0; i < spawnTries; i++) {
            CustomMob mob = mobsPool.next();
            if (mob.canSpawn(loc)) {
                // Cancel the vanilla spawn only when the custom one really happened
                return spawn(mob, loc) != null;
            }
        }
        return false;
    }

    public boolean checkWorld(String world) {
        return worlds.contains(world);
    }

    @Override
    public String getConfigPath() {
        return "mobs";
    }

    /**
     * Handle a new ticking mob
     * @param entity Entity to handle
     * @param mob Related custom mob
     */
    public void handleTicking(LivingEntity entity, CustomMob.Ticking mob) {
        tickingEntities.get(mob).put(entity.getUniqueId(), entity);
    }

    /**
     * Let mobs clean their transient state before the worlds are saved
     */
    public void disable() {
        for (CustomMob mob : mobs.values()) {
            try {
                mob.onDisable();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Failed to disable mob " + mob.getCustomType() + ": " + e);
            }
        }
    }

    public Plugin getPlugin() {
        return plugin;
    }

    public Set<String> getMobs() {
        return mobs.keySet();
    }
}
