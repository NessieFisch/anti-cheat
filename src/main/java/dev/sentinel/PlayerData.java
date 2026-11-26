package dev.sentinel;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player state shared by all checks. Only touched from the main server thread. */
public final class PlayerData {

    public record Pos(long ms, double x, double y, double z) {}

    public final UUID uuid;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    // permission cache
    public boolean bypass;
    public long permCheckedAt;

    // movement
    public boolean hasLast, lastGround;
    public int airTicks;
    public double lastDx, lastDy, lastDz, lastH;
    public long lastMoveMs;
    public double timerBalance;
    public double yawDelta, pitchDelta;
    public long rotMs;
    public final ArrayDeque<Pos> history = new ArrayDeque<>();

    // exemption timestamps (ms)
    public long lastDisrupt, lastGlide, lastRiptide, lastSpecial, lastIce;
    public long lastSneakToggle, lastSetback, lastUseStart, lastInvOpen;

    // vertical knockback follow-up + lag tracking
    public double kbVy, kbMaxDy;
    public long kbStart;
    public boolean kbGround;
    public long lagUntil, lastDt;

    // combat
    public final ArrayDeque<Long> clicks = new ArrayDeque<>();
    public long lastAttackMs;
    public int lastAttackTick = -1;
    public UUID lastTarget;
    public boolean swingSinceAttack = true, hadAttack;
    public final double[] aim = new double[10];
    public int aimCount, aimIdx;

    // blocks
    public boolean digging;
    public long diggingSince;
    public long breakKey = Long.MIN_VALUE;
    public long breakStart;
    public boolean breakInsta;
    public int breakTick = -1, breaksThisTick;
    public final ArrayDeque<Long> breaks = new ArrayDeque<>();
    public final ArrayDeque<Long> places = new ArrayDeque<>();
    public int xrayTotal, xrayRare;

    // inventory
    public final ArrayDeque<Long> invClicks = new ArrayDeque<>();

    // generic per-check buffers (a check must trip several times before it flags)
    private final Map<String, Double> buffers = new HashMap<>();

    public double addBuf(String key, double amount) {
        return buffers.merge(key, amount, Double::sum);
    }

    public void decayBuf(String key, double amount) {
        buffers.computeIfPresent(key, (k, v) -> v - amount <= 0 ? null : v - amount);
    }

    public void resetBuf(String key) {
        buffers.remove(key);
    }
}
