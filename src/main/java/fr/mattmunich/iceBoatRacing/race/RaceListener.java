package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.LiveScoreManager;
import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.checkpoint.Checkpoint;
import fr.mattmunich.iceBoatRacing.pitbox.PitBox;
import fr.mattmunich.iceBoatRacing.pitbox.PitBoxManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;
import java.util.Objects;

import static fr.mattmunich.iceBoatRacing.Main.*;
import static fr.mattmunich.iceBoatRacing.Main.warn;
import static fr.mattmunich.iceBoatRacing.Messages.*;

public class RaceListener implements Listener {

    private final RaceManager raceManager;
    private final PitBoxManager pitBoxManager;
    private final LiveScoreManager liveScoreManager;

    public RaceListener(RaceManager raceManager, PitBoxManager pitBoxManager, LiveScoreManager liveScoreManager) {
        this.raceManager = raceManager;
        this.pitBoxManager = pitBoxManager;
        this.liveScoreManager = liveScoreManager;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        //Chest pit stops are served on foot, so keep watching the box zone even while the
        //driver is not in their boat (walking out abandons the stop)
        pitBoxManager.handleChestSessionMove(player, event.getTo());

        if (!player.isInsideVehicle()) return;
        if (!(player.getVehicle() instanceof Boat)) return;

        Racer racer = null;
        for(Race race : raceManager.activeRaces) {
            if(race.racers.containsKey(player.getUniqueId())) racer = race.racers.get(player.getUniqueId());
        }

        if (racer == null || racer.race == null || racer.car == null) {
            player.sendActionBar(Objects.requireNonNull(c("§e§oNo race racer")));
            return;
        }

        //Finished racers stay in `race.racers` for the final ranking, but must not be processed again
        if (!racer.racing || racer.finished) return;

        Race race = racer.race;

        //Test the whole movement segment (not just the current point) against checkpoints, and
        //loop rather than checking only one per tick. Looping matters: if two checkpoints sit close
        //together (e.g. a sector gate right next to an auto-generated checkpoint on a narrow bridge),
        //a single tick's movement could cross both — checking only the first would silently strand
        //the racer on the second one forever, since next tick they're already past it.
        Location from = racer.from == null ? event.getFrom() :  racer.from;
        Location to = event.getTo();
        racer.from = to;

        while (true) {
            int nextCheckpointID = racer.checkpointIndex + 1;
            Checkpoint nextCheckpoint = race.getCheckpoint(nextCheckpointID);

            if (nextCheckpoint == null) {
                // Shouldn't happen
                racer.checkpointIndex = 0;
                break;
            }

            if (!nextCheckpoint.crosses(from, to)) {
                //Allow to skip 1 checkpoint if detection fails
                int afterNextCheckpointID = racer.checkpointIndex + 2;
                Checkpoint afterNextCheckpoint = race.getCheckpoint(afterNextCheckpointID);

                if(afterNextCheckpoint == null || !afterNextCheckpoint.crosses(from, to)) break;
                warn("Allowed " + player.getName() + " to skip checkpoint " + nextCheckpoint.getId() + " as they crossed checkpoint " + afterNextCheckpoint.getId()
                        + "\nIf this happens often, please check your server performances and if the player has an unstable connection or some kind of cheats.");
                long now = System.currentTimeMillis();

                //process skipped checkpoint
                if (nextCheckpoint.getType().equals(Checkpoint.Type.START_FINISH)) {
                    crossStartFinish(racer, now, race);
                    break;
                } else if (nextCheckpoint.getType().equals(Checkpoint.Type.SECTOR)) {
                    crossSector(racer, nextCheckpoint, now, player);
                }

                //process actually crossed checkpoint
                if (afterNextCheckpoint.getType().equals(Checkpoint.Type.START_FINISH)) {
                    crossStartFinish(racer, now, race);
                    break;
                } else if (afterNextCheckpoint.getType().equals(Checkpoint.Type.SECTOR)) {
                    crossSector(racer, afterNextCheckpoint, now, player);
                }

                racer.checkpointIndex+=2;
//                main.liveSidebar.getScore(player).setScore(((racer.lapCount-1) * race.getCheckpoints().size()) + racer.checkpointIndex);
                liveScoreManager.updateRacer(racer);

                player.sendActionBar(getMessage("race.onCrossGate.actionBar", formatArguments(
                        "id", "" + afterNextCheckpoint.getId(),
                        "total", "" + race.getCheckpoints().size()
                )));
                break;
            }

            long now = System.currentTimeMillis();

            if (nextCheckpoint.getType().equals(Checkpoint.Type.START_FINISH)) {
                crossStartFinish(racer, now, race);
                break;
            }

            if (nextCheckpoint.getType().equals(Checkpoint.Type.SECTOR)) {
                crossSector(racer, nextCheckpoint, now, player);
            }

            racer.checkpointIndex++;
//            main.liveSidebar.getScore(player).setScore(((racer.lapCount-1) * race.getCheckpoints().size()) + racer.checkpointIndex);
            liveScoreManager.updateRacer(racer);

            //Normal gates are otherwise silent: ping the driver's progress so a missed gate is
            //noticeable (their gate counter simply stops ticking up)
            player.sendActionBar(getMessage("race.onCrossGate.actionBar", formatArguments(
                    "id", "" + nextCheckpoint.getId(),
                    "total", "" + race.getCheckpoints().size()
            )));
        }

        handlePitBox(player, racer, race, to);
    }

