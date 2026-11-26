package dev.sentinel;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;

/** Inventory checks: moving while an inventory is open, and impossibly fast clicking (chest stealers). */
public final class InventoryListener implements Listener {
    private final Sentinel plugin;
    private final ViolationManager vm;

    public InventoryListener(Sentinel plugin) {
        this.plugin = plugin;
        this.vm = plugin.violations();
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) plugin.data(p).lastInvOpen = System.currentTimeMillis();
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        PlayerData d = plugin.data(p);
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (!plugin.shouldCheck(p, d)) return;
        long now = System.currentTimeMillis();

        if (plugin.enabled("fastclick")) {
            d.invClicks.addLast(now);
            while (!d.invClicks.isEmpty() && now - d.invClicks.peekFirst() > 500) d.invClicks.removeFirst();
            if (d.invClicks.size() > plugin.cfg("fastclick", "max-per-500ms", 14)) {
                vm.flag(p, "fastclick", d.invClicks.size() + " clicks in 500ms");
                e.setCancelled(true);
                return;
            }
        }

        if (plugin.enabled("invmove")) {
            boolean moving = p.isSprinting() && d.lastH > 0.15 && now - d.lastMoveMs < 150
                    && now - d.lastInvOpen > 1000 && now - d.lastDisrupt > 1500;
            if (moving) {
                double buf = d.addBuf("invmove", 1.0);
                if (buf >= plugin.cfg("invmove", "buffer", 3.0)) {
                    vm.flag(p, "invmove", "sprinting while clicking in inventory");
                    d.resetBuf("invmove");
                    e.setCancelled(true);
                }
            } else {
                d.decayBuf("invmove", 0.5);
            }
        }
    }
}
