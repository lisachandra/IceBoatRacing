package fr.mattmunich.iceBoatRacing.cars;

import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.Racer;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.NoSuchElementException;

public class CarListener implements Listener {

    private final RaceManager raceManager;

    public CarListener(RaceManager raceManager) {
        this.raceManager = raceManager;
    }

    @EventHandler
    public void onExitVehicle(VehicleExitEvent e) {
        if (!(e.getExited() instanceof Player p)) return;
        Racer data = null;
        for(Race race : raceManager.activeRaces) if(race.racers.containsKey(p.getUniqueId())) data = race.racers.get(p.getUniqueId());
        if (data == null || data.race == null || data.car == null) return;
        if(data.race.isNotStarting() && !data.race.isPreparing() && !data.race.hasStarted()) return;

        e.setCancelled(true);
    }

    @EventHandler
    public void onMoveCar(VehicleMoveEvent e) {
        Vehicle vehicle = e.getVehicle();
        if(!(vehicle instanceof Boat)) return;
        Entity passenger;
        try { passenger = vehicle.getPassengers().getFirst(); } catch (NoSuchElementException ignored) { return; }
        if(passenger == null) return;
        if(!(passenger instanceof Player p)) return;
        Racer data = null;
        for(Race race : raceManager.activeRaces) if(race.racers.containsKey(p.getUniqueId())) data = race.racers.get(p.getUniqueId());
        if (data == null || data.race == null || data.car == null) return;
        if(!data.race.isPreparing() && data.race.isNotStarting() && data.pittingBox==null) return;

        //Pretty much cancels movement
        e.getVehicle().teleport(e.getFrom());
    }
}
