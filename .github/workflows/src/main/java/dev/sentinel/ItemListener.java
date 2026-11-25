package dev.sentinel;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;

/** Item-use checks: fastbow and fasteat. Also records when item use began (used by noslow). */
public final class ItemListener implements Listener {
    private final Sentinel plugin;
    private final ViolationManager vm;

    public ItemListener(Sentinel plugin) {
        this.plugin = plugin;
        this.vm = plugin.violations();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseStart(PlayerInteractEvent e) {
        Action a = e.getAction();
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && e.getItem() != null) {
            plugin.data(e.getPlayer()).lastUseStart = System.currentTimeMillis();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getBow() == null || e.getBow().getType() != Material.BOW) return;
        PlayerData d = plugin.data(p);
        if (!plugin.enabled("fastbow") || !plugin.shouldCheck(p, d)) return;

        long elapsed = System.currentTimeMillis() - d.lastUseStart;
        double ticks = (elapsed + 100.0) / 50.0; // allow ~2 ticks of slack
        double t = ticks / 20.0;
        double expected = Math.min(1.0, (t * t + t * 2.0) / 3.0);
        if (e.getForce() > expected + plugin.cfg("fastbow", "tolerance", 0.2)) {
            if (vm.flag(p, "fastbow", "force=" + String.format("%.2f", e.getForce())
                    + " after " + elapsed + "ms")) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (!plugin.enabled("fasteat") || !plugin.shouldCheck(p, d)) return;

        long elapsed = System.currentTimeMillis() - d.lastUseStart;
        long min = (long) plugin.cfg("fasteat", "min-ms", 700);
        if (elapsed >= 0 && elapsed < min) {
            double buf = d.addBuf("fasteat", 1.0);
            if (buf >= plugin.cfg("fasteat", "buffer", 2.0)) {
                if (vm.flag(p, "fasteat", "consumed after " + elapsed + "ms")) e.setCancelled(true);
                d.resetBuf("fasteat");
            }
        } else {
            d.decayBuf("fasteat", 0.5);
        }
    }
}
