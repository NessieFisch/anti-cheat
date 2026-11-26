package dev.sentinel;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Combat checks: reach (lag-compensated with position history), hitbox/killaura, wall hits,
 * multi-aura, aim lock, autoclicker, noswing, criticals, attack-while-using-item.
 */
public final class CombatListener implements Listener {
    private final Sentinel plugin;
    private final ViolationManager vm;

    public CombatListener(Sentinel plugin) {
        this.plugin = plugin;
        this.vm = plugin.violations();
    }

    private static String f(double v) {
        return String.format("%.2f", v);
    }

    /** Candidate hitboxes for the victim: current box plus where it was ~ping ms ago. */
    private List<BoundingBox> boxes(Entity victim, long now, int ping) {
        List<BoundingBox> list = new ArrayList<>(8);
        BoundingBox cur = victim.getBoundingBox();
        list.add(cur);
        if (victim instanceof Player vp) {
            PlayerData vd = plugin.data(vp);
            double hw = (cur.getMaxX() - cur.getMinX()) / 2.0;
            double h = cur.getHeight();
            long lo = Math.max(0, ping + 50 - 200);
            long hi = ping + 50 + 200;
            for (PlayerData.Pos pos : vd.history) {
                long age = now - pos.ms();
                if (age >= lo && age <= hi) {
                    list.add(new BoundingBox(pos.x() - hw, pos.y(), pos.z() - hw, pos.x() + hw, pos.y() + h, pos.z() + hw));
                }
            }
        }
        return list;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player a)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        PlayerData d = plugin.data(a);
        if (!plugin.shouldCheck(a, d)) return;
        GameMode gm = a.getGameMode();
        if (gm == GameMode.SPECTATOR) return;

        Entity victim = e.getEntity();
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        boolean cancel = false;
        // lag spikes bunch packets together and wreck position/timing based checks
        boolean lagging = now < d.lagUntil;
        boolean creative = gm == GameMode.CREATIVE;

        World w = a.getWorld();
        Location eyeLoc = a.getEyeLocation();
        Vector eye = eyeLoc.toVector();
        Vector look = eyeLoc.getDirection();
        double range = Phys.attr(a, Attribute.ENTITY_INTERACTION_RANGE, 3.0);
        int ping = Math.min(a.getPing(), 400);
        List<BoundingBox> boxes = boxes(victim, now, ping);
        BoundingBox cur = boxes.get(0);

        // ---------- Reach ----------
        double minDist = Double.MAX_VALUE;
        for (BoundingBox b : boxes) minDist = Math.min(minDist, Phys.distToBox(eye, b));
        if (plugin.enabled("reach") && !a.isInsideVehicle() && !lagging && !creative) {
            double allowed = range + plugin.cfg("reach", "tolerance", 0.4);
            if (minDist > allowed) {
                double buf = d.addBuf("reach", 1.0);
                if (buf >= plugin.cfg("reach", "buffer", 2.0)) {
                    cancel |= vm.flag(a, "reach", "dist=" + f(minDist) + " max=" + f(allowed));
                    d.resetBuf("reach");
                }
            } else {
                d.decayBuf("reach", 0.5);
            }
        }

        // ---------- Hitbox / killaura (is the target actually under the crosshair?) ----------
        if (plugin.enabled("hitbox") && !lagging && !creative) {
            double rotTol = 0;
            if (now - d.rotMs < 150) {
                rotTol = Math.min(1.0, Math.toRadians(d.yawDelta + d.pitchDelta) * Math.max(minDist, 1.0));
            }
            double expand = plugin.cfg("hitbox", "expand", 0.1) + rotTol;
            boolean hit = false;
            for (BoundingBox b : boxes) {
                if (b.clone().expand(expand).rayTrace(eye, look, range + 3.0) != null) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                double buf = d.addBuf("hitbox", 1.0);
                if (buf >= plugin.cfg("hitbox", "buffer", 2.0)) {
                    cancel |= vm.flag(a, "hitbox", "target not under crosshair");
                    d.resetBuf("hitbox");
                }
            } else {
                d.decayBuf("hitbox", 0.5);
            }
        }

        // ---------- Hits through walls ----------
        if (plugin.enabled("wallhit") && !lagging) {
            if (Phys.blockedBetween(w, eye, cur.getCenter(), null)) {
                double buf = d.addBuf("wallhit", 1.0);
                if (buf >= plugin.cfg("wallhit", "buffer", 3.0)) {
                    cancel |= vm.flag(a, "wallhit", "block between attacker and target");
                    d.resetBuf("wallhit");
                }
            } else {
                d.decayBuf("wallhit", 0.5);
            }
        }

        // ---------- Multi-aura (two different targets in the same tick) ----------
        if (plugin.enabled("killaura") && !lagging) {
            if (d.lastTarget != null && !d.lastTarget.equals(victim.getUniqueId()) && d.lastAttackTick == tick) {
                double buf = d.addBuf("multi", 1.0);
                if (buf >= plugin.cfg("killaura", "buffer", 3.0)) {
                    cancel |= vm.flag(a, "killaura", "multiple targets in one tick");
                    d.resetBuf("multi");
                }
            } else {
                d.decayBuf("multi", 0.2);
            }
        }

