package dev.sentinel;

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

/**
 * Movement checks.
 *
 * IMPORTANT design notes (these are what keep false positives down):
 *  - Paper only fires PlayerMoveEvent after ~1/16 block of movement, so slow movement (sneaking, eating,
 *    slow falling, apex of a jump) produces events several ticks apart. Every distance is therefore
 *    normalised per tick, and tick-exact physics (fly/jump/step) only runs on two back-to-back
 *    single-tick events.
 *  - Lag spikes pause checks for a second.
 *  - Teleports, damage, explosions, vehicles, gamemode changes etc. pause checks (general.disrupt-ms).
 *  - Every check must trip several times (buffer) before it flags.
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

    /** Start tracking vertical knockback (a player hit on the ground must get launched upward). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        PlayerData d = plugin.data(e.getPlayer());
        double vy = e.getVelocity().getY();
        if (vy >= 0.25) {
            d.kbVy = vy;
            d.kbStart = System.currentTimeMillis();
            d.kbMaxDy = -9;
            d.kbGround = d.lastGround;
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
        long prevDt = d.lastDt;
        d.lastDt = dt;

        if (!plugin.shouldCheck(p, d) || plugin.lowTps()) {
            d.hasLast = false;
            d.timerBalance = 0;
            d.kbVy = 0;
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
            d.kbVy = 0;
            return;
        }

        // ---------------- lag / timing state ----------------
        // While moving, events arrive every tick. A long gap right after movement means lag, not cheating.
        if (d.hasLast && dt > 300 && d.lastH > 0.05) d.lagUntil = now + 1000;
        boolean lagging = now < d.lagUntil;
        int ticks = d.hasLast ? (int) Math.max(1, Math.min(10, Math.round(dt / 50.0))) : 1;
        // true only when this event AND the previous one were each exactly one tick after their predecessor
        boolean single = d.hasLast && dt <= 75 && prevDt <= 75;
        boolean disrupted = now - d.lastDisrupt < plugin.disruptMs();

        // ---------------- Timer (packet rate) ----------------
        if (plugin.enabled("timer")) {
            if (disrupted || !d.hasLast || lagging) {
                d.timerBalance = 0;
            } else {
                double credit = plugin.cfg("timer", "max-credit-ms", 1000);
                d.timerBalance = Math.max(-credit, d.timerBalance + 50.0 - dt);
                if (d.timerBalance > plugin.cfg("timer", "max-ahead-ms", 250)) {
                    rollback |= vm.flag(p, "timer", "ahead=" + (int) d.timerBalance + "ms");
                    d.timerBalance = 0;
                }
            }
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);
        double hTick = h / ticks;

        World w = p.getWorld();
        BoundingBox bbFrom = p.getBoundingBox();
        BoundingBox bbTo = bbFrom.clone().shift(dx, dy, dz);

        boolean groundTo = Phys.groundBelow(w, bbTo, 0.1);
        boolean groundFrom = d.lastGround;
        int scan = Phys.scan(w, bbTo);
        boolean liquid = (scan & Phys.LIQUID) != 0;
        boolean specialBlock = (scan & Phys.SPECIAL) != 0;
        // effects that change gravity count as "special movement" for a while
        if (scan != 0 || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || p.hasPotionEffect(PotionEffectType.LEVITATION)) d.lastSpecial = now;
        boolean recentSpecial = now - d.lastSpecial < 2500;

        boolean ice = Phys.onIce(w, bbTo);
        if (ice) d.lastIce = now;
        boolean recentIce = now - d.lastIce < 1500;

        boolean glide = p.isGliding();
        if (glide) d.lastGlide = now;
        boolean recentGlide = now - d.lastGlide < 2500;
        if (p.isRiptiding()) d.lastRiptide = now;
        boolean recentRiptide = now - d.lastRiptide < 3000;

        boolean calm = !disrupted && !lagging && !recentGlide && !recentRiptide
                && !p.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE);

        d.airTicks = groundTo ? 0 : d.airTicks + 1;
        double base = Phys.attr(p, Attribute.MOVEMENT_SPEED, 0.1);

        PotionEffect jb = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
        int jbLevel = jb == null ? 0 : jb.getAmplifier() + 1;

        // ---------------- Vertical knockback follow-up ----------------
        if (d.kbVy > 0) {
            long wait = p.getPing() + 60L;
            long since = now - d.kbStart;
            if (since > 5000 || lagging) {
                d.kbVy = 0;
            } else if (since >= wait && since <= wait + 400) {
                d.kbMaxDy = Math.max(d.kbMaxDy, dy);
            } else if (since > wait + 400) {
                if (plugin.enabled("velocity") && d.kbGround && d.hasLast && scan == 0
                        && !Phys.headBlocked(w, bbFrom) && d.kbMaxDy < d.kbVy * 0.3) {
                    double buf = d.addBuf("velocity", 1.0);
                    if (buf >= plugin.cfg("velocity", "buffer", 3.0)) {
                        vm.flag(p, "velocity", "launched " + f(Math.max(d.kbMaxDy, 0)) + " of " + f(d.kbVy));
                        d.resetBuf("velocity");
                    }
                } else {
                    d.decayBuf("velocity", 0.5);
                }
                d.kbVy = 0;
            }
        }

        // ---------------- Speed (per-tick, sprint always allowed) ----------------
        if (plugin.enabled("speed") && moved && d.hasLast && calm) {
            double walk = p.isSprinting() ? base / 1.3 : base;
            double limit = walk * 1.3 * 2.158;
            limit += (groundTo && groundFrom && d.airTicks == 0) ? 0.05 : plugin.cfg("speed", "air-bonus", 0.35);
            if (liquid) limit += 0.12;
            if (recentIce) limit *= 1.9;
            limit *= plugin.cfg("speed", "tolerance", 1.12);
            if (hTick > limit) {
                double buf = d.addBuf("speed", 1.0 + (hTick / limit - 1.0) * 4.0);
                if (buf >= plugin.cfg("speed", "buffer", 6.0)) {
                    rollback |= vm.flag(p, "speed", "h/tick=" + f(hTick) + " max=" + f(limit));
                    d.resetBuf("speed");
                }
            } else {
                d.decayBuf("speed", 0.35);
            }
        }

        // ---------------- Fly (gravity prediction, single-tick events only) ----------------
        if (plugin.enabled("fly") && single && calm && scan == 0 && !recentSpecial && !groundTo
                && d.airTicks >= 4 && !p.isClimbing()
                && !Phys.headBlocked(w, bbTo)) {
            double g = Phys.attr(p, Attribute.GRAVITY, 0.08);
            double predicted = (d.lastDy - g) * 0.98;
            double diff = dy - predicted;
            if (diff > plugin.cfg("fly", "max-diff", 0.08) && !Phys.nearGroundEntity(p)) {
                double buf = d.addBuf("fly", 1.0);
                if (buf >= plugin.cfg("fly", "buffer", 6.0)) {
                    rollback |= vm.flag(p, "fly", "dy=" + f(dy) + " expected=" + f(predicted));
                    d.resetBuf("fly");
                }
            } else {
                d.decayBuf("fly", 0.5);
            }
        }

        // ---------------- Jump height ----------------
        if (plugin.enabled("jump") && single && calm && !recentSpecial && groundFrom && !groundTo && dy > 0) {
            double max = Phys.attr(p, Attribute.JUMP_STRENGTH, 0.42) + 0.1 * jbLevel
                    + plugin.cfg("jump", "tolerance", 0.3);
            if (dy > max) {
                rollback |= vm.flag(p, "jump", "dy=" + f(dy) + " max=" + f(max));
            }
        }

        // ---------------- Step ----------------
        if (plugin.enabled("step") && single && calm && !recentSpecial && groundFrom && groundTo
                && dy > Phys.attr(p, Attribute.STEP_HEIGHT, 0.6) + 0.15) {
            rollback |= vm.flag(p, "step", "dy=" + f(dy));
        }

        // ---------------- NoFall / ground spoof (strict) ----------------
        if (plugin.enabled("nofall") && single && calm && scan == 0 && !recentSpecial
                && p.isOnGround() && !groundTo && !groundFrom && d.airTicks >= 4
                && !Phys.groundBelow(w, bbTo, 0.35) && !Phys.groundBelow(w, bbFrom, 0.35)
                && !Phys.nearGroundEntity(p)) {
            double buf = d.addBuf("nofall", 1.0);
            if (buf >= plugin.cfg("nofall", "buffer", 5.0)) {
                rollback |= vm.flag(p, "nofall", "claims ground while airborne");
                d.resetBuf("nofall");
            }
        } else {
            d.decayBuf("nofall", 0.25);
        }

        // ---------------- Jesus (experimental, off by default) ----------------
        if (plugin.enabled("jesus") && moved && single && calm && liquid && !specialBlock && !groundTo
                && !p.isSwimming() && !p.isClimbing() && hTick > 0.12 && Math.abs(dy) < 0.002
                && !Phys.nearGroundEntity(p)) {
            double buf = d.addBuf("jesus", 1.0);
            if (buf >= plugin.cfg("jesus", "buffer", 14.0)) {
                rollback |= vm.flag(p, "jesus", "walking on liquid");
                d.resetBuf("jesus");
            }
        } else {
            d.resetBuf("jesus");
        }

        // ---------------- Phase (off by default: vanilla already blocks moving into blocks) ----------------
        if (plugin.enabled("phase") && moved && single && h > 0.05 && calm && !p.isSleeping()) {
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
                if (buf >= plugin.cfg("sprint", "food-buffer", 15.0)) {
                    rollback |= vm.flag(p, "sprint", "sprinting with food=" + p.getFoodLevel());
                    d.resetBuf("sprint-food");
                }
            } else {
                d.decayBuf("sprint-food", 1.0);
            }
            if (single && p.isSprinting() && groundTo && groundFrom && h > 0.22 && !recentIce) {
                double yawRad = Math.toRadians(to.getYaw());
                double dot = (dx * -Math.sin(yawRad) + dz * Math.cos(yawRad)) / h;
                if (dot < -0.1736) { // moving > 100 degrees away from where they look
                    double buf = d.addBuf("sprint-omni", 1.0);
                    if (buf >= plugin.cfg("sprint", "omni-buffer", 12.0)) {
                        rollback |= vm.flag(p, "sprint", "omni-directional sprint");
                        d.resetBuf("sprint-omni");
                    }
                } else {
                    d.decayBuf("sprint-omni", 1.0);
                }
            }
        }

        // ---------------- NoSlow (item use) + sneak speed ----------------
        if (plugin.enabled("noslow") && moved && d.hasLast && calm && groundTo && groundFrom && !recentIce) {
            boolean using = p.isHandRaised() && now - d.lastUseStart > 800;
            boolean flagged = false;
            String detail = "";
            if (using) {
                double walk = p.isSprinting() ? base / 1.3 : base;
                double limit = walk * 2.158 * 0.5 + 0.03;
                if (p.isSprinting()) {
                    flagged = true;
                    detail = "sprinting while using item";
                } else if (hTick > limit) {
                    flagged = true;
                    detail = "h/tick=" + f(hTick) + " max=" + f(limit) + " while using item";
                }
            } else if (p.isSneaking() && now - d.lastSneakToggle > 600 && !p.isHandRaised()) {
                ItemStack legs = p.getInventory().getLeggings();
                int swift = legs == null ? 0 : legs.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
                double factor = Math.min(1.0, 0.3 + 0.15 * swift);
                double limit = base * 2.158 * factor * 1.4 + 0.04;
                if (hTick > limit) {
                    flagged = true;
                    detail = "h/tick=" + f(hTick) + " max=" + f(limit) + " while sneaking";
                }
            }
            if (flagged) {
                double buf = d.addBuf("noslow", 1.0);
                if (buf >= plugin.cfg("noslow", "buffer", 8.0)) {
                    rollback |= vm.flag(p, "noslow", detail);
                    d.resetBuf("noslow");
                }
            } else {
                d.decayBuf("noslow", 0.5);
            }
        }

        // ---------------- Elytra ----------------
        if (plugin.enabled("elytra") && glide && single && !disrupted && !lagging) {
            double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (speed > plugin.cfg("elytra", "max-speed", 4.0)) {
                rollback |= vm.flag(p, "elytra", "speed=" + f(speed));
            }
            if (scan == 0 && !groundTo && d.airTicks > 5 && h < 0.02 && Math.abs(dy) < 0.02) {
                double buf = d.addBuf("elytra-hover", 1.0);
                if (buf >= 8) {
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

        // ---------------- state for next event ----------------
        d.lastDx = dx;
        d.lastDy = dy;
        d.lastDz = dz;
        d.lastH = hTick;
        d.lastGround = groundTo;
        d.hasLast = true;
        d.history.addLast(new PlayerData.Pos(now, to.getX(), to.getY(), to.getZ()));
        while (d.history.size() > 60) d.history.removeFirst();
    }
}
