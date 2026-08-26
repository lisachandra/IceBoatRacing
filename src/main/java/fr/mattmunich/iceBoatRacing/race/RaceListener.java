package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
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
import static fr.mattmunich.iceBoatRacing.Messages.*;

public class RaceListener implements Listener {

    private final Main main;
    private final RaceManager raceManager;
    private final PitBoxManager pitBoxManager;

    public RaceListener(Main main, RaceManager raceManager, PitBoxManager pitBoxManager) {
        this.main = main;
        this.raceManager = raceManager;
        this.pitBoxManager = pitBoxManager;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (!player.isInsideVehicle()) return;
        if (!(player.getVehicle() instanceof Boat)) return;

        RaceData data = null;
        for(Race race : raceManager.activeRaces) {
            if(race.racers.containsKey(player.getUniqueId())) data = race.racers.get(player.getUniqueId());
        }

        if (data == null || data.race == null || data.car == null) {
            player.sendActionBar(Objects.requireNonNull(c("§e§oNo race data")));
            return;
        }

        if(ENFORCE_RACING_GAMEMODE && !player.getGameMode().equals(RACING_GAMEMODE)) {
            player.sendActionBar(Objects.requireNonNull(c("§cIllegal GameMode!")));
            return;
        }

        Race race = data.race;

        //Test the whole movement segment (not just the current point) against checkpoints, and
        //loop rather than checking only one per tick. Looping matters: if two checkpoints sit close
        //together (e.g. a sector gate right next to an auto-generated checkpoint on a narrow bridge),
        //a single tick's movement could cross both — checking only the first would silently strand
        //the racer on the second one forever, since next tick they're already past it.
        Location from = data.from == null ? event.getFrom() :  data.from;
        Location to = event.getTo();
        data.from = to;

        while (true) {
            int nextCheckpointID = data.checkpointIndex + 1;
            Checkpoint nextCheckpoint = race.getCheckpoint(nextCheckpointID);

            if (nextCheckpoint == null) {
                // Shouldn't happen
                data.checkpointIndex = 0;
                break;
            }

            if (!nextCheckpoint.crosses(from, to)) {
                //Allow to skip 1 checkpoint if detection fails
                int afterNextCheckpointID = data.checkpointIndex + 2;
                Checkpoint afterNextCheckpoint = race.getCheckpoint(afterNextCheckpointID);

                if(afterNextCheckpoint == null || !afterNextCheckpoint.crosses(from, to)) break;
                main.warn("Allowed " + player.getName() + " to skip checkpoint " + nextCheckpoint.getId() + " as they crossed checkpoint " + afterNextCheckpoint.getId()
                        + "\nIf this happens often, please check your server performances and if the player has an unstable connection or some kind of cheats.");
                long now = System.currentTimeMillis();

                //process skipped checkpoint
                if (nextCheckpoint.getType().equals(Checkpoint.Type.START_FINISH)) {
                    crossStartFinish(data, now, race);
                    break;
                } else if (nextCheckpoint.getType().equals(Checkpoint.Type.SECTOR)) {
                    crossSector(data, nextCheckpoint, now, player);
                }

                //process actually crossed checkpoint
                if (afterNextCheckpoint.getType().equals(Checkpoint.Type.START_FINISH)) {
                    crossStartFinish(data, now, race);
                    break;
                } else if (afterNextCheckpoint.getType().equals(Checkpoint.Type.SECTOR)) {
                    crossSector(data, afterNextCheckpoint, now, player);
                }

                data.checkpointIndex+=2;
                main.liveSidebar.getScore(player).setScore(((data.lapCount-1) * race.getCheckpoints().size()) + data.checkpointIndex);
                break;
            }

            long now = System.currentTimeMillis();

            if (nextCheckpoint.getType().equals(Checkpoint.Type.START_FINISH)) {
                crossStartFinish(data, now, race);
                break;
            }

            if (nextCheckpoint.getType().equals(Checkpoint.Type.SECTOR)) {
                crossSector(data, nextCheckpoint, now, player);
            }

            data.checkpointIndex++;
            main.liveSidebar.getScore(player).setScore(((data.lapCount-1) * race.getCheckpoints().size()) + data.checkpointIndex);
        }

        handlePitBox(player, data, race, to);
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
    /// @param data The racer's data
    /// @param now The current time in millis
    /// @param race The race (like what else am I meant to say)
    private void crossStartFinish(RaceData data, long now, Race race) {
        //1. When starting the race = crossing the start line for the first time
        // -> lapCount is set to 1 below
        if(data.lapCount==0) data.startTime = now;

        //2. Complete lap
        if (data.lapCount>0 && !(data.lapCount == race.getLapCount())) onCompleteLap(data, now);

        //3. Finish race
        if(data.lapCount == race.getLapCount()) {
            onCompleteLap(data, now);
            onFinishRace(data, now);

            //End race automatically
            if(race.racing.isEmpty()) {
                try {
                    race.end(true);
                } catch (Exception e) {
                    switch (e.getMessage()) {
                        case "NO_RANKING" -> Bukkit.getConsoleSender().sendMessage(Messages.getMessage("race.onEnd.error.noRanking"));
                        case "NO_RACERS" -> Bukkit.getConsoleSender().sendMessage(Messages.getMessage("race.onEnd.error.noRacers"));
                        default -> {
                            Bukkit.getConsoleSender().sendMessage(Messages.getMessage("error.unknown"));
                            main.err("Could not send ranking on race end", e);
                        }
                    }
                }
            }

            data.checkpointIndex = 0;
            return;  //racer just finished; nothing more to process for them this tick
        }

        data.lapTime = now;
        data.lapCount++;
        data.checkpointIndex = 1;
        main.liveSidebar.getScore(data.player).setScore(((data.lapCount-1) * race.getCheckpoints().size()) + data.checkpointIndex);
    }

    private static void crossSector(RaceData data, Checkpoint nextCheckpoint, long now, Player player) {
        data.addSectorTime(nextCheckpoint.getSectorID(), now, data.lapTime);
        Bukkit.broadcast(getMessage("race.onCrossSector",
                formatArguments(
                        "player", l(player.displayName()),
                        "ID", String.valueOf(nextCheckpoint.getSectorID()),
                        "time", formatTime(now - data.lapTime)
                )
        ));
    }

    private void onCompleteLap(RaceData data, long now) {
        Player player = data.player;
        long lapDuration = now - data.lapTime;
        long result = data.race.isBestLapTimeYet(lapDuration);
        boolean bestLapTimeYet = result != -1;
        data.lapTimes.add(lapDuration);
        if(bestLapTimeYet) {
            Bukkit.broadcast(getMessage("race.onCompleteLap.messageBestLapTimeYet",
                    formatArguments(
                            "player", l(player.displayName()),
                            "ID",  "" + data.lapCount,
                            "time", formatTime(lapDuration)
                    )
            ));
        } else {
            Bukkit.broadcast(getMessage("race.onCompleteLap.message",
                    formatArguments(
                            "player", l(player.displayName()),
                            "ID",  "" + data.lapCount,
                            "time", formatTime(lapDuration)
                    )
            ));
        }
        Title title = Title.title(
                getMessage("race.onCompleteLap.title", formatArguments(
                        "currentLapCount", "" + data.lapCount,
                        "raceLapCount", "" + data.race.getLapCount()
                )), getMessage("race.onCompleteLap.subtitle"));

        player.showTitle(title);
    }

    private static void onFinishRace(RaceData data, long now) {
        Player player = data.player;
        Race race = data.race;
        Map<Integer, RaceData> finished = race.rankings;
        race.racing.remove(data);
        data.endTime = now;
        data.racing = false;
        race.rankings.put(finished.size(), data);

        int ranking = finished.size();
        data.ranking = ranking; //Used for the final ranking later on
        data.disqualified = data.pitStopsCompleted < race.getRequiredPitStops();
        boolean isWinner = ranking==1;

        long raceTime = now - data.startTime;
        long gapToWinner = isWinner ? -1 : (raceTime - finished.get(0).getRaceTime());
        //get(ranking-1) = self ; get(ranking-2) = player #(ranking-1) ; because ranking = size (starts @ 1) and get(x) starts @ 0
        long gapToNext = isWinner ? -1 : (raceTime - finished.get(ranking-2).getRaceTime());
        data.gapToNextTime = gapToNext;

        Map<Integer,Long> bestSectorsTimes = data.bestSectorsTimes();

        int players = race.racers.size();

        Bukkit.broadcast(getMessage("race.onFinish.message",
                formatArguments(
                        "time", formatTime(raceTime),
                        "ranking", ranking + ""
                ),
                formatComponentArguments("player", player.displayName())
        ));

        if (data.disqualified) {
            Bukkit.broadcast(getMessage("race.onFinish.disqualifiedNotice", formatArguments(
                    "player", player.getName(),
                    "completed", data.pitStopsCompleted,
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
                            "meanLapTime", formatTime(data.meanLapTime()),
                            "bestLapTime", formatTime(data.bestLapTime()),
                            "worstLapTime", formatTime(data.worstLapTime())
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
                            "meanLapTime", formatTime(data.meanLapTime()),
                            "bestLapTime", formatTime(data.bestLapTime()),
                            "worstLapTime", formatTime(data.worstLapTime()),
                            "gapToWinner", formatTime(gapToWinner),
                            "gapToNext", formatTime(gapToNext)
                    ),
                    formatComponentArguments(
                            "bestSectorsTimes", bestSectorsTimesMessage
                    )
            ));
        }

        if (SPECTATE_ON_FINISH) {
            player.setGameMode(GameMode.SPECTATOR);
            boolean success = data.car.destroy();
            if (!success && player.getVehicle() != null) player.getVehicle().remove();
        } else {
            race.racing.remove(data);
        }
    }

    private void handlePitBox(Player player, RaceData data, Race race, Location to) {
        if (data.pitStopsCompleted >= race.getRequiredPitStops()) return; // done for this race
        if (data.pittingBox != null) return; // already serving a stop
        long sinceLastPitBox = System.currentTimeMillis() - data.lastPitBox;
        if(sinceLastPitBox < 10000) return; //10 secs delay before pitting again -> prevent double pitting

        for (PitBox box : race.getPitBoxes()) {
            if (!box.contains(to)) continue;
            if (box.isOccupied()) continue;
            if (!box.isAllowed(player.getName())) continue;

            pitBoxManager.startSession(player, data, box);
            return;
        }
    }
}