        // ---------- Aim lock (inhumanly perfect aim at target centre) ----------
        if (plugin.enabled("aim") && minDist > 1.5) {
            Vector toCenter = cur.getCenter().subtract(eye).normalize();
            double dot = Math.max(-1.0, Math.min(1.0, look.dot(toCenter)));
            double err = Math.toDegrees(Math.acos(dot));
            d.aim[d.aimIdx] = err;
            d.aimIdx = (d.aimIdx + 1) % d.aim.length;
            if (d.aimCount < d.aim.length) d.aimCount++;
            if (d.aimCount >= d.aim.length) {
                double sum = 0;
                for (double v : d.aim) sum += v;
                double avg = sum / d.aim.length;
                if (avg < plugin.cfg("aim", "min-avg-angle", 0.4)) {
                    vm.flag(a, "aim", "avg error " + f(avg) + " deg over " + d.aim.length + " hits");
                    d.aimCount = 0;
                }
            }
        }

        // ---------- NoSwing (attacked twice with no arm swing in between) ----------
        if (plugin.enabled("noswing") && !lagging) {
            if (d.hadAttack && !d.swingSinceAttack && now - d.lastAttackMs < 1500) {
                double buf = d.addBuf("noswing", 1.0);
                if (buf >= plugin.cfg("noswing", "buffer", 2.0)) {
                    vm.flag(a, "noswing", "attack without swing animation");
                    d.resetBuf("noswing");
                }
            } else {
                d.decayBuf("noswing", 0.5);
            }
        }

        // ---------- Attack while using an item (vanilla client cannot do this) ----------
        if (plugin.enabled("attackwhileusing")) {
            if (a.isHandRaised()) {
                double buf = d.addBuf("awu", 1.0);
                if (buf >= plugin.cfg("attackwhileusing", "buffer", 2.0)) {
                    cancel |= vm.flag(a, "attackwhileusing", "attacked while using item/blocking");
                    d.resetBuf("awu");
                }
            } else {
                d.decayBuf("awu", 0.5);
            }
        }

        // ---------- Criticals (fake micro-jump) ----------
        if (plugin.enabled("criticals")) {
            float fd = a.getFallDistance();
            if (fd > 0.0f && fd < 0.2f && !a.isOnGround()
                    && Phys.groundBelow(w, a.getBoundingBox(), 0.2)
                    && Phys.scan(w, a.getBoundingBox()) == 0 && !a.isClimbing()) {
                double buf = d.addBuf("crit", 1.0);
                if (buf >= plugin.cfg("criticals", "buffer", 3.0)) {
                    vm.flag(a, "criticals", "crit while standing on ground, fall=" + f(fd));
                    d.resetBuf("crit");
                }
            } else {
                d.decayBuf("crit", 0.25);
            }
        }

        d.hadAttack = true;
        d.swingSinceAttack = false;
        d.lastAttackMs = now;
        d.lastAttackTick = tick;
        d.lastTarget = victim.getUniqueId();

        if (cancel && plugin.getConfig().getBoolean("combat.cancel-flagged-hits", true)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        PlayerData d = plugin.data(p);
        d.swingSinceAttack = true;
        if (!plugin.shouldCheck(p, d)) return;

        long now = System.currentTimeMillis();
        // continuous swinging while mining is normal; don't count it
        if (d.digging && now - d.diggingSince < 8000) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        d.clicks.addLast(now);
        while (!d.clicks.isEmpty() && now - d.clicks.peekFirst() > 1000) d.clicks.removeFirst();
        if (!plugin.enabled("autoclicker")) return;

        int cps = d.clicks.size();
        if (now < d.lagUntil) return; // packet bursts after lag look like fast clicking
        if (cps > plugin.cfg("autoclicker", "max-cps", 24)) {
            double buf = d.addBuf("cps", 1.0);
            if (buf >= plugin.cfg("autoclicker", "buffer", 2.0)) {
                vm.flag(p, "autoclicker", "cps=" + cps);
                d.resetBuf("cps");
            }
            return;
        } else {
            d.decayBuf("cps", 0.1);
        }
        if (cps >= 10) {
            Long[] t = d.clicks.toArray(new Long[0]);
            double[] iv = new double[t.length - 1];
            double mean = 0;
            for (int i = 1; i < t.length; i++) {
                iv[i - 1] = t[i] - t[i - 1];
                mean += iv[i - 1];
            }
            mean /= iv.length;
            double var = 0;
            for (double v : iv) var += (v - mean) * (v - mean);
            double sd = Math.sqrt(var / iv.length);
            if (sd < plugin.cfg("autoclicker", "min-stddev-ms", 7)) {
                double buf = d.addBuf("clicker-sd", 1.0);
                if (buf >= plugin.cfg("autoclicker", "sd-buffer", 6.0)) {
                    vm.flag(p, "autoclicker", "robotic timing sd=" + f(sd) + "ms cps=" + cps);
                    d.resetBuf("clicker-sd");
                }
            } else {
                d.decayBuf("clicker-sd", 0.5);
            }
        }
    }
}
