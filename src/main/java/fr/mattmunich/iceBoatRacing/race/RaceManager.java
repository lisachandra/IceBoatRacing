package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
import fr.mattmunich.iceBoatRacing.cars.Car;
import fr.mattmunich.iceBoatRacing.cars.CarManager;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointManager;
import fr.mattmunich.iceBoatRacing.pitbox.PitBoxManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.c;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class RaceManager {

    private final Main main;
    private final CarManager carManager;
    private final CheckpointManager checkpointManager;
    public final PitBoxManager pitBoxManager;
    public final List<Race> races = new ArrayList<>();
    public final List<Race> activeRaces = new ArrayList<>();

    /**
     * A map that contains the races that failed to load on startup. <br/>
     * Format: {@code Map<worldName, raceName>}
     */
    public final Map<String, String> unloadedRaces = new HashMap<>();

    public RaceManager(Main main, CarManager carManager, CheckpointManager checkpointManager, PitBoxManager pitBoxManager) {
        this.main = main;
        this.carManager = carManager;
        this.checkpointManager = checkpointManager;
        this.pitBoxManager = pitBoxManager;
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
                main.err("Couldn't save race " + race.getName(),e);
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

    public Map<UUID, RaceData> getRacers() {
        Map<UUID, RaceData> allRacers = new HashMap<>();
        getRaces().forEach(race -> allRacers.putAll(race.racers));
        return allRacers;
    }

    public RaceData getRacer(@NotNull UUID uniqueId) {
        return getRacers().get(uniqueId);
    }

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
        }

        final int[] timesRun = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(main, () -> {
            int fromX = config.getInt("lights.from.x",123456789);
            int fromY = config.getInt("lights.from.y",123456789);
            int fromZ = config.getInt("lights.from.z",123456789);
            int toX = config.getInt("lights.to.x",123456789);
            int toY = config.getInt("lights.to.y",123456789);
            int toZ = config.getInt("lights.to.z",123456789);
            boolean raceLightsEnabled = config.getBoolean("lights.enabled");
            if(fromX == 123456789 || fromY == 123456789 || fromZ == 123456789 || toX == 123456789 || toY == 123456789 || toZ == 123456789) raceLightsEnabled = false;
            String titleContent;
            switch (timesRun[0]) {
                case 0 -> {
                    titleContent = "§45";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.BROWN_STAINED_GLASS);
                }
                case 1 -> {
                    titleContent = "§c4";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.RED_STAINED_GLASS);
                }
                case 2 -> {
                    titleContent = "§e3";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.ORANGE_STAINED_GLASS);
                }
                case 3 -> {
                    titleContent = "§22";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.YELLOW_STAINED_GLASS);
                }
                case 4 -> {
                    titleContent = "§a1";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.LIME_STAINED_GLASS);
                }
                case 5 -> {
                    titleContent = "§3GO!";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.GREEN_STAINED_GLASS);
                }
                default -> {
                    titleContent = "§cError";
                    if (raceLightsEnabled) fillRegion(race.world, fromX, fromY, fromZ, toX, toY, toZ, Material.BLACK_STAINED_GLASS);
                }
            }

            Title title = Title.title(Objects.requireNonNull(c(titleContent)), Objects.requireNonNull(c("")));


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
        Map<UUID, RaceData> racers = race.racers;
        Player owner = Bukkit.getPlayer(car.getOwner());
        if (owner == null) return;
        RaceData racer = new RaceData(owner);

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

        main.liveSidebar.getScore(owner.getName()).resetScore();
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
        sender.sendMessage(Messages.getMessage("race.prepare", Messages.formatArguments("name", race.getName())));
        race.preparingRace = true;
        activeRaces.add(race);
        for (Car car : race.getCars()) {
            Player owner = Bukkit.getPlayer(car.getOwner());
            if (owner == null) continue;

            prepareRacer(race, car);
        }
    }

    public void cancelPrepareRace(CommandSender sender, Race race) {
        sender.sendMessage(Messages.getMessage("race.cancelPrepare", Messages.formatArguments("name", race.getName())));
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

        Bukkit.broadcast(Messages.getMessage("race.end", Messages.formatArguments("name", race.getName())));
        for (Car car : race.getCars()) {
            Player owner = Bukkit.getPlayer(car.getOwner());
            if (owner == null) continue;
            RaceData data = race.racers.get(owner.getUniqueId());
            if (data != null && data.pittingBox != null) pitBoxManager.cancelSession(owner, data.pittingBox);

            car.destroy();
            race.racers.remove(owner.getUniqueId());

            main.liveSidebar.getScore(owner.getName()).resetScore();
        }

        if(!race.racers.isEmpty()) {
            race.racing.clear();
        }

        race.hasRaceStarted=false;
    }

    private void fillRegion(World world,
                           int x1, int y1, int z1,
                           int x2, int y2, int z2,
                           Material material) {

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }
}
