package fr.mattmunich.iceBoatRacing.checkpoint;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.err;

public class CheckpointManager {

    private final Main main;
    private  RaceManager raceManager;

    public CheckpointManager(Main main) {
        this.main = main;
    }

    public void setRaceManager(RaceManager raceManager) {
        this.raceManager = raceManager;
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
                if (!sameWorld(loc, checkpoint.getCenter())) continue;

                if (loc.distance(checkpoint.getCenter()) < bestDistance) {
                    bestRace = entry.getKey();
                    bestDistance = loc.distance(checkpoint.getCenter());
                    nearest = checkpoint;
                    nearestAlt = null;
                }
                for (Checkpoint.AlternateRoute alt : checkpoint.getAlternates()) {
                    if (!sameWorld(loc, alt.getCenter())) continue;

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

    /** {@link Location#distance} throws across worlds, and races can live in different ones. */
    private static boolean sameWorld(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld());
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
            err("Couldn't remove checkpoint " + checkpoint.getId() + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
            return false;
        }

        race.removeCheckpoint(checkpoint);

        //Removing an ID leaves a gap, and a gap makes the race impossible to finish because the
        //next checkpoint is looked up by `index + 1`. Close it right away, unless a race is
        //currently using these IDs (renumbering under a racer would shift the target mid-lap).
        if (!race.isPreparing() && !race.isStarting() && !race.hasStarted()) {
            renumber(race);
        }
        return true;
    }

    /**
     * Saves a checkpoint for a race
     * @param race The race that the checkpoint is assigned to
     * @param l1 The 1st location of the checkpoint (pos 1)
     * @param l2 The 2nd location of the checkpoint (pos 2)
     * @param type The checkpoint's type (NORMAL or START_FINISH)
     */
    public Checkpoint saveCheckpoint(Race race, Location l1, Location l2, Checkpoint.Type type) {
        int ID = nextId(race);

        YamlConfiguration config = race.getConfig();
        if(config == null) {
            main.log("§cCheckpoint " + ID + " for race " + race.getName() + " wasn't saved, see cause above.");
            return null;
        }

        writeBox(config, ID, l1, l2, type);

        if (type == Checkpoint.Type.SECTOR) {
            config.set("checkpoints." + ID + ".sectorID", nextSectorId(race));
        }

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't save checkpoint " + ID + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
            return null;
        }

        Location min = min(l1, l2);
        Location max = max(l1, l2);
        Checkpoint checkpoint = type == Checkpoint.Type.SECTOR
                ? new Checkpoint(ID, config.getInt("checkpoints." + ID + ".sectorID"), min, max)
                : new Checkpoint(ID, min, max, type);
        race.addCheckpoint(checkpoint);
        return checkpoint;
    }

    /**
     * Saves a BOX checkpoint at a specific ID instead of appending at the end.
     * <p>
     * When {@code insert} is true every existing checkpoint with an ID {@code >= id} is shifted up
     * by one, so the new checkpoint takes its place in the lap order and nothing is overwritten.
     * When {@code insert} is false the checkpoint already at {@code id} is replaced in place
     * (its old geometry, sector ID and alternate routes are discarded).
     *
     * @param race the race to attach the checkpoint to
     * @param l1 first corner of the checkpoint (pos1)
     * @param l2 second corner of the checkpoint (pos2)
     * @param type the checkpoint type
     * @param id the target ID (1-based, matching the lap order)
     * @param insert true to shift the following IDs up, false to overwrite the target ID
     * @return the saved checkpoint, or null on failure
     */
    public Checkpoint saveCheckpointAt(Race race, Location l1, Location l2, Checkpoint.Type type, int id, boolean insert) {
        YamlConfiguration config = race.getConfig();
        if(config == null) {
            main.log("§cCheckpoint " + id + " for race " + race.getName() + " wasn't saved, see cause above.");
            return null;
        }

        if (insert) {
            shiftIdsUp(config, id);
        } else {
            config.set("checkpoints." + id, null);
        }

        writeBox(config, id, l1, l2, type);

        //A SECTOR checkpoint also needs its own split-timing ID, continuing from the race's existing sectors
        if (type == Checkpoint.Type.SECTOR) {
            config.set("checkpoints." + id + ".sectorID", nextSectorId(race));
        }

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't save checkpoint " + id + " for race " + race.getName() + " because the it's config threw an error on saving. ", e);
            return null;
        }

