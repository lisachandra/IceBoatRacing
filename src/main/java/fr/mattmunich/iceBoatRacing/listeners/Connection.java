package fr.mattmunich.iceBoatRacing.listeners;

import fr.mattmunich.iceBoatRacing.LiveScoreManager;
import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.cars.Car;
import fr.mattmunich.iceBoatRacing.cars.CarManager;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.Racer;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;

import static fr.mattmunich.iceBoatRacing.Main.*;
import static fr.mattmunich.iceBoatRacing.Messages.formatArguments;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;

public class Connection implements Listener {

    private final Main main;
    private final RaceManager raceManager;
    private final CarManager carManager;
    private final LiveScoreManager liveScoreManager;

    public Connection(Main main, RaceManager raceManager, CarManager carManager, LiveScoreManager liveScoreManager) {
        this.main = main;
        this.raceManager = raceManager;
        this.carManager = carManager;
        this.liveScoreManager = liveScoreManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(getMessage("noPrefix.join",formatArguments("player", p.getName())));

        if (raceManager.races.isEmpty()) return;

        for(Race race : raceManager.races) {
            Racer racer = race.racers.get(p.getUniqueId());
            if (racer == null) continue;
            if(race.isPreparing() && race.racers.containsKey(p.getUniqueId())) {
                //Logs in while registration is open: they are registered but not placed yet,
                //so just let them know rather than teleporting them into a boat.
                p.sendMessage(getMessage("race.intermission.rejoined", formatArguments("race", race.getName())));
            } else if (race.hasStarted() && race.racers.containsKey(p.getUniqueId()) && ALLOW_REJOIN) {
                //Logs back in when race is still going
                Location logOutLocation = racer.getLogoutLocation();
                if(logOutLocation == null) {
                    warn("Couldn't put " + p.getName() + " back in it's car after logging back in because the log out location was null.");
                    return;
                }
                p.teleport(logOutLocation);

                //Always remove and place back car to prevent bugs
                Car car = racer.car;
                carManager.spawnCar(race, car, racer.player, logOutLocation);

                //Add racer back to racing list
                if (!race.racing.contains(racer)) race.racing.add(racer);
                racer.racing = true;
            } else {
                //Means player logged out during race and logged back in when it was ended
                if (racer.car != null) racer.car.destroy();
                raceManager.teleportBack(p, racer);
                race.racers.remove(p.getUniqueId());
                liveScoreManager.removeRacer(racer);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        e.quitMessage(getMessage("noPrefix.quit",formatArguments("player", p.getName())));

        for (Race race : new ArrayList<>(raceManager.activeRaces)) {
            Racer racer = race.racers.get(p.getUniqueId());
            if(racer == null) return;

            //Still in the registration intermission: nothing was placed yet, so just drop them
            //from the roster. `racing` is empty at this point for everyone, so the auto-end
            //check below must not run.
            if (race.isPreparing()) {
                race.racers.remove(p.getUniqueId());
                race.racing.remove(racer);
                liveScoreManager.removeRacer(racer);
                if (racer.car != null) racer.car.destroy();
                return;
            }

            racer.racing = false;
            Car car = racer.car;
            if (car != null) {
                race.racing.remove(racer);
                if (ALLOW_REJOIN) {
                    racer.setLogoutLocation(car.getLocation());
                    if (REMOVE_CAR_ON_LOGOUT) {
                        car.destroy();
                    }
                } else {
                    car.destroy();
                    liveScoreManager.removeRacer(racer);
                    race.racers.remove(p.getUniqueId());
                }
            } else {
                liveScoreManager.removeRacer(racer);
            }

            //Auto end race if the last racer leaves
            if(race.hasStarted() && race.racing.isEmpty()) {
                try {
                    race.end(true);
                } catch (Exception err) {
                    switch (err.getMessage()) {
                        case "NO_RANKING" -> Bukkit.getConsoleSender().sendMessage(getMessage("race.onEnd.error.noRanking"));
                        case "NO_RACERS" -> Bukkit.getConsoleSender().sendMessage(getMessage("race.onEnd.error.noRacers"));
                        default -> Bukkit.getConsoleSender().sendMessage(getMessage("error.unknown"));
                    }
                }
            }
        }


    }
}
