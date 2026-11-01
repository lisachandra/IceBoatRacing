package fr.mattmunich.iceBoatRacing.cars;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import fr.mattmunich.iceBoatRacing.Main;
import org.bukkit.entity.Player;

import fr.mattmunich.iceBoatRacing.race.Racer;
import fr.mattmunich.iceBoatRacing.race.RaceManager;

import static fr.mattmunich.iceBoatRacing.Main.*;

/**
 * Suppresses corrective VehicleMove packets sent to racing players.
 * <p>
 * Boat-vs-boat collision is predicted client-side; the server independently
 * tracks each boat's position too, and when the two disagree (which happens
 * constantly during close racing) it sends the driver a VehicleMove packet
 * to resync — causing rubberbanding/freezing.
 * <p>
 * Only registered when PacketEvents is installed on the server — see in
 * {@link fr.mattmunich.iceBoatRacing.Main}. Never reference this class unconditionally.
 */
public class BoatCollisionSuppressor extends PacketListenerAbstract {


    private final Main main;
    private RaceManager raceManager;

    public BoatCollisionSuppressor(Main main, RaceManager raceManager) {
        this.main = main;
        this.raceManager = raceManager;
    }

    public static void register(Main main, RaceManager raceManager) {
        PacketEvents.getAPI().getEventManager()
                .registerListener(new BoatCollisionSuppressor(main, raceManager));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.VEHICLE_MOVE) {
            return;
        }

        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) {
            return;
        }

        if (raceManager == null) {
            warn("RaceManager is null, trying to define it again...");
            this.raceManager = main.getRaceManager();
            if (raceManager == null) {
                warn("RaceManager is still null, trying harder...");
                raceManager = main.resetRaceManager();
                if (raceManager == null) {
                    severe("Could not reset race manager! Try restarting the server.");
                } else {
                    log("Success!");
                }
            } else {
                log("Success!");
            }
        }

        Racer racer = raceManager.getRacer(player.getUniqueId());
        if (racer == null || !racer.racing) {
            return; // not racing — let normal vehicle-move corrections through
        }

        // Confirming the wrapper parses cleanly is mostly a sanity check here;
        // we're not reading fields off it, just gating on packet type + race state.
        new WrapperPlayServerVehicleMove(event);

        event.setCancelled(true);
    }
}
