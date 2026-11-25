package dev.sentinel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationManager {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String DEFAULT_FORMAT =
            "&8[&cSentinel&8] &f%player% &7flagged &c%check% &8(&7vl %vl%&8) &7%detail% &8| &7%ping%ms";

    private final Sentinel plugin;
    private final Map<UUID, Map<String, Double>> vls = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = ConcurrentHashMap.newKeySet();

    public ViolationManager(Sentinel plugin) {
        this.plugin = plugin;
    }

    /** Flags using the check's configured weight. Returns true if the caller should set the player back / cancel. */
    public boolean flag(Player p, String check, String detail) {
        double w = plugin.getConfig().getDouble("checks." + check + ".weight", 1.0);
        return flag(p, check, w, detail);
    }

    public boolean flag(Player p, String check, double weight, String detail) {
        Map<String, Double> m = vls.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        double vl = m.merge(check, weight, Double::sum);
        alert(p, check, vl, detail);
        punish(p, check, vl, m);
        FileConfiguration c = plugin.getConfig();
        return c.getBoolean("setback.enabled", true) && vl >= c.getDouble("setback.min-vl", 4.0);
    }

    private void alert(Player p, String check, double vl, String detail) {
        FileConfiguration c = plugin.getConfig();
        if (vl < c.getDouble("alerts.min-vl", 1.0)) return;
        long now = System.currentTimeMillis();
        String key = p.getUniqueId() + ":" + check;
        Long last = lastAlert.get(key);
        if (last != null && now - last < c.getLong("alerts.cooldown-ms", 400L)) return;
        lastAlert.put(key, now);

        String msg = c.getString("alerts.format", DEFAULT_FORMAT)
                .replace("%player%", p.getName())
                .replace("%check%", check)
                .replace("%vl%", String.format("%.1f", vl))
                .replace("%detail%", detail)
                .replace("%ping%", String.valueOf(p.getPing()));
        Component comp = LEGACY.deserialize(msg);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("sentinel.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                staff.sendMessage(comp);
            }
        }
        String plain = msg.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        if (c.getBoolean("logging.console", true)) plugin.getLogger().info(plain);
        if (c.getBoolean("logging.file", true)) appendLog(plain);
    }

    private void appendLog(String line) {
        File f = new File(plugin.getDataFolder(), "violations.log");
        String out = "[" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "] " + line + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.writeString(f.toPath(), out, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        });
    }

    private void punish(Player p, String check, double vl, Map<String, Double> m) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("punishments.enabled", false)) return;
        for (Map<?, ?> a : c.getMapList("punishments.actions")) {
            Object chk = a.get("check");
            Object thr = a.get("vl");
            Object cmds = a.get("commands");
            if (!(thr instanceof Number n) || !(cmds instanceof List<?> list)) continue;
            String target = chk == null ? "*" : chk.toString();
            boolean all = target.equals("*");
            if (!all && !target.equalsIgnoreCase(check)) continue;
            double cur = all ? m.values().stream().mapToDouble(Double::doubleValue).sum() : vl;
            if (cur < n.doubleValue()) continue;
            if (all) m.clear(); else m.remove(check);
            String name = p.getName();
            for (Object o : list) {
                String cmd = o.toString()
                        .replace("%player%", name)
                        .replace("%check%", check)
                        .replace("%vl%", String.format("%.1f", cur));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            break;
        }
    }

    public void decay(double amount) {
        for (Map<String, Double> m : vls.values()) {
            m.replaceAll((k, v) -> v - amount);
            m.values().removeIf(v -> v <= 0);
        }
        vls.values().removeIf(Map::isEmpty);
    }

    public Map<String, Double> snapshot(UUID id) {
        Map<String, Double> m = vls.get(id);
        return m == null ? new HashMap<>() : new HashMap<>(m);
    }

    public void reset(UUID id) {
        vls.remove(id);
    }

    public void clear(UUID id) {
        vls.remove(id);
        alertsOff.remove(id);
        String prefix = id + ":";
        lastAlert.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** @return true if alerts are now ON for this staff member */
    public boolean toggleAlerts(UUID id) {
        if (alertsOff.remove(id)) return true;
        alertsOff.add(id);
        return false;
    }
}