    /// # Cross start finish
    /// _The method that handles the event of a player crossing the start/finish line._
    /// ---
    /// The system resembles to how Mario Cart handles laps, but it is in no way a copy, this is code is
    /// in no way the code Mario Cart and has been written by me (mattmunich).
    ///
    /// What I mean by that is: when you start your final lap, a title saying "3/3" (for example) will be sent,
    /// indicating that you are starting the 3rd lap out of 3 (in this example), which is the final lap.
    ///
    /// #### 1. Handles when a player starts the race: they cross the start line for the first time
    /// -> Lap count is set to 1 below.
    ///
    /// #### 2. Complete lap is handles by [onCompleteLap]
    ///
    /// #### 3. Handles when a player finishes the race
    /// > -> run [onCompleteLap] <br/>
    /// > -> run [onFinishRace] <br/>
    /// > -> Check if there are no more racers <br/>
    /// >   -> If so: send the ranking with [Race#sendRanking] and end the race using [Race#end]
    ///
    /// #### Finally:
    /// - update {@code lapTime} to `now`
    /// - update {@code lapCount} to += 1
    /// - update {@code checkpointIndex} to 1 (so that we handle checkpoint 2 on next {@link PlayerMoveEvent})
    /// - update the SideBar
    ///
    /// @param racer The racer's data
    /// @param now The current time in millis
    /// @param race The race (like what else am I meant to say)
    private void crossStartFinish(Racer racer, long now, Race race) {
        //1. When starting the race = crossing the start line for the first time
        // -> lapCount is set to 1 below
        if(racer.lapCount==0) racer.startTime = now;

        //2. Complete lap
        if (racer.lapCount>0 && !(racer.lapCount == race.getLapCount())) onCompleteLap(racer, now);

        //3. Finish race
        if(racer.lapCount == race.getLapCount()) {
            onCompleteLap(racer, now);
            onFinishRace(racer, now);

            //End race automatically
            if(race.racing.isEmpty()) {
                try {
                    race.end(true);
                } catch (Exception e) {
                    switch (e.getMessage()) {
                        case "NO_RANKING" -> Bukkit.getConsoleSender().sendMessage(getMessage("race.onEnd.error.noRanking"));
                        case "NO_RACERS" -> Bukkit.getConsoleSender().sendMessage(getMessage("race.onEnd.error.noRacers"));
                        default -> {
                            Bukkit.getConsoleSender().sendMessage(getMessage("error.unknown"));
                            err("Could not send ranking on race end", e);
                        }
                    }
                }
            }

            racer.checkpointIndex = 0;
            return;  //racer just finished; nothing more to process for them this tick
        }

        racer.lapTime = now;
        racer.lapCount++;
        racer.checkpointIndex = 1;
//        main.liveSidebar.getScore(racer.player).setScore(((racer.lapCount-1) * race.getCheckpoints().size()) + racer.checkpointIndex);
        liveScoreManager.updateRacer(racer);
    }

    private static void crossSector(Racer racer, Checkpoint nextCheckpoint, long now, Player player) {
        racer.addSectorTime(nextCheckpoint.getSectorID(), now, racer.lapTime);
        Bukkit.broadcast(getMessage("race.onCrossSector",
                formatArguments(
                        "player", l(player.displayName()),
                        "ID", String.valueOf(nextCheckpoint.getSectorID()),
                        "time", formatTime(now - racer.lapTime)
                )
        ));
    }

    private void onCompleteLap(Racer racer, long now) {
        Player player = racer.player;
        long lapDuration = now - racer.lapTime;
        long result = racer.race.isBestLapTimeYet(lapDuration);
        boolean bestLapTimeYet = result != -1;
        racer.lapTimes.add(lapDuration);
        if(bestLapTimeYet) {
            Bukkit.broadcast(getMessage("race.onCompleteLap.messageBestLapTimeYet",
                    formatArguments(
                            "player", l(player.displayName()),
                            "ID",  "" + racer.lapCount,
                            "time", formatTime(lapDuration)
                    )
            ));
        } else {
            Bukkit.broadcast(getMessage("race.onCompleteLap.message",
                    formatArguments(
                            "player", l(player.displayName()),
                            "ID",  "" + racer.lapCount,
                            "time", formatTime(lapDuration)
                    )
            ));
        }
        Title title = Title.title(
                getMessage("race.onCompleteLap.title", formatArguments(
                        "currentLapCount", "" + racer.lapCount,
                        "raceLapCount", "" + racer.race.getLapCount()
                )), getMessage("race.onCompleteLap.subtitle"));

        player.showTitle(title);
    }

