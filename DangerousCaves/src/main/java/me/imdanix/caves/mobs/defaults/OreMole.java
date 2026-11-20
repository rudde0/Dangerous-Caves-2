package me.imdanix.caves.mobs.defaults;

import me.imdanix.caves.mobs.CustomMob;
import me.imdanix.caves.mobs.MobBase;
import me.imdanix.caves.mobs.MobsManager;
import me.imdanix.caves.util.Materials;
import me.imdanix.caves.util.PmeBridge;
import me.imdanix.caves.util.random.Rng;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cevher Köstebeği (config key {@code ore-mole}): a small husk (scale attribute 0.6) wearing the ProMobEngine
 * model {@code ore_mole}. It is only chosen while the engine has that model and removes itself when the model
 * cannot be put on, so Java players never meet a bare husk (Bedrock and old clients see the small husk).
 * Pickaxes hurt it more; it drops ores instead of the husk's loot.
 * <p>
 * Skill "Toprak Altı Pususu" (burrow ambush). The ticks are a contract with the model's clips:
 * <pre>
 *  0  target 6-16 blocks away (horizontal), the mole on the ground, cooldown over:
 *     AI off, invulnerable, play "burrow" (16 ticks, fully under the floor at its end)
 * 0   idle "buried" is set with the dive, so the clip blends into it when it ends
 * 16  teleport to the burst point (the target's position moved 1 block toward the mole, solid ground with
 *     2 free blocks above; none: cancel; the engine reads a jump over 2 blocks as a teleport, not a walk),
 *     sound "rumble"
 * 16-35 tell: block-crack particles of the floor every 4 ticks at the burst point
 * 36  idle "" and play "emerge" (24 ticks, its hit at its tick 4)
 * 40  survival/adventure players (not NPCs) within 1.6 blocks horizontally and 2 vertically take
 *     burrow-damage without a source entity and a 0.55 upward knock; AI on, vulnerable
 * </pre>
 * The sequence is driven by the ENTITY tick (every 4 ticks; 16, 36 and 40 are multiples of it), each phase
 * by the server tick elapsed since the start, so an extra manual tick cannot skip or repeat one. If the
 * mole dies or unloads, or the target leaves, the mole gets its AI and vulnerability back. Both are saved
 * with the entity, so they are also healed when a mole loads and when the plugin disables.
 * <p>
 * Known clip interaction: AI returns at tick 40 while "emerge" runs to tick 60, so a melee swing in that
 * window starts the ATTACK clip over the end of "emerge".
 */
public class OreMole extends MobBase implements CustomMob.Ticking, Listener {
    private static final double SCALE = 0.6;

    private static final int T_UNDER = 16;
    private static final int T_EMERGE = 36;
    private static final int T_HIT = 40;
    private static final int TELL_PERIOD = 4;

    private static final double MIN_RANGE_SQUARED = 6 * 6;
    private static final double MAX_RANGE_SQUARED = 16 * 16;
    // Farther than this from the mole, the target has left
    private static final double LEASH_SQUARED = 32 * 32;
    private static final double HIT_RADIUS = 1.6;
    private static final double HIT_HEIGHT = 2;
    private static final double KNOCK_Y = 0.55;
    // The burst point search: the ray may start up to 3 blocks above the target's feet and reach 4 below
    private static final int RAY_UP = 3;
    private static final int RAY_DOWN = 4;
    // When no burst point exists right now, look again after this many ticks instead of every ENTITY tick
    private static final int RETRY_TICKS = 40;
    private static final int PRUNE_PERIOD = 600;

    private static final BlockData[] ORES = {
            Material.COAL_ORE.createBlockData(), Material.COPPER_ORE.createBlockData(),
            Material.IRON_ORE.createBlockData(), Material.GOLD_ORE.createBlockData(),
            Material.REDSTONE_ORE.createBlockData(), Material.LAPIS_ORE.createBlockData(),
            Material.EMERALD_ORE.createBlockData(), Material.DIAMOND_ORE.createBlockData()
    };
    private static final List<String> DEFAULT_DROPS = List.of(
            "COAL 2-6 60", "RAW_COPPER 2-5 50", "RAW_IRON 1-3 45", "RAW_GOLD 1-2 25",
            "LAPIS_LAZULI 2-5 30", "REDSTONE 2-5 30", "EMERALD 1 5", "DIAMOND 1 4"
    );

    private final Plugin plugin;
    private final Map<UUID, Burrow> burrows = new HashMap<>();
    private final Map<UUID, Integer> nextBurrow = new HashMap<>();
    private int lastPrune;

