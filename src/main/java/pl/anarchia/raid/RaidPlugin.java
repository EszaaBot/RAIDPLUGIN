package pl.anarchia.raid;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.function.operation.Operations;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.boss.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileInputStream;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public final class RaidPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private BossBar bossBar;
    private Location raidOrigin;
    private Location dropLocation;
    private Location regionMin;
    private Location regionMax;
    private TextDisplay floatingLabel;
    private boolean active;
    private boolean warning;
    private long raidEndsAt;
    private long nextDropAt;
    private String handledScheduleKey = "";
    private final Random random = new Random();
    private final Set<UUID> menuViewers = new HashSet<>();
    // Dostęp do waypointu otrzymuje wyłącznie gracz po udanej, płatnej teleportacji.
    private final Set<UUID> waypointUsers = new HashSet<>();
    private final Set<UUID> pendingPaidTeleports = new HashSet<>();
    private static final String TIMES_MENU = "§8RAID §7» §cGodziny";
    private static final String HOUR_MENU = "§8RAID §7» §cWybierz godzinę";
    private static final String MINUTE_MENU = "§8RAID §7» §cWybierz minuty";
    private static final String DROP_MENU = "§8RAID §7» §cEdytor dropu";
    private final Map<UUID, Map<Integer, String>> timeMenuActions = new HashMap<>();
    private final Map<UUID, Map<Integer, Integer>> dropMenuActions = new HashMap<>();
    private final Map<UUID, Integer> selectedHours = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "raid.schem").exists()) saveResource("raid.schem", false);
        setupEconomy();
        // Główna komenda oraz osobne, jawnie zarejestrowane komendy administracyjne.
        for (String name : List.of("rajd", "rajdstart", "rajdstop", "rajddrop", "rajdgodziny", "rajdreload", "rajdtp")) {
            PluginCommand registered = getCommand(name);
            if (registered != null) registered.setExecutor(this);
            else getLogger().severe("Brak komendy '" + name + "' w plugin.yml!");
        }
        getServer().getPluginManager().registerEvents(this, this);
        bossBar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("RaidPlugin uruchomiony. WorldEdit=" + (Bukkit.getPluginManager().getPlugin("WorldEdit") != null)
                + ", Vault/Economy=" + (economy != null));
    }

    @Override
    public void onDisable() {
        if (bossBar != null) bossBar.removeAll();
        removeFloatingLabel();
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void tick() {
        if (active) {
            long now = System.currentTimeMillis();
            for (Player online : Bukkit.getOnlinePlayers()) if (!bossBar.getPlayers().contains(online)) bossBar.addPlayer(online);
            long left = Math.max(0, raidEndsAt - now);
            bossBar.setTitle("§cSERWER NAJECHANY §8• §f" + formatTime(left / 1000));
            bossBar.setProgress(Math.max(0.0, Math.min(1.0, (double) left / (getConfig().getLong("raid.duration-seconds", 1800) * 1000.0))));
            if (now >= nextDropAt) dropLoot();
            updateWaypoint();
            if (now >= raidEndsAt) stopRaid("§cRaid dobiegł końca.");
            return;
        }
        if (!getConfig().getBoolean("schedule.enabled", true)) return;
        ZonedDateTime now = ZonedDateTime.now(zone());
        for (String time : getConfig().getStringList("schedule.times")) {
            try {
                LocalTime targetTime = LocalTime.parse(time);
                ZonedDateTime target = now.toLocalDate().atTime(targetTime).atZone(zone());
                if (target.isBefore(now.minusSeconds(1))) target = target.plusDays(1);
                long seconds = Duration.between(now, target).getSeconds();
                String key = target.toLocalDate() + "@" + time;
                if (seconds <= getConfig().getLong("schedule.warning-seconds", 600) && seconds > 0 && !handledScheduleKey.equals(key)) {
                    handledScheduleKey = key;
                    beginWarning(target);
                }
            } catch (DateTimeParseException ex) {
                getLogger().warning("Niepoprawna godzina w schedule.times: " + time);
            }
        }
        if (warning && raidOrigin == null && System.currentTimeMillis() >= raidEndsAt) startRaid();
        if (warning && raidOrigin != null) {
            for (Player online : Bukkit.getOnlinePlayers()) if (!bossBar.getPlayers().contains(online)) bossBar.addPlayer(online);
            long left = Math.max(0, raidEndsAt - System.currentTimeMillis());
            bossBar.setTitle("§cSERWER ZOSTANIE NAJECHANY §8• §f" + formatTime(left / 1000));
            bossBar.setProgress(Math.max(0.0, Math.min(1.0, (double) left / (getConfig().getLong("schedule.warning-seconds", 600) * 1000.0))));
        }
    }

    private ZoneId zone() {
        try { return ZoneId.of(getConfig().getString("timezone", "Europe/Warsaw")); }
        catch (Exception ex) { return ZoneId.of("Europe/Warsaw"); }
    }

    private void beginWarning(ZonedDateTime target) {
        warning = true;
        raidOrigin = null;
        raidEndsAt = target.toInstant().toEpochMilli();
        bossBar.setColor(BarColor.RED);
        bossBar.setTitle("§cSERWER ZOSTANIE NAJECHANY §8• §f10:00");
        bossBar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        Bukkit.broadcastMessage("§c§l⚔ RAID §8» §cSerwer zostanie najechany za 10 minut!");
    }

    private void startRaid() {
        if (active) return;
        World world = Bukkit.getWorld(getConfig().getString("raid.world", "world"));
        if (world == null) {
            Bukkit.broadcastMessage("§cRaid nie wystartował: nie znaleziono świata z configu.");
            warning = false;
            bossBar.removeAll();
            return;
        }
        Location origin = randomRaidLocation(world);
        if (origin == null) {
            Bukkit.broadcastMessage("§cRaid nie wystartował: nie udało się znaleźć lokalizacji.");
            warning = false;
            bossBar.removeAll();
            return;
        }
        try {
            pasteSchematic(world, origin);
        } catch (Exception ex) {
            getLogger().severe("Nie udało się wkleić raid.schem: " + ex.getMessage());
            Bukkit.broadcastMessage("§cRaid nie wystartował: błąd wczytywania schematu. Sprawdź WorldEdit i raid.schem.");
            warning = false;
            bossBar.removeAll();
            return;
        }
        raidOrigin = origin;
        waypointUsers.clear();
        ConfigurationSection off = getConfig().getConfigurationSection("raid.drop-offset");
        int ox = off == null ? 0 : off.getInt("x", 0);
        int oy = off == null ? 2 : off.getInt("y", 2);
        int oz = off == null ? 0 : off.getInt("z", 0);
        dropLocation = origin.clone().add(ox + 0.5, oy, oz + 0.5);
        active = true;
        warning = false;
        long duration = getConfig().getLong("raid.duration-seconds", 1800);
        raidEndsAt = System.currentTimeMillis() + duration * 1000L;
        nextDropAt = System.currentTimeMillis() + getConfig().getLong("raid.drop-interval-seconds", 300) * 1000L;
        bossBar.setTitle("§cSERWER NAJECHANY");
        bossBar.setProgress(1.0);
        for (Player p : Bukkit.getOnlinePlayers()) bossBar.addPlayer(p);
        spawnFloatingLabel();
        Bukkit.broadcastMessage("§c§l⚔ RAID ROZPOCZĘTY! §7Wpisz §f/rajd §7aby otworzyć menu.");
        dropLoot();
    }

    private Location randomRaidLocation(World world) {
        Location center = world.getWorldBorder().getCenter();
        double borderRadius = Math.max(100, world.getWorldBorder().getSize() / 2.0 - 100);
        double max = Math.min(getConfig().getDouble("raid.max-radius", 5000), borderRadius);
        double min = Math.min(getConfig().getDouble("raid.min-radius", 1000), max);
        for (int i = 0; i < 40; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double radius = min + random.nextDouble() * Math.max(1, max - min);
            int x = (int) Math.round(center.getX() + Math.cos(angle) * radius);
            int z = (int) Math.round(center.getZ() + Math.sin(angle) * radius);
            if (!world.isChunkGenerated(x >> 4, z >> 4)) continue;
            world.getChunkAt(x >> 4, z >> 4);
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() + 2 || y >= world.getMaxHeight() - 10) continue;
            Material ground = world.getBlockAt(x, y - 1, z).getType();
            if (ground == Material.LAVA || ground == Material.WATER || ground == Material.MAGMA_BLOCK) continue;
            return new Location(world, x, y, z);
        }
        return null;
    }

    private void pasteSchematic(World world, Location origin) throws Exception {
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) throw new IllegalStateException("Brak WorldEdit");
        File file = new File(getDataFolder(), getConfig().getString("raid.schematic-file", "raid.schem"));
        if (!file.isFile()) throw new IllegalStateException("Brak pliku " + file.getName());
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) throw new IllegalStateException("Nieobsługiwany format schematu");
        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) { clipboard = reader.read(); }
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            Operations.complete(new ClipboardHolder(clipboard).createPaste(editSession)
                    .to(BlockVector3.at(origin.getBlockX(), origin.getBlockY(), origin.getBlockZ()))
                    .ignoreAirBlocks(false).build());
        }
        regionMin = origin.clone();
        regionMax = origin.clone().add(max.x() - min.x(), max.y() - min.y(), max.z() - min.z());
    }

    private void dropLoot() {
        if (!active || dropLocation == null) return;
        List<Map<?, ?>> loot = getConfig().getMapList("loot");
        List<Map<?, ?>> pool = new ArrayList<>(loot);
        int count = Math.max(1, getConfig().getInt("raid.items-per-drop", 2));
        int dropped = 0;
        for (int i = 0; i < count && !pool.isEmpty(); i++) {
            Map<?, ?> entry = weightedPick(pool);
            if (entry == null) break;
            pool.remove(entry);
            try {
                ItemStack item;
                Object serialized = entry.get("item");
                if (serialized instanceof Map<?, ?> itemMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typedMap = (Map<String, Object>) itemMap;
                    item = ItemStack.deserialize(typedMap);
                } else {
                    Material material = Material.valueOf(String.valueOf(entry.get("material")).toUpperCase(Locale.ROOT));
                    if (!material.isItem()) continue;
                    int amount = Math.max(1, Math.min(material.getMaxStackSize(), number(entry.get("amount"), 1)));
                    item = new ItemStack(material, amount);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && entry.get("display-name") != null) {
                        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', String.valueOf(entry.get("display-name"))));
                        item.setItemMeta(meta);
                    }
                }
                if (item == null || item.getType().isAir()) continue;
                dropLocation.getWorld().dropItemNaturally(dropLocation, item);
                dropped++;
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Niepoprawny material w loot: " + entry.get("material"));
            }
        }
        nextDropAt = System.currentTimeMillis() + getConfig().getLong("raid.drop-interval-seconds", 300) * 1000L;
        if (dropped > 0) Bukkit.broadcastMessage("§c§lRAID §8» §7Na miejscu raidu pojawił się nowy drop!");
    }

    private Map<?, ?> weightedPick(List<Map<?, ?>> pool) {
        int total = 0;
        for (Map<?, ?> item : pool) total += Math.max(1, number(item.get("weight"), 1));
        if (total <= 0) return pool.get(random.nextInt(pool.size()));
        int roll = random.nextInt(total);
        for (Map<?, ?> item : pool) {
            roll -= Math.max(1, number(item.get("weight"), 1));
            if (roll < 0) return item;
        }
        return pool.get(pool.size() - 1);
    }

    private int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private void spawnFloatingLabel() {
        removeFloatingLabel();
        if (!getConfig().getBoolean("raid.waypoint.floating-label", true)) return;
        floatingLabel = raidOrigin.getWorld().spawn(raidOrigin.clone().add(0, 3, 0), TextDisplay.class, display -> {
            display.setText("§c§lRAJD");
            display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(false);
        });
        // Etykieta nie jest publiczna: pokażemy ją dopiero graczom, którzy zapłacili za TP.
        for (Player p : Bukkit.getOnlinePlayers()) p.hideEntity(this, floatingLabel);
    }

    private void removeFloatingLabel() {
        if (floatingLabel != null && floatingLabel.isValid()) floatingLabel.remove();
        floatingLabel = null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (floatingLabel != null && floatingLabel.isValid() && !waypointUsers.contains(player.getUniqueId())) {
            player.hideEntity(this, floatingLabel);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Jedyny teleport, który odblokowuje waypoint, to teleport opłacony komendą/GUI pluginu.
        if (pendingPaidTeleports.remove(id)) return;
        if (waypointUsers.remove(id)) {
            if (floatingLabel != null && floatingLabel.isValid()) event.getPlayer().hideEntity(this, floatingLabel);
            World destinationWorld = event.getTo() == null ? event.getPlayer().getWorld() : event.getTo().getWorld();
            event.getPlayer().setCompassTarget(destinationWorld.getSpawnLocation());
        }
    }

    private void updateWaypoint() {
        if (raidOrigin == null || !getConfig().getBoolean("raid.waypoint.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!waypointUsers.contains(p.getUniqueId())) continue;
            // RTP/spawn oraz Nether/End nie mogą przypadkowo aktywować ani pokazywać waypointu.
            if (!p.getWorld().equals(raidOrigin.getWorld())
                    || p.getLocation().distanceSquared(raidOrigin) > Math.pow(getConfig().getInt("raid.teleport-radius", 500) + 150, 2)) {
                waypointUsers.remove(p.getUniqueId());
                if (floatingLabel != null && floatingLabel.isValid()) p.hideEntity(this, floatingLabel);
                p.setCompassTarget(p.getWorld().getSpawnLocation());
                continue;
            }
            double distance = p.getLocation().distance(raidOrigin);
            p.sendActionBar("§c§lRAJD §8» §f" + Math.round(distance) + " m");
            if (getConfig().getBoolean("raid.waypoint.compass-target", true)) p.setCompassTarget(raidOrigin);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (waypointUsers.contains(p.getUniqueId()) && p.getWorld().equals(raidOrigin.getWorld())) {
                p.spawnParticle(Particle.END_ROD, raidOrigin.clone().add(0.5, 2, 0.5), 2, 0.2, 0.3, 0.2, 0.01);
            }
        }
    }

    private void stopRaid(String message) {
        active = false;
        warning = false;
        raidOrigin = null;
        waypointUsers.clear();
        dropLocation = null;
        regionMin = null;
        regionMax = null;
        removeFloatingLabel();
        bossBar.removeAll();
        Bukkit.broadcastMessage(message);
    }

    private String formatTime(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Obsługa oddzielnych aliasów: /rajdstart, /rajdstop itd.
        String invoked = label.toLowerCase(Locale.ROOT);
        if (!invoked.equals("rajd")) {
            String sub = switch (invoked) {
                case "rajdstart" -> "start";
                case "rajdstop" -> "stop";
                case "rajddrop" -> "drop";
                case "rajdgodziny" -> "godziny";
                case "rajdreload" -> "reload";
                case "rajdtp" -> "tp";
                default -> "";
            };
            String[] mapped = new String[args.length + 1];
            mapped[0] = sub;
            System.arraycopy(args, 0, mapped, 1, args.length);
            args = mapped;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("tp")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Ta komenda jest tylko dla graczy."); return true; }
            teleportToRaid(player);
            return true;
        }
        if (args.length > 0) {
            if (!sender.hasPermission("raid.admin")) { sender.sendMessage("§cNie masz uprawnień."); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start", "spawn", "res" -> {
                    if (active) sender.sendMessage("§cRaid już trwa.");
                    else { warning = false; startRaid(); sender.sendMessage("§aWysłano próbę uruchomienia raidu."); }
                }
                case "stop" -> { if (active || warning) stopRaid("§cRaid został zakończony przez administrację."); else sender.sendMessage("§cNie ma aktywnego raidu."); }
                case "reload" -> { reloadConfig(); sender.sendMessage("§aPrzeładowano config.yml."); }
                case "godziny" -> {
                    if (args.length == 1 && sender instanceof Player player) openTimesMenu(player);
                    else editTimes(sender, args);
                }
                case "drop" -> {
                    if (args.length == 1 && sender instanceof Player player) openDropMenu(player);
                    else editDrop(sender, args);
                }
                default -> sendHelp(sender);
            }
            return true;
        }
        if (!(sender instanceof Player player)) { sender.sendMessage("Ta komenda jest tylko dla graczy."); return true; }
        openMenu(player);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§c§lRAID §8» §7Komendy:");
        sender.sendMessage("§e/rajd §7- menu gracza");
        sender.sendMessage("§e/rajd tp §7- losowy TP do 500 kratek od raidu za $500");
        sender.sendMessage("§e/rajd start §7- natychmiastowy spawn raidu (admin)");
        sender.sendMessage("§e/rajd stop §7- zakończ raid (admin)");
        sender.sendMessage("§e/rajdgodziny §7- edytor godzin w GUI (admin)");
        sender.sendMessage("§e/rajddrop §7- edytor dropu w GUI (admin)");
        sender.sendMessage("§e/rajd reload §7- przeładuj config (admin)");
    }

    private void editTimes(CommandSender sender, String[] args) {
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            if (sender instanceof Player player && sender.hasPermission("raid.admin")) openTimesMenu(player);
            else sender.sendMessage("§cGodziny raidów: §f" + String.join(", ", getConfig().getStringList("schedule.times")));
            return;
        }
        List<String> times = new ArrayList<>(getConfig().getStringList("schedule.times"));
        String action = args[1].toLowerCase(Locale.ROOT);
        try {
            if (action.equals("add") && args.length >= 3) {
                LocalTime.parse(args[2]);
                if (!times.contains(args[2])) times.add(args[2]);
            } else if (action.equals("remove") && args.length >= 3) {
                times.remove(args[2]);
            } else if (action.equals("set") && args.length >= 3) {
                times.clear();
                for (String time : args[2].split(",")) { LocalTime.parse(time); times.add(time); }
            } else { sender.sendMessage("§cUżycie: /rajd godziny list|add HH:mm|remove HH:mm|set HH:mm,HH:mm"); return; }
            Collections.sort(times);
            getConfig().set("schedule.times", times);
            saveConfig();
            sender.sendMessage("§aZapisano godziny: §f" + String.join(", ", times));
        } catch (DateTimeParseException ex) {
            sender.sendMessage("§cNiepoprawna godzina. Użyj formatu HH:mm, np. 18:30.");
        }
    }

    private void editDrop(CommandSender sender, String[] args) {
        List<Map<?, ?>> loot = new ArrayList<>(getConfig().getMapList("loot"));
        if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
            if (sender instanceof Player player && sender.hasPermission("raid.admin")) { openDropMenu(player); return; }
            if (loot.isEmpty()) { sender.sendMessage("§cLista dropu jest pusta."); return; }
            for (int i = 0; i < loot.size(); i++) {
                Map<?, ?> item = loot.get(i);
                sender.sendMessage("§e" + (i + 1) + ". §f" + item.get("material") + " x" + item.get("amount") + " §7(waga " + item.get("weight") + ")");
            }
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        try {
            if (action.equals("add") && args.length >= 4) {
                Material material = Material.valueOf(args[2].toUpperCase(Locale.ROOT));
                if (!material.isItem()) throw new IllegalArgumentException();
                int amount = Integer.parseInt(args[3]);
                int weight = args.length >= 5 ? Integer.parseInt(args[4]) : 10;
                if (amount < 1 || weight < 1) throw new IllegalArgumentException();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("material", material.name()); entry.put("amount", amount); entry.put("weight", weight);
                loot.add(entry);
            } else if (action.equals("remove") && args.length >= 3) {
                int index = Integer.parseInt(args[2]) - 1;
                if (index < 0 || index >= loot.size()) { sender.sendMessage("§cNie ma takiej pozycji na liście."); return; }
                loot.remove(index);
            } else { sender.sendMessage("§cUżycie: /rajd drop list|add MATERIAL ILOSC [WAGA]|remove NUMER"); return; }
            getConfig().set("loot", loot);
            saveConfig();
            sender.sendMessage("§aZapisano drop. Aktualna liczba pozycji: §f" + loot.size());
        } catch (IllegalArgumentException ex) {
            sender.sendMessage("§cBłędny materiał/liczba. Przykład: /rajd drop add MACE 1 5");
        }
    }

    private ItemStack menuItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openTimesMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, TIMES_MENU);
        timeMenuActions.remove(player.getUniqueId());
        Map<Integer, String> actions = new HashMap<>();
        List<String> times = new ArrayList<>(getConfig().getStringList("schedule.times"));
        Collections.sort(times);
        int slot = 10;
        for (String time : times) {
            if (slot > 43) break;
            inv.setItem(slot, menuItem(Material.CLOCK, "§e" + time,
                    List.of("§7Kliknij, aby usunąć tę godzinę.", "§cUsuń z harmonogramu")));
            actions.put(slot, time);
            slot++;
            if (slot == 17 || slot == 26 || slot == 35) slot += 2;
        }
        inv.setItem(49, menuItem(Material.LIME_DYE, "§a§lDODAJ GODZINĘ",
                List.of("§7Wybierz godzinę i minuty", "§eKliknij, aby otworzyć wybór")));
        inv.setItem(45, menuItem(Material.PAPER, "§fZAPISYWANIE AUTOMATYCZNE",
                List.of("§7Każda zmiana zapisuje się", "§7od razu w config.yml")));
        player.openInventory(inv);
        timeMenuActions.put(player.getUniqueId(), actions);
    }

    private void openHourPicker(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, HOUR_MENU);
        for (int hour = 0; hour < 24; hour++) {
            inv.setItem(hour, menuItem(Material.CLOCK, "§e" + String.format(Locale.ROOT, "%02d:xx", hour),
                    List.of("§7Kliknij, aby wybrać godzinę")));
        }
        inv.setItem(49, menuItem(Material.ARROW, "§cWróć", List.of("§7Powrót do harmonogramu")));
        player.openInventory(inv);
    }

    private void openMinutePicker(Player player, int hour) {
        selectedHours.put(player.getUniqueId(), hour);
        Inventory inv = Bukkit.createInventory(null, 27, MINUTE_MENU);
        int[] minutes = {0, 15, 30, 45};
        for (int i = 0; i < minutes.length; i++) {
            String time = String.format(Locale.ROOT, "%02d:%02d", hour, minutes[i]);
            inv.setItem(10 + i * 2, menuItem(Material.CLOCK, "§e" + time,
                    List.of("§7Kliknij, aby dodać tę godzinę", "§aZmiana zapisze się automatycznie")));
        }
        inv.setItem(22, menuItem(Material.ARROW, "§cWróć", List.of("§7Wybierz inną godzinę")));
        player.openInventory(inv);
    }

    private void addRaidTime(Player player, String time) {
        List<String> times = new ArrayList<>(getConfig().getStringList("schedule.times"));
        if (times.contains(time)) {
            player.sendMessage("§cTa godzina już jest ustawiona.");
            openTimesMenu(player);
            return;
        }
        times.add(time);
        Collections.sort(times);
        getConfig().set("schedule.times", times);
        saveConfig();
        player.sendMessage("§aDodano godzinę raidu: §e" + time);
        openTimesMenu(player);
    }

    private void removeRaidTime(Player player, String time) {
        List<String> times = new ArrayList<>(getConfig().getStringList("schedule.times"));
        times.remove(time);
        getConfig().set("schedule.times", times);
        saveConfig();
        player.sendMessage("§cUsunięto godzinę raidu: §e" + time);
        openTimesMenu(player);
    }

    private void openDropMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, DROP_MENU);
        List<Map<?, ?>> loot = getConfig().getMapList("loot");
        Map<Integer, Integer> actions = new HashMap<>();
        int slot = 0;
        for (int i = 0; i < loot.size() && slot < 45; i++) {
            Map<?, ?> entry = loot.get(i);
            ItemStack icon = getLootIcon(entry);
            if (icon == null) continue;
            ItemMeta meta = icon.getItemMeta();
            List<String> lore = meta != null && meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add("§7Waga losowania: §e" + number(entry.get("weight"), 10));
            lore.add("§cKliknij, aby usunąć");
            lore.add("§ePPM, aby zmienić wagę");
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            actions.put(slot, i);
            slot++;
        }
        inv.setItem(45, menuItem(Material.HOPPER, "§a§lDODAWANIE PRZEDMIOTÓW",
                List.of("§7Kliknij przedmiot w swoim ekwipunku", "§7poniżej, aby dodać go do dropu.", "§7Dodawany jest dokładny item ze stacka.")));
        inv.setItem(49, menuItem(Material.BARRIER, "§cZAMKNIJ", List.of("§7Zmiany zapisują się automatycznie")));
        inv.setItem(53, menuItem(Material.PAPER, "§fINFORMACJA",
                List.of("§7LPM na item z listy: usuń", "§7PPM na item z listy: zmień wagę", "§7Kliknij item w swoim ekwipunku: dodaj")));
        player.openInventory(inv);
        dropMenuActions.put(player.getUniqueId(), actions);
    }

    private ItemStack getLootIcon(Map<?, ?> entry) {
        Object serialized = entry.get("item");
        try {
            if (serialized instanceof Map<?, ?> itemMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedMap = (Map<String, Object>) itemMap;
                return ItemStack.deserialize(typedMap);
            }
            Material material = Material.valueOf(String.valueOf(entry.get("material")).toUpperCase(Locale.ROOT));
            if (!material.isItem()) return null;
            ItemStack item = new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), number(entry.get("amount"), 1))));
            if (entry.get("display-name") != null) {
                ItemMeta meta = item.getItemMeta();
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', String.valueOf(entry.get("display-name"))));
                item.setItemMeta(meta);
            }
            return item;
        } catch (Exception ex) { return null; }
    }

    private void addLootItem(Player player, ItemStack source) {
        if (source == null || source.getType().isAir()) return;
        ItemStack item = source.clone();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("material", item.getType().name());
        entry.put("amount", item.getAmount());
        entry.put("weight", 10);
        entry.put("item", item.serialize());
        List<Map<?, ?>> loot = new ArrayList<>(getConfig().getMapList("loot"));
        loot.add(entry);
        getConfig().set("loot", loot);
        saveConfig();
        player.sendMessage("§aDodano §f" + item.getType().name() + " §ado puli dropu. Zapisano w config.yml.");
        openDropMenu(player);
    }

    private void removeLootItem(Player player, int index) {
        List<Map<?, ?>> loot = new ArrayList<>(getConfig().getMapList("loot"));
        if (index < 0 || index >= loot.size()) { openDropMenu(player); return; }
        loot.remove(index);
        getConfig().set("loot", loot);
        saveConfig();
        player.sendMessage("§cUsunięto przedmiot z puli dropu.");
        openDropMenu(player);
    }

    private void cycleLootWeight(Player player, int index) {
        List<Map<?, ?>> loot = new ArrayList<>(getConfig().getMapList("loot"));
        if (index < 0 || index >= loot.size()) { openDropMenu(player); return; }
        Map<?, ?> old = loot.get(index);
        Map<String, Object> updated = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : old.entrySet()) updated.put(String.valueOf(e.getKey()), e.getValue());
        int[] weights = {1, 5, 10, 20, 50, 100};
        int current = number(updated.get("weight"), 10);
        int next = weights[0];
        for (int i = 0; i < weights.length; i++) if (weights[i] == current) next = weights[(i + 1) % weights.length];
        updated.put("weight", next);
        loot.set(index, updated);
        getConfig().set("loot", loot);
        saveConfig();
        player.sendMessage("§aNowa waga przedmiotu: §e" + next);
        openDropMenu(player);
    }

    private void handleAdminGuiClick(InventoryClickEvent event, Player player) {
        String title = event.getView().getTitle();
        UUID id = player.getUniqueId();
        if (title.equals(TIMES_MENU)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 49) { openHourPicker(player); return; }
            String time = timeMenuActions.getOrDefault(id, Collections.emptyMap()).get(event.getRawSlot());
            if (time != null) removeRaidTime(player, time);
            return;
        }
        if (title.equals(HOUR_MENU)) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 49) { openTimesMenu(player); return; }
            if (slot >= 0 && slot < 24) openMinutePicker(player, slot);
            return;
        }
        if (title.equals(MINUTE_MENU)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 22) { openHourPicker(player); return; }
            int slot = event.getRawSlot();
            int hour = selectedHours.getOrDefault(id, -1);
            if (hour >= 0) {
                int[] slots = {10, 12, 14, 16};
                int[] minutes = {0, 15, 30, 45};
                for (int i = 0; i < slots.length; i++) if (slot == slots[i]) { addRaidTime(player, String.format(Locale.ROOT, "%02d:%02d", hour, minutes[i])); return; }
            }
            return;
        }
        if (title.equals(DROP_MENU)) {
            event.setCancelled(true);
            if (event.getRawSlot() == 49) { player.closeInventory(); return; }
            if (event.getClickedInventory() == player.getInventory()) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir()) addLootItem(player, clicked);
                return;
            }
            Integer index = dropMenuActions.getOrDefault(id, Collections.emptyMap()).get(event.getRawSlot());
            if (index != null) {
                if (event.isRightClick()) cycleLootWeight(player, index);
                else removeLootItem(player, index);
            }
        }
    }

    private void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§8Raid §7- §cAnarchia SMP");
        ItemStack info = new ItemStack(Material.NETHER_STAR);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§c§lINFORMACJE O RAIDZIE");
        List<String> lore = new ArrayList<>();
        lore.add(active ? "§aStatus: §lTRWA" : warning ? "§eStatus: §lZA CHWILĘ" : "§7Status: §lNIEAKTYWNY");
        if (active && raidOrigin != null) lore.add("§7Świat: §f" + raidOrigin.getWorld().getName());
        lore.add("§7Teleport: §e$" + getConfig().getDouble("raid.teleport-cost", 500));
        lore.add("§7Promień teleportacji: §f" + getConfig().getInt("raid.teleport-radius", 500) + " kratek");
        im.setLore(lore); info.setItemMeta(im); inv.setItem(13, info);
        ItemStack tp = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tm = tp.getItemMeta();
        tm.setDisplayName("§c§lTELEPORTUJ NA RAID");
        tm.setLore(List.of("§7Losowy punkt w promieniu §f500 §7kratek", "§7Koszt: §e$" + getConfig().getDouble("raid.teleport-cost", 500), "", "§eKliknij, aby się teleportować."));
        tp.setItemMeta(tm); inv.setItem(11, tp);
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta cm = compass.getItemMeta(); cm.setDisplayName("§cWaypoint raidu");
        cm.setLore(List.of("§7Po rozpoczęciu raidu kompas", "§7i pasek akcji wskazują jego położenie.")); compass.setItemMeta(cm); inv.setItem(15, compass);
        player.openInventory(inv); menuViewers.add(player.getUniqueId());
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (title.equals(TIMES_MENU) || title.equals(HOUR_MENU) || title.equals(MINUTE_MENU) || title.equals(DROP_MENU)) {
            handleAdminGuiClick(event, player);
            return;
        }
        if (!menuViewers.contains(player.getUniqueId()) || !title.equals("§8Raid §7- §cAnarchia SMP")) return;
        event.setCancelled(true);
        if (event.getRawSlot() == 11) {
            player.closeInventory();
            menuViewers.remove(player.getUniqueId());
            teleportToRaid(player);
        }
    }

    @EventHandler
    public void onMenuClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        menuViewers.remove(id);
        timeMenuActions.remove(id);
        dropMenuActions.remove(id);
    }

    private void teleportToRaid(Player player) {
        if (!active || raidOrigin == null) { player.sendMessage("§cRaid obecnie nie trwa."); return; }
        if (economy == null) { player.sendMessage("§cBrak Vault lub ekonomii. Zainstaluj Vault i EssentialsX."); return; }
        double cost = getConfig().getDouble("raid.teleport-cost", 500);
        if (!economy.has(player, cost)) { player.sendMessage("§cNie masz wystarczająco pieniędzy. Koszt: $" + cost); return; }
        int radius = getConfig().getInt("raid.teleport-radius", 500);
        World world = raidOrigin.getWorld();
        Location destination = null;
        for (int i = 0; i < 30; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(random.nextDouble()) * radius;
            int x = raidOrigin.getBlockX() + (int) Math.round(Math.cos(angle) * r);
            int z = raidOrigin.getBlockZ() + (int) Math.round(Math.sin(angle) * r);
            if (!world.isChunkGenerated(x >> 4, z >> 4)) continue;
            int y = world.getHighestBlockYAt(x, z) + 1;
            Material floor = world.getBlockAt(x, y - 1, z).getType();
            if (floor == Material.LAVA || floor == Material.WATER || floor == Material.MAGMA_BLOCK) continue;
            destination = new Location(world, x + 0.5, y, z + 0.5);
            break;
        }
        if (destination == null) { player.sendMessage("§cNie znaleziono bezpiecznego miejsca teleportacji."); return; }
        economy.withdrawPlayer(player, cost);
        pendingPaidTeleports.add(player.getUniqueId());
        player.teleportAsync(destination).thenAccept(success -> {
            Bukkit.getScheduler().runTask(this, () -> {
                pendingPaidTeleports.remove(player.getUniqueId());
                if (success) {
                    waypointUsers.add(player.getUniqueId());
                    if (floatingLabel != null && floatingLabel.isValid()) player.showEntity(this, floatingLabel);
                    player.setCompassTarget(raidOrigin);
                    player.sendMessage("§aTeleportowano w okolice raidu. Koszt: §e$" + cost);
                    player.sendMessage("§cWaypoint RAJD został odblokowany tylko dla Ciebie.");
                } else {
                    economy.depositPlayer(player, cost);
                    player.sendMessage("§cTeleportacja nie powiodła się. Pieniądze zwrócono.");
                }
            });
        });
    }

    private boolean insideProtectedRegion(Location location) {
        if (!getConfig().getBoolean("raid.protect-entire-schematic", true) || regionMin == null || regionMax == null) return false;
        if (!location.getWorld().equals(regionMin.getWorld())) return false;
        return location.getBlockX() >= Math.min(regionMin.getBlockX(), regionMax.getBlockX())
                && location.getBlockX() <= Math.max(regionMin.getBlockX(), regionMax.getBlockX())
                && location.getBlockY() >= Math.min(regionMin.getBlockY(), regionMax.getBlockY())
                && location.getBlockY() <= Math.max(regionMin.getBlockY(), regionMax.getBlockY())
                && location.getBlockZ() >= Math.min(regionMin.getBlockZ(), regionMax.getBlockZ())
                && location.getBlockZ() <= Math.max(regionMin.getBlockZ(), regionMax.getBlockZ());
    }

    @EventHandler public void onBreak(BlockBreakEvent event) {
        if (insideProtectedRegion(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cNie możesz niszczyć bloków struktury raidu!");
        }
    }
    @EventHandler public void onEntityExplode(EntityExplodeEvent event) { event.blockList().removeIf(b -> insideProtectedRegion(b.getLocation())); }
    @EventHandler public void onBlockExplode(BlockExplodeEvent event) { event.blockList().removeIf(b -> insideProtectedRegion(b.getLocation())); }
}
