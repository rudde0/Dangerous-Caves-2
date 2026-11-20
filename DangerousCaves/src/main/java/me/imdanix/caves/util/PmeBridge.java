package me.imdanix.caves.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflection bridge to ProMobEngine (packet-based 3D models worn by vanilla mobs), without a compile dependency.
 * <p>
 * The engine's public methods on its plugin object are looked up once, when the engine enables (or is already
 * enabled when DangerousCaves starts), and dropped when it disables. Every call is a no-op returning
 * false when the engine is absent. All calls must be made on the main thread, as the engine requires.
 * <p>
 * Viewers on Bedrock or clients below 1.20.2 get the base entity's packets untouched, so a modelled mob
 * should still look acceptable as its vanilla base.
 */
public final class PmeBridge implements Listener {
    private static final String NAME = "ProMobEngine";

    private static Logger logger;
    private static Object engine;
    private static Method attach;
    private static Method play;
    private static Method idle;
    private static Method stop;
    private static Method has;
    private static Method sound;
    private static Method soundAt;
    private static Method height;
    private static boolean failureLogged;

    private PmeBridge() {}

    /**
     * Looks the engine up if it is enabled already, and follows it enabling and disabling later
     * @param owner DangerousCaves
     */
    public static void init(Plugin owner) {
        logger = owner.getLogger();
        Bukkit.getPluginManager().registerEvents(new PmeBridge(), owner);
        Plugin plugin = Bukkit.getPluginManager().getPlugin(NAME);
        if (plugin != null && plugin.isEnabled()) hook(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (NAME.equals(event.getPlugin().getName())) hook(event.getPlugin());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (NAME.equals(event.getPlugin().getName()) && engine != null) {
            unhook();
            logger.info("ProMobEngine disabled: modelled mobs fall back to their vanilla look.");
        }
    }

    private static void hook(Plugin plugin) {
        Class<?> type = plugin.getClass();
        try {
            attach = type.getMethod("attach", LivingEntity.class, String.class);
            play = type.getMethod("play", Entity.class, String.class);
            idle = type.getMethod("idle", Entity.class, String.class);
            stop = type.getMethod("stop", Entity.class);
            has = type.getMethod("has", Entity.class);
            sound = type.getMethod("sound", Entity.class, String.class);
            soundAt = type.getMethod("sound", Entity.class, String.class, Location.class);
            height = type.getMethod("height", String.class);
            engine = plugin;
            failureLogged = false;
            logger.info("ProMobEngine found: modelled mobs enabled.");
        } catch (NoSuchMethodException | SecurityException e) {
            unhook();
            logger.warning("ProMobEngine is installed but its API does not match (" + e.getMessage()
                    + "); modelled mobs use their fallback.");
        }
    }

    private static void unhook() {
        engine = null;
        attach = play = idle = stop = has = sound = soundAt = height = null;
    }

    /**
     * @return Is the engine enabled and its API resolved
     */
    public static boolean isAvailable() {
        return engine != null;
    }

    /**
     * @param modelId Model id
     * @return Is the engine available and does it know this model
     */
    public static boolean hasModel(String modelId) {
        Object result = call(height, modelId);
        return result instanceof Number number && number.doubleValue() >= 0;
    }

    /**
     * Puts a model on a mob (scale and equipment should be set before)
     * @return False when the engine is absent, the model unknown or the engine's model limit reached
     */
    public static boolean attach(LivingEntity entity, String modelId) {
        return Boolean.TRUE.equals(call(attach, entity, modelId));
    }

    /**
     * @return Does the entity wear a model right now
     */
    public static boolean has(Entity entity) {
        return Boolean.TRUE.equals(call(has, entity));
    }

    /**
     * Starts an action clip
     */
    public static boolean play(Entity entity, String clip) {
        return Boolean.TRUE.equals(call(play, entity, clip));
    }

    /**
     * Replaces the idle clip; an empty name restores the model's own
     */
    public static boolean idle(Entity entity, String clip) {
        return Boolean.TRUE.equals(call(idle, entity, clip == null ? "" : clip));
    }

    /**
     * Ends the running action clip, the model blends back to idle/walk
     */
    public static boolean stop(Entity entity) {
        return Boolean.TRUE.equals(call(stop, entity));
    }

    /**
     * Plays one of the model's sounds at the mob
     */
    public static boolean sound(Entity entity, String name) {
        return Boolean.TRUE.equals(call(sound, entity, name));
    }

    /**
     * Plays one of the model's sounds at a place in the mob's world
     */
    public static boolean sound(Entity entity, String name, Location at) {
        return Boolean.TRUE.equals(call(soundAt, entity, name, at));
    }

    private static Object call(Method method, Object... args) {
        Object target = engine;
        if (target == null || method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            logFailure(method, e.getCause());
        } catch (IllegalAccessException | IllegalArgumentException e) {
            logFailure(method, e);
        }
        return null;
    }

    private static void logFailure(Method method, Throwable cause) {
        // Once per enable: a broken call must not turn every spawn into a stack trace
        if (failureLogged) return;
        failureLogged = true;
        logger.log(Level.WARNING, "ProMobEngine." + method.getName() + " failed (further failures are not logged)", cause);
    }
}
