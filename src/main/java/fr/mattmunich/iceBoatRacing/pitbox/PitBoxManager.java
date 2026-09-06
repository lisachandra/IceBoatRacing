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
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Messages.*;

public class PitBoxManager {

    private final Main main;

    /**
     * Tracks the repeating velocity-zero task for each player currently serving a pit stop,
     * so it can be canceled on completion, race end, or plugin disable.
     */
    private final Map<UUID, BukkitTask> activeSessions = new HashMap<>();

    public PitBoxManager(Main main) {
        this.main = main;
    }


    // ---------------------------------------------------------------------
    // Storage (mirrors CheckpointManager's save/load conventions)
    // ---------------------------------------------------------------------

    public PitBox savePitBox(Race race, String name, Location l1, Location l2, PitBox.TaskType taskType, int duration, List<UUID> allowed, PitBoxColor color) {
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
        config.set(path + ".allowed", allowedUUIDs);
        config.set(path + ".color", color.name());

        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't save pit box " + id + " for race " + race.getName() + " because its config threw an error on saving.", e);
            return null;
        }

        PitBox box = new PitBox(id, name, min, max, taskType, duration, allowed, color);
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
            main.err("Couldn't update allowed list for pit box " + box.getId() + " on race " + race.getName() + " because its config threw an error on saving.", e);
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
            main.err("Couldn't update color for pit box " + box.getId() + " on race " + race.getName() + " because its config threw an error on saving.", e);
            return false;
        }

        box.setColor(color);
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
            main.err("Couldn't remove pit box " + box.getId() + " for race " + race.getName() + " because its config threw an error on saving.", e);
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
            int id = Integer.parseInt(key);
            String worldName = config.getString("pitboxes." + key + ".world");
            String name = config.getString("pitboxes." + key + ".name", "Pit " + id);

            Location min = deserialize(worldName, config.getString("pitboxes." + key + ".min"));
            Location max = deserialize(worldName, config.getString("pitboxes." + key + ".max"));
            if (min == null || max == null) continue;

            String taskTypeString = config.getString("pitboxes." + key + ".taskType");
            PitBox.TaskType taskType = taskTypeString == null ? PitBox.TaskType.TIMED : PitBox.TaskType.valueOf(taskTypeString);

            int duration = config.getInt("pitboxes." + key + ".duration", 5);
            List<String> allowedStrings = config.getStringList("pitboxes." + key + ".allowed");
            if (allowedStrings.getFirst().equalsIgnoreCase("*")) allowedStrings = new ArrayList<>();

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

            if(updated) {
                List<String> allowedString = new ArrayList<>();
                allowed.forEach(uuid -> allowedString.add(uuid.toString()));
                config.set("pitboxes." + id + ".allowed", allowedString);
            }

            String colorString = config.getString("pitboxes." + key + ".color");
            PitBoxColor color = colorString == null ? PitBoxColor.ICE : PitBoxColor.valueOf(colorString);

            race.addPitBox(new PitBox(id, name, min, max, taskType, duration, allowed, color));
        }

        race.getPitBoxes().sort(Comparator.comparingInt(PitBox::getId));
    }

    private int nextId(Race race) {
        return race.getPitBoxes().stream().mapToInt(PitBox::getId).max().orElse(0) + 1;
    }

    // ---------------------------------------------------------------------
    // Active pit-stop sessions
    // ---------------------------------------------------------------------

    /**
     * Starts a pit stop for {@code player} in {@code box}. The player cannot leave early:
     * a repeating task zeroes the boat's velocity every tick for the box's duration, which
     * both holds the player in place and keeps them inside the trigger volume — so no
     * separate exit/cancel detection is needed for the mandatory-timed case.
     */
    public void startSession(Player player, Racer data, PitBox box) {
        box.setOccupant(player.getUniqueId());
        data.pittingBox = box;
        data.lastPitBox = System.currentTimeMillis();

        Bukkit.broadcast(buildBoxBoxMessage(player, box));

        long endTimeMillis = System.currentTimeMillis() + (box.getDuration() * 1000L);
        final int[] lastShownSecond = {-1}; // effectively-final holder so the lambda can mutate it

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(main, () -> {
//            if (player.getVehicle() instanceof Boat boat) {
//                boat.setVelocity(new Vector(0, 0, 0));
//            }

            long remainingMillis = endTimeMillis - System.currentTimeMillis();
            int secondsLeft = (int) Math.max(0, Math.ceil(remainingMillis / 1000.0));

            // Only push a new title when the displayed number actually changes —
            // this task ticks every 1L (for smooth velocity zeroing), but the
            // countdown itself only needs to update once per second.
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

        activeSessions.put(player.getUniqueId(), task);
    }

    private void completeSession(Player player, Racer data, PitBox box) {
        BukkitTask task = activeSessions.remove(player.getUniqueId());
        if (task != null) task.cancel();

        box.setOccupant(null);
        data.pittingBox = null;
        data.pitStopsCompleted += 1;

        player.sendMessage(getMessage("pitbox.completed", formatArguments(
                "completed", data.pitStopsCompleted,
                "required", data.race.getRequiredPitStops()
        )));
    }

    /**
     * Call when a race ends or a player's race data is reset, so a mid-stop task doesn't
     * keep running (and zeroing velocity) after the race is no longer tracking them.
     */
    public void cancelSession(Player player, PitBox box) {
        BukkitTask task = activeSessions.remove(player.getUniqueId());
        if (task != null) task.cancel();
        if (box != null) box.setOccupant(null);
    }

    public boolean hasActiveSession(Player player) {
        return activeSessions.containsKey(player.getUniqueId());
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