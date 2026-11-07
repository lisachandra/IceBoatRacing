package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.LiveScoreManager;
import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
import fr.mattmunich.iceBoatRacing.Result;
import fr.mattmunich.iceBoatRacing.cars.Car;
import fr.mattmunich.iceBoatRacing.cars.CarManager;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointManager;
import fr.mattmunich.iceBoatRacing.pitbox.PitBoxManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.*;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;
import static fr.mattmunich.iceBoatRacing.Messages.getStringMessage;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class RaceManager {

    private final Main main;
    private final CarManager carManager;
    private final CheckpointManager checkpointManager;
    public final PitBoxManager pitBoxManager;
    public final LiveScoreManager liveScoreManager;
    public final List<Race> races = new ArrayList<>();
    public final List<Race> activeRaces = new ArrayList<>();

    /**
     * A map that contains the races that failed to load on startup. <br/>
     * Format: {@code Map<worldName, raceName>}
     */
    public final Map<String, String> unloadedRaces = new HashMap<>();

    public RaceManager(Main main, CarManager carManager, CheckpointManager checkpointManager, PitBoxManager pitBoxManager, LiveScoreManager liveScoreManager) {
        this.main = main;
        this.carManager = carManager;
        this.checkpointManager = checkpointManager;
        this.pitBoxManager = pitBoxManager;
        this.liveScoreManager = liveScoreManager;
    }

    /**
     * @param raceName The name of the race
     * @return The {@link Race} with the matching name
     */
    public Race getRace(String raceName) {
        return races.stream().filter(
                r -> r.getName().equalsIgnoreCase(raceName)
        ).findFirst().orElse(null);
    }

    /**
     * Saves all the races
     */
    public void saveAllRaces() {
        if (races.isEmpty()) {
            warn("No races found, therefore none were saved.");
        } else {
            for (Race race : new ArrayList<>(races)) {
                try {
                    race.saveConfig();
                } catch (IOException e) {
                    err("Could not save race " + race.getName(), e);
                }
            }
        }
    }

    /**
     * @return A list of all the loaded races
     */
    public List<Race> getRaces() {
        return races;
    }

    /**
     * Loads and initializes all the races from file
     */
    public void loadAllRaces() {
        log("Loading all races...");
        int loadedRaces = 0;

        if(!races.isEmpty()) {
            races.forEach(race -> {try { race.end(false); } catch (Exception ignored) {}});
            races.clear();
        }
        File racesFolder = new File(main.getDataFolder(), "races");
        if (!racesFolder.exists()) return;

        for (File file : Objects.requireNonNull(racesFolder.listFiles())) {
            if (!file.getName().endsWith(".yml")) {
                warn("File " + file.getName() + " wasn't loaded as a race, as it is not a .yml");
                continue;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String name = config.getString("name");

            if(name == null) {
                warn("Ignored " + file.getName() + "because it didn't contain any name for the race.");
                continue;
            }

            log("Loading race " + name);
            String worldName = Objects.requireNonNull(config.getString("world"));
            World world = Bukkit.getWorld(worldName);
            if(world == null) {
                severe("Couldn't load race " + name + " because it's world wasn't found.");
                unloadedRaces.put(worldName, name);
                continue;
            }

            Race race = new Race(name, world);
            race.setRaceManager(this);
            race.setConfig(config);

            checkpointManager.loadRaceCheckpoints(race);
            pitBoxManager.loadRacePitBoxes(race);
            carManager.loadCars(race);

            int lapCount = config.getInt("lapCount");
            if (lapCount <= 0) {
                lapCount = 10;
                warn(race.getName() + "'s lap count wasn't found, therefore was set to the default value : 10" );
            }

            race.setLapCount(lapCount);
            race.setRequiredPitStops(config.getInt("requiredPitStops", 0));
            race.setStartRotation(config.getInt("startRotation", 0));

            //Lights
            boolean lightsEnabled = config.getBoolean("lights.enabled", false);
            if(lightsEnabled) {
                Location from = new Location(
                        race.getWorld(),
                        config.getInt("lights.from.x"),
                        config.getInt("lights.from.y"),
                        config.getInt("lights.from.z")
                );

                Location to = new Location(
                        race.getWorld(),
                        config.getInt("lights.to.x"),
                        config.getInt("lights.to.y"),
                        config.getInt("lights.to.z")
                );
                Block origin = race.getWorld().getBlockAt(0, 0, 0);
                if(from.getBlock().equals(origin) && to.getBlock().equals(origin)) {
                    //No values found in the config
                    race.setLights(new Race.Lights(false, null, null));
                } else {
                    Race.Lights lights = new Race.Lights(true, from, to);
                    race.setLights(lights);
                }
            } else {
                race.setLights(new Race.Lights(false, null, null));
            }

            races.add(race);
            log("Race " + name + " has been loaded");
            loadedRaces++;
        }
        log("Loaded " + loadedRaces + " race(s)!");
    }

    /**
     * Loads and initializes all the races from file
     */
    public boolean loadRace(String raceName) {
        log("Loading race " + raceName + "...");

        File racesFolder = new File(main.getDataFolder(), "races");
        if (!racesFolder.exists()) {
            severe("The race folder wasn't found, therefore " + raceName + " could not be loaded.");
            return false;
        }

        File file = new File(racesFolder, raceName + ".yml");
        if (!file.exists()) {
            severe("The race " + raceName + "'s file wasn't found and therefore could not be loaded.");
            return false;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String name = config.getString("name");

        if(name == null) {
            severe("Race " + file.getName() + " wasn't loaded because it didn't contain any name.");
            return false;
        }

        String worldName = Objects.requireNonNull(config.getString("world"));
        World world = Bukkit.getWorld(worldName);
        if(world == null) {
            severe("Couldn't load race " + name + " because it's world wasn't found.");
            unloadedRaces.put(worldName, name);
            return false;
        }
        Race race = new Race(name, world);
        race.setRaceManager(this);
        race.setConfig(config);

        checkpointManager.loadRaceCheckpoints(race);
        pitBoxManager.loadRacePitBoxes(race);
        carManager.loadCars(race);

        int lapCount = config.getInt("lapCount");
        if (lapCount <= 0) {
            lapCount = 10;
            warn(race.getName() + "'s lap count wasn't found, therefore was set to the default value : 10" );
        }

        race.setLapCount(lapCount);
        race.setRequiredPitStops(config.getInt("requiredPitStops", 0));
        race.setStartRotation(config.getInt("startRotation", 0));

        //Lights
        boolean lightsEnabled = config.getBoolean("lights.enabled", false);
        if(lightsEnabled) {
            Location from = new Location(
                    race.getWorld(),
                    config.getInt("lights.from.x"),
                    config.getInt("lights.from.y"),
                    config.getInt("lights.from.z")
            );

            Location to = new Location(
                    race.getWorld(),
                    config.getInt("lights.to.x"),
                    config.getInt("lights.to.y"),
                    config.getInt("lights.to.z")
            );
            Block origin = race.getWorld().getBlockAt(0, 0, 0);
            if(from.getBlock().equals(origin) && to.getBlock().equals(origin)) {
                //No values found in the config
                race.setLights(new Race.Lights(false, null, null));
            } else {
                Race.Lights lights = new Race.Lights(true, from, to);
                race.setLights(lights);
            }
        } else {
            race.setLights(new Race.Lights(false, null, null));
        }

        races.add(race);

        log("Loaded race " + raceName + " successfully!");
        return true;
    }

    /**
     * Updates all the races from file. (Called on reload)
     */
    public void updateAllRaces() {
        log("Updating all races...");
        int loadedRaces = 0;

        if(!races.isEmpty()) {
            for (Race race : new ArrayList<>(races)) {
                boolean success = race.update();
                if(success) log("Race " + race.getName() + " has been updated");
                else warn("Could not update race " + race.getName() + ", see error above.");
                loadedRaces++;
                try { race.end(false); } catch (Exception ignored) {}
            }
            log("Updated " + loadedRaces + " race(s)!");
        } else {
            warn("No races updated (none were found).");
        }
    }

    /**
     * Creates and loads the inputted {@code race}: <br/>
     * - Creates the config file <br/>
     * - Saves it to {@link RaceManager#races} <br/>
     * - Initializes {@link RaceManager} and the config for the {@link Race}
     * @param race The race to create
     * @return The created race
     */
    @SuppressWarnings("UnusedReturnValue")
    public Race createRace(Race race) {
        log("Creating race " + race.getName());
        File raceFile = new File(main.getDataFolder(), "races/" + race.getName().replace(" ","_") + ".yml");
        raceFile.getParentFile().mkdirs();

        YamlConfiguration config = new YamlConfiguration();
        config.set("name", race.getName());
        config.set("world", race.getWorld().getName());
        config.set("lapCount", race.getLapCount());
        config.set("startRotation", race.getStartRotation());
        config.set("requiredPitStops", race.getRequiredPitStops());
        config.set("startRotation", 0);
        config.set("lights.enabled", false);

        try {
            config.save(raceFile);
            log("Race " + race.getName() + " has been saved");
        } catch (IOException e) {
            err("Couldn't create race " + race.getName(),e);
            return null;
        }

        //Update race in race list
        races.add(race);
        race.setRaceManager(this);
        race.setConfig(config);
        return race;
    }

    /**
     * Updates the race from its config file
     * @param race The race to update
     * @return Whether the operation succeeded
     */
    public boolean updateRace(Race race) {
        try {
            File raceFile = new File(main.getDataFolder(), "races/" + race.getName().replace(" ","_") + ".yml");
            if (!raceFile.exists()) {
                warn("Race " + race.getName() + " wasn't updated because it's config file does not exist.");
                return false;
            }

            YamlConfiguration config;
            try {
                config = YamlConfiguration.loadConfiguration(raceFile);
            } catch (Exception e) {
                err("Couldn't load config file for race " + race.getName(),e);
                return false;
            }

            try {
                config.save(raceFile);
            } catch (IOException e) {
                err("Couldn't save race " + race.getName(),e);
                return false;
            }

            int lapCount = config.getInt("lapCount");
            if (lapCount <= 0) {
                lapCount = 10;
                warn(race.getName() + "'s lap count wasn't found, therefore was set to the default value : 10" );
            }

            race.setLapCount(lapCount);
            race.setRequiredPitStops(config.getInt("requiredPitStops", 0));
            race.setStartRotation(config.getInt("startRotation", 0));

            //Lights
            boolean lightsEnabled = config.getBoolean("lights.enabled", false);
            if(lightsEnabled) {
                Location from = new Location(
                        race.getWorld(),
                        config.getInt("lights.from.x"),
                        config.getInt("lights.from.y"),
                        config.getInt("lights.from.z")
                );

                Location to = new Location(
                        race.getWorld(),
                        config.getInt("lights.to.x"),
                        config.getInt("lights.to.y"),
                        config.getInt("lights.to.z")
                );
                Block origin = race.getWorld().getBlockAt(0, 0, 0);
                if(from.getBlock().equals(origin) && to.getBlock().equals(origin)) {
                    //No values found in the config
                    race.setLights(new Race.Lights(false, null, null));
                } else {
                    Race.Lights lights = new Race.Lights(true, from, to);
                    race.setLights(lights);
                }
            } else {
                race.setLights(new Race.Lights(false, null, null));
            }

            //More like reload from file if file/Race unsynced (not really load)
            carManager.loadCars(race);
            checkpointManager.loadRaceCheckpoints(race);
            pitBoxManager.loadRacePitBoxes(race);
            race.setConfig(config);

            //Update race in race list
            races.remove(race);
            races.add(race);

            if(activeRaces.contains(race)) {
                activeRaces.remove(race);
                activeRaces.add(race);
            }
            return true;
        } catch (Exception e) {
            err("An error occurred when updating the race " + race.getName(), e);
            return false;
        }
    }

    /**
     * Deletes the inputted {@code race}
     * @param race The race to delete
     * @return Whether the operation succeeded
     */
    public boolean deleteRace(Race race) {
        log("Deleting race " + race.getName());
        try { race.end(false); } catch (Exception ignored) {}

        File raceFile = new File(main.getDataFolder(), "races/" + race.getName().replace(" ","_") + ".yml");
        boolean deleted = raceFile.delete();

        races.remove(race);

        if(deleted) log("Race " + race.getName() + " has been deleted");
        return deleted;
    }

    /**
     * Gets the {@code race}'s config from file
     * @param race The race to get the config for
     * @return The {@code race}'s config
     */
    public YamlConfiguration getRaceConfig(Race race) {
        File racesFolder = new File(main.getDataFolder(), "races");

        if (!racesFolder.exists()) {
            log("§cCouldn't get config for race " + race.getName() + " because the race folder didn't exist.");
            return null;
        }

        File file = new File(racesFolder, race.getName().replace(" ","_") + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!file.exists()) {
            log("§cCouldn't get config for race " + race.getName() + " because the race config file didn't exist.");
            return null;
        }

        return config;
    }

    /**
     * Saves the {@code config} for the {@code race}
     * @param race The race that the config belongs to
     * @param config The {@link YamlConfiguration} to save
     * @throws IOException When the saving fails, caused by {@code config.save(file)}
     */
    public void saveRaceConfig(Race race, YamlConfiguration config) throws IOException {
        File racesFolder = new File(main.getDataFolder(), "races");

        if (!racesFolder.exists()) {
            log("§cCouldn't get config for race " + race.getName() + " because the race folder didn't exist.");
            return;
        }

        File file = new File(racesFolder, race.getName() + ".yml");
        config.save(file);
    }

    //RaceData management

    /**
     * @return All the racers from every race
     */
    public Map<UUID, Racer> getRacers() {
        Map<UUID, Racer> allRacers = new HashMap<>();
        getRaces().forEach(race -> allRacers.putAll(race.racers));
        return allRacers;
    }

    public Racer getRacer(@NotNull UUID uniqueId) {
        return getRacers().get(uniqueId);
    }

    /**
     * @param player The player to check
     * @return Whether the {@code player} is racing
     */
    @SuppressWarnings("unused")
    public boolean isRacing(Player player) {
        return getRacers().containsKey(player.getUniqueId()) && getRacers().get(player.getUniqueId()).racing;
    }

    //Race start/end/...

    /**
     * Starts the inputted {@code race}, putting every joined racer into their boat.
     * <p>
     * Only players who opted in through the intermission ({@link #joinRace(Player, Race)}) are
     * touched. Nobody is teleported, gamemode-switched or otherwise pulled into the race for
     * simply owning a car — that consent step is the whole point of the intermission.
     * @param race The race to start
     */
    public Result startRace(Race race) {
        YamlConfiguration config = getRaceConfig(race);
        if(config == null) {
            log("§cCouldn't start race " + race.getName() + " because the race config file didn't exist.");
            return Result.ERROR;
        }

        if (race.startingRace || race.hasStarted()) {
            log("§cCouldn't start race " + race.getName() + " because it is already starting or running.");
            return Result.ERROR;
        }

        if (race.racers.isEmpty()) {
            log("§cCouldn't start race " + race.getName() + " because nobody joined it.");
            return Result.WARN;
        }

        closeRegistration(race);
        race.startingRace = true;
        race.participantCount = race.racers.size();
        if (!activeRaces.contains(race)) activeRaces.add(race);

        for (Racer racer : new ArrayList<>(race.racers.values())) {
            Player player = racer.player;
            if (player == null || !player.isOnline()) continue;
            if (racer.car == null) {
                warn("Couldn't start racer " + player.getName() + " for race " + race.getName() + ": no car assigned.");
                continue;
            }

            carManager.spawnCar(race, racer.car, player);

            racer.racing = true;
            racer.checkpointIndex = -1;
            racer.lapCount = 0;
            racer.lapTime = 0;
            racer.startTime = 0;
            racer.race = race;

            if (!race.racing.contains(racer)) race.racing.add(racer);
            liveScoreManager.addRacer(racer);
        }

        final int[] timesRun = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(main, () -> {
            String titleContent;
            Material mat;
            switch (timesRun[0]) {
                case 0 -> {
                    titleContent = "§45";
                    mat = Material.BROWN_STAINED_GLASS;
                }
                case 1 -> {
                    titleContent = "§c4";
                    mat = Material.RED_STAINED_GLASS;
                }
                case 2 -> {
                    titleContent = "§e3";
                    mat = Material.ORANGE_STAINED_GLASS;
                }
                case 3 -> {
                    titleContent = "§22";
                    mat = Material.YELLOW_STAINED_GLASS;
                }
                case 4 -> {
                    titleContent = "§a1";
                    mat = Material.LIME_STAINED_GLASS;
                }
                case 5 -> {
                    titleContent = "§3GO!";
                    mat = Material.GREEN_STAINED_GLASS;
                }
                default -> {
                    titleContent = "§cError";
                    mat = Material.BLACK_STAINED_GLASS;
                }
            }

            Title title = Title.title(Objects.requireNonNull(c(titleContent)), Objects.requireNonNull(c("")));
            if (race.getLights() != null && race.getLights().enabled()) fillRegion(race.world, race.getLights().from(), race.getLights().to(), mat);


            for(UUID racer : race.racers.keySet()) {
                Player p = Bukkit.getPlayer(racer);
                //Title with time before start
                if(p == null) continue;
                p.showTitle(title);

                //Sound
                if(timesRun[0] != 5) p.playSound(Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.NEUTRAL, 1F, 1F));
                else p.playSound(Sound.sound(Key.key("minecraft:block.note_block.pling"), Sound.Source.NEUTRAL, 1F, 2F));

            }
            if(timesRun[0] == 5) {
                race.startingRace = false;
                race.hasRaceStarted = true;
                task[0].cancel();
            }
            timesRun[0]++;
        },0L,20L);
        return Result.SUCCESS;
    }

    /**
     * Opens the registration intermission for {@code race}: broadcasts a join announcement with a
     * clickable button and starts the countdown. Nobody is teleported or gamemode-switched — only
     * players who run {@code /race join} (or click the button) are added.
     *
     * @param sender who gets the admin feedback
     * @param race the race to open
     * @param seconds how long registration stays open
     */
    public void openRegistration(CommandSender sender, Race race, int seconds) {
        if (race.isPreparing()) {
            closeRegistration(race);
            sender.sendMessage(getMessage("race.intermission.cancelled", Messages.formatArguments("race", race.getName())));
            return;
        }

        if (race.hasStarted() || race.isStarting()) {
            sender.sendMessage(getMessage("race.intermission.alreadyRunning", Messages.formatArguments("race", race.getName())));
            return;
        }

        //Clear leftovers from a previous session: any boat still out there, sidebar rows, and
        //stale racer objects with their finished flags.
        for (Racer racer : new ArrayList<>(race.racers.values())) {
            if (racer.car != null) racer.car.destroy();
            racer.racing = false;
            racer.finished = false;
            liveScoreManager.removeRacer(racer);
        }
        race.racers.clear();
        race.racing.clear();
        race.rankings.clear();
        race.currentBestLapTime = Long.MAX_VALUE;
        race.participantCount = 0;
        race.preparingRace = true;
        if (!activeRaces.contains(race)) activeRaces.add(race);
        race.intermissionEndsAt = System.currentTimeMillis() + (seconds * 1000L);
        sender.sendMessage(getMessage("race.intermission.opened", Messages.formatArguments(
                "race", race.getName(),
                "seconds", "" + seconds
        )));

        broadcastJoinAnnouncement(race, seconds);
        scheduleIntermission(race, seconds);
    }

    private void scheduleIntermission(Race race, int seconds) {
        if (race.intermissionTask != null) race.intermissionTask.cancel();

        List<Integer> reminders = new ArrayList<>(INTERMISSION_REMINDERS);
        final int[] elapsed = {0};

        race.intermissionTask = Bukkit.getScheduler().runTaskTimer(main, () -> {
            if (!race.preparingRace) {
                race.intermissionTask = null;
                return;
            }

            elapsed[0]++;
            int remaining = seconds - elapsed[0];

            if (reminders.contains(remaining)) {
                Bukkit.broadcast(getMessage("race.intermission.countdown", Messages.formatArguments(
                        "race", race.getName(),
                        "seconds", "" + remaining,
                        "count", "" + race.racers.size()
                )).append(c(" ")).append(joinButton(race)));
            }

            if (remaining > 0) return;

            if (race.intermissionTask != null) {
                race.intermissionTask.cancel();
                race.intermissionTask = null;
            }

            if (race.racers.size() < INTERMISSION_MIN_RACERS) {
                Bukkit.broadcast(getMessage("race.intermission.closedNoRacers", Messages.formatArguments(
                        "race", race.getName(),
                        "min", "" + INTERMISSION_MIN_RACERS
                )));
                closeRegistration(race);
                race.racers.clear();
                activeRaces.remove(race);
                return;
            }

            if (INTERMISSION_AUTO_START) {
                startRace(race);
            } else {
                //Keep the roster: the admin still has to run /race start by hand.
                Bukkit.broadcast(getMessage("race.intermission.closedAwaitingStart", Messages.formatArguments(
                        "race", race.getName(),
                        "count", "" + race.racers.size()
                )));
                closeRegistration(race);
            }
        }, 20L, 20L);
    }

    private void broadcastJoinAnnouncement(Race race, int seconds) {
        Bukkit.broadcast(getMessage("race.intermission.announce", Messages.formatArguments(
                "race", race.getName(),
                "seconds", "" + seconds,
                "laps", "" + race.getLapCount()
        ), Messages.formatComponentArguments(
                "join", joinButton(race)
        )));
    }

    /** The clickable [Join] component, reused by the announcement and the countdown reminders. */
    private net.kyori.adventure.text.Component joinButton(Race race) {
        return c(getStringMessage("race.intermission.joinButton"))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/race join " + race.getName()))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        c(getStringMessage("race.intermission.joinButtonHover"))
                ));
    }

    /**
     * Stops the intermission countdown without touching any player.
     * Does not remove the race from {@code activeRaces}: the caller decides whether the race is
     * about to start (keep it active) or is being abandoned (caller removes it).
     */
    public void closeRegistration(Race race) {
        race.preparingRace = false;
        if (race.intermissionTask != null) {
            race.intermissionTask.cancel();
            race.intermissionTask = null;
        }
        race.intermissionEndsAt = 0;
    }

    /**
     * Adds {@code player} to {@code race}'s roster during the intermission.
     * <p>
     * This is the only way into a race. The player must own a registered car for the race, since
     * the car supplies both the boat and the starting position. Nothing is moved or changed yet:
     * they stay wherever they are until the race actually starts.
     *
     * @return true if the player is now registered
     */
    public Result joinRace(Player player, Race race) {
        if (!race.isPreparing()) {
            player.sendMessage(getMessage("race.intermission.notOpen", Messages.formatArguments("race", race.getName())));
            return Result.ERROR;
        }

        if (race.racers.containsKey(player.getUniqueId())) {
            player.sendMessage(getMessage("race.intermission.alreadyJoined", Messages.formatArguments("race", race.getName())));
            return Result.WARN;
        }

        Racer other = getRacer(player.getUniqueId());
        if (other != null && other.race != null && other.race != race) {
            player.sendMessage(getMessage("race.intermission.alreadyInOther", Messages.formatArguments(
                    "race", other.race.getName()
            )));
            return Result.ERROR;
        }

        Car car = getCarFor(race, player.getUniqueId());
        if (car == null) {
            player.sendMessage(getMessage("race.intermission.noCar", Messages.formatArguments("race", race.getName())));
            return Result.ERROR;
        }

        Racer racer = new Racer(player);
        racer.player = player;
        racer.car = car;
        racer.race = race;
        racer.racing = false;
        racer.checkpointIndex = -1;
        racer.lapCount = 0;
        racer.returnLocation = player.getLocation().clone();

        race.racers.put(player.getUniqueId(), racer);

        player.sendMessage(getMessage("race.intermission.joined", Messages.formatArguments(
                "race", race.getName(),
                "count", "" + race.racers.size()
        )));
        return Result.SUCCESS;
    }

    /**
     * Removes {@code player} from {@code race}. If the race is already running this also puts
     * their car away and returns them to where they joined from.
     */
    public Result leaveRace(Player player, Race race) {
        Racer racer = race.racers.get(player.getUniqueId());
        if (racer == null) {
            player.sendMessage(getMessage("race.intermission.notJoined", Messages.formatArguments("race", race.getName())));
            return Result.ERROR;
        }

        race.racers.remove(player.getUniqueId());
        race.racing.remove(racer);
        liveScoreManager.removeRacer(racer);
        if (racer.pittingBox != null) pitBoxManager.cancelSession(player, racer.pittingBox);

        //destroy() removes the boat entity and clears Car.boat, so this is safe to call whether
        //or not the racer was already placed in it
        if (racer.car != null) racer.car.destroy();
        if (player.getVehicle() != null) player.getVehicle().remove();

        racer.racing = false;
        teleportBack(player, racer);

        player.sendMessage(getMessage("race.intermission.left", Messages.formatArguments("race", race.getName())));

        //Last racer out during a run ends it, mirroring the quit handling
        if (race.hasStarted() && race.racing.isEmpty()) {
            try {
                race.end(true);
            } catch (Exception ignored) {
                //Ranking messages are best-effort; the race is torn down either way
            }
        }
        return Result.SUCCESS;
    }

    /** Finds the car of {@code owner} in {@code race}, or null if they have none registered. */
    public Car getCarFor(Race race, UUID owner) {
        for (Car car : race.getCars()) {
            if (car.getOwner().equals(owner)) return car;
        }
        return null;
    }

    /** Sends a player back to where they joined the race; world spawn as a fallback. */
    public void teleportBack(Player player, Racer racer) {
        if (!TELEPORT_BACK_AFTER_RACE) return;

        Location target = racer.returnLocation;
        if (target == null || target.getWorld() == null) {
            target = racer.race != null ? racer.race.getWorld().getSpawnLocation() : null;
        }
        if (target == null) return;

        player.teleport(target);
    }

    /**
     * Ends the inputted {@code race}.
     * <p>
     * Only joined racers are cleaned up. Players who own a car for this race but never joined are
     * left completely alone — no gamemode change, no teleport. Nobody's gamemode is touched at all:
     * racers keep whatever mode they were already in on this survival server.
     * @param race The race to end
     */
    public void endRace(Race race) {
        if(!race.hasStarted()) return;
        activeRaces.remove(race);
        race.rankings = new HashMap<>();

        race.currentBestLapTime = Long.MAX_VALUE;

        race.racing = new ArrayList<>();

        race.startingRace = false;
        race.preparingRace = false;
        race.hasRaceStarted = false;

        Bukkit.broadcast(getMessage("race.end", Messages.formatArguments("name", race.getName())));

        for (Racer racer : new ArrayList<>(race.racers.values())) {
            Player owner = racer.player;
            if (owner != null && racer.pittingBox != null) pitBoxManager.cancelSession(owner, racer.pittingBox);

            if (racer.car != null) racer.car.destroy();
            racer.racing = false;

            if (owner != null && owner.isOnline()) {
                if (owner.getVehicle() != null) owner.getVehicle().remove();
                liveScoreManager.removeRacer(racer);
                teleportBack(owner, racer);
            }
        }
        race.racers.clear();

        liveScoreManager.empty();
        race.hasRaceStarted=false;
    }

    /**
     * Fills a cuboid region between two locations with the given material.
     *
     * @param world    the world in which to fill the region
     * @param from     the first corner of the region
     * @param to     the second corner of the region
     * @param material the material to fill the region with
     */
    public void fillRegion(World world, Location from, Location to, Material material) {
        int minX = Math.min(from.getBlockX(), to.getBlockX());
        int minY = Math.min(from.getBlockY(), to.getBlockY());
        int minZ = Math.min(from.getBlockZ(), to.getBlockZ());

        int maxX = Math.max(from.getBlockX(), to.getBlockX());
        int maxY = Math.max(from.getBlockY(), to.getBlockY());
        int maxZ = Math.max(from.getBlockZ(), to.getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setType(material);
                }
            }
        }
    }
}
