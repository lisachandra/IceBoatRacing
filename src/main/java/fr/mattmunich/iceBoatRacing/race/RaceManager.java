package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.LiveScoreManager;
import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
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

import static fr.mattmunich.iceBoatRacing.Main.SPECTATE_ON_FINISH;
import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;

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
            main.warn("No races found, therefore none were saved.");
        } else {
            for (Race race : new ArrayList<>(races)) {
                try {
                    race.saveConfig();
                } catch (IOException e) {
                    main.err("Could not save race " + race.getName(), e);
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
        main.log("Loading all races...");
        int loadedRaces = 0;

        if(!races.isEmpty()) {
            races.forEach(race -> {try { race.end(false); } catch (Exception ignored) {}});
            races.clear();
        }
        File racesFolder = new File(main.getDataFolder(), "races");
        if (!racesFolder.exists()) return;

        for (File file : Objects.requireNonNull(racesFolder.listFiles())) {
            if (!file.getName().endsWith(".yml")) {
                main.warn("File " + file.getName() + " wasn't loaded as a race, as it is not a .yml");
                continue;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String name = config.getString("name");

            if(name == null) {
                main.warn("Ignored " + file.getName() + "because it didn't contain any name for the race.");
                continue;
            }

            main.log("Loading race " + name);
            String worldName = Objects.requireNonNull(config.getString("world"));
            World world = Bukkit.getWorld(worldName);
            if(world == null) {
                main.severe("Couldn't load race " + name + " because it's world wasn't found.");
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
                main.warn(race.getName() + "'s lap count wasn't found, therefore was set to the default value : 10" );
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
            main.log("Race " + name + " has been loaded");
            loadedRaces++;
        }
        main.log("Loaded " + loadedRaces + " race(s)!");
    }

    /**
     * Loads and initializes all the races from file
     */
    public boolean loadRace(String raceName) {
        main.log("Loading race " + raceName + "...");

        File racesFolder = new File(main.getDataFolder(), "races");
        if (!racesFolder.exists()) {
            main.severe("The race folder wasn't found, therefore " + raceName + " could not be loaded.");
            return false;
        }

        File file = new File(racesFolder, raceName + ".yml");
        if (!file.exists()) {
            main.severe("The race " + raceName + "'s file wasn't found and therefore could not be loaded.");
            return false;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String name = config.getString("name");

        if(name == null) {
            main.severe("Race " + file.getName() + " wasn't loaded because it didn't contain any name.");
            return false;
        }

        String worldName = Objects.requireNonNull(config.getString("world"));
        World world = Bukkit.getWorld(worldName);
        if(world == null) {
            main.severe("Couldn't load race " + name + " because it's world wasn't found.");
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
            main.warn(race.getName() + "'s lap count wasn't found, therefore was set to the default value : 10" );
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

        main.log("Loaded race " + raceName + " successfully!");
        return true;
    }

    /**
     * Updates all the races from file. (Called on reload)
     */
    public void updateAllRaces() {
        main.log("Updating all races...");
        int loadedRaces = 0;

        if(!races.isEmpty()) {
            for (Race race : new ArrayList<>(races)) {
                boolean success = race.update();
                if(success) main.log("Race " + race.getName() + " has been updated");
                else main.warn("Could not update race " + race.getName() + ", see error above.");
                loadedRaces++;
                try { race.end(false); } catch (Exception ignored) {}
            }
            main.log("Updated " + loadedRaces + " race(s)!");
        } else {
            main.warn("No races updated (none were found).");
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
    public Race createRace(Race race) {
        main.log("Creating race " + race.getName());
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
            main.log("Race " + race.getName() + " has been saved");
        } catch (IOException e) {
            main.err("Couldn't create race " + race.getName(),e);
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
                main.warn("Race " + race.getName() + " wasn't updated because it's config file does not exist.");
                return false;
            }

            YamlConfiguration config;
            try {
                config = YamlConfiguration.loadConfiguration(raceFile);
            } catch (Exception e) {
                main.err("Couldn't load config file for race " + race.getName(),e);
                return false;
            }

            try {
                config.save(raceFile);
            } catch (IOException e) {
                main.err("Couldn't save race " + race.getName(),e);
                return false;
            }

            int lapCount = config.getInt("lapCount");
            if (lapCount <= 0) {
                lapCount = 10;
                main.warn(race.getName() + "'s lap count wasn't found, therefore was set to the default value : 10" );
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
            main.err("An error occurred when updating the race " + race.getName(), e);
            return false;
        }
    }

    /**
     * Deletes the inputted {@code race}
     * @param race The race to delete
     * @return Whether the operation succeeded
     */
    public boolean deleteRace(Race race) {
        main.log("Deleting race " + race.getName());
        try { race.end(false); } catch (Exception ignored) {}

        File raceFile = new File(main.getDataFolder(), "races/" + race.getName().replace(" ","_") + ".yml");
        boolean deleted = raceFile.delete();

        races.remove(race);

        if(deleted) main.log("Race " + race.getName() + " has been deleted");
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
            main.log("§cCouldn't get config for race " + race.getName() + " because the race folder didn't exist.");
            return null;
        }

        File file = new File(racesFolder, race.getName().replace(" ","_") + ".yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!file.exists()) {
            main.log("§cCouldn't get config for race " + race.getName() + " because the race config file didn't exist.");
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
            main.log("§cCouldn't get config for race " + race.getName() + " because the race folder didn't exist.");
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
    public boolean isRacing(Player player) {
        return getRacers().containsKey(player.getUniqueId()) && getRacers().get(player.getUniqueId()).racing;
    }

    //Race start/end/...

    /**
     * Starts the inputted {@code race}.
     * @param race The race to start
     */
    public void startRace(Race race) {
        YamlConfiguration config = getRaceConfig(race);
        if(config == null) {
            main.log("§cCouldn't start race " + race.getName() + " because the race config file didn't exist.");
            return;
        }

        race.startingRace = true;
        activeRaces.add(race);
        if(!race.preparingRace) {
            for (Car car : race.getCars()) {
                prepareRacer(race, car);
            }
        } else {
            race.preparingRace = false;
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
                race.startingRace=false;
                race.hasRaceStarted=true;
                task[0].cancel();
            }
            timesRun[0]++;
        },0L,20L);
    }

    /**
     * Prepares a racer for the inputted {@code race}
     * @param race The race to prepare the racer for
     * @param car The racer's car
     */
    public void prepareRacer(Race race, Car car) {
        Map<UUID, Racer> racers = race.racers;
        Player owner = Bukkit.getPlayer(car.getOwner());
        if (owner == null) return;
        Racer racer = new Racer(owner);

        owner.setGameMode(GameMode.ADVENTURE);
        carManager.spawnCar(race, car, owner);

        racer.racing = true;
        racer.car = car;
        racer.checkpointIndex = -1;
        racer.lapCount = 0;
        racer.lapTime = 0;
        racer.startTime = 0;
        racer.race = race;
        racer.player = owner;

        liveScoreManager.removeRacer(owner);
        liveScoreManager.addRacer(racer);
        race.racing.add(racer);
        racers.put(owner.getUniqueId(), racer);
    }

    /**
     * Toggles the preparation mode for the {@code race}
     * @param sender Who the feedback will be sent to
     * @param race The race to toggle the preparation for
     */
    public void togglePrepareRace(CommandSender sender, Race race) {
        if(race.preparingRace) cancelPrepareRace(sender, race);
        else prepareRace(sender, race);
    }

    public void prepareRace(CommandSender sender, Race race) {
        sender.sendMessage(getMessage("race.prepare", Messages.formatArguments("name", race.getName())));
        race.preparingRace = true;
        activeRaces.add(race);
        for (Car car : race.getCars()) {
            Player owner = Bukkit.getPlayer(car.getOwner());
            if (owner == null) continue;

            prepareRacer(race, car);
        }
    }

    public void cancelPrepareRace(CommandSender sender, Race race) {
        sender.sendMessage(getMessage("race.cancelPrepare", Messages.formatArguments("name", race.getName())));
        race.preparingRace = false;
        activeRaces.remove(race);
        for (Car car : race.getCars()) {
            Player owner = Bukkit.getPlayer(car.getOwner());
            if (owner == null) continue;
            try {
                car.getBoat().remove();
            } catch (Exception ignored) { continue; }
            owner.teleport(race.getWorld().getSpawnLocation());
            race.racers.remove(owner.getUniqueId());
        }
    }

    /**
     * Ends the inputted {@code race}
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
        for (Car car : race.getCars()) {
            Player owner = Bukkit.getPlayer(car.getOwner());
            if (owner == null) continue;
            if (SPECTATE_ON_FINISH) owner.setGameMode(GameMode.SPECTATOR);
            Racer data = race.racers.get(owner.getUniqueId());
            if (data != null && data.pittingBox != null) pitBoxManager.cancelSession(owner, data.pittingBox);

            car.destroy();
            race.racers.remove(owner.getUniqueId());
        }

        liveScoreManager.clear();
        if(!race.racers.isEmpty()) {
            race.racing.clear();
        }

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
