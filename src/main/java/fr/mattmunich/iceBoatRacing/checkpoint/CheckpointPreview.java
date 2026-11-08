package fr.mattmunich.iceBoatRacing.checkpoint;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns who is watching which race's checkpoint outlines, and the task that redraws them.
 * <p>
 * Sessions are keyed by {@link UUID} (not by {@code Player}, which would pin a disconnected player
 * forever) and are cancelled on quit, on race deletion, on world unload, and on plugin disable.
 * A redraw can also be forced immediately with {@link #refresh(Race)}, so a checkpoint you just
 * created or moved appears in the same tick instead of after the next poll.
 */
public class CheckpointPreview implements Listener {

    /** How often the outlines are redrawn, in ticks. Short enough that particles never visibly lapse. */
    private static final long REDRAW_PERIOD = 10L;

    /** What {@link #toggle(Player, Race)} did. */
    public enum ToggleResult {
        /** The preview is now on for this race. */
        ON,
        /** The preview was on for this race and is now off. */
        OFF,
        /** The preview was on for another race; it has switched to this one. */
        SWITCHED
    }

    private record Session(Race race, BukkitTask task) {}

    private final Main main;
    private final Map<UUID, Session> sessions = new HashMap<>();

    /** Checkpoints that already failed to render once, so a broken gate doesn't log every tick. */
    private final Set<String> reportedFailures = new HashSet<>();

    public CheckpointPreview(Main main) {
        this.main = main;
    }

    public ToggleResult toggle(Player player, Race race) {
        Session existing = sessions.get(player.getUniqueId());

        if (existing != null && existing.race() == race) {
            stop(player);
            return ToggleResult.OFF;
        }

        boolean wasWatchingOtherRace = existing != null;
        stop(player);
        start(player, race);
        return wasWatchingOtherRace ? ToggleResult.SWITCHED : ToggleResult.ON;
    }

    /**
     * Starts the preview for {@code race} unless this player is already watching it. Used by the
     * checkpoint commands so an edit is always visible without a separate {@code /checkpoint view}.
     */
    public void ensureViewing(Player player, Race race) {
        Session existing = sessions.get(player.getUniqueId());
        if (existing != null && existing.race() == race) return;
        stop(player);
        start(player, race);
    }

    /**
     * Redraws {@code race} now for everyone watching it, instead of waiting for the next poll.
     * Cheap when nobody is watching.
     */
    public void refresh(Race race) {
        List<UUID> viewers = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (entry.getValue().race() == race) viewers.add(entry.getKey());
        }

        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            Session session = sessions.get(id);
            if (player == null || !player.isOnline()) {
                if (session != null) session.task().cancel();
                sessions.remove(id);
                continue;
            }
            draw(player, race);
        }
    }

    public void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) session.task().cancel();
    }

    /** Cancels every preview of {@code race} — used when the race is deleted or its world unloads. */
    public void stopRace(Race race) {
        Iterator<Map.Entry<UUID, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            if (entry.getValue().race() != race) continue;
            entry.getValue().task().cancel();
            iterator.remove();
        }
    }

    public void stopAll() {
        for (Session session : sessions.values()) session.task().cancel();
        sessions.clear();
    }

    private void start(Player player, Race race) {
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(main, () -> {
            Session session = sessions.get(player.getUniqueId());
            if (session == null || session.race() != race) return;

            if (!player.isOnline() || race.getCheckpoints().isEmpty()) {
                stop(player);
                return;
            }

            draw(player, race);
        }, 0L, REDRAW_PERIOD);

        sessions.put(player.getUniqueId(), new Session(race, task));
    }

    private void draw(Player player, Race race) {
        for (Checkpoint checkpoint : race.getCheckpoints()) {
            try {
                CheckpointRenderer.render(player, checkpoint);
            } catch (RuntimeException e) {
                //One malformed checkpoint must not kill the whole preview task, and it must not
                //re-log twice a second either.
                String key = race.getName() + "#" + checkpoint.getId();
                if (reportedFailures.add(key)) {
                    Main.warn("Couldn't draw checkpoint " + checkpoint.getId() + " of race "
                            + race.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
    }
}
