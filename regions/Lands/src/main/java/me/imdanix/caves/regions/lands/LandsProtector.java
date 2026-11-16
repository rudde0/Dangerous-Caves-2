package me.imdanix.caves.regions.lands;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.imdanix.caves.regions.ActionType;
import me.imdanix.caves.regions.RegionProtector;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * Lands 7.x integration.
 * <p>
 * A claimed area is never touched: no cave-ins, no caves aging, no entity griefing, whatever the
 * land's flags say. Lands' own natural flags are deliberately not consulted - {@code BLOCK_SPREADING}
 * is enabled by default there (it's the flag that lets grass and vines grow), so honouring it would
 * leave claims open to aging until every single owner turned it off.
 * <p>
 * Lands has no flag for player effects (ambient sounds, hypoxia), so those are controlled by the
 * chosen mode: {@code lands} keeps them inside claims, {@code lands-effectless} blocks them there.
 */
public class LandsProtector implements RegionProtector {
    private static LandsIntegration landsIntegration;

    private final boolean effect;

    public LandsProtector(boolean effect) {
        this.effect = effect;
    }

    @Override
    public void onEnable() {
        if (landsIntegration != null) return;
        landsIntegration = LandsIntegration.of(Bukkit.getPluginManager().getPlugin("DangerousCaves"));
    }

    @Override
    public String getName() {
        return effect ? "lands" : "lands-effectless";
    }

    @Override
    public boolean test(ActionType type, Location location) {
        if (landsIntegration == null) return true;

        Area area = landsIntegration.getArea(location);
        // Wilderness - nothing to protect
        if (area == null) return true;

        return type == ActionType.EFFECT && effect;
    }
}
