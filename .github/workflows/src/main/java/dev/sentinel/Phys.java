package dev.sentinel;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Set;

/** Collision / world helpers. */
public final class Phys {
    private Phys() {}

    public static final int LIQUID = 1;
    public static final int SPECIAL = 2;

    private static final Set<Material> SPECIAL_BLOCKS = Set.of(
            Material.COBWEB, Material.SWEET_BERRY_BUSH, Material.HONEY_BLOCK, Material.SLIME_BLOCK,
            Material.SCAFFOLDING, Material.BUBBLE_COLUMN, Material.POWDER_SNOW, Material.BIG_DRIPLEAF);

    private static final Set<Material> ICE = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    public static double attr(Player p, Attribute a, double def) {
        AttributeInstance ai = p.getAttribute(a);
        return ai == null ? def : ai.getValue();
    }

    /** True if any block collision shape overlaps the probe box. Never loads chunks. */
    public static boolean collides(World w, BoundingBox probe) {
        int x0 = (int) Math.floor(probe.getMinX()), x1 = (int) Math.floor(probe.getMaxX());
        int y0 = (int) Math.floor(probe.getMinY()), y1 = (int) Math.floor(probe.getMaxY());
        int z0 = (int) Math.floor(probe.getMinZ()), z1 = (int) Math.floor(probe.getMaxZ());
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.isPassable()) continue;
                    BoundingBox local = probe.clone().shift(-x, -y, -z);
                    if (b.getCollisionShape().overlaps(local)) return true;
                }
            }
        }
        return false;
    }

    /** Solid ground within {@code down} blocks beneath the box. */
    public static boolean groundBelow(World w, BoundingBox bb, double down) {
        BoundingBox probe = new BoundingBox(
                bb.getMinX() + 0.001, bb.getMinY() - down, bb.getMinZ() + 0.001,
                bb.getMaxX() - 0.001, bb.getMinY() + 0.001, bb.getMaxZ() - 0.001);
        return collides(w, probe);
    }

    public static boolean headBlocked(World w, BoundingBox bb) {
        BoundingBox probe = new BoundingBox(
                bb.getMinX() + 0.001, bb.getMaxY() - 0.001, bb.getMinZ() + 0.001,
                bb.getMaxX() - 0.001, bb.getMaxY() + 0.3, bb.getMaxZ() - 0.001);
        return collides(w, probe);
    }

    /** Bit flags: LIQUID, SPECIAL (climbables, webs, slime, honey, beds, bubble columns...). */
    public static int scan(World w, BoundingBox bb) {
        int flags = 0;
        int x0 = (int) Math.floor(bb.getMinX() - 0.3), x1 = (int) Math.floor(bb.getMaxX() + 0.3);
        int y0 = (int) Math.floor(bb.getMinY() - 0.6), y1 = (int) Math.floor(bb.getMaxY() + 0.2);
        int z0 = (int) Math.floor(bb.getMinZ() - 0.3), z1 = (int) Math.floor(bb.getMaxZ() + 0.3);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = y0; y <= y1; y++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (b.isLiquid()) flags |= LIQUID;
                    else if (SPECIAL_BLOCKS.contains(m) || Tag.CLIMBABLE.isTagged(m) || Tag.BEDS.isTagged(m)) flags |= SPECIAL;
                }
            }
        }
        return flags;
    }

    public static boolean onIce(World w, BoundingBox bb) {
        int y = (int) Math.floor(bb.getMinY() - 0.5);
        for (int x = (int) Math.floor(bb.getMinX()); x <= (int) Math.floor(bb.getMaxX()); x++) {
            for (int z = (int) Math.floor(bb.getMinZ()); z <= (int) Math.floor(bb.getMaxZ()); z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                if (ICE.contains(w.getBlockAt(x, y, z).getType())) return true;
            }
        }
        return false;
    }

    public static boolean nearGroundEntity(Player p) {
        for (Entity e : p.getNearbyEntities(1.5, 2.0, 1.5)) {
            if (e instanceof Boat || e instanceof Shulker) return true;
        }
        return false;
    }

    /** Distance from a point to the closest point of a box (0 if inside). */
    public static double distToBox(Vector p, BoundingBox b) {
        double dx = Math.max(Math.max(b.getMinX() - p.getX(), 0.0), p.getX() - b.getMaxX());
        double dy = Math.max(Math.max(b.getMinY() - p.getY(), 0.0), p.getY() - b.getMaxY());
        double dz = Math.max(Math.max(b.getMinZ() - p.getZ(), 0.0), p.getZ() - b.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** True if a solid block (other than the target) sits clearly between the eye and the target point. */
    public static boolean blockedBetween(World w, Vector eye, Vector target, Block targetBlock) {
        Vector dir = target.clone().subtract(eye);
        double dist = dir.length();
        if (dist < 1.0) return false;
        dir.normalize();
        RayTraceResult r = w.rayTraceBlocks(eye.toLocation(w), dir, dist, FluidCollisionMode.NEVER, true);
        if (r == null || r.getHitBlock() == null) return false;
        if (targetBlock != null && r.getHitBlock().equals(targetBlock)) return false;
        return eye.distance(r.getHitPosition()) < dist - 0.6;
    }

    public static long key(Block b) {
        return (((long) b.getX() & 0x3FFFFFFL) << 38) | (((long) b.getZ() & 0x3FFFFFFL) << 12) | ((long) b.getY() & 0xFFFL);
    }
}
