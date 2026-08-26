package fr.mattmunich.iceBoatRacing.checkpoint;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.*;

public class CheckpointManager {

    private final Main main;
    private  RaceManager raceManager;

    private final Map<Player,BukkitTask> viewingCheckpoints = new HashMap<>();

    public CheckpointManager(Main main) {
        this.main = main;
    }

    public void setRaceManager(RaceManager raceManager) {
        this.raceManager = raceManager;
    }

    /**
     * Get the checkpoint with a specific ID from a specific race
     * @param race The race that contains the checkpoint
     * @param ID The ID of the checkpoint
     */
    public Checkpoint get(Race race, int ID) {
        return ID < race.getCheckpoints().size() ? race.getCheckpoints().get(ID) : null;
    }

    /**
     * Get all the checkpoints
     * @return a map of all the races as keys and their checkpoint list as values
     */
    public Map<Race, List<Checkpoint>> getAll() {
        Map<Race, List<Checkpoint>> checkpoints = new HashMap<>();
        for (Race race : raceManager.races) {
            checkpoints.put(race, race.getCheckpoints());
        }
        return checkpoints;
    }

    /**
     * Get all the checkpoints in the form of a list
     *@return a list of all the checkpoints
     */
    public List<Checkpoint> getAllNoRaceInfo() {
        List<Checkpoint> checkpoints = new ArrayList<>();
        for (Race race : raceManager.races) {
            checkpoints.addAll(race.getCheckpoints());
        }
        return checkpoints;
    }

    /**
     * Will return a checkpoint found at the location
     * @param loc The location where to look for checkpoints
     * @return Returns in the form of a map of the race and the checkpoint found
     *
     */
    public NearestCheckpointOutput getNearest(Location loc) {
        double bestDistance = Double.MAX_VALUE;
        Race bestRace = null;
        Checkpoint nearest = null;
        Checkpoint.AlternateRoute nearestAlt = null;
        for(Map.Entry<Race, List<Checkpoint>> entry : getAll().entrySet()) {
            for (Checkpoint checkpoint : entry.getValue()) {
                if (loc.distance(checkpoint.getCenter()) < bestDistance) {
                    bestRace = entry.getKey();
                    bestDistance = loc.distance(checkpoint.getCenter());
                    nearest = checkpoint;
                    nearestAlt = null;
                }
                for (Checkpoint.AlternateRoute alt : checkpoint.getAlternates()) {
                    if (loc.distance(alt.getCenter()) < bestDistance) {
                        bestRace = entry.getKey();
                        bestDistance = loc.distance(alt.getCenter());
                        nearestAlt = alt;
                        nearest = null;
                    }
                }
            }
        }
        return new NearestCheckpointOutput(bestRace, nearest, nearestAlt, bestDistance);
    }

    public record NearestCheckpointOutput(Race bestRace, Checkpoint checkpoint, Checkpoint.AlternateRoute alt, Double distance) {}

