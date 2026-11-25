package dev.sentinel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SentinelCommand implements CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final List<String> SUBS = List.of("alerts", "info", "reset", "reload");

    private final Sentinel plugin;

    public SentinelCommand(Sentinel plugin) {
        this.plugin = plugin;
    }

    private void msg(CommandSender s, String text) {
        Component c = LEGACY.deserialize("&8[&cSentinel&8] &7" + text);
        s.sendMessage(c);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "Commands: /" + label + " alerts | info <player> | reset <player> | reload");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Players only.");
                    return true;
                }
                if (!p.hasPermission("sentinel.alerts")) {
                    msg(sender, "&cNo permission.");
                    return true;
                }
                boolean on = plugin.violations().toggleAlerts(p.getUniqueId());
                msg(sender, "Alerts " + (on ? "&aenabled" : "&cdisabled") + "&7.");
            }
            case "info" -> {
                if (!sender.hasPermission("sentinel.admin")) {
                    msg(sender, "&cNo permission.");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, "Usage: /" + label + " info <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    msg(sender, "Player not online.");
                    return true;
                }
                Map<String, Double> vls = plugin.violations().snapshot(t.getUniqueId());
                if (vls.isEmpty()) {
                    msg(sender, t.getName() + " has no violations. Ping: " + t.getPing() + "ms");
                    return true;
                }
                msg(sender, "Violations for &f" + t.getName() + " &7(ping " + t.getPing() + "ms):");
                vls.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .forEach(en -> msg(sender, " &c" + en.getKey() + "&8: &f" + String.format("%.1f", en.getValue())));
            }
            case "reset" -> {
                if (!sender.hasPermission("sentinel.admin")) {
                    msg(sender, "&cNo permission.");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, "Usage: /" + label + " reset <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    msg(sender, "Player not online.");
                    return true;
                }
                plugin.violations().reset(t.getUniqueId());
                msg(sender, "Reset violations for " + t.getName() + ".");
            }
            case "reload" -> {
                if (!sender.hasPermission("sentinel.admin")) {
                    msg(sender, "&cNo permission.");
                    return true;
                }
                plugin.reloadConfig();
                msg(sender, "Config reloaded.");
            }
            default -> msg(sender, "Unknown subcommand.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
