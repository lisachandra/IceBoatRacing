package fr.mattmunich.iceBoatRacing.pitbox;

import fr.mattmunich.iceBoatRacing.race.Race;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PendingPitBoxConfig {

    public final Race race;
    public String name;
    public Location pos1;
    public Location pos2;
    public int duration = 5;
    public List<UUID> allowed = new ArrayList<>();
    public PitBoxColor color = PitBoxColor.ICE;

    public PendingPitBoxConfig(Race race) {
        this.race = race;
    }
}