    private String model;
    private double attackDamage;
    private double pickaxeModifier;
    private double burrowDamage;
    private int cooldownMin;
    private int cooldownMax;
    private int exp;
    private List<Drop> drops = List.of();

    public OreMole(MobsManager mobs) {
        super(EntityType.HUSK, "ore-mole", 3, 30d, "&9Cevher Köstebeği");
        this.plugin = mobs.getPlugin();
    }

    @Override
    protected void configure(ConfigurationSection cfg) {
        model = cfg.getString("model", "ore_mole");
        attackDamage = Math.max(0, cfg.getDouble("attack-damage", 5));
        pickaxeModifier = Math.max(0, cfg.getDouble("pickaxe-modifier", 1.5));
        burrowDamage = Math.max(0, cfg.getDouble("burrow-damage", 6));
        exp = Math.max(0, cfg.getInt("exp", 10));
        double[] cooldown = parseRange(cfg.getString("burrow-cooldown", "12-16"), 12, 16);
        cooldownMin = (int) Math.round(cooldown[0] * 20);
        cooldownMax = (int) Math.round(cooldown[1] * 20);
        drops = parseDrops(cfg.isList("drops") ? cfg.getStringList("drops") : DEFAULT_DROPS);
    }

    @Override
    protected boolean randomizeData() {
        // No baby husks, chicken jockeys or random gear under the model
        return false;
    }

    @Override
    public boolean canSpawn(Location loc) {
        // Never a bare husk: only while the engine knows the model
        return PmeBridge.hasModel(model);
    }

    @Override
    public void prepare(LivingEntity entity) {
        // Scale before the model: the engine fits the hitbox to the model from the current size
        setBaseAttribute(entity, Attribute.SCALE, SCALE);
        setBaseAttribute(entity, Attribute.ATTACK_DAMAGE, attackDamage);
        if (entity instanceof Ageable ageable) ageable.setAdult();
        entity.setCanPickupItems(false);
        EntityEquipment equipment = entity.getEquipment();
        if (equipment != null) equipment.clear();
        // The engine's model limit can refuse it; MobsManager then lets the vanilla spawn stand
        if (!PmeBridge.attach(entity, model)) entity.remove();
    }