    private void onFinishRace(Racer racer, long now) {
        Player player = racer.player;
        Race race = racer.race;

        liveScoreManager.removeRacer(racer); //make sure that the last players are visible

        Map<Integer, Racer> finished = race.rankings;
        race.racing.remove(racer);
        racer.endTime = now;
        racer.racing = false;
        racer.finished = true;
        race.rankings.put(finished.size(), racer);

        int ranking = finished.size();
        racer.ranking = ranking; //Used for the final ranking later on
        racer.disqualified = racer.pitStopsCompleted < race.getRequiredPitStops();
        boolean isWinner = ranking==1;

        long raceTime = now - racer.startTime;
        long gapToWinner = isWinner ? -1 : (raceTime - finished.get(0).getRaceTime());
        //get(ranking-1) = self ; get(ranking-2) = player #(ranking-1) ; because ranking = size (starts @ 1) and get(x) starts @ 0
        long gapToNext = isWinner ? -1 : (raceTime - finished.get(ranking-2).getRaceTime());
        racer.gapToNextTime = gapToNext;

        Map<Integer,Long> bestSectorsTimes = racer.bestSectorsTimes();

        int players = race.participantCount > 0 ? race.participantCount : race.racers.size();

        Bukkit.broadcast(getMessage("race.onFinish.message",
                formatArguments(
                        "time", formatTime(raceTime),
                        "ranking", ranking + ""
                ),
                formatComponentArguments("player", player.displayName())
        ));

        if (racer.disqualified) {
            Bukkit.broadcast(getMessage("race.onFinish.disqualifiedNotice", formatArguments(
                    "player", player.getName(),
                    "completed", racer.pitStopsCompleted,
                    "required", race.getRequiredPitStops()
            )));
        }

        Title title = Title.title(
                getMessage("race.onFinish.title"),
                getMessage("race.onFinish.subtitle", formatArguments("ranking", ranking + ""))
        );

        player.showTitle(title);

        Component bestSectorsTimesMessage = c("§cNo data");
        if (!bestSectorsTimes.isEmpty()) {
            bestSectorsTimesMessage = c("");
            for (Map.Entry<Integer, Long> entry : bestSectorsTimes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                bestSectorsTimesMessage = bestSectorsTimesMessage.append(getMessage("race.onFinish.sectorFormat",formatArguments(
                    "sectorID", "" + entry.getKey(),
                        "time", formatTime(entry.getValue())
                )));
            }
            if (bestSectorsTimesMessage.equals(c(""))) bestSectorsTimesMessage = c("§cNo data");
        }


        if(isWinner) {
            player.sendMessage(getMessage("race.onFinish.statsMessageWinner",
                    formatArguments(
                            "ranking", ranking + "",
                            "players",  players + "",
                            "raceTime", formatTime(raceTime),
                            "meanLapTime", formatTime(racer.meanLapTime()),
                            "bestLapTime", formatTime(racer.bestLapTime()),
                            "worstLapTime", formatTime(racer.worstLapTime())
                    ),
                    formatComponentArguments(
                            "bestSectorsTimes", bestSectorsTimesMessage
                    )
            ));
        } else {
            player.sendMessage(getMessage("race.onFinish.statsMessage",
                    formatArguments(
                            "ranking", ranking + "",
                            "players",  players + "",
                            "raceTime", formatTime(raceTime),
                            "meanLapTime", formatTime(racer.meanLapTime()),
                            "bestLapTime", formatTime(racer.bestLapTime()),
                            "worstLapTime", formatTime(racer.worstLapTime()),
                            "gapToWinner", formatTime(gapToWinner),
                            "gapToNext", formatTime(gapToNext)
                    ),
                    formatComponentArguments(
                            "bestSectorsTimes", bestSectorsTimesMessage
                    )
            ));
        }

        //Put the finished racer back where they joined from. They stay in `race.racers` so the
        //final ranking still reports the full field, but `racer.racing` is false so onMove()
        //ignores them and they can't re-cross the line and be ranked twice.
        if (racer.car != null) {
            boolean destroyed = racer.car.destroy();
            if (!destroyed && player.getVehicle() != null) player.getVehicle().remove();
        }
        if (player.getVehicle() != null) player.getVehicle().remove();

        raceManager.teleportBack(player, racer);
    }

    private void handlePitBox(Player player, Racer racer, Race race, Location to) {
        if (racer.pitStopsCompleted >= race.getRequiredPitStops()) return; // done for this race
        if (racer.pittingBox != null) return; // already serving a stop
        long sinceLastPitBox = System.currentTimeMillis() - racer.lastPitBox;
        if(sinceLastPitBox < 10000) return; //10 secs delay before pitting again -> prevent double pitting

        for (PitBox box : race.getPitBoxes()) {
            if (!box.contains(to)) continue;
            if (!box.isAllowed(player)) continue;

            pitBoxManager.startSession(player, racer, box);
            return;
        }
    }
}