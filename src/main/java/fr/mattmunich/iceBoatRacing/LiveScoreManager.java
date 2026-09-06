package fr.mattmunich.iceBoatRacing;

import fr.mattmunich.iceBoatRacing.race.Racer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import static fr.mattmunich.iceBoatRacing.Messages.*;

public class LiveScoreManager {
    Scoreboard scoreboard;
    Objective liveScore;
    private String entryFormat(Racer racer) { return "liveScore-" + racer.getPlayer().getName(); }

    public LiveScoreManager() {
        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective tempSidebar = scoreboard.getObjective("IBR.liveScore");
        if(tempSidebar != null) tempSidebar.unregister();
        liveScore = scoreboard.registerNewObjective("IBR.liveScore", Criteria.DUMMY, getMessage("liveScore.title"));
        liveScore.setDisplaySlot(DisplaySlot.SIDEBAR);
        liveScore.numberFormat(NumberFormat.blank());
    }

    public void clear() {
        liveScore.unregister();
        liveScore = null;
    }

    public void addRacer(Racer racer) {
        Score s = liveScore.getScore(entryFormat(racer));
        s.customName(formatScore(racer));
        s.setScore(0);
    }

    public void removeRacer(Racer racer) {
        Score s = liveScore.getScore(entryFormat(racer));
        s.resetScore();
    }
    public void removeRacer(Player racer) {
        Score s = liveScore.getScore(racer);
        s.resetScore();
    }

    public void updateRacer(Racer racer) {
        Score s = liveScore.getScore(entryFormat(racer));
        s.setScore(racer.getScore());
        s.customName(formatScore(racer));
    }

    private Component formatScore(Racer racer) {
        return getMessage("liveScore.format", formatArguments(
//                "ranking", "?", //TODO calculate live ranking ?
                "player", racer.getPlayer().getName(),
                "completedLapCount", racer.getCompletedLapCount(),
                "lapCount", racer.getRace().getLapCount(),
                "completedPitStops", racer.getCompletedPitStops(),
                "requiredPitStops", racer.getRace().getRequiredPitStops()
        ));
    }
}
