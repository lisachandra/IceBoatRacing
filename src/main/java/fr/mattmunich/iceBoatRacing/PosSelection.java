package fr.mattmunich.iceBoatRacing;

import fr.mattmunich.iceBoatRacing.race.RaceCreator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;

public class PosSelection implements Listener {
    public static final Map<Player, Location> pos1 = new HashMap<>();
    public static final Map<Player, Location> pos2 = new HashMap<>();

    public PosSelection() {
    }

    @EventHandler
    public void onSelect(PlayerInteractEvent event) {
        if (event.getItem() == null) return;
        if (event.getItem().getType() != Material.WOODEN_SHOVEL) return;
        if (RaceCreator.creatingRace.get(event.getPlayer()) != null && RaceCreator.creatingRace.get(event.getPlayer()) == 3)
            return;

        Player p = event.getPlayer();

        if (!p.hasPermission("iceboatracing.command.checkpoint") || !p.hasPermission("iceboatracing.command.race")) return;

        Block clicked = event.getClickedBlock();

        if (clicked == null) return;

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            clearIfOtherWorld(p, clicked.getLocation(), pos2);
            Location old = pos1.put(p, clicked.getLocation());
            if (pos1.get(p).equals(old)) return;
            p.sendMessage(Messages.getMessage("pos.1", Messages.formatArguments("x", "" + clicked.getX(), "y", "" + clicked.getY(), "z", "" + clicked.getZ())));
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            clearIfOtherWorld(p, clicked.getLocation(), pos1);
            Location old = pos2.put(p, clicked.getLocation());
            if (pos2.get(p).equals(old)) return;
            p.sendMessage(Messages.getMessage("pos.2", Messages.formatArguments("x", "" + clicked.getX(), "y", "" + clicked.getY(), "z", "" + clicked.getZ())));
        }
    }

    /**
     * Drops the opposite corner if it was picked in a different world. A box spanning two worlds
     * would otherwise be stored with one corner's coordinates and the other corner's world.
     */
    private void clearIfOtherWorld(Player p, Location clicked, Map<Player, Location> opposite) {
        Location other = opposite.get(p);
        if (other != null && !clicked.getWorld().equals(other.getWorld())) {
            opposite.remove(p);
        }
    }
}