package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.cars.Car;
import fr.mattmunich.iceBoatRacing.pitbox.PitBox;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;

public class Racer {
    public Player player;

    public boolean racing = false;
    public int ranking = -1;
    public boolean disqualified = false;
    public int checkpointIndex = 0;
    public int lapCount = -1;
    public Location from;

    public Car car;

    public int pitStopsCompleted = 0;
    public PitBox pittingBox = null;
    public long lastPitBox = 0;

    public long startTime = 0;
    public long lapTime = 0;
    public long endTime = 0;
    public long gapToNextTime = 0;
    public final List<Long> lapTimes = new ArrayList<>();
    public final Map<Integer,List<Long>> sectorsTimes = new HashMap<>();

    public Race race;
    public Location logoutLocation;

    public Racer(Player player) {
        this.player = player;
    }

    public Player getPlayer() { return player; }

    public Race getRace() { return race; }

    public int getCompletedLapCount() {
        return lapCount;
    }

    public int getCompletedPitStops() {
        return pitStopsCompleted;
    }

    public int getScore() {
        return ((lapCount-1) * getRace().getCheckpoints().size()) + checkpointIndex;
    }

    public long bestLapTime() {
        return Collections.min(lapTimes);
    }

    public long worstLapTime() {
        return Collections.max(lapTimes);
    }

    public Map<Integer,Long> bestSectorsTimes() {
        Map<Integer,Long> bestSectorsTimes = new HashMap<>();
        for (Integer s : sectorsTimes.keySet()) {
            List<Long> sTimes = sectorsTimes.get(s);
            bestSectorsTimes.put(s, Collections.min(sTimes));
        }
        return bestSectorsTimes;
    }

    public long meanLapTime() {
        return (long) lapTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0);
    }

    public long getRaceTime() {
        return endTime - startTime;
    }

    public List<Long> getLapTimes() {
        return lapTimes;
    }

    public Map<Integer,List<Long>> getSectorsTimes() {
        return sectorsTimes;
    }

    public void addSectorTime(int sectorID, long now, long lapTime) {
        List<Long> sectorTimes = sectorsTimes.getOrDefault(sectorID, new ArrayList<>());
        sectorTimes.add(now-lapTime);
        sectorsTimes.put(sectorID, sectorTimes);
    }

    public Location getLogoutLocation() {
        return logoutLocation;
    }

    public void setLogoutLocation(Location logoutLocation) {
        this.logoutLocation = logoutLocation;
    }
}