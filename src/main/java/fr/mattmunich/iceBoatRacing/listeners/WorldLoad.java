package fr.mattmunich.iceBoatRacing.listeners;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WorldLoad implements Listener {

    private final Main main;
    private final RaceManager raceManager;

    public WorldLoad(Main main, RaceManager raceManager) {
        this.main = main;
        this.raceManager = raceManager;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        Bukkit.getScheduler().runTask(main, () -> {
            for (Map.Entry<String, String> unloadedRace : raceManager.unloadedRaces.entrySet()) {
                if (!world.getName().equals(unloadedRace.getKey())) continue;
                raceManager.unloadedRaces.remove(unloadedRace.getKey(), unloadedRace.getValue());
                String raceName = unloadedRace.getValue();
                boolean success = raceManager.loadRace(raceName);
                if(success) {
                    main.log("Successfully loaded " + raceName + " after world load.");
                } else {
                    main.warn("Could not load race " + raceName + " after world load, see error above.");
                }
            }
        });
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        List<Race> raceList = new ArrayList<>(raceManager.getRaces());
        for (Race race : raceList) {
            if (!world.equals(race.getWorld())) continue;
            try {
                race.end(false);
                race.saveConfig();
            } catch (IOException e) {
                main.warn("Could not save race " + race.getName() + " on world unload, see error above.");
                continue;
            } catch (Exception ignored) {}

            raceManager.races.remove(race);
            //The race object keeps its checkpoint list, so without this its preview would keep drawing.
            main.getCheckpointPreview().stopRace(race);
            main.log("Successfully saved " + race.getName() + " on world unload.");
        }
    }
}