    @Override
    public void onLoad(LivingEntity entity) {
        // Saved mid-burrow (a crash, an unload): AI off and invulnerability came back with the chunk
        burrows.remove(entity.getUniqueId());
        restore(entity);
        // The engine puts the model back from its own data a tick after the load; still none a tick later
        // means the engine or the model is gone, and the mole would be a bare husk
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (entity.isValid() && !PmeBridge.has(entity) && !PmeBridge.attach(entity, model)) entity.remove();
        }, 2L);
    }

    @Override
    public void onDisable() {
        // Before the worlds are saved, so no mole is stored without AI
        for (Burrow burrow : burrows.values()) {
            if (burrow.mole.isValid()) restore(burrow.mole);
        }
        burrows.clear();
    }

    @Override
    public void tick(LivingEntity entity) {
        int now = Bukkit.getCurrentTick();
        if (now - lastPrune >= PRUNE_PERIOD) prune(now);

        UUID id = entity.getUniqueId();
        Burrow burrow = burrows.get(id);
        if (burrow != null) {
            advance(entity, burrow, now);
            return;
        }

        Integer next = nextBurrow.get(id);
        if (next != null && now < next) return;
        if (!(entity instanceof Mob mob) || !(mob.getTarget() instanceof Player target)
                || !isVictim(target) || target.getWorld() != entity.getWorld() || !entity.isOnGround()) return;
        Location at = entity.getLocation();
        Location targetLoc = target.getLocation();
        double dx = targetLoc.getX() - at.getX();
        double dz = targetLoc.getZ() - at.getZ();
        double distanceSquared = dx * dx + dz * dz;
        if (distanceSquared < MIN_RANGE_SQUARED || distanceSquared > MAX_RANGE_SQUARED) return;
        // The clip is the tell: without the model there is no skill
        if (!PmeBridge.has(entity)) return;
        if (findBurstPoint(targetLoc, at) == null) {
            nextBurrow.put(id, now + RETRY_TICKS);
            return;
        }

        mob.setAware(false);
        entity.setInvulnerable(true);
        if (!PmeBridge.play(entity, "burrow")) {
            // The model has no such clip
            restore(entity);
            nextBurrow.put(id, now + cooldown());
            return;
        }
        // Set with the dive, not after it: the clip blends straight into the buried pose when it ends at 16,
        // whatever tick of the 4-tick loop notices that (setting it later showed the whole mole for a few ticks)
        PmeBridge.idle(entity, "buried");
        burrows.put(id, new Burrow(entity, target, now));
    }

    private void advance(LivingEntity entity, Burrow burrow, int now) {
        Player target = burrow.target;
        if (!isVictim(target) || target.getWorld() != entity.getWorld()
                || target.getLocation().distanceSquared(entity.getLocation()) > LEASH_SQUARED) {
            finish(entity, burrow, now, true);
            return;
        }
        int elapsed = now - burrow.start;
        switch (burrow.phase) {
            case DIVING -> {
                if (elapsed < T_UNDER) return;
                Burst burst = findBurstPoint(target.getLocation(), entity.getLocation());
                if (burst == null) {
                    finish(entity, burrow, now, true);
                    return;
                }
                if (!entity.teleport(burst.at)) {
                    // Another plugin refused it: the tell and the hit would happen where the mole is not
                    finish(entity, burrow, now, true);
                    return;
                }
                burrow.burst = burst;
                burrow.phase = Phase.TELL;
                PmeBridge.sound(entity, "rumble");
                crack(burst);
                burrow.lastTell = now;
            }
            case TELL -> {
                if (elapsed < T_EMERGE) {
                    if (now - burrow.lastTell >= TELL_PERIOD) {
                        crack(burrow.burst);
                        burrow.lastTell = now;
                    }
                    return;
                }
                PmeBridge.idle(entity, "");
                PmeBridge.play(entity, "emerge");
                burrow.phase = Phase.EMERGING;
            }
            case EMERGING -> {
                if (elapsed < T_HIT) return;
                hit(burrow.burst.at);
                finish(entity, burrow, now, false);
            }
        }
    }

    private void finish(LivingEntity entity, Burrow burrow, int now, boolean cancelled) {
        burrows.remove(entity.getUniqueId());
        restore(entity);
        if (cancelled) {
            // A wind-up whose target is gone ends at once; "buried" goes back to the model's own idle
            if (burrow.phase == Phase.DIVING) PmeBridge.stop(entity);
            if (burrow.phase == Phase.DIVING || burrow.phase == Phase.TELL) PmeBridge.idle(entity, "");
        }
        nextBurrow.put(entity.getUniqueId(), now + cooldown());
    }

    private void hit(Location center) {
        for (Player player : center.getNearbyPlayers(HIT_RADIUS, HIT_HEIGHT)) {
            if (!isVictim(player)) continue;
            Location loc = player.getLocation();
            double dx = loc.getX() - center.getX();
            double dz = loc.getZ() - center.getZ();
            if (dx * dx + dz * dz > HIT_RADIUS * HIT_RADIUS || Math.abs(loc.getY() - center.getY()) > HIT_HEIGHT) continue;
            player.damage(burrowDamage);
            Vector velocity = player.getVelocity();
            velocity.setY(KNOCK_Y);
            player.setVelocity(velocity);
        }
    }

    private static void crack(Burst burst) {
        Location at = burst.at;
        at.getWorld().spawnParticle(Particle.BLOCK, at.getX(), at.getY() + 0.1, at.getZ(),
                10, 0.35, 0.05, 0.35, 0.1, burst.floor);
    }

    /**
     * One downward ray: it starts at the first free block from the burst column's feet height up (at most
     * 3 above) and must meet solid ground within 4 blocks below the feet, with 2 free blocks above the ground
     * @param target Where the target stands
     * @param mole Where the mole is
     * @return Burst point facing the target, or null
     */
    private static Burst findBurstPoint(Location target, Location mole) {
        double dx = mole.getX() - target.getX();
        double dz = mole.getZ() - target.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3) return null;
        double x = target.getX() + dx / length;
        double z = target.getZ() + dz / length;
        World world = target.getWorld();
        int bx = Location.locToBlock(x);
        int bz = Location.locToBlock(z);
        int feet = target.getBlockY();

        int start = feet;
        while (!isFree(world.getBlockAt(bx, start, bz))) {
            if (++start > feet + RAY_UP) return null;
        }
        for (int y = start - 1; y >= feet - RAY_DOWN - 1; y--) {
            Block ground = world.getBlockAt(bx, y, bz);
            if (isFree(ground)) continue;
            if (ground.isLiquid() || !ground.getType().isSolid()) return null;
            // The block above is free (the ray came through it); the head needs the next one too
            if (!isFree(world.getBlockAt(bx, y + 2, bz))) return null;
            BoundingBox box = ground.getBoundingBox();
            double top = box.getHeight() > 0 ? box.getMaxY() : y + 1;
            // Fences and walls: no standing on them
            if (top > y + 1) return null;
            Location at = new Location(world, x, top, z);
            at.setDirection(new Vector(target.getX() - x, 0, target.getZ() - z));
            return new Burst(at, ground.getBlockData());
        }
        return null;
    }

    private static boolean isFree(Block block) {
        return block.isPassable() && !block.isLiquid();
    }

    private static boolean isVictim(Player player) {
        if (!player.isValid() || player.isDead() || player.hasMetadata("NPC")) return false;
        GameMode mode = player.getGameMode();
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    private static void restore(LivingEntity entity) {
        if (entity instanceof Mob mob) mob.setAware(true);
        entity.setInvulnerable(false);
    }

    private int cooldown() {
        return cooldownMax > cooldownMin ? cooldownMin + Rng.nextInt(cooldownMax - cooldownMin + 1) : cooldownMin;
    }

    private void prune(int now) {
        lastPrune = now;
        nextBurrow.values().removeIf(next -> next <= now);
        // A burrow the ENTITY tick stopped advancing: the mole died or unloaded
        Iterator<Burrow> iterator = burrows.values().iterator();
        while (iterator.hasNext()) {
            Burrow burrow = iterator.next();
            if (now - burrow.start <= T_HIT + 100) continue;
            if (burrow.mole.isValid()) restore(burrow.mole);
            iterator.remove();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !isThis(event.getEntity())
                || !Materials.isPickaxe(player.getInventory().getItemInMainHand().getType())) return;
        event.setDamage(event.getDamage() * pickaxeModifier);
        // Ore chips off its back
        LivingEntity mole = (LivingEntity) event.getEntity();
        Location back = mole.getLocation();
        Vector facing = back.getDirection().setY(0);
        back.add(-facing.getX() * 0.25, mole.getHeight() * 0.8, -facing.getZ() * 0.25);
        mole.getWorld().spawnParticle(Particle.BLOCK, back, 6, 0.15, 0.1, 0.15, 0.1, ORES[Rng.nextInt(ORES.length)]);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isThis(entity)) return;
        burrows.remove(entity.getUniqueId());
        nextBurrow.remove(entity.getUniqueId());
        List<ItemStack> items = event.getDrops();
        items.clear();
        // Against mob farms: ores only for a player kill, exp only where vanilla drops it (a player hit it recently)
        if (entity.getKiller() != null) {
            for (Drop drop : drops) {
                if (Rng.chance(drop.chance)) {
                    items.add(new ItemStack(drop.type, drop.min >= drop.max ? drop.min : Rng.nextInt(drop.min, drop.max + 1)));
                }
            }
        }
        if (event.getDroppedExp() > 0) event.setDroppedExp(exp);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        // A husk under water turns into a zombie; the mole never finishes that conversion
        if (!isThis(event.getEntity())) return;
        event.setCancelled(true);
        if (event.getEntity() instanceof Zombie zombie) zombie.setConversionTime(-1);
    }

    /**
     * "MATERIAL MIN-MAX CHANCE", e.g. "RAW_IRON 1-3 45"; amount and chance (percent) are optional
     */
    private List<Drop> parseDrops(List<String> lines) {
        List<Drop> parsed = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            Material type = parts[0].isEmpty() ? null : Material.matchMaterial(parts[0]);
            double[] amount = parts.length > 1 ? parseRange(parts[1], -1, -1) : new double[]{1, 1};
            double chance = parts.length > 2 ? parseDouble(parts[2], -1) : 100;
            if (type == null || !type.isItem() || amount[0] < 1 || chance < 0) {
                plugin.getLogger().warning("mobs.ore-mole.drops: cannot read '" + line + "' (expected e.g. \"RAW_IRON 1-3 45\")");
                continue;
            }
            parsed.add(new Drop(type, (int) amount[0], (int) Math.max(amount[0], amount[1]), chance / 100));
        }
        return parsed;
    }

    /**
     * "12-16" or "14"
     */
    private static double[] parseRange(String str, double defMin, double defMax) {
        if (str == null) return new double[]{defMin, defMax};
        String[] parts = str.trim().toLowerCase(Locale.ROOT).split("-", 2);
        double min = parseDouble(parts[0], -1);
        double max = parts.length > 1 ? parseDouble(parts[1], -1) : min;
        if (min < 0 || max < min) return new double[]{defMin, defMax};
        return new double[]{min, max};
    }

    private static double parseDouble(String str, double def) {
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private enum Phase {
        DIVING, TELL, EMERGING
    }

    private record Drop(Material type, int min, int max, double chance) {}

    private record Burst(Location at, BlockData floor) {}

    private static final class Burrow {
        private final LivingEntity mole;
        private final Player target;
        private final int start;
        private Phase phase = Phase.DIVING;
        private Burst burst;
        private int lastTell;

        private Burrow(LivingEntity mole, Player target, int start) {
            this.mole = mole;
            this.target = target;
            this.start = start;
        }
    }
}