    public boolean remove(Race race, Checkpoint checkpoint) {
        if (checkpoint == null) return false;

        YamlConfiguration config = race.getConfig();
        if(config == null) {
            main.log("§cCheckpoint " + checkpoint.getId() + " for race " + race.getName() + " wasn't removed, see cause above.");
            return false;
        }

        config.set("checkpoints." + checkpoint.getId(), null);
        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't remove checkpoint " + checkpoint.getId() + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
            return false;
        }
        return race.removeCheckpoint(checkpoint);
    }

    /**
     * Saves a checkpoint for a race
     * @param race The race that the checkpoint is assigned to
     * @param l1 The 1st location of the checkpoint (pos 1)
     * @param l2 The 2nd location of the checkpoint (pos 2)
     * @param type The checkpoint's type (NORMAL or START_FINISH)
     */
    public Checkpoint saveCheckpoint(Race race, Location l1, Location l2, Checkpoint.Type type) {
        Location min = min(l1, l2);
        Location max = max(l1, l2);

        int ID = nextId(race);

        YamlConfiguration config = race.getConfig();
        if(config == null) {
            main.log("§cCheckpoint " + ID + " for race " + race.getName() + " wasn't saved, see cause above.");
            return null;
        }

        String path = "checkpoints." + ID;

        config.set(path + ".shape", Checkpoint.Shape.BOX.name());
        config.set(path + ".world", min.getWorld().getName());
        config.set(path + ".min", serialize(min));
        config.set(path + ".max", serialize(max));
        config.set(path + ".type", type.name());

        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't save checkpoint " + ID + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
            return null;
        }

        Checkpoint checkpoint = new Checkpoint(ID, min, max, type);
        race.addCheckpoint(checkpoint);
        return checkpoint;
    }

    public Checkpoint saveSectorCheckpoint(Race race, Location l1, Location l2) {
        Location min = min(l1, l2);
        Location max = max(l1, l2);

        int ID = nextId(race);
        int sectorID = nextSectorId(race);

        YamlConfiguration config = race.getConfig();
        if(config == null) {
            main.log("§cCheckpoint " + ID + " for race " + race.getName() + " wasn't saved, see cause above.");
            return null;
        }

        String path = "checkpoints." + ID;

        config.set(path + ".shape", Checkpoint.Shape.BOX.name());
        config.set(path + ".world", min.getWorld().getName());
        config.set(path + ".min", serialize(min));
        config.set(path + ".max", serialize(max));
        config.set(path + ".type", Checkpoint.Type.SECTOR.name());
        config.set(path + ".sectorID", sectorID);

        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't save checkpoint " + ID + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
            return null;
        }

        Checkpoint checkpoint = new Checkpoint(ID, sectorID, min, max);
        race.addCheckpoint(checkpoint);
        return checkpoint;
    }

    /**
     * Batch-saves a set of auto-traced oriented-plane checkpoints for a race in a single config write.
     * IDs on the passed-in checkpoints are ignored and reassigned sequentially starting at the race's
     * current max ID + 1. Any SECTOR-type checkpoints in the batch get sequential sector IDs continuing
     * from the race's existing sectors.
     * @param race the race to attach the checkpoints to
     * @param planeCheckpoints the generated plane checkpoints (from CheckpointGeometry/AutoTraceManager), in track order
     * @return true if the save succeeded
     */
    public boolean saveTracedCheckpoints(Race race, List<Checkpoint> planeCheckpoints) {
        if (planeCheckpoints.isEmpty()) return false;

        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cAuto-traced checkpoints for race " + race.getName() + " weren't saved, see cause above.");
            return false;
        }

        int ID = nextId(race);
        int sectorCounter = nextSectorId(race);
        List<Checkpoint> toAdd = new ArrayList<>();

        for (Checkpoint generated : planeCheckpoints) {
            Location center = generated.getCenter();
            Vector normal = generated.getNormal();
            Checkpoint.Type type = generated.getType();

            String path = "checkpoints." + ID;
            config.set(path + ".shape", Checkpoint.Shape.PLANE.name());
            config.set(path + ".world", center.getWorld().getName());
            config.set(path + ".center", serializePrecise(center));
            config.set(path + ".normal", serializeVector(normal));
            config.set(path + ".halfWidth", generated.getHalfWidth());
            config.set(path + ".halfHeight", generated.getHalfHeight());
            config.set(path + ".type", type.name());

            Checkpoint checkpoint = new Checkpoint(ID, center, normal, generated.getHalfWidth(), generated.getHalfHeight(), type);

            if (type == Checkpoint.Type.SECTOR) {
                checkpoint.setSectorID(sectorCounter);
                config.set(path + ".sectorID", sectorCounter);
                sectorCounter++;
            }

            toAdd.add(checkpoint);
            ID++;
        }

        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't save auto-traced checkpoints for race " + race.getName() + " because the it's config threw an error on saving. ", e);
            return false;
        }

        for (Checkpoint checkpoint : toAdd) race.addCheckpoint(checkpoint);
        Vector normal = race.getCheckpoints().getFirst().getNormal();
        float yaw = (float) Math.toDegrees(Math.atan2(-normal.getX(), normal.getZ()));
        race.setStartRotation(yaw);
        return true;
    }

    /**
     * Adds an alternate route (bypass gate) to an existing checkpoint — crossing this gate counts
     * as crossing the target checkpoint, without needing to physically cross its primary plane.
     * Used for e.g. a stands/pit lane that rejoins the track past the start/finish line.
     * @param race the race the checkpoint belongs to
     * @param target the checkpoint to attach the alternate route to
     * @param center center of the alternate gate
     * @param normal direction of travel through the alternate gate
     * @param halfWidth half-width of the alternate gate
     * @param halfHeight half-height of the alternate gate
     * @return true if the save succeeded
     */
    public boolean saveAlternateRoute(Race race, Checkpoint target, Location center, Vector normal, double halfWidth, double halfHeight) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cAlternate route for checkpoint " + target.getId() + " on race " + race.getName() + " wasn't saved, see cause above.");
            return false;
        }

        int altIndex = target.getAlternates().size();
        String path = "checkpoints." + target.getId() + ".alternates." + altIndex;

        config.set(path + ".center", serializePrecise(center));
        config.set(path + ".normal", serializeVector(normal));
        config.set(path + ".halfWidth", halfWidth);
        config.set(path + ".halfHeight", halfHeight);

        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't save alternate route for checkpoint " + target.getId() + " on race " + race.getName() + " because the it's config threw an error on saving. ", e);
            return false;
        }

        target.addAlternate(center, normal, halfWidth, halfHeight);
        return true;
    }

    /**
     * Deletes every checkpoint for a race in one go — used by /checkpoint clearAll to reset a
     * track before a fresh auto-trace recording, instead of removing checkpoints one at a time.
     * @return true if the clear succeeded
     */
    public boolean clearAllCheckpoints(Race race) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cCheckpoints for race " + race.getName() + " weren't cleared, see cause above.");
            return false;
        }

        config.set("checkpoints", null);

        try {
            race.saveConfig();
        } catch (IOException e) {
            main.err("Couldn't clear checkpoints for race " + race.getName() + " because the it's config threw an error on saving. ", e);
            return false;
        }

        race.clearCheckpoints();
        return true;
    }

    public void loadRaceCheckpoints(Race race) {
        YamlConfiguration config = race.getConfig();
        if(config == null) {
            main.log("§cCheckpoints for race " + race.getName() + " were not loaded, see cause above.");
            return;
        }

        race.clearCheckpoints();
        if (!config.isConfigurationSection("checkpoints")) {
            main.log("§eNo checkpoints to load for race " + race.getName());
            return;
        }

        for (String key : Objects.requireNonNull(
                config.getConfigurationSection("checkpoints")
        ).getKeys(false)) {

            int ID = Integer.parseInt(key);
            String worldName = config.getString("checkpoints." + key + ".world");
            String shapeString = config.getString("checkpoints." + key + ".shape");
            Checkpoint.Shape shape = shapeString == null ? Checkpoint.Shape.BOX : Checkpoint.Shape.valueOf(shapeString);

            String typeString = config.getString("checkpoints." + key + ".type");
            Checkpoint.Type type = typeString == null ? Checkpoint.Type.NORMAL : Checkpoint.Type.valueOf(typeString);

            Checkpoint checkpoint = null;

            if (shape == Checkpoint.Shape.PLANE) {
                Location center = deserializePrecise(worldName, config.getString("checkpoints." + key + ".center"));
                Vector normal = deserializeVector(config.getString("checkpoints." + key + ".normal"));
                double halfWidth = config.getDouble("checkpoints." + key + ".halfWidth");
                double halfHeight = config.getDouble("checkpoints." + key + ".halfHeight");

                if (center != null && normal != null) {
                    checkpoint = new Checkpoint(ID, center, normal, halfWidth, halfHeight, type);
                    if (type == Checkpoint.Type.SECTOR) {
                        checkpoint.setSectorID(config.getInt("checkpoints." + key + ".sectorID"));
                    }
                }
            } else {
                Location min = deserialize(worldName, config.getString("checkpoints." + key + ".min"));
                Location max = deserialize(worldName, config.getString("checkpoints." + key + ".max"));

                if (min != null && max != null) {
                    if (type == Checkpoint.Type.SECTOR) {
                        int sectorID = config.getInt("checkpoints." + key + ".sectorID");
                        checkpoint = new Checkpoint(ID, sectorID, min, max);
                    } else if (type == Checkpoint.Type.START_FINISH) {
                        checkpoint = new Checkpoint(ID, min, max, Checkpoint.Type.START_FINISH);
                    } else {
                        checkpoint = new Checkpoint(ID, min, max);
                    }
                }
            }

            if (checkpoint == null) continue;

            if (config.isConfigurationSection("checkpoints." + key + ".alternates")) {
                for (String altKey : Objects.requireNonNull(
                        config.getConfigurationSection("checkpoints." + key + ".alternates")
                ).getKeys(false)) {
                    String altPath = "checkpoints." + key + ".alternates." + altKey;
                    Location altCenter = deserializePrecise(worldName, config.getString(altPath + ".center"));
                    Vector altNormal = deserializeVector(config.getString(altPath + ".normal"));
                    double altHW = config.getDouble(altPath + ".halfWidth");
                    double altHH = config.getDouble(altPath + ".halfHeight");

                    if (altCenter != null && altNormal != null) {
                        checkpoint.addAlternate(altCenter, altNormal, altHW, altHH);
                    }
                }
            }

            race.addCheckpoint(checkpoint);
        }

        race.getCheckpoints().sort(Comparator.comparingInt(Checkpoint::getId));
    }

    /**
     * Toggles the particle preview on/off. While on, redraws every second until toggled off,
     * the session ends, or the preview list changes shape drastically.
     * @return true if the preview is now ON, false if it's now OFF (or couldn't start)
     */
    public boolean toggleViewCheckpoints(Player p, Race race) {
        BukkitTask existing = viewingCheckpoints.remove(p);
        if (existing != null) {
            existing.cancel();
            return false;
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(main, () -> {
            if (race.getCheckpoints().isEmpty()) {
                BukkitTask t = viewingCheckpoints.remove(p);
                if (t != null) t.cancel();
                return;
            }
            for (Checkpoint c : race.getCheckpoints()) drawCheckpointOutline(c);
        }, 0L, 20L);

        viewingCheckpoints.put(p, task);
        return true;
    }

    private void drawCheckpointOutline(Checkpoint c) {
        Location center = c.getCenter();
        Vector right = c.getRight();
        Vector up = c.getUp();
        double hw = c.getHalfWidth();
        double hh = c.getHalfHeight();

        Vector rightHW = right.clone().multiply(hw);
        Vector upHH = up.clone().multiply(hh);

        Vector topRight = center.toVector().add(rightHW).add(upHH);
        Vector bottomRight = center.toVector().add(rightHW).subtract(upHH);
        Vector bottomLeft = center.toVector().subtract(rightHW).subtract(upHH);
        Vector topLeft = center.toVector().subtract(rightHW).add(upHH);

        Particle particle = switch (c.getType()) {
            case START_FINISH -> Particle.FLAME;
            case SECTOR -> Particle.HAPPY_VILLAGER;
            default -> Particle.END_ROD;
        };

        drawLine(center.getWorld(), particle, topRight, bottomRight);
        drawLine(center.getWorld(), particle, bottomRight, bottomLeft);
        drawLine(center.getWorld(), particle, bottomLeft, topLeft);
        drawLine(center.getWorld(), particle, topLeft, topRight);

        for (Checkpoint.AlternateRoute alt : c.getAlternates()) {
            Location altCenter = alt.getCenter();
            Vector altRight = alt.getRight();
            Vector altUp = alt.getUp();
            double altHalfWidth = alt.getHalfWidth();
            double altHalfHeight = alt.getHalfHeight();

            Vector altRightHW = altRight.clone().multiply(altHalfWidth);
            Vector altUpHH = altUp.clone().multiply(altHalfHeight);

            Vector altTopRight = altCenter.toVector().add(altRightHW).add(altUpHH);
            Vector altBottomRight = altCenter.toVector().add(altRightHW).subtract(altUpHH);
            Vector altBottomLeft = altCenter.toVector().subtract(altRightHW).subtract(altUpHH);
            Vector altTopLeft = altCenter.toVector().subtract(altRightHW).add(altUpHH);

            Particle altParticle = Particle.CLOUD;

            drawLine(altCenter.getWorld(), altParticle, altTopRight, altBottomRight);
            drawLine(altCenter.getWorld(), altParticle, altBottomRight, altBottomLeft);
            drawLine(altCenter.getWorld(), altParticle, altBottomLeft, altTopLeft);
            drawLine(altCenter.getWorld(), altParticle, altTopLeft, altTopRight);
        }
    }

    private void drawLine(org.bukkit.World world, Particle particle, Vector from, Vector to) {
        double length = from.distance(to);
        int steps = Math.max(1, (int) (length / 0.4));
        Vector step = to.clone().subtract(from).multiply(1.0 / steps);

        Vector cursor = from.clone();
        for (int i = 0; i <= steps; i++) {
            world.spawnParticle(particle, cursor.getX(), cursor.getY(), cursor.getZ(), 1, 0, 0, 0, 0);
            cursor.add(step);
        }
    }

    private int nextId(Race race) {
        return race.getCheckpoints().stream()
                .mapToInt(Checkpoint::getId)
                .max()
                .orElse(0) + 1;
    }

    private int nextSectorId(Race race) {
        int max = 0;
        for (Checkpoint check : race.getCheckpoints()) {
            if (check.getType() == Checkpoint.Type.SECTOR) {
                max = Math.max(max, check.getSectorID());
            }
        }
        return max + 1;
    }

    public int getNearestCheckpoint(Race race, Location point) {
        List<Checkpoint> checkpoints = race.getCheckpoints();
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (Checkpoint c : checkpoints) {
            double d = c.getCenter().distance(point);
            if (d < bestDist) {
                bestDist = d;
                best = c.getId();
            }
        }
        return best;
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

    /**
     * Full double-precision location serialization, used for plane checkpoint centers
     * (unlike box corners, these come from resampled/recentered track points and shouldn't be
     * snapped to blocks).
     */
    private String serializePrecise(Location loc) {
        return loc.getX() + "," + loc.getY() + "," + loc.getZ();
    }

    private Location deserializePrecise(String worldName, String value) {
        if (value == null) return null;
        World world = Bukkit.getWorld(worldName);
        String[] parts = value.split(",");
        return new Location(
                world,
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2])
        );
    }

    private String serializeVector(Vector v) {
        return v.getX() + "," + v.getY() + "," + v.getZ();
    }

    private Vector deserializeVector(String value) {
        if (value == null) return null;
        String[] parts = value.split(",");
        return new Vector(
                Double.parseDouble(parts[0]),
                Double.parseDouble(parts[1]),
                Double.parseDouble(parts[2])
        );
    }

    public int count() {
        return getAll().size();
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
}