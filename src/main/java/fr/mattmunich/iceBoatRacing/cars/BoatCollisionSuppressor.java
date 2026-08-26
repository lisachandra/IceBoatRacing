package fr.mattmunich.iceBoatRacing.cars;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import org.bukkit.entity.Player;

import fr.mattmunich.iceBoatRacing.race.RaceData;
import fr.mattmunich.iceBoatRacing.race.RaceManager;

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

    private final RaceManager raceManager;

    public BoatCollisionSuppressor(RaceManager raceManager) {
        this.raceManager = raceManager;
    }

    public static void register(RaceManager raceManager) {
        PacketEvents.getAPI().getEventManager()
                .registerListener(new BoatCollisionSuppressor(raceManager));
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

        RaceData racer = raceManager.getRacer(player.getUniqueId());
        if (racer == null || !racer.racing) {
            return; // not racing — let normal vehicle-move corrections through
        }

        // Confirming the wrapper parses cleanly is mostly a sanity check here;
        // we're not reading fields off it, just gating on packet type + race state.
        new WrapperPlayServerVehicleMove(event);

        event.setCancelled(true);
    }
}
