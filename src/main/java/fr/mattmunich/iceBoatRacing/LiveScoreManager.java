package fr.mattmunich.iceBoatRacing;

import fr.mattmunich.iceBoatRacing.race.Racer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.mattmunich.iceBoatRacing.Messages.*;

/**
 * Per-player live timing sidebar.
 * <p>
 * Each racer gets their own scoreboard, so the plugin never touches the server's main
 * scoreboard and non-racers on a survival server never see a race sidebar. Players get the
 * scoreboard they were using before the race back as soon as they stop racing.
 */
public class LiveScoreManager {
    private static final String OBJECTIVE = "IBR.liveScore";

    /** The sidebar scoreboard currently shown to each racer. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    /** What each racer was using before we replaced it, so it can be restored afterwards. */
    private final Map<UUID, Scoreboard> previousBoards = new HashMap<>();

    private String entryFormat(Racer racer) { return "liveScore-" + racer.getPlayer().getName(); }

    /**
     * Shows the sidebar to {@code racer} and creates their row.
     * Silently does nothing when the racer is offline, so callers don't need null checks.
     */
    public void addRacer(Racer racer) {
        Player player = racer.getPlayer();
        if (player == null || !player.isOnline()) return;

        //Re-adding the same racer: drop the old sidebar first so rows don't stack
        if (boards.containsKey(player.getUniqueId())) removeRacer(player);

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, getMessage("liveScore.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());

        Score score = objective.getScore(entryFormat(racer));
        score.customName(formatScore(racer));
        score.setScore(0);

        previousBoards.put(player.getUniqueId(), player.getScoreboard());
        boards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
    }

    /**
     * Removes the sidebar from {@code racer} and puts them back on whatever they were using.
     */
    public void removeRacer(Racer racer) {
        Player player = racer.getPlayer();
        if (player == null) return;
        removeRacer(player);
    }

    public void removeRacer(Player player) {
        if (player == null) return;

        Scoreboard board = boards.remove(player.getUniqueId());
        Scoreboard previous = previousBoards.remove(player.getUniqueId());
        if (board == null) return;

        if (!player.isOnline()) return;

        if (previous != null && !previous.equals(Bukkit.getScoreboardManager().getMainScoreboard())) {
            player.setScoreboard(previous);
        } else {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /**
     * Refreshes a racer's row. No-op if they don't have a sidebar (e.g. they already finished).
     */
    public void updateRacer(Racer racer) {
        Player player = racer.getPlayer();
        if (player == null) return;

        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) return;

        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) return;

        Score score = objective.getScore(entryFormat(racer));
        score.setScore(racer.getScore());
        score.customName(formatScore(racer));
    }

    /**
     * Drops every sidebar. Called on race end and reload; players get their previous scoreboard back.
     */
    public void empty() {
        for (UUID uuid : new HashMap<>(boards).keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                removeRacer(player);
            } else {
                boards.remove(uuid);
                previousBoards.remove(uuid);
            }
        }
    }

    /**
     * Same as {@link #empty()}, kept for the reload path.
     */
    public void clear() {
        empty();
    }

    private Component formatScore(Racer racer) {
        return getMessage("liveScore.format", formatArguments(
                "player", racer.getPlayer().getName(),
                "completedLapCount", racer.getCompletedLapCount(),
                "lapCount", racer.getRace().getLapCount(),
                "completedPitStops", racer.getCompletedPitStops(),
                "requiredPitStops", racer.getRace().getRequiredPitStops()
        ));
    }
}
