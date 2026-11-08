package fr.mattmunich.iceBoatRacing.cars;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.pitbox.PitBox;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.Racer;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.Bukkit;
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

    private final Main main;
    private final RaceManager raceManager;
    private final CarManager carManager;

    public CarListener(Main main, RaceManager raceManager, CarManager carManager) {
        this.main = main;
        this.raceManager = raceManager;
        this.carManager = carManager;
    }

    @EventHandler
    public void onExitVehicle(VehicleExitEvent e) {
        if (!(e.getExited() instanceof Player p)) return;
        Racer data = null;
        for(Race race : raceManager.activeRaces) if(race.racers.containsKey(p.getUniqueId())) data = race.racers.get(p.getUniqueId());
        if (data == null || data.race == null || data.car == null) return;

        //Only lock in drivers sitting in their own race car: someone who joined the registration
        //while sitting in a random boat can still hop out of it.
        if (e.getVehicle() != data.car.getBoat()) return;

        //From the grid to the lights, drivers stay seated
        if (!data.race.hasStarted()) {
            e.setCancelled(true);
            return;
        }

        //Chest pit stops are served on foot: a dismount during an active session just parks the
        //boat while the driver walks to the chest and back. TIMED stops keep the replacement
        //behavior, since the driver has no reason to be out of the boat.
        if (data.pittingBox != null && data.pittingBox.getTaskType() == PitBox.TaskType.CHEST) return;

        //Once the race is running, exiting replaces the boat: the old (possibly stuck) one is
        //removed and a fresh one spawns right where they stand. Done 1 tick later, because
        //passengers cannot be re-added while the dismount is still being processed.
        final Racer racer = data;
        Bukkit.getScheduler().runTask(main, () -> replaceBoat(racer));
    }

    /**
     * Gives {@code data} a fresh boat at their current position, so a stuck boat can't trap them
     * until the end of the race. Anything happening between the dismount and this call (leaving
     * the race, finishing, race end) cancels the replacement.
     */
    private void replaceBoat(Racer data) {
        Player player = data.player;
        if (player == null || !player.isOnline()) return;
        if (!data.racing || data.finished || !data.race.hasStarted()) return;
        if (player.getVehicle() != null) return; //already back in a boat

        carManager.respawnCar(data.car, player);
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

        //Only hold their own race car, not any boat they happen to sit in
        if (vehicle != data.car.getBoat()) return;

        //Freeze the boat on the grid during the countdown and hold it in place during a pit stop.
        //(The hard per-tick grid lock in startRace does most of the countdown work; this catches
        //whatever slips between two ticks.)
        if (data.race.isStarting() || data.pittingBox != null) {
            //Pretty much cancels movement
            e.getVehicle().teleport(e.getFrom());
        }
    }
}
