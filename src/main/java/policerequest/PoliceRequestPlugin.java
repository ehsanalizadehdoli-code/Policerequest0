package policerequest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PoliceRequestPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey targetKey;

    // درخواست‌های در انتظار: بازیکن -> زمان ساخت
    private final Map<UUID, Long> pending = new HashMap<>();
    // مأموریت‌های فعال
    private final Map<UUID, UUID> officerToPatient = new HashMap<>();
    private final Map<UUID, UUID> patientToOfficer = new HashMap<>();
    // کول‌داون: بازیکن -> زمان پایان
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        targetKey = new NamespacedKey(this, "police_target");

        getCommand("police").setExecutor(this);
        getCommand("police").setTabCompleter(this);
        getCommand("policereload").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        long interval = Math.max(1, getConfig().getInt("tracking.update-interval", 10));
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, interval);
    }

    // ---------- دستورها ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("policereload")) {
            if (!sender.hasPermission("police.reload")) {
                sender.sendMessage(comp("no-permission"));
                return true;
            }
            reloadConfig();
            sender.sendMessage(comp("reloaded"));
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(comp("player-only"));
            return true;
        }

        if (args.length == 0) {
            request(p);
        } else if (args[0].equalsIgnoreCase("accept") && args.length >= 2) {
            accept(p, args[1]);
        } else {
            send(p, "invalid-usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("police.accept")) {
                out.add("accept");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("accept")) {
            for (UUID id : pending.keySet()) {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) {
                    out.add(pl.getName());
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(prefix));
        return out;
    }

    private void request(Player p) {
        if (!p.hasPermission("police.use")) {
            send(p, "no-permission");
            return;
        }
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        Long until = cooldowns.get(id);
        if (until != null && until > now) {
            send(p, "cooldown", "{time}", String.valueOf((until - now + 999) / 1000));
            return;
        }
        boolean onePer = getConfig().getBoolean("request.one-request-per-player", true);
        if (onePer && (pending.containsKey(id) || patientToOfficer.containsKey(id))) {
            send(p, "already-active");
            return;
        }

        pending.put(id, now);
        int cd = getConfig().getInt("request.cooldown", 60);
        if (cd > 0) {
            cooldowns.put(id, now + cd * 1000L);
        }

        send(p, "sent");
        sound(p, "sounds.request-created");

        if (getConfig().getBoolean("request.notify-officers", true)) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p) || !o.hasPermission("police.accept")) {
                    continue;
                }
                send(o, "notification", "{player}", p.getName());
                Component click = comp("accept-instruction", "{player}", p.getName())
                        .clickEvent(ClickEvent.runCommand("/police accept " + p.getName()));
                o.sendMessage(click);
                sound(o, "sounds.request-created");
            }
        }
    }

    private void accept(Player officer, String targetName) {
        if (!officer.hasPermission("police.accept")) {
            send(officer, "no-permission");
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            send(officer, "player-not-found");
            return;
        }
        if (!pending.containsKey(target.getUniqueId())) {
            send(officer, "not-available");
            return;
        }
        if (officerToPatient.containsKey(officer.getUniqueId())) {
            send(officer, "already-busy");
            return;
        }

        ItemStack compass = findCompass(officer);
        if (compass == null) {
            send(officer, "compass-required");
            return;
        }
        ItemMeta meta = compass.getItemMeta();
        if (meta == null) {
            send(officer, "bind-failed");
            return;
        }
        meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target.getUniqueId().toString());
        compass.setItemMeta(meta);

        pending.remove(target.getUniqueId());
        officerToPatient.put(officer.getUniqueId(), target.getUniqueId());
        patientToOfficer.put(target.getUniqueId(), officer.getUniqueId());

        send(officer, "accepted", "{player}", target.getName());
        send(officer, "compass-guide");
        send(target, "officer-notified", "{officer}", officer.getName());
        sound(officer, "sounds.request-accepted");
        sound(target, "sounds.request-accepted");
    }

    // ---------- ردیابی ----------

    private void tick() {
        long now = System.currentTimeMillis();

        int timeout = getConfig().getInt("request.request-timeout", 0);
        if (timeout > 0) {
            pending.entrySet().removeIf(e -> {
                if (now - e.getValue() > timeout * 1000L) {
                    Player pl = Bukkit.getPlayer(e.getKey());
                    if (pl != null) {
                        send(pl, "expired");
                    }
                    return true;
                }
                return false;
            });
        }

        double completion = getConfig().getDouble("tracking.completion-distance", 2.0);
        boolean actionbar = getConfig().getBoolean("tracking.show-actionbar", true);
        boolean warnWorld = getConfig().getBoolean("world.show-different-world-warning", true);

        for (Map.Entry<UUID, UUID> e : new ArrayList<>(officerToPatient.entrySet())) {
            UUID officerId = e.getKey();
            Player officer = Bukkit.getPlayer(officerId);
            Player patient = Bukkit.getPlayer(e.getValue());

            if (officer == null) {
                cancelTracking(officerId);
                continue;
            }
            if (patient == null) {
                send(officer, "target-unavailable");
                cancelTracking(officerId);
                continue;
            }
            if (!officer.getWorld().equals(patient.getWorld())) {
                if (warnWorld) {
                    officer.sendActionBar(comp("different-world", "{player}", patient.getName(), false));
                }
                continue;
            }

            officer.setCompassTarget(patient.getLocation());
            double d = officer.getLocation().distance(patient.getLocation());

            if (d <= completion) {
                send(officer, "arrived", "{player}", patient.getName());
                send(patient, "officer-arrived", "{officer}", officer.getName());
                sound(officer, "sounds.arrival");
                sound(patient, "sounds.arrival");
                cancelTrackingSilently(officerId);
                continue;
            }
            if (actionbar) {
                officer.sendActionBar(comp("actionbar",
                        "{player}", patient.getName(), "{distance}", String.valueOf(Math.round(d)), false));
            }
        }
    }

    private void cancelTracking(UUID officerId) {
        Player officer = Bukkit.getPlayer(officerId);
        if (officer != null && officerToPatient.containsKey(officerId)) {
            send(officer, "cancelled");
        }
        cancelTrackingSilently(officerId);
    }

    private void cancelTrackingSilently(UUID officerId) {
        UUID patientId = officerToPatient.remove(officerId);
        if (patientId != null) {
            patientToOfficer.remove(patientId);
        }
        Player officer = Bukkit.getPlayer(officerId);
        if (officer != null) {
            for (ItemStack item : officer.getInventory().getContents()) {
                unbind(item);
            }
            Location l = officer.getBedSpawnLocation();
            if (l == null) {
                l = officer.getWorld().getSpawnLocation();
            }
            officer.setCompassTarget(l);
        }
    }

    // ---------- لیسنرها ----------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        ItemStack stack = e.getItemDrop().getItemStack();
        if (stack.getType() != Material.COMPASS || !isBound(stack)) {
            return;
        }
        if (!getConfig().getBoolean("compass.cancel-on-drop", true)) {
            return;
        }
        unbind(stack);
        e.getItemDrop().setItemStack(stack);
        cancelTracking(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (officerToPatient.containsKey(id) && getConfig().getBoolean("compass.cancel-on-death", true)) {
            for (ItemStack drop : e.getDrops()) {
                unbind(drop);
            }
            cancelTracking(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pending.remove(id);

        if (officerToPatient.containsKey(id)) {
            cancelTrackingSilently(id);
        }
        UUID officerId = patientToOfficer.get(id);
        if (officerId != null) {
            Player officer = Bukkit.getPlayer(officerId);
            if (officer != null) {
                send(officer, "target-unavailable");
            }
            cancelTracking(officerId);
        }
    }

    // ---------- کمکی‌ها ----------

    private ItemStack findCompass(Player p) {
        boolean requireSingle = getConfig().getBoolean("compass.require-single-compass", true);
        ItemStack first = null;
        int total = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == Material.COMPASS) {
                total += item.getAmount();
                if (first == null) {
                    first = item;
                }
            }
        }
        if (first == null) {
            return null;
        }
        if (requireSingle && total != 1) {
            return null;
        }
        return first;
    }

    private boolean isBound(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(targetKey, PersistentDataType.STRING);
    }

    private void unbind(ItemStack item) {
        if (!isBound(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(targetKey);
        item.setItemMeta(meta);
    }

    private void sound(Player p, String path) {
        if (!getConfig().getBoolean("sounds.enabled", true)) {
            return;
        }
        String name = getConfig().getString(path);
        if (name == null || name.isEmpty()) {
            return;
        }
        try {
            p.playSound(p.getLocation(), Sound.valueOf(name.toUpperCase()), 1f, 1f);
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Unknown sound in config: " + name);
        }
    }

    private Component comp(String key, String... kv) {
        return comp(key, kv, true);
    }

    private Component comp(String key, String a, String b, boolean prefix) {
        return comp(key, new String[]{a, b}, prefix);
    }

    private Component comp(String key, String a, String b, String c, String d, boolean prefix) {
        return comp(key, new String[]{a, b, c, d}, prefix);
    }

    private Component comp(String key, String[] kv, boolean prefix) {
        String s = (prefix ? getConfig().getString("messages.prefix", "") : "")
                + getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace(kv[i], kv[i + 1]);
        }
        return LEGACY.deserialize(s);
    }

    private void send(Player p, String key, String... kv) {
        p.sendMessage(comp(key, kv, true));
    }
}
