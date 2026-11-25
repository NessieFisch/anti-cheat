package dev.sentinel;

import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Movement checks: speed, fly (gravity prediction), jump, step, nofall (ground spoof), jesus, phase,
 * timer, sprint/omnisprint, noslow, sneak speed, elytra, velocity (anti-knockback), badpackets.
 */
public final class MovementListener implements Listener {
    private final Sentinel plugin;
    private final ViolationManager vm;

    public MovementListener(Sentinel plugin) {
        this.plugin = plugin;
        this.vm = plugin.violations();
    }

    private static double wrap(double a) {
        a %= 360.0;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    private static String f(double v) {
        return String.format("%.3f", v);
    }

    /** Start tracking knockback so we can later verify the player actually moved. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        Vector v = e.getVelocity();
        double h = Math.hypot(v.getX(), v.getZ());
        if (h >= 0.3) {
            d.kbDir = new Vector(v.getX(), 0, v.getZ()).normalize();
            d.kbExpected = h;
            d.kbDisp = 0;
            d.kbTicks = 5;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        long now = System.currentTimeMillis();
        long dt = now - d.lastMoveMs;
        d.lastMoveMs = now;

        if (!plugin.shouldCheck(p, d) || plugin.lowTps()) {
            d.hasLast = false;
            d.timerBalance = 0;
            d.kbTicks = 0;
            return;
        }

        boolean moved = e.hasChangedPosition();
        boolean rollback = false;

        d.yawDelta = Math.abs(wrap(to.getYaw() - from.getYaw()));
        d.pitchDelta = Math.abs(to.getPitch() - from.getPitch());
        d.rotMs = now;

        if (plugin.enabled("badpackets") && Math.abs(to.getPitch()) > 90.5f) {
            rollback |= vm.flag(p, "badpackets", "pitch=" + f(to.getPitch()));
        }

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.getAllowFlight() || p.isFlying()
                || p.isInsideVehicle() || p.isSleeping() || p.isDead()) {
            d.hasLast = false;
            d.airTicks = 0;
            d.timerBalance = 0;
            d.kbTicks = 0;
            return;
        }

        boolean disrupted = now - d.lastDisrupt < 1500;

        // ---------------- Timer (packet rate) ----------------
        if (plugin.enabled("timer")) {
            if (disrupted || !d.hasLast) {
                d.timerBalance = 0;
            } else {
                double credit = plugin.cfg("timer", "max-credit-ms", 1000);
                d.timerBalance = Math.max(-credit, d.timerBalance + 50.0 - dt);
                if (d.timerBalance > plugin.cfg("timer", "max-ahead-ms", 200)) {
                    rollback |= vm.flag(p, "timer", "ahead=" + (int) d.timerBalance + "ms");
                    d.timerBalance = 0;
                }
            }
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);

        World w = p.getWorld();
        BoundingBox bbFrom = p.getBoundingBox();
        BoundingBox bbTo = bbFrom.clone().shift(dx, dy, dz);

        boolean groundTo = Phys.groundBelow(w, bbTo, 0.1);
        boolean groundFrom = d.lastGround;
        int scan = Phys.scan(w, bbTo);
        boolean liquid = (scan & Phys.LIQUID) != 0;
        boolean specialBlock = (scan & Phys.SPECIAL) != 0;
        if (scan != 0) d.lastSpecial = now;
        boolean recentSpecial = now - d.lastSpecial < 2000;

        boolean ice = Phys.onIce(w, bbTo);
        if (ice) d.lastIce = now;
        boolean recentIce = now - d.lastIce < 1500;

        boolean glide = p.isGliding();
        if (glide) d.lastGlide = now;
        boolean recentGlide = now - d.lastGlide < 2000;
        if (p.isRiptiding()) d.lastRiptide = now;
        boolean recentRiptide = now - d.lastRiptide < 2500;

        boolean consecutive = d.hasLast && dt <= 110;
        boolean calm = !disrupted && !recentGlide && !recentRiptide
                && !p.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE);

        d.airTicks = groundTo ? 0 : d.airTicks + 1;
        double base = Phys.attr(p, Attribute.MOVEMENT_SPEED, 0.1);

        PotionEffect jb = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
        int jbLevel = jb == null ? 0 : jb.getAmplifier() + 1;

        // ---------------- Knockback follow-up (anti-KB / velocity) ----------------
        if (d.kbTicks > 0 && d.kbDir != null) {
            d.kbDisp += dx * d.kbDir.getX() + dz * d.kbDir.getZ();
            d.kbTicks--;
            if (d.kbTicks == 0 && plugin.enabled("velocity") && scan == 0 && !p.isInsideVehicle()
                    && d.kbDisp < d.kbExpected * plugin.cfg("velocity", "min-ratio", 0.5)) {
                Location l = p.getLocation();
                RayTraceResult low = w.rayTraceBlocks(l.clone().add(0, 0.2, 0), d.kbDir, 1.6, FluidCollisionMode.NEVER, true);
                RayTraceResult high = w.rayTraceBlocks(l.clone().add(0, 1.0, 0), d.kbDir, 1.6, FluidCollisionMode.NEVER, true);
                if (low == null && high == null) {
                    double buf = d.addBuf("velocity", 1.0);
                    if (buf >= plugin.cfg("velocity", "buffer", 2.0)) {
                        vm.flag(p, "velocity", "moved=" + f(d.kbDisp) + " expected=" + f(d.kbExpected));
                        d.resetBuf("velocity");
                    }
                }
            } else if (d.kbTicks == 0) {
                d.decayBuf("velocity", 0.5);
            }
        }

        // ---------------- Speed ----------------
        if (plugin.enabled("speed") && moved && consecutive && calm) {
            double limit = base * 2.158;
            limit += (groundTo && groundFrom && d.airTicks == 0) ? 0.04 : plugin.cfg("speed", "air-bonus", 0.32);
            if (recentIce) limit *= 1.9;
            limit *= plugin.cfg("speed", "tolerance", 1.08);
            if (h > limit) {
                double buf = d.addBuf("speed", 1.0 + (h / limit - 1.0) * 4.0);
                if (buf >= plugin.cfg("speed", "buffer", 4.0)) {
                    rollback |= vm.flag(p, "speed", "h=" + f(h) + " max=" + f(limit));
                    d.resetBuf("speed");
                }
            } else {
                d.decayBuf("speed", 0.35);
            }
        }

        // ---------------- Fly (gravity prediction) ----------------
        if (plugin.enabled("fly") && consecutive && calm && scan == 0 && !recentSpecial && !groundTo
                && d.airTicks >= 3 && !p.isClimbing()
                && !p.hasPotionEffect(PotionEffectType.LEVITATION)
                && !Phys.headBlocked(w, bbTo)) {
            double g = Phys.attr(p, Attribute.GRAVITY, 0.08);
            double predicted = (d.lastDy - g) * 0.98;
            double diff = dy - predicted;
            if (diff > plugin.cfg("fly", "max-diff", 0.05) && !Phys.nearGroundEntity(p)) {
                double buf = d.addBuf("fly", 1.0);
                if (buf >= plugin.cfg("fly", "buffer", 4.0)) {
                    rollback |= vm.flag(p, "fly", "dy=" + f(dy) + " expected=" + f(predicted));
                    d.resetBuf("fly");
                }
            } else {
                d.decayBuf("fly", 0.5);
            }
        }

        // ---------------- Jump height ----------------
        if (plugin.enabled("jump") && consecutive && calm && !recentSpecial && groundFrom && !groundTo && dy > 0) {
            double max = Phys.attr(p, Attribute.JUMP_STRENGTH, 0.42) + 0.1 * jbLevel
                    + plugin.cfg("jump", "tolerance", 0.16);
            if (dy > max) {
                rollback |= vm.flag(p, "jump", "dy=" + f(dy) + " max=" + f(max));
            }
        }

        // ---------------- Step ----------------
        if (plugin.enabled("step") && moved && consecutive && calm && !recentSpecial && groundFrom && groundTo
                && dy > Phys.attr(p, Attribute.STEP_HEIGHT, 0.6) + 0.1) {
            rollback |= vm.flag(p, "step", "dy=" + f(dy));
        }

        // ---------------- NoFall / ground spoof ----------------
        if (plugin.enabled("nofall") && consecutive && calm && scan == 0 && !recentSpecial
                && p.isOnGround() && !groundTo && !groundFrom && d.airTicks >= 2 && !Phys.nearGroundEntity(p)) {
            double buf = d.addBuf("nofall", 1.0);
            if (buf >= plugin.cfg("nofall", "buffer", 3.0)) {
                rollback |= vm.flag(p, "nofall", "claims ground while airborne");
                d.resetBuf("nofall");
            }
        } else {
            d.decayBuf("nofall", 0.25);
        }

        // ---------------- Jesus (experimental) ----------------
        if (plugin.enabled("jesus") && moved && consecutive && calm && liquid && !specialBlock && !groundTo
                && !p.isSwimming() && !p.isClimbing() && h > 0.12 && Math.abs(dy) < 0.002
                && !Phys.nearGroundEntity(p)) {
            double buf = d.addBuf("jesus", 1.0);
            if (buf >= plugin.cfg("jesus", "buffer", 14.0)) {
                rollback |= vm.flag(p, "jesus", "walking on liquid");
                d.resetBuf("jesus");
            }
        } else {
            d.resetBuf("jesus");
        }

        // ---------------- Phase / noclip ----------------
        if (plugin.enabled("phase") && moved && h > 0.05 && calm && !p.isSleeping()) {
            BoundingBox innerTo = bbTo.clone().expand(-0.11);
            if (Phys.collides(w, innerTo) && !Phys.collides(w, bbFrom.clone().expand(-0.11))) {
                double buf = d.addBuf("phase", 1.0);
                if (buf >= plugin.cfg("phase", "buffer", 3.0)) {
                    rollback |= vm.flag(p, "phase", "entered solid block");
                    d.resetBuf("phase");
                }
            } else {
                d.decayBuf("phase", 0.5);
            }
        }

        // ---------------- Sprint / omnisprint ----------------
        if (plugin.enabled("sprint") && moved && calm) {
            if (p.isSprinting() && p.getFoodLevel() <= 6) {
                double buf = d.addBuf("sprint-food", 1.0);
                if (buf >= 5) {
                    rollback |= vm.flag(p, "sprint", "sprinting with food=" + p.getFoodLevel());
                    d.resetBuf("sprint-food");
                }
            } else {
                d.decayBuf("sprint-food", 1.0);
            }
            if (p.isSprinting() && groundTo && groundFrom && h > 0.22 && !recentIce) {
                double yawRad = Math.toRadians(to.getYaw());
                double dot = (dx * -Math.sin(yawRad) + dz * Math.cos(yawRad)) / h;
                if (dot < -0.1736) { // moving > 100 degrees away from where they look
                    double buf = d.addBuf("sprint-omni", 1.0);
                    if (buf >= 10) {
                        rollback |= vm.flag(p, "sprint", "omni-directional sprint");
                        d.resetBuf("sprint-omni");
                    }
                } else {
                    d.decayBuf("sprint-omni", 1.0);
                }
            }
        }

        // ---------------- NoSlow (item use) + sneak speed ----------------
        if (plugin.enabled("noslow") && moved && consecutive && calm && groundTo && groundFrom && !recentIce) {
            boolean using = p.isHandRaised() && now - d.lastUseStart > 500;
            boolean flagged = false;
            String detail = "";
            if (using) {
                double walk = p.isSprinting() ? base / 1.3 : base;
                double limit = walk * 2.158 * 0.5 + 0.02;
                if (p.isSprinting()) {
                    flagged = true;
                    detail = "sprinting while using item";
                } else if (h > limit) {
                    flagged = true;
                    detail = "h=" + f(h) + " max=" + f(limit) + " while using item";
                }
            } else if (p.isSneaking() && now - d.lastSneakToggle > 400 && !p.isHandRaised()) {
                ItemStack legs = p.getInventory().getLeggings();
                int swift = legs == null ? 0 : legs.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
                double factor = Math.min(1.0, 0.3 + 0.15 * swift);
                double limit = base * 2.158 * factor * 1.25 + 0.03;
                if (h > limit) {
                    flagged = true;
                    detail = "h=" + f(h) + " max=" + f(limit) + " while sneaking";
                }
            }
            if (flagged) {
                double buf = d.addBuf("noslow", 1.0);
                if (buf >= plugin.cfg("noslow", "buffer", 6.0)) {
                    rollback |= vm.flag(p, "noslow", detail);
                    d.resetBuf("noslow");
                }
            } else {
                d.decayBuf("noslow", 0.5);
            }
        }

        // ---------------- Elytra ----------------
        if (plugin.enabled("elytra") && glide && consecutive && !disrupted) {
            double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (speed > plugin.cfg("elytra", "max-speed", 4.0)) {
                rollback |= vm.flag(p, "elytra", "speed=" + f(speed));
            }
            if (!groundTo && d.airTicks > 5 && h < 0.02 && Math.abs(dy) < 0.02) {
                double buf = d.addBuf("elytra-hover", 1.0);
                if (buf >= 6) {
                    rollback |= vm.flag(p, "elytra", "hovering while gliding");
                    d.resetBuf("elytra-hover");
                }
            } else {
                d.decayBuf("elytra-hover", 1.0);
            }
        }

        if (rollback) {
            e.setCancelled(true);
            d.lastSetback = now;
            d.hasLast = false;
            d.timerBalance = 0;
            return;
        }

        // ---------------- state for next tick ----------------
        d.lastDx = dx;
        d.lastDy = dy;
        d.lastDz = dz;
        d.lastH = h;
        d.lastGround = groundTo;
        d.hasLast = true;
        d.history.addLast(new PlayerData.Pos(now, to.getX(), to.getY(), to.getZ()));
        while (d.history.size() > 60) d.history.removeFirst();
    }
}