        //Rebuild from the saved config so the in-memory list matches the new ID order
        loadRaceCheckpoints(race);
        return race.getCheckpoint(id);
    }

    /**
     * Rewrites every checkpoint of {@code race} as a contiguous 1..N range, preserving the
     * current order. Used to close the gap left behind by {@link #remove(Race, Checkpoint)},
     * which deletes an ID without renumbering — a gap makes the race impossible to complete
     * because {@code RaceListener} looks each next checkpoint up by {@code index + 1}.
     *
     * @return the number of checkpoints renumbered, or -1 on failure
     */
    public int renumber(Race race) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cCheckpoints for race " + race.getName() + " weren't renumbered, see cause above.");
            return -1;
        }

        //Drive the rewrite from the config's own keys, not the in-memory list: an entry the loader
        //skipped (unknown shape, bad number) must not be deleted by a renumber.
        List<Integer> ids = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("checkpoints");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    ids.add(Integer.parseInt(key));
                } catch (NumberFormatException e) {
                    main.log("§eSkipping non-numeric checkpoint key '" + key + "' on race " + race.getName() + " while renumbering.");
                }
            }
        }
        Collections.sort(ids);

        if (ids.isEmpty()) return 0;

        //Grab every section up front, then clear the whole block and write it back at 1..N. Null
        //sections are dropped rather than skipped in place, so the result can't contain a hole.
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int id : ids) {
            ConfigurationSection entry = config.getConfigurationSection("checkpoints." + id);
            if (entry != null) sections.add(entry.getValues(true));
        }

        config.set("checkpoints", null);
        for (int i = 0; i < sections.size(); i++) {
            String path = "checkpoints." + (i + 1);
            for (Map.Entry<String, Object> entry : sections.get(i).entrySet()) {
                //getValues(true) yields each leaf under its full relative path *plus* a live section
                //object for every intermediate key. Re-setting those objects grafts the old subtree
                //back in, cycle included, and the next save recurses until the stack dies.
                if (entry.getValue() instanceof ConfigurationSection) continue;
                config.set(path + "." + entry.getKey(), entry.getValue());
            }
        }

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't renumber checkpoints for race " + race.getName() + " because the it's config threw an error on saving. ", e);
            return -1;
        }

        loadRaceCheckpoints(race);
        return sections.size();
    }

    /**
     * Rewrites a checkpoint between BOX and PLANE in place, keeping its ID, type, sector ID and
     * alternate routes. This is the only way to promote a hand-placed box to an oriented gate: the
     * shape is fixed when a {@link Checkpoint} is built, and {@code /checkpoint create} always
     * writes a box.
     * <p>
     * BOX to PLANE takes the box's horizontal centre line as the gate span, so the resulting plane
     * faces the same way the box did (across the tunnel/room the box spanned) and is exactly as
     * wide. PLANE to BOX keeps the same span and builds a thin box around it, tall enough to cover
     * the plane's height.
     *
     * @param race the race that owns the checkpoint
     * @param checkpoint the checkpoint to rewrite, read for its current geometry
     * @param target the shape to convert to
     * @return the reloaded checkpoint, or null if the conversion or save failed
     */
    public Checkpoint convertShape(Race race, Checkpoint checkpoint, Checkpoint.Shape target) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cCheckpoint " + checkpoint.getId() + " of race " + race.getName() + " wasn't converted, see cause above.");
            return null;
        }
        if (checkpoint.getShape() == target) return checkpoint;

        String path = "checkpoints." + checkpoint.getId();
        int id = checkpoint.getId();
        Checkpoint.Type type = checkpoint.getType();

        if (target == Checkpoint.Shape.PLANE) {
            Location min = checkpoint.getMin();
            Location max = checkpoint.getMax();
            double sizeX = max.getX() - min.getX();
            double sizeZ = max.getZ() - min.getZ();

            //Facing comes from the track direction between the neighbouring gates, which is what the
            //auto-tracer uses too, then snapped to a cardinal axis so a hand-placed box becomes a
            //clean gate rather than one skewed by whatever angle the neighbours happen to sit at.
            Vector normal = inferTravelNormal(race, checkpoint);
            if (normal == null) {
                //No usable neighbour: a box that is flat on one axis was already being used as a
                //plane, so its thin axis is the facing. A square box falls back to facing Z.
                normal = sizeX <= sizeZ ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
            } else {
                normal = Math.abs(normal.getX()) >= Math.abs(normal.getZ())
                        ? new Vector(1, 0, 0) : new Vector(0, 0, 1);
            }

            //The gate spans the axis its normal doesn't point along, so it stays as wide as the box.
            double halfWidth = normal.getX() != 0 ? sizeZ / 2.0 : sizeX / 2.0;

            Location center = checkpoint.getCenter();
            double halfHeight = Math.max((max.getY() - min.getY()) / 2.0, 2.5);

            config.set(path + ".shape", Checkpoint.Shape.PLANE.name());
            config.set(path + ".min", null);
            config.set(path + ".max", null);
            config.set(path + ".center", serializePrecise(center));
            config.set(path + ".normal", serializeVector(normal));
            config.set(path + ".halfWidth", halfWidth);
            config.set(path + ".halfHeight", halfHeight);
        } else {
            //PLANE to BOX: one block thick along the normal, as wide and tall as the plane.
            Location center = checkpoint.getCenter();
            double halfWidth = checkpoint.getHalfWidth();
            double halfHeight = checkpoint.getHalfHeight();
            Vector normal = checkpoint.getNormal() == null ? new Vector(0, 0, 1) : checkpoint.getNormal();

            boolean alongX = Math.abs(normal.getX()) >= Math.abs(normal.getZ());
            double thickness = 1.0;
            double spanX = alongX ? halfWidth : thickness;
            double spanZ = alongX ? thickness : halfWidth;

            //YAML stores corners, and Checkpoint#boxMax widens the upper one by one on load, so the
            //stored max has to be the extent minus one or the box comes back a block too big.
            Location boxMin = new Location(center.getWorld(),
                    center.getX() - spanX, center.getY() - halfHeight, center.getZ() - spanZ);
            Location boxMax = new Location(center.getWorld(),
                    center.getX() + spanX - 1, center.getY() + halfHeight - 1, center.getZ() + spanZ - 1);

            config.set(path + ".shape", Checkpoint.Shape.BOX.name());
            config.set(path + ".center", null);
            config.set(path + ".normal", null);
            config.set(path + ".halfWidth", null);
            config.set(path + ".halfHeight", null);
            config.set(path + ".min", serialize(boxMin));
            config.set(path + ".max", serialize(boxMax));
        }

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't convert checkpoint " + id + " of race " + race.getName() + " to " + target + " because the it's config threw an error on saving. ", e);
            return null;
        }

        loadRaceCheckpoints(race);
        return race.getCheckpoint(id);
    }

    /**
     * Direction of travel through {@code checkpoint}, taken from the line between its neighbours in
     * the lap order. Uses the neighbouring checkpoints' centres, so it works for a box that has no
     * orientation of its own.
     * @return a horizontal unit vector pointing along the track at that gate, or null when there is
     *         no usable neighbour (a one-checkpoint race, or a gate placed on top of its neighbour)
     */
    private Vector inferTravelNormal(Race race, Checkpoint checkpoint) {
        List<Checkpoint> checkpoints = race.getCheckpoints();
        int index = checkpoints.indexOf(checkpoint);
        if (index < 0) return null;

        Checkpoint previous = index > 0 ? checkpoints.get(index - 1) : null;
        Checkpoint next = index + 1 < checkpoints.size() ? checkpoints.get(index + 1) : null;

        //A lap is a loop, so the first gate can borrow the last one and vice versa.
        if (previous == null) previous = checkpoints.size() > 1 ? checkpoints.getLast() : null;
        if (next == null) next = checkpoints.size() > 1 ? checkpoints.getFirst() : null;
        if (previous == null || next == null || previous == checkpoint || next == checkpoint) return null;

        Location center = checkpoint.getCenter();
        if (!sameWorld(center, previous.getCenter()) || !sameWorld(center, next.getCenter())) return null;

        //Average the two incoming/outgoing directions so a sharp corner doesn't skew the gate.
        Vector incoming = center.toVector().subtract(previous.getCenter().toVector()).setY(0);
        Vector outgoing = next.getCenter().toVector().subtract(center.toVector()).setY(0);

        Vector direction = incoming.lengthSquared() > 1e-6 && outgoing.lengthSquared() > 1e-6
                ? incoming.normalize().add(outgoing.normalize())
                : incoming.lengthSquared() > 1e-6 ? incoming : outgoing;

        if (direction.lengthSquared() < 1e-6) return null;
        return direction.normalize();
    }

    /**
     * Rewrites a checkpoint's type in place, keeping its shape, geometry and alternate routes.
     * <p>
     * Sector IDs are reassigned 1..N in lap order afterwards. Converting a gate away from SECTOR
     * would otherwise leave a gap, and converting one back would take {@code max + 1} and land it
     * behind a gate earlier in the lap — a split-time display that reads "sector 3" then "sector 2".
     * Since this only runs while the race is idle (see the command's canEditOrder guard), no
     * in-flight racer is holding sector times against the old numbering.
     *
     * @param race the race that owns the checkpoint
     * @param checkpoint the checkpoint to rewrite
     * @param target the type to convert to
     * @return the reloaded checkpoint, or null if the save failed
     */
    public Checkpoint convertType(Race race, Checkpoint checkpoint, Checkpoint.Type target) {
        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cCheckpoint " + checkpoint.getId() + " of race " + race.getName() + " wasn't converted, see cause above.");
            return null;
        }
        if (checkpoint.getType() == target) return checkpoint;

        String path = "checkpoints." + checkpoint.getId();
        config.set(path + ".type", target.name());

        //A sector ID on a non-sector gate would keep it in the split-time table; drop it.
        if (target != Checkpoint.Type.SECTOR) config.set(path + ".sectorID", null);

        renumberSectors(race, config, checkpoint, target);

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't convert checkpoint " + checkpoint.getId() + " of race " + race.getName() + " to " + target + " because the it's config threw an error on saving. ", e);
            return null;
        }

        loadRaceCheckpoints(race);
        return race.getCheckpoint(checkpoint.getId());
    }

    /**
     * Renumbers every SECTOR checkpoint 1..N in lap order, after {@code converted} has been given
     * {@code newType} but before the config is saved.
     */
    private void renumberSectors(Race race, YamlConfiguration config, Checkpoint converted, Checkpoint.Type newType) {
        int next = 1;
        for (Checkpoint checkpoint : race.getCheckpoints()) {
            Checkpoint.Type type = checkpoint == converted ? newType : checkpoint.getType();
            String path = "checkpoints." + checkpoint.getId();

            if (type == Checkpoint.Type.SECTOR) {
                config.set(path + ".sectorID", next);
                next++;
            } else {
                config.set(path + ".sectorID", null);
            }
        }
    }

    /**
     * Shifts every checkpoint with an ID {@code >= from} up by one, in descending order so no
     * entry is overwritten before it has been copied.
     */
    private void shiftIdsUp(YamlConfiguration config, int from) {
        ConfigurationSection checkpoints = config.getConfigurationSection("checkpoints");
        if (checkpoints == null) return;

        List<Integer> toShift = new ArrayList<>();
        for (String key : checkpoints.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                if (id >= from) toShift.add(id);
            } catch (NumberFormatException ignored) {
                //Non-numeric key, not a checkpoint ID — leave it alone
            }
        }
        toShift.sort(Collections.reverseOrder());

        for (int id : toShift) {
            ConfigurationSection section = config.getConfigurationSection("checkpoints." + id);
            if (section == null) continue;

            Map<String, Object> values = section.getValues(true);
            String path = "checkpoints." + (id + 1);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                //See renumber(): the section objects in this map would graft the source subtree back
                //in and leave a cycle behind for the next save to choke on.
                if (entry.getValue() instanceof ConfigurationSection) continue;
                config.set(path + "." + entry.getKey(), entry.getValue());
            }
            config.set("checkpoints." + id, null);
        }
    }

    /**
     * Writes a BOX checkpoint's fields under {@code checkpoints.<id>}.
     */
    private void writeBox(YamlConfiguration config, int id, Location l1, Location l2, Checkpoint.Type type) {
        Location min = min(l1, l2);
        Location max = max(l1, l2);
        String path = "checkpoints." + id;

        config.set(path + ".shape", Checkpoint.Shape.BOX.name());
        config.set(path + ".world", min.getWorld().getName());
        config.set(path + ".min", serialize(min));
        config.set(path + ".max", serialize(max));
        config.set(path + ".type", type.name());
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
            err("Couldn't save checkpoint " + ID + " for race " + race.getName() + " because the it's config threw an error on saving. ",e);
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
            err("Couldn't save auto-traced checkpoints for race " + race.getName() + " because the it's config threw an error on saving. ", e);
            return false;
        }

        for (Checkpoint checkpoint : toAdd) race.addCheckpoint(checkpoint);

        //The start line's facing decides where boats spawn pointing. Take it from the first PLANE
        //checkpoint in the race; a BOX checkpoint has no normal, and this used to throw *after* the
        //checkpoints were already saved, so a retry duplicated the whole set.
        for (Checkpoint checkpoint : race.getCheckpoints()) {
            if (checkpoint.getShape() != Checkpoint.Shape.PLANE) continue;
            Vector normal = checkpoint.getNormal();
            if (normal == null) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-normal.getX(), normal.getZ()));
            race.setStartRotation(yaw);
            break;
        }
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

        target.addAlternate(center, normal, halfWidth, halfHeight);

        //Rewrite the whole block from the list rather than appending at `size()`, which would overwrite
        //a surviving route after one has been removed.
        config.set("checkpoints." + target.getId() + ".alternates", null);
        yamlAlternates(config, target);

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't save alternate route for checkpoint " + target.getId() + " on race " + race.getName() + " because the it's config threw an error on saving. ", e);
            target.getAlternates().removeLast();
            return false;
        }

        return true;
    }

    /**
     * Detaches an alternate route from a checkpoint and persists the change. Without the save the
     * route reappears on the next reload, since {@code Alternates}<em></em> is only ever written here.
     * @return true if the route was attached and the resulting list was saved
     */
    public boolean removeAlternate(Race race, Checkpoint target, Checkpoint.AlternateRoute alt) {
        int index = target.getAlternates().indexOf(alt);
        if (index < 0) return false;

        YamlConfiguration config = race.getConfig();
        if (config == null) {
            main.log("§cAlternate route " + index + " of checkpoint " + target.getId() + " on race " + race.getName() + " wasn't removed, see cause above.");
            return false;
        }

        Checkpoint.AlternateRoute removed = target.getAlternates().remove(index);

        config.set("checkpoints." + target.getId() + ".alternates", null);
        yamlAlternates(config, target);

        try {
            race.saveConfig();
        } catch (IOException e) {
            err("Couldn't remove alternate route " + index + " of checkpoint " + target.getId() + " on race " + race.getName() + " because the it's config threw an error on saving. ", e);
            target.getAlternates().add(index, removed);
            return false;
        }

        return true;
    }

    /** Writes every alternate route of {@code checkpoint} under {@code checkpoints.<id>.alternates}. */
    private void yamlAlternates(YamlConfiguration config, Checkpoint checkpoint) {
        List<Checkpoint.AlternateRoute> alternates = checkpoint.getAlternates();
        for (int i = 0; i < alternates.size(); i++) {
            Checkpoint.AlternateRoute alt = alternates.get(i);
            String path = "checkpoints." + checkpoint.getId() + ".alternates." + i;

            config.set(path + ".center", serializePrecise(alt.getCenter()));
            config.set(path + ".normal", serializeVector(alt.normal));
            config.set(path + ".halfWidth", alt.getHalfWidth());
            config.set(path + ".halfHeight", alt.getHalfHeight());
        }
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
            err("Couldn't clear checkpoints for race " + race.getName() + " because the it's config threw an error on saving. ", e);
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

            //One malformed entry must not abort loading: clearCheckpoints() already ran, so throwing
            //here would leave the race with no checkpoints at all and stop later races from loading.
            int ID;
            try {
                ID = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                main.log("§eSkipping checkpoint '" + key + "' of race " + race.getName() + ": the ID isn't a number.");
                continue;
            }

            String worldName = config.getString("checkpoints." + key + ".world");
            String shapeString = config.getString("checkpoints." + key + ".shape");
            Checkpoint.Shape shape;
            try {
                shape = shapeString == null ? Checkpoint.Shape.BOX : Checkpoint.Shape.valueOf(shapeString.toUpperCase());
            } catch (IllegalArgumentException e) {
                main.log("§eCheckpoint " + key + " of race " + race.getName() + " has unknown shape '" + shapeString + "', treating it as BOX.");
                shape = Checkpoint.Shape.BOX;
            }

            String typeString = config.getString("checkpoints." + key + ".type");
            Checkpoint.Type type;
            try {
                type = typeString == null ? Checkpoint.Type.NORMAL : Checkpoint.Type.valueOf(typeString.toUpperCase());
            } catch (IllegalArgumentException e) {
                main.log("§eCheckpoint " + key + " of race " + race.getName() + " has unknown type '" + typeString + "', treating it as NORMAL.");
                type = Checkpoint.Type.NORMAL;
            }

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
            //Location#distance throws across worlds, which happens as soon as the player isn't
            //standing in this race's world.
            if (!sameWorld(point, c.getCenter())) continue;

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
        int total = 0;
        for (List<Checkpoint> checkpoints : getAll().values()) total += checkpoints.size();
        return total;
    }

    /**
     * Lower block corner of the two selections, sorted on every axis. This is the corner as clicked,
     * not the extent: the YAML stores corners and {@link Checkpoint} widens them on construction, so
     * files written before that normalization load correctly too.
     */
    private Location min(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    /** Upper block corner of the two selections, sorted on every axis and not widened. */
    private Location max(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }
}