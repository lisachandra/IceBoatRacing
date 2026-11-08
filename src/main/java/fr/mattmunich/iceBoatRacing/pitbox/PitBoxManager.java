package fr.mattmunich.iceBoatRacing.pitbox;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.Racer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.err;
import static fr.mattmunich.iceBoatRacing.Main.warn;
import static fr.mattmunich.iceBoatRacing.Messages.*;

public class PitBoxManager implements Listener {

    private final Main main;

    /**
     * One active pit-stop session per player, whichever the box's task type. Sessions are fully
     * independent, so any number of players can pit at the same time — even in the same box.
     * TIMED sessions only use {@code countdownTask}; CHEST sessions only use {@code lootGui}.
     */
    private static final class PitSession {
        final Racer racer;
        final PitBox box;
        BukkitTask countdownTask; // TIMED
        Inventory lootGui; // CHEST, kept per session so partially looted stock isn't reset on reopen
        int lastShownRemaining = -1; // CHEST, dedupes the action bar

        PitSession(Racer racer, PitBox box) {
            this.racer = racer;
            this.box = box;
        }

        boolean isChest() {
            return box.getTaskType() == PitBox.TaskType.CHEST;
        }
    }

    private final Map<UUID, PitSession> sessions = new HashMap<>();

    public PitBoxManager(Main main) {
        this.main = main;
    }


    // ---------------------------------------------------------------------
    // Storage (mirrors CheckpointManager's save/load conventions)
    // ---------------------------------------------------------------------

    public PitBox savePitBox(Race race, String name, Location l1, Location l2, PitBox.TaskType taskType, int duration, List<UUID> allowed, PitBoxColor color) {
        return savePitBox(race, name, l1, l2, taskType, duration, 5, Material.SNOWBALL, allowed, color);
    }

    public PitBox savePitBox(Race race, String name, Location l1, Location l2, PitBox.TaskType taskType, int duration, int lootCount, Material lootItem, List<UUID> allowed, PitBoxColor color) {
        Location min = min(l1, l2);
        Location max = max(l1, l2);
        int id = nextId(race);

        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cPit box " + id + " for race " + race.getName() + " wasn't saved, see cause above.");
            return null;
        }

        List<String> allowedUUIDs = new ArrayList<>();
        allowed.forEach(uuid -> allowedUUIDs.add(uuid.toString()));

