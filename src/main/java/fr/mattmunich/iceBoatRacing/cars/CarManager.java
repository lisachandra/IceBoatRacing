package fr.mattmunich.iceBoatRacing.cars;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.*;

public class CarManager {
    private RaceManager raceManager;

    public CarManager() {}

    public void setRaceManager(RaceManager raceManager) {
        this.raceManager = raceManager;
    }

    public @Nullable Car get(Race race, int id) {
        return race.getCars().stream()
                .filter(c -> c.getId() == id)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all the checkpoints
     * @return a map of all the races as keys and their checkpoint list as values
     */
    public Map<Race, List<Car>> getAll() {
        Map<Race, List<Car>> cars = new HashMap<>();
        for (Race race : raceManager.races) {
            cars.put(race, race.getCars());
        }
        return cars;
    }

    public boolean changeOwner(Race race, Car car, UUID newOwner) {
        car.setOwner(newOwner);

        YamlConfiguration config = race.getConfig();
        if(config == null) return false;

        String path = "cars." + car.getId() + ".owner";
        config.set(path, newOwner.toString());

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't rename car " + car.getId() + " for race " + race.getName(),e);
            return false;
        }

        Player p = Bukkit.getPlayer(newOwner);
        if (p != null && race.racers.containsKey(p.getUniqueId())) {
            race.racers.get(p.getUniqueId()).car = car;
        }
        return true;
    }

    public void spawnCar(Race race, Car car, Player player) {
        Location startLoc = car.getStartingLocation().clone();
        Material boatMat = car.getBoatMaterial();
        player.teleport(startLoc);

        // Spawn boat
        Boat boat = (Boat) startLoc.getWorld().spawnEntity(startLoc, boatEntityFromMaterial(boatMat));

        Component customName;

        if(car.getCustomName() == null) customName = c("Race car");
        else customName = c(car.getCustomName());

        boat.customName(customName);

        Location boatLocation = boat.getLocation();

        boatLocation.setRotation(race.getStartRotation(),0F);
        boat.teleport(boatLocation);

        boat.setInvulnerable(true);

        boat.addPassenger(player);
        car.setBoat(boat);
    }

    public void spawnCar(Race race, Car car, Player player, Location spawnLocation) {
        Material boatMat = car.getBoatMaterial();
        player.teleport(spawnLocation);

        //No double cars
        if(car.getBoat() != null) car.destroy();


        // Spawn boat
        Boat boat = (Boat) spawnLocation.getWorld().spawnEntity(spawnLocation, boatEntityFromMaterial(boatMat));

        Component customName;

        if(car.getCustomName() == null) customName = c("Race car");
        else customName = c(car.getCustomName());

        boat.customName(customName);

        Location boatLocation = boat.getLocation();

        float startRotation = car.getStartingLocation() == spawnLocation
                            ? race.getStartRotation()
                            : spawnLocation.getYaw();
        boatLocation.setRotation(startRotation,0F);
        boat.teleport(boatLocation);

        boat.setInvulnerable(true);

        boat.addPassenger(player);
        car.setBoat(boat);
    }

    public void saveCar(Race race, UUID owner, Location startingLocation, ItemStack boatItem) {

        int id = count(race);
        String path = "cars." + id;
        String customName = s(boatItem.getItemMeta().customName()).isEmpty() ? "Race car" : s(boatItem.getItemMeta().customName());

        YamlConfiguration config = race.getConfig();

        config.set(path + ".world", startingLocation.getWorld().getName());
        config.set(path + ".startingLocation", serialize(startingLocation));
        config.set(path + ".owner", owner.toString());
        config.set(path + ".boatMaterial", boatItem.getType().name());
        config.set(path + ".boatCustomName", customName);

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't save car " + id + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
        }

        race.addCar(new Car(id, owner, startingLocation, boatItem.getType(), s(boatItem.getItemMeta().customName())));
    }

    public void loadCars(Race race) {
        race.clearCars();
        
        YamlConfiguration config = race.getConfig();

        if (!config.isConfigurationSection("cars")) return;

        for (String key : Objects.requireNonNull(
                config.getConfigurationSection("cars")
        ).getKeys(false)) {

            int id = Integer.parseInt(key);
            String path = "cars." + id;

            String worldName = config.getString(path + ".world");
            Location loc = deserialize(
                    worldName,
                    config.getString(path + ".startingLocation")
            );

            if (loc == null) continue;

            UUID owner = UUID.fromString(
                    Objects.requireNonNull(config.getString(path + ".owner"))
            );

            Material material = Material.valueOf(
                    config.getString(path + ".boatMaterial", "OAK_BOAT")
            );

            String customName = config.getString(path + ".boatCustomName");

            race.addCar(new Car(id, owner, loc, material, customName));
        }
    }

    public int count(Race race) {
        return race.getCars().size();
    }

    private String serialize(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private Location deserialize(String worldName, String value) {
        if (value == null) return null;

        World world = Bukkit.getWorld(worldName);
//        if (world == null) return null;

        String[] parts = value.split(",");
        return new Location(
                world,
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2])
        );
    }

    public boolean remove(Race race, Car car) {
        if (car == null) return false;
        if (race == null) return false;

        YamlConfiguration config = race.getConfig();
        if(config == null) return false;

        config.set("cars." + car.getId(), null);
        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't remove car " + car.getId() + " for race " + race.getName(),e);
            return false;
        }
        return race.removeCar(car);
    }


    private EntityType boatEntityFromMaterial(Material material) {
        return switch (material) {
            //case OAK_BOAT -> EntityType.OAK_BOAT;
            case SPRUCE_BOAT -> EntityType.SPRUCE_BOAT;
            case BIRCH_BOAT -> EntityType.BIRCH_BOAT;
            case JUNGLE_BOAT -> EntityType.JUNGLE_BOAT;
            case ACACIA_BOAT -> EntityType.ACACIA_BOAT;
            case DARK_OAK_BOAT -> EntityType.DARK_OAK_BOAT;
            case MANGROVE_BOAT -> EntityType.MANGROVE_BOAT;
            case CHERRY_BOAT -> EntityType.CHERRY_BOAT;
            case BAMBOO_RAFT -> EntityType.BAMBOO_RAFT;
            case PALE_OAK_BOAT -> EntityType.PALE_OAK_BOAT;
            case BAMBOO_CHEST_RAFT -> EntityType.BAMBOO_CHEST_RAFT;
            case ACACIA_CHEST_BOAT -> EntityType.ACACIA_CHEST_BOAT;
            case BIRCH_CHEST_BOAT -> EntityType.BIRCH_CHEST_BOAT;
            case CHERRY_CHEST_BOAT -> EntityType.CHERRY_CHEST_BOAT;
            case DARK_OAK_CHEST_BOAT -> EntityType.DARK_OAK_CHEST_BOAT;
            case JUNGLE_CHEST_BOAT -> EntityType.JUNGLE_CHEST_BOAT;
            case MANGROVE_CHEST_BOAT -> EntityType.MANGROVE_CHEST_BOAT;
            case OAK_CHEST_BOAT -> EntityType.OAK_CHEST_BOAT;
            case PALE_OAK_CHEST_BOAT -> EntityType.PALE_OAK_CHEST_BOAT;
            case SPRUCE_CHEST_BOAT -> EntityType.SPRUCE_CHEST_BOAT;
            default -> EntityType.OAK_BOAT;
        };
    }
}
