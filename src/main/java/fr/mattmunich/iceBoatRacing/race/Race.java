package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.Result;
import fr.mattmunich.iceBoatRacing.cars.Car;
import fr.mattmunich.iceBoatRacing.checkpoint.Checkpoint;
import fr.mattmunich.iceBoatRacing.pitbox.PitBox;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.formatTime;
import static fr.mattmunich.iceBoatRacing.Messages.formatArguments;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;

public class Race {

    private RaceManager raceManager;
    YamlConfiguration config = null;

    final String name;
    final World world;

    float startRotation = 0;
    int lapCount = 10;
    int requiredPitStops = 0;

    final List<Checkpoint> checkpoints = new ArrayList<>();
    final List<Car> cars = new ArrayList<>();
    public final Map<UUID, Racer> racers = new HashMap<>();

    /**
     * List of the racers that are actively racing
     * (-> those who have finished the race are not included)
     */
    public List<Racer> racing = new ArrayList<>();
    boolean startingRace = false;
    boolean preparingRace = false;
    boolean hasRaceStarted = false;
    /**
     * List of the racers who hae finished the race.
     * rankings.get(0) is first, rankings.get(1) is second and so on
     */
    public Map<Integer, Racer> rankings = new HashMap<>();
    public long currentBestLapTime = Long.MAX_VALUE;


    public Race(String name, World world) {
        this.name = name;
        this.world = world;
    }

    public record Lights(boolean enabled, Location from, Location to) {}

    public Lights lights = new Lights(false, null, null);

    public Lights getLights() {
        return lights.from == null || lights.to == null ? null : lights;
    }

    public void setLights(Lights lights) {
        this.lights = lights;
    }

    /**
     * Required when initializing the race, as it relies on it for a bunch of methods below.
     * @param raceManager {@link RaceManager}
     */
    public void setRaceManager(RaceManager raceManager) {
        this.raceManager = raceManager;
    }

    //All the config things
    public void setConfig(YamlConfiguration config) {
        this.config = config;
    }

    public YamlConfiguration getConfig() {
        return config;
    }

    public void saveConfig() throws IOException {
        raceManager.saveRaceConfig(this, config);
    }

    public boolean update() {
        return raceManager.updateRace(this);
    }


    //All the start/end and so on things
    public Result start() {
        return raceManager.startRace(this);
    }

    /**
     * Ends the race
     * @param sendRanking Whether the ranking message should be sent
     * @throws Exception Only when {@code sendRanking} is true: rethrows {@link Race#sendRanking()}, race will still be ended properly
     */
    public void end(boolean sendRanking) throws Exception {
        try {
            if (sendRanking) sendRanking();
        } finally {
            raceManager.endRace(this);
        }

    }

    public void togglePrepare(CommandSender sender) {
        raceManager.togglePrepareRace(sender, this);
    }
    public boolean isNotStarting() {
        return !startingRace;
    }
    public boolean isPreparing() {
        return preparingRace;
    }
    public boolean hasStarted() {
        return hasRaceStarted;
    }

    //Basic values' getters
    public String getName() {
        return name;
    }
    public World getWorld() {
        return world;
    }

    public float getStartRotation() {
        return startRotation;
    }
    public void setStartRotation(float startRotation) {
        this.startRotation = startRotation;
    }

    //Lap count
    public int getLapCount() {
        return lapCount;
    }
    public void setLapCount(int lapCount) {
        this.lapCount = Math.max(lapCount, 1);
    }

    //Pit stops
    public int getRequiredPitStops() {
        return requiredPitStops;
    }

    public void setRequiredPitStops(int requiredPitStops) {
        this.requiredPitStops = Math.max(requiredPitStops, 0);
    }

    //Checkpoints
    public List<Checkpoint> getCheckpoints() {
        return checkpoints;
    }

    public @Nullable Checkpoint getCheckpoint(int ID) {
        final Checkpoint[] result = new Checkpoint[1];
        result[0] = null;
        getCheckpoints().forEach(checkpoint -> {if(checkpoint.getId()==ID) result[0]=checkpoint;});
        return result[0];
    }

    public void addCheckpoint(Checkpoint checkpoint) {
        checkpoints.add(checkpoint);
    }

    public void clearCheckpoints() {
        checkpoints.clear();
    }

    public boolean removeCheckpoint(Checkpoint checkpoint) {
        return checkpoints.remove(checkpoint);
    }

    //Pit Boxes
    // field, next to `checkpoints`
    final List<PitBox> pitBoxes = new ArrayList<>();

    // methods, next to getCheckpoint/addCheckpoint/clearCheckpoints/removeCheckpoint
    public List<PitBox> getPitBoxes() {
        return pitBoxes;
    }

    public @Nullable PitBox getPitBox(int id) {
        for (PitBox box : pitBoxes) {
            if (box.getId() == id) return box;
        }
        return null;
    }

    public @Nullable PitBox getPitBox(String name) {
        for (PitBox box : pitBoxes) {
            if (box.getName().equalsIgnoreCase(name)) return box;
        }
        return null;
    }

    public void addPitBox(PitBox box) {
        pitBoxes.add(box);
    }

    public void clearPitBoxes() {
        pitBoxes.clear();
    }

    public boolean removePitBox(PitBox box) {
        return pitBoxes.remove(box);
    }

    //Cars
    public List<Car> getCars() {
        return cars;
    }

    public void addCar(Car car) {
        cars.add(car);
    }

    public void clearCars() {
        cars.clear();
    }

    public boolean removeCar(Car car) {
        return cars.remove(car);
    }