        String path = "pitboxes." + id;
        config.set(path + ".name", name);
        config.set(path + ".world", min.getWorld().getName());
        config.set(path + ".min", serialize(min));
        config.set(path + ".max", serialize(max));
        config.set(path + ".taskType", taskType.name());
        config.set(path + ".duration", duration);
        config.set(path + ".loot", lootCount);
        config.set(path + ".lootItem", lootItem.name());
        config.set(path + ".allowed", allowedUUIDs);
        config.set(path + ".color", color.name());

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't save pit box " + id + " for race " + race.getName() + " because its config threw an error on saving.", e);
            return null;
        }

        PitBox box = new PitBox(id, name, min, max, taskType, duration, lootCount, lootItem, allowed, color);
        race.addPitBox(box);
        return box;
    }

    public boolean setAllowed(Race race, PitBox box, List<UUID> allowed) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cAllowed list for pit box " + box.getId() + " on race " + race.getName() + " wasn't updated, see cause above.");
            return false;
        }

        List<String> allowedString = new ArrayList<>();
        allowed.forEach(uuid -> allowedString.add(uuid.toString()));

        config.set("pitboxes." + box.getId() + ".allowed", allowedString);

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't update allowed list for pit box " + box.getId() + " on race " + race.getName() + " because its config threw an error on saving.", e);
            return false;
        }

        box.setAllowed(allowed);
        return true;
    }

    public boolean setColor(Race race, PitBox box, PitBoxColor color) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cColor for pit box " + box.getId() + " on race " + race.getName() + " wasn't updated, see cause above.");
            return false;
        }

        config.set("pitboxes." + box.getId() + ".color", color.name());

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't update color for pit box " + box.getId() + " on race " + race.getName() + " because its config threw an error on saving.", e);
            return false;
        }

        box.setColor(color);
        return true;
    }

    public boolean setTaskType(Race race, PitBox box, PitBox.TaskType taskType) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cTask type for pit box " + box.getId() + " on race " + race.getName() + " wasn't updated, see cause above.");
            return false;
        }

        config.set("pitboxes." + box.getId() + ".taskType", taskType.name());

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't update task type for pit box " + box.getId() + " on race " + race.getName() + " because its config threw an error on saving.", e);
            return false;
        }

        box.setTaskType(taskType);
        return true;
    }

    public boolean setLoot(Race race, PitBox box, int lootCount, Material lootItem) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cLoot settings for pit box " + box.getId() + " on race " + race.getName() + " weren't updated, see cause above.");
            return false;
        }

        config.set("pitboxes." + box.getId() + ".loot", lootCount);
        if (lootItem != null) config.set("pitboxes." + box.getId() + ".lootItem", lootItem.name());

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't update loot settings for pit box " + box.getId() + " on race " + race.getName() + " because its config threw an error on saving.", e);
            return false;
        }

        box.setLootCount(lootCount);
        if (lootItem != null) box.setLootItem(lootItem);
        return true;
    }

    public boolean remove(Race race, PitBox box) {
        if (box == null) return false;

        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cPit box " + box.getId() + " for race " + race.getName() + " wasn't removed, see cause above.");
            return false;
        }

        config.set("pitboxes." + box.getId(), null);
        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't remove pit box " + box.getId() + " for race " + race.getName() + " because its config threw an error on saving.", e);
            return false;
        }
        return race.removePitBox(box);
    }

    public void loadRacePitBoxes(Race race) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cPit boxes for race " + race.getName() + " were not loaded, see cause above.");
            return;
        }

        race.clearPitBoxes();
        if (!config.isConfigurationSection("pitboxes")) return;

        for (String key : Objects.requireNonNull(config.getConfigurationSection("pitboxes")).getKeys(false)) {
            //One malformed pit box must not abort loading: the exception used to escape loadAllRaces()
            //and stop every race after this one from loading too.
            try {
                race.addPitBox(loadPitBox(race, config, key));
            } catch (RuntimeException e) {
                warn("Skipping pit box '" + key + "' of race " + race.getName() + ": " + e.getMessage());
            }
        }

        race.getPitBoxes().sort(Comparator.comparingInt(PitBox::getId));
    }

    /**
     * Rebuilds one pit box from its YAML entry.
     * @throws RuntimeException if the entry can't be understood — callers report it and skip the box
     */
    private PitBox loadPitBox(Race race, YamlConfiguration config, String key) {
        int id = Integer.parseInt(key);
        String worldName = config.getString("pitboxes." + key + ".world");
        String name = config.getString("pitboxes." + key + ".name", "Pit " + id);

        Location min = deserialize(worldName, config.getString("pitboxes." + key + ".min"));
        Location max = deserialize(worldName, config.getString("pitboxes." + key + ".max"));
        if (min == null || max == null) {
            throw new IllegalArgumentException("the corners weren't found");
        }

        String taskTypeString = config.getString("pitboxes." + key + ".taskType");
        PitBox.TaskType taskType = taskTypeString == null ? PitBox.TaskType.TIMED : PitBox.TaskType.valueOf(taskTypeString.toUpperCase());

        int duration = config.getInt("pitboxes." + key + ".duration", 5);
        List<String> allowedStrings = config.getStringList("pitboxes." + key + ".allowed");

        //An empty list means "everyone may use this box": that is what a missing key stores, and what
        //"*" means. Testing it before getFirst() keeps an open box from throwing NoSuchElementException,
        //which used to abort loading of this race and of every race after it.
        boolean everyone = allowedStrings.isEmpty()
                || (allowedStrings.size() == 1 && allowedStrings.getFirst().equalsIgnoreCase("*"));
        if (everyone) allowedStrings = new ArrayList<>();

        boolean updated = false;

        List<UUID> allowed = new ArrayList<>();
        for (String allowedString : allowedStrings) {
            try {
                allowed.add(UUID.fromString(allowedString));
            } catch (IllegalArgumentException e) {
                //Convert old allowed names to UUIDs - will be saved later on.
                allowed.add(Bukkit.getOfflinePlayer(allowedString).getUniqueId());
                updated = true;
            }
        }

        if (updated) {
            List<String> allowedString = new ArrayList<>();
            allowed.forEach(uuid -> allowedString.add(uuid.toString()));
            config.set("pitboxes." + id + ".allowed", allowedString);
        }

        String colorString = config.getString("pitboxes." + key + ".color");
        PitBoxColor color;
        try {
            color = colorString == null ? PitBoxColor.ICE : PitBoxColor.valueOf(colorString.toUpperCase());
        } catch (IllegalArgumentException e) {
            warn("Pit box " + id + " of race " + race.getName() + " has unknown color '" + colorString + "', using ICE.");
            color = PitBoxColor.ICE;
        }

        //Chest loot settings (only meaningful for CHEST boxes, harmless otherwise)
        int lootCount = config.getInt("pitboxes." + key + ".loot", 5);
        if (lootCount < 1) lootCount = 1;
        if (lootCount > 27) lootCount = 27;

        Material lootItem;
        String lootItemName = config.getString("pitboxes." + key + ".lootItem", "SNOWBALL");
        try {
            lootItem = Material.valueOf(lootItemName.toUpperCase());
            if (!lootItem.isItem()) throw new IllegalArgumentException(lootItemName + " is not an item");
        } catch (IllegalArgumentException e) {
            warn("Pit box " + id + " of race " + race.getName() + " has unknown loot item '" + lootItemName + "', using SNOWBALL.");
            lootItem = Material.SNOWBALL;
        }

        return new PitBox(id, name, min, max, taskType, duration, lootCount, lootItem, allowed, color);
    }

    private int nextId(Race race) {
        return race.getPitBoxes().stream().mapToInt(PitBox::getId).max().orElse(0) + 1;
    }

    // ---------------------------------------------------------------------
    // Active pit-stop sessions
    // ---------------------------------------------------------------------

    /**
     * Starts a pit stop for {@code player} in {@code box}.
     * <p>
     * TIMED boxes hold the player in place: the boat is snapped back on every move (see
     * {@code CarListener}), and a repeating task runs the countdown titles for the box's
     * duration — no separate exit/cancel detection is needed.
     * <p>
     * CHEST boxes are served on foot: the driver hops out, opens the chest inside the box and
     * loots the required items from a per-player GUI to finish the stop.
     */
    public void startSession(Player player, Racer data, PitBox box) {
        data.pittingBox = box;

        Bukkit.broadcast(buildBoxBoxMessage(player, box));

        PitSession session = new PitSession(data, box);
        sessions.put(player.getUniqueId(), session);

        if (box.getTaskType() == PitBox.TaskType.CHEST) {
            //The cooldown is stamped on completion instead of here, so a driver who abandons
            //the stop and returns right away isn't locked out by the re-trigger guard.
            startChestSession(player, session);
            return;
        }

        data.lastPitBox = System.currentTimeMillis();

        long endTimeMillis = System.currentTimeMillis() + (box.getDuration() * 1000L);
        final int[] lastShownSecond = {-1}; // effectively-final holder so the lambda can mutate it

        session.countdownTask = Bukkit.getScheduler().runTaskTimer(main, () -> {
//            if (player.getVehicle() instanceof Boat boat) {
//                boat.setVelocity(new Vector(0, 0, 0));
//            }

            long remainingMillis = endTimeMillis - System.currentTimeMillis();
            int secondsLeft = (int) Math.max(0, Math.ceil(remainingMillis / 1000.0));

            // Only push a new title when the displayed number actually changes —
            // this task ticks every 1L, but the countdown itself only needs to
            // update once per second.
            if (secondsLeft != lastShownSecond[0]) {
                lastShownSecond[0] = secondsLeft;

                Title title = secondsLeft > 0
                        ? Title.title(
                        getMessage("pitbox.countdown.title", formatArguments("seconds", secondsLeft)),
                        getMessage("pitbox.countdown.subtitle"))
                        : Title.title(getMessage("pitbox.countdown.go"), Component.empty());

                player.showTitle(title);
                player.playSound(Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.NEUTRAL, 1F, secondsLeft > 0 ? 1F : 2F));
            }

            if (remainingMillis <= 0) {
                completeSession(player, data, box);
            }
        }, 0L, 1L);
    }

    // ----- CHEST sessions -----

    private void startChestSession(Player player, PitSession session) {
        //The stop is served on foot: vanilla blocks opening containers while riding
        if (player.getVehicle() != null) {
            Bukkit.getScheduler().runTask(main, player::leaveVehicle);
        }

        //The chest is matched when it is opened, so it can be placed at any point — before or
        //after the box is defined, even mid-session. This is only an early heads-up.
        if (findChestInZone(session.box) == null) {
            player.sendMessage(getMessage("pitbox.chest.noChest"));
        }

        player.sendMessage(getMessage("pitbox.chest.instruction", formatArguments(
                "count", session.box.getLootCount(),
                "item", prettyMaterial(session.box.getLootItem())
        )));
    }

    /**
     * Finds the first chest (or trapped chest) placed inside the box's zone — or directly on top
     * of it, since chests usually sit on the marked floor — or null.
     */
    private @Nullable Chest findChestInZone(PitBox box) {
        World world = box.getMin().getWorld();
        if (world == null) return null;
        //The Y scan goes one block above the zone: a chest standing on the zone's floor is
        //outside the strict cuboid but is still "in the box" for every practical purpose
        for (int x = box.getMin().getBlockX(); x <= box.getMax().getBlockX(); x++) {
            for (int y = box.getMin().getBlockY(); y <= box.getMax().getBlockY() + 1; y++) {
                for (int z = box.getMin().getBlockZ(); z <= box.getMax().getBlockZ(); z++) {
                    if (world.getBlockAt(x, y, z).getState() instanceof Chest chest) return chest;
                }
            }
        }
        return null;
    }

    /**
     * "Pit junk" used to pad the loot GUI so the chest opens full like a stocked parts pile.
     * The configured loot item is excluded from this pool at fill time.
     */
    private static final List<Material> PIT_FILLER = List.of(
            Material.STICK, Material.COAL, Material.IRON_NUGGET, Material.COBBLESTONE,
            Material.LEATHER, Material.PAPER, Material.STRING, Material.BONE, Material.CLAY_BALL
    );

    private static final Random LOOT_RANDOM = new Random();

    private Inventory createLootGui(PitSession session) {
        Component title = getMessage("pitbox.chest.title", formatArguments(
                "count", session.box.getLootCount(),
                "item", prettyMaterial(session.box.getLootItem())
        ));
        Inventory gui = Bukkit.createInventory(null, 27, title);

        //Shuffle the loot items across the whole grid and pad every other slot with random pit
        //junk, so the chest reads as a full parts pile instead of N items lined up in the corner.
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < gui.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, LOOT_RANDOM);

        for (int i = 0; i < session.box.getLootCount(); i++) {
            gui.setItem(slots.get(i), new ItemStack(session.box.getLootItem()));
        }

        List<Material> fillerPool = PIT_FILLER.stream()
                .filter(material -> material != session.box.getLootItem())
                .toList();
        for (int i = session.box.getLootCount(); i < slots.size(); i++) {
            gui.setItem(slots.get(i), new ItemStack(fillerPool.get(LOOT_RANDOM.nextInt(fillerPool.size()))));
        }
        return gui;
    }

    /**
     * Swaps the real chest for the session's loot GUI: the placed chest is only the trigger, so
     * there is no stock to refill between races and nothing for other players to loot first.
     */
    @EventHandler
    public void onOpenChest(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        PitSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isChest()) return;

        InventoryHolder holder = e.getInventory().getHolder();
        if (!(holder instanceof Chest chest)) return;
        //Any chest in the session's box triggers the GUI — either half of a double chest, and one
        //block above the zone too. Matching by zone (not by a stored location) also means the
        //chest can be placed, moved or replaced at any point during the session.
        if (!isChestInZone(session.box, chest.getLocation())) return;

        e.setCancelled(true);
        if (session.lootGui == null) session.lootGui = createLootGui(session);

        final Inventory gui = session.lootGui;
        Bukkit.getScheduler().runTask(main, () -> {
            if (sessions.get(player.getUniqueId()) != session) return;
            player.openInventory(gui);
        });
    }

    /** True if {@code loc} is inside the box zone, or directly on top of its highest face. */
    private boolean isChestInZone(PitBox box, Location loc) {
        if (box.contains(loc)) return true;
        return loc.getBlockX() >= box.getMin().getBlockX() && loc.getBlockX() <= box.getMax().getBlockX()
                && loc.getBlockZ() >= box.getMin().getBlockZ() && loc.getBlockZ() <= box.getMax().getBlockZ()
                && loc.getBlockY() == box.getMax().getBlockY() + 1;
    }

    @EventHandler
    public void onLootClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        PitSession session = sessions.get(player.getUniqueId());
        if (session == null || session.lootGui == null) return;
        if (!e.getView().getTopInventory().equals(session.lootGui)) return;

        //Recount one tick later, once the click has actually been applied
        Bukkit.getScheduler().runTask(main, () -> checkLootComplete(player, session));
    }

    @EventHandler
    public void onLootClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        PitSession session = sessions.get(player.getUniqueId());
        if (session == null || session.lootGui == null) return;
        if (!e.getInventory().equals(session.lootGui)) return;

        //Partial loot stays in the session GUI — they can reopen the chest and continue
        checkLootComplete(player, session);
    }

    private void checkLootComplete(Player player, PitSession session) {
        if (sessions.get(player.getUniqueId()) != session || session.lootGui == null) return;

        int remaining = remainingLoot(session);
        if (remaining > 0) {
            if (remaining != session.lastShownRemaining) {
                session.lastShownRemaining = remaining;
                player.sendActionBar(getMessage("pitbox.chest.actionBar", formatArguments("remaining", remaining)));
            }
            return;
        }

        completeSession(player, session.racer, session.box);
        player.closeInventory();
    }

    private int remainingLoot(PitSession session) {
        int count = 0;
        for (ItemStack item : session.lootGui.getContents()) {
            if (item != null && item.getType() == session.box.getLootItem()) count += item.getAmount();
        }
        return count;
    }

    /**
     * Called on every move while a CHEST session is running (the driver is on foot, so this is
     * invoked from {@code RaceListener} before its in-vehicle gate). Walking out of the box
     * abandons the stop; driving back in starts a fresh one.
     */
    public void handleChestSessionMove(Player player, Location to) {
        PitSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isChest()) return;
        if (session.box.contains(to)) return;

        releaseSession(player, session, true);
    }

    /** Ends a chest session without counting the stop, freeing the driver to leave. */
    private void releaseSession(Player player, PitSession session, boolean sendLeftMessage) {
        sessions.remove(player.getUniqueId());
        if (session.countdownTask != null) session.countdownTask.cancel();
        session.racer.pittingBox = null;
        if (sendLeftMessage) {
            player.sendMessage(getMessage("pitbox.chest.left"));
        }
    }

    private void completeSession(Player player, Racer data, PitBox box) {
        PitSession session = sessions.remove(player.getUniqueId());
        if (session != null && session.countdownTask != null) session.countdownTask.cancel();

        data.pittingBox = null;
        data.pitStopsCompleted += 1;
        data.lastPitBox = System.currentTimeMillis();

        player.sendMessage(getMessage("pitbox.completed", formatArguments(
                "completed", data.pitStopsCompleted,
                "required", data.race.getRequiredPitStops()
        )));
    }

    /**
     * Call when a race ends, a player leaves, or they quit, so a mid-stop session doesn't keep
     * running after the race is no longer tracking them.
     */
    public void cancelSession(Player player) {
        PitSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            if (session.countdownTask != null) session.countdownTask.cancel();
            session.racer.pittingBox = null;
        }
    }

    @SuppressWarnings("unused")
    public boolean hasActiveSession(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    private String prettyMaterial(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder pretty = new StringBuilder();
        for (String word : words) {
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return pretty.toString();
    }

    // ---------------------------------------------------------------------
    // Serialization helpers (identical convention to CheckpointManager)
    // ---------------------------------------------------------------------

    private String serialize(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location deserialize(String worldName, String value) {
        if (value == null) return null;
        World world = Bukkit.getWorld(worldName);
        String[] parts = value.split(",");
        return new Location(world,
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2]));
    }

    private Location min(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    private Location max(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }

    private Component buildBoxBoxMessage(Player player, PitBox box) {
        String raw = getStringMessage("pitbox.boxBox").replace("%player%", player.getName());
        String tagged = box.getColor().getTagPrefix() + raw + box.getColor().getTagSuffix();
        return MiniMessage.miniMessage().deserialize(tagged);
    }
}