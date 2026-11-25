package dev.sentinel;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.Location;

import java.util.Locale;

/** Tracks join/quit and "disruptions" (anything that legitimately moves a player) so checks can back off. */
public final class StateListener implements Listener {
    private final Sentinel plugin;

    public StateListener(Sentinel plugin) {
        this.plugin = plugin;
    }

    private void disrupt(Player p) {
        plugin.data(p).lastDisrupt = System.currentTimeMillis();
    }

    private void disruptNear(Location center, double radius) {
        if (center.getWorld() == null) return;
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= radius * radius) disrupt(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        disrupt(p);
        if (plugin.enabled("clientbrand")) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> checkBrand(p), 60L);
        }
    }

    private void checkBrand(Player p) {
        if (!p.isOnline()) return;
        String brand = p.getClientBrandName();
        if (brand == null) return;
        String lower = brand.toLowerCase(Locale.ROOT);
        for (String blocked : plugin.getConfig().getStringList("checks.clientbrand.blocked")) {
            if (!blocked.isBlank() && lower.contains(blocked.toLowerCase(Locale.ROOT))) {
                plugin.violations().flag(p, "clientbrand", 10.0, "brand=" + brand);
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.removeData(e.getPlayer().getUniqueId());
        plugin.violations().clear(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        d.hasLast = false;
        d.timerBalance = 0;
        d.airTicks = 0;
        d.kbTicks = 0;
        d.history.clear();
        // our own setback teleports must not grant a free exemption window
        if (now - d.lastSetback > 300) d.lastDisrupt = now;
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        disrupt(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        disrupt(e.getPlayer());
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        disrupt(e.getPlayer());
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p) disrupt(p);
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) disrupt(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) disrupt(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        disrupt(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) plugin.data(p).lastGlide = System.currentTimeMillis();
    }

    @EventHandler
    public void onRiptide(PlayerRiptideEvent e) {
        plugin.data(e.getPlayer()).lastRiptide = System.currentTimeMillis();
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        plugin.data(e.getPlayer()).lastSneakToggle = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        disruptNear(e.getLocation(), 14);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        disruptNear(e.getBlock().getLocation(), 14);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        Entity en = e.getEntity();
        if (en instanceof WindCharge) disruptNear(en.getLocation(), 8);
    }
}