    //In race ranking
    /**
     * @param time The time of the player who finished a lap (to check if it is the best lap time yet)
     * @return -1 if the inputted time is longer than the current best lap time. Otherwise, it returns the lastBestLapTime.
     */
    public long isBestLapTimeYet(long time) {
        if (currentBestLapTime > time) {
            long lastBestLapTime = currentBestLapTime;
            currentBestLapTime = time;
            return lastBestLapTime;
        }
        else return -1;
    }

    /**
     * Sends the ranking message for the race
     * @throws Exception Throws {@code NO_RACERS} when the race had no racers and {@code NO_RAKING} when there was no ranking for the race
     */
    public void sendRanking() throws Exception {
        if (racers.isEmpty()) throw new Exception("NO_RACERS");
        if (rankings.isEmpty()) throw new Exception("NO_RANKING");

        Bukkit.broadcast(getMessage("race.onEnd.top"));

        // Split by DSQ status while preserving original finish-crossing order within each group.
        // rankings' keys are sequential ints assigned in crossing order (see onFinishRace), so
        // walking 0..size-1 reconstructs that order reliably (racers.values() below does not).
        List<Racer> qualified = new ArrayList<>();
        List<Racer> disqualified = new ArrayList<>();
        for (int i = 0; i < rankings.size(); i++) {
            Racer data = rankings.get(i);
            if (data == null) continue;
            if (data.disqualified) disqualified.add(data);
            else qualified.add(data);
        }

        long previousTime = -1;
        int displayRank = 1;
        for (Racer data : qualified) {
            long raceTime = data.getRaceTime();
            if (displayRank == 1) {
                Bukkit.broadcast(getMessage("race.onEnd.winnerFormat", formatArguments(
                        "ranking", displayRank + "",
                        "player", data.player == null ? "OFFLINE" : data.player.getName(),
                        "raceTime", formatTime(raceTime)
                )));
            } else {
                long gap = raceTime - previousTime;
                Bukkit.broadcast(getMessage("race.onEnd.playerFormat", formatArguments(
                        "ranking", displayRank + "",
                        "player", data.player == null ? "OFFLINE" : data.player.getName(),
                        "raceTime", formatTime(raceTime),
                        "gapToNext", formatTime(gap)
                )));
            }
            previousTime = raceTime;
            displayRank++;
        }

        for (Racer data : disqualified) {
            Bukkit.broadcast(getMessage("race.onEnd.disqualifiedFormat", formatArguments(
                    "player", data.player == null ? "OFFLINE" : data.player.getName(),
                    "raceTime", formatTime(data.getRaceTime())
            )));
        }

        // Lap/sector highlights are order-independent (best/worst across everyone), unaffected by DSQ placement
        long bestLapTime = Long.MAX_VALUE;
        long worstLapTime = Long.MIN_VALUE;

        Player bestLapPlayer = null;
        Player worstLapPlayer = null;

        Map<Integer, Long> bestSectorTimes = new HashMap<>();
        Map<Integer, Player> bestSectorPlayers = new HashMap<>();

        for (Racer data : racers.values()) {
            if (!data.getLapTimes().isEmpty()) {
                for (long lapTime : data.getLapTimes()) {
                    if (lapTime < bestLapTime) {
                        bestLapTime = lapTime;
                        bestLapPlayer = data.player;
                    }
                    if (lapTime > worstLapTime) {
                        worstLapTime = lapTime;
                        worstLapPlayer = data.player;
                    }
                }
            }

            Map<Integer, List<Long>> sectorTimes = data.getSectorsTimes();
            if (sectorTimes != null) {
                for (int sectorID : sectorTimes.keySet()) {
                    bestSectorTimes.putIfAbsent(sectorID, Long.MAX_VALUE);
                    Long playerSectorTime = Collections.min(sectorTimes.get(sectorID));
                    if (playerSectorTime < bestSectorTimes.get(sectorID)) {
                        bestSectorTimes.put(sectorID, playerSectorTime);
                        bestSectorPlayers.put(sectorID, data.player);
                    }
                }
            }
        }

        long winnerTime = qualified.isEmpty() ? 0 : qualified.getFirst().getRaceTime();
        long lastQualifiedTime = qualified.isEmpty() ? 0 : qualified.getLast().getRaceTime();
        long winnerGap = lastQualifiedTime - winnerTime;

        Bukkit.broadcast(getMessage("race.onEnd.highlights", formatArguments(
                "bestLapPlayer", bestLapPlayer == null ? "§c§oOffline" : bestLapPlayer.getName(),
                "bestLapTime", bestLapTime == Long.MAX_VALUE ? "N/A" : formatTime(bestLapTime),
                "worstLapPlayer", worstLapPlayer == null ? "§c§oOffline" : worstLapPlayer.getName(),
                "worstLapTime", worstLapTime == Long.MIN_VALUE ? "N/A" : formatTime(worstLapTime),
                "winnerGap", formatTime(winnerGap)
        )));

        if (!bestSectorTimes.isEmpty()) {
            Bukkit.broadcast(getMessage("race.onEnd.bestSectorTimesTop"));
            for (int sectorID : bestSectorTimes.keySet()) {
                Player sectorRecordHolder = bestSectorPlayers.get(sectorID);
                String playerName = (sectorRecordHolder == null) ? "§c§oUnknown" : sectorRecordHolder.getName();
                long recordTime = bestSectorTimes.get(sectorID);

                Bukkit.broadcast(getMessage("race.onEnd.sectorFormat", formatArguments(
                        "sectorID", String.valueOf(sectorID),
                        "player", playerName,
                        "time", recordTime == Long.MAX_VALUE ? "N/A" : formatTime(recordTime)
                )));
            }
        }

        Bukkit.broadcast(getMessage("race.onEnd.bottom"));
    }
}
