package dev.sentinel;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Block checks: fastbreak, nuker, block reach, ghosthand, fastplace, scaffold, xray statistics. */
public final class BlockListener implements Listener {
    private final Sentinel plugin;
    private final ViolationManager vm;

    public BlockListener(Sentinel plugin) {
        this.plugin = plugin;
        this.vm = plugin.violations();
    }

    private static boolean survival(Player p) {
        GameMode gm = p.getGameMode();
        return gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(BlockDamageEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        long now = System.currentTimeMillis();
        d.digging = true;
        d.diggingSince = now;
        d.breakStart = now;
        d.breakKey = Phys.key(e.getBlock());
        d.breakInsta = e.getInstaBreak();
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent e) {
        plugin.data(e.getPlayer()).digging = false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        Block b = e.getBlock();
        long now = System.currentTimeMillis();

        if (!survival(p) || !plugin.shouldCheck(p, d)) {
            d.digging = false;
            return;
        }
        boolean cancel = false;

        // ---- FastBreak ----
        if (plugin.enabled("fastbreak") && !d.breakInsta && d.breakKey == Phys.key(b)) {
            float speed = b.getBreakSpeed(p);
            if (speed > 0f && speed < 1f) {
                long expected = (long) (Math.ceil(1.0 / speed) * 50.0);
                long elapsed = now - d.breakStart;
                if (elapsed < expected * plugin.cfg("fastbreak", "ratio", 0.65)) {
                    double buf = d.addBuf("fastbreak", 1.0);
                    if (buf >= plugin.cfg("fastbreak", "buffer", 2.0)) {
                        cancel |= vm.flag(p, "fastbreak", "took " + elapsed + "ms, expected " + expected + "ms");
                        d.resetBuf("fastbreak");
                    }
                } else {
                    d.decayBuf("fastbreak", 0.5);
                }
            }
        }
        d.digging = false;

        // ---- Nuker (many blocks per tick / per second) ----
        if (plugin.enabled("nuker")) {
            int tick = Bukkit.getCurrentTick();
            if (d.breakTick == tick) d.breaksThisTick++;
            else {
                d.breakTick = tick;
                d.breaksThisTick = 1;
            }
            d.breaks.addLast(now);
            while (!d.breaks.isEmpty() && now - d.breaks.peekFirst() > 1000) d.breaks.removeFirst();
            if (d.breaksThisTick > plugin.cfg("nuker", "max-per-tick", 2)
                    || d.breaks.size() > plugin.cfg("nuker", "max-per-second", 30)) {
                cancel |= vm.flag(p, "nuker", d.breaksThisTick + " blocks this tick, " + d.breaks.size() + "/s");
            }
        }

        World w = p.getWorld();
        Location eyeLoc = p.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        Vector look = eyeLoc.getDirection();
        BoundingBox blockBox = BoundingBox.of(b);

        // ---- Block reach ----
        if (plugin.enabled("blockreach")) {
            double range = Phys.attr(p, Attribute.BLOCK_INTERACTION_RANGE, 4.5);
            double dist = Phys.distToBox(eye, blockBox);
            double max = range + plugin.cfg("blockreach", "tolerance", 0.8);
            if (dist > max) {
                cancel |= vm.flag(p, "blockreach", "dist=" + String.format("%.2f", dist) + " max=" + String.format("%.2f", max));
            }
        }

        // ---- GhostHand / break through walls or without looking ----
        if (plugin.enabled("ghosthand")) {
            boolean bad = blockBox.clone().expand(0.3).rayTrace(eye, look, 8.0) == null
                    || Phys.blockedBetween(w, eye, blockBox.getCenter(), b);
            if (bad) {
                double buf = d.addBuf("ghost-break", 1.0);
                if (buf >= plugin.cfg("ghosthand", "buffer", 3.0)) {
                    cancel |= vm.flag(p, "ghosthand", "broke block not under crosshair / through wall");
                    d.resetBuf("ghost-break");
                }
            } else {
                d.decayBuf("ghost-break", 0.5);
            }
        }

        // ---- X-ray statistics (alert only, never cancels) ----
        if (plugin.enabled("xray")) {
            Material m = b.getType();
            boolean rare = Tag.DIAMOND_ORES.isTagged(m) || m == Material.ANCIENT_DEBRIS;
            boolean base = rare || Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m);
            if (base) {
                d.xrayTotal++;
                if (rare) d.xrayRare++;
                int window = (int) plugin.cfg("xray", "window-blocks", 150);
                if (d.xrayTotal >= window) {
                    double ratio = (double) d.xrayRare / d.xrayTotal;
                    if (d.xrayRare >= plugin.cfg("xray", "min-rare", 5) && ratio > plugin.cfg("xray", "max-ratio", 0.04)) {
                        vm.flag(p, "xray", d.xrayRare + " rare ores in " + d.xrayTotal + " blocks");
                    }
                    d.xrayTotal = 0;
                    d.xrayRare = 0;
                }
            }
        }

        if (cancel) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (!survival(p) || !plugin.shouldCheck(p, d)) return;
        long now = System.currentTimeMillis();
        boolean cancel = false;

        // ---- FastPlace ----
        if (plugin.enabled("fastplace")) {
            d.places.addLast(now);
            while (!d.places.isEmpty() && now - d.places.peekFirst() > 1000) d.places.removeFirst();
            if (d.places.size() > plugin.cfg("fastplace", "max-per-second", 16)) {
                cancel |= vm.flag(p, "fastplace", d.places.size() + " blocks/s");
            }
        }

        // ---- Scaffold ----
        if (plugin.enabled("scaffold")) {
            Location eyeLoc = p.getEyeLocation();
            Vector eye = eyeLoc.toVector();
            Vector look = eyeLoc.getDirection();
            Block against = e.getBlockAgainst();
            Block placed = e.getBlock();
            double range = Phys.attr(p, Attribute.BLOCK_INTERACTION_RANGE, 4.5);

            boolean notLooking = BoundingBox.of(against).expand(0.3).rayTrace(eye, look, range + 2.0) == null;
            boolean underFeetForward = p.isSprinting() && eyeLoc.getPitch() < 30f
                    && placed.getY() == p.getLocation().getBlockY() - 1
                    && Math.abs(placed.getX() + 0.5 - p.getX()) < 1.0
                    && Math.abs(placed.getZ() + 0.5 - p.getZ()) < 1.0;
            if (notLooking || underFeetForward) {
                double buf = d.addBuf("scaffold", 1.0);
                if (buf >= plugin.cfg("scaffold", "buffer", 3.0)) {
                    cancel |= vm.flag(p, "scaffold", notLooking ? "placed against block not under crosshair" : "sprint-bridging while looking ahead");
                    d.resetBuf("scaffold");
                }
            } else {
                d.decayBuf("scaffold", 0.5);
            }
        }

        if (cancel) e.setCancelled(true);
    }

    /** Right-click through walls. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        if (!plugin.enabled("ghosthand") || !survival(p) || !plugin.shouldCheck(p, d)) return;

        Vector eye = p.getEyeLocation().toVector();
        Vector center = BoundingBox.of(clicked).getCenter();
        if (Phys.blockedBetween(p.getWorld(), eye, center, clicked)) {
            double buf = d.addBuf("ghost-use", 1.0);
            if (buf >= plugin.cfg("ghosthand", "buffer", 3.0)) {
                vm.flag(p, "ghosthand", "interacted through a block");
                d.resetBuf("ghost-use");
                e.setCancelled(true);
            }
        } else {
            d.decayBuf("ghost-use", 0.5);
        }
    }
}
