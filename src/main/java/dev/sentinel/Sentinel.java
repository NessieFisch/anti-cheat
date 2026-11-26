package dev.sentinel;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Sentinel extends JavaPlugin {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private ViolationManager violations;
    private volatile boolean lowTps;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        violations = new ViolationManager(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new StateListener(this), this);
        pm.registerEvents(new MovementListener(this), this);
        pm.registerEvents(new CombatListener(this), this);
        pm.registerEvents(new BlockListener(this), this);
        pm.registerEvents(new InventoryListener(this), this);
        pm.registerEvents(new ItemListener(this), this);

        SentinelCommand cmd = new SentinelCommand(this);
        PluginCommand pc = getCommand("sentinel");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            double[] tps = Bukkit.getTPS();
            lowTps = tps.length > 0 && tps[0] < getConfig().getDouble("general.min-tps", 18.0);
        }, 100L, 40L);

        // violation decay every 30s
        Bukkit.getScheduler().runTaskTimer(this,
                () -> violations.decay(getConfig().getDouble("violations.decay-per-minute", 4.0) / 2.0),
                600L, 600L);

        getLogger().info("Sentinel enabled. Punishments are "
                + (getConfig().getBoolean("punishments.enabled", false) ? "ON" : "OFF (alert-only)") + ".");
    }

    public PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), PlayerData::new);
    }

    public void removeData(UUID id) {
        data.remove(id);
    }

    public ViolationManager violations() {
        return violations;
    }

    private boolean hasExemptPrefix(String name) {
        for (String prefix : getConfig().getStringList("general.exempt-name-prefixes")) {
            if (!prefix.isEmpty() && name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** How long (ms) checks back off after teleports, knockback, damage, etc. */
    public long disruptMs() {
        return getConfig().getLong("general.disrupt-ms", 2500L);
    }

    public boolean lowTps() {
        return lowTps;
    }

    public boolean enabled(String check) {
        return getConfig().getBoolean("checks." + check + ".enabled", true);
    }

    public double cfg(String check, String key, double def) {
        return getConfig().getDouble("checks." + check + "." + key, def);
    }

    /** Bypass permission + ping gate. */
    public boolean shouldCheck(Player p, PlayerData d) {
        long now = System.currentTimeMillis();
        if (now - d.permCheckedAt > 5000) {
            d.bypass = p.hasPermission("sentinel.bypass") || hasExemptPrefix(p.getName());
            d.permCheckedAt = now;
        }
        if (d.bypass) return false;
        return p.getPing() <= getConfig().getInt("general.max-ping", 600);
    }
}
