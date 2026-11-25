# Sentinel

Event-based PvP anticheat for **Paper 26.2** (Java 25). One jar, no dependencies.

## Build it on GitHub (no local setup needed)

1. Create a new GitHub repository.
2. Upload **everything in this folder** (including the hidden `.github` folder), or push it with git.
3. Open the **Actions** tab. The *Build Sentinel* workflow runs automatically on every push.
   (If it doesn't, click the workflow, then **Run workflow**.)
4. When it finishes, open the run and download the **Sentinel** artifact. Unzip it: the `.jar` is your plugin.
5. Drop the jar into your server's `plugins/` folder and restart.

If the build fails, open the failed step and send me the error text. The code was written without
being compiled, so a small API mismatch with the 26.2 API is possible and quick to fix.

If the server refuses to load it because of `api-version`, change `'26.2'` to `'26.1'` in `plugin.yml`.

## First run

Punishments are **off**. Sentinel only alerts staff (permission `sentinel.alerts`) and logs to
`plugins/Sentinel/violations.log`. Run like this for a few days, tune `config.yml`, then set
`punishments.enabled: true`.

## Checks

**Movement:** speed, fly (gravity prediction), jump, step, nofall (ground spoof), jesus, phase/noclip,
timer, sprint/omnisprint, noslow (item use + sneak), elytra, velocity (anti-knockback), badpackets

**Combat:** reach (ping-compensated), hitbox/killaura, wallhit, multi-aura, aim lock, autoclicker (CPS +
robotic timing), noswing, attack-while-using-item, criticals

**World:** fastbreak, nuker, block reach, ghosthand (through walls), fastplace, scaffold, x-ray statistics

**Inventory/items:** inventory-move, fast clicking (chest stealer), fastbow, fasteat

**Client:** blocked client-brand list

## Commands / permissions

| Command | Permission |
|---|---|
| `/sentinel alerts` | `sentinel.alerts` |
| `/sentinel info <player>` | `sentinel.admin` |
| `/sentinel reset <player>` | `sentinel.admin` |
| `/sentinel reload` | `sentinel.admin` |

`sentinel.bypass` exempts a player from all checks (staff/testing).

## Honest limitations

- It works from Paper events, not raw packets. It cannot catch everything a packet-level commercial
  anticheat can (e.g. subtle timer/blink variants, rotation-pattern killaura, spoofed client brands).
- **X-ray:** the statistics check only alerts. For real protection, turn on Paper's built-in anti-xray
  (`anticheat.anti-xray` in `config/paper-world-defaults.yml`).
- Thresholds are conservative guesses. Tune against your own players before enabling bans.
- Not Folia-compatible (uses the normal Bukkit scheduler).
- `fasteat` assumes vanilla eating times; disable it if you customise consumables.
- `jesus` is experimental; disable it if it flags swimmers.
