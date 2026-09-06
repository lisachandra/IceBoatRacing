package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.cars.CarCreator;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.mattmunich.iceBoatRacing.Main.s;
import static fr.mattmunich.iceBoatRacing.Messages.formatArguments;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;
import static fr.mattmunich.iceBoatRacing.Messages.getStringMessage;

public class RaceCreator implements Listener {

    /*
     * -----------------------
     * |    Race creation    |
     * -----------------------
     * Stages/Steps:
     * 1. Name the race
     * 2. Define lap count
     * 3. Ask if autotrace now (yes => 4. ; no => 5.)
     * 4. Autotrace
     * 5. Ask if define cars now (yes => 6. ; no => end )
     * 6. Define cars
     * 7. End (Save)
     *
     * The actual things to do:
     * -> Define to 1 if define finish =/= start
     */

    private final Main main;
    private final RaceManager raceManager;
    private final CarCreator carCreator;

    public RaceCreator(Main main, RaceManager raceManager, CarCreator carCreator) {
        this.main = main;
        this.raceManager = raceManager;
        this.carCreator = carCreator;
    }

    /**
     * The map that contains players who are creating a race and the stage of the creation process
     */
    public static final Map<Player, Integer> creatingRace = new HashMap<>();

    //Saved data during the process
    /**
     * Saves the name of the race during the creation process
     */
    static final Map<Player, Race> tempRace =  new HashMap<>();

    /**
     * When player wants to cancel the creation process of a race, it will be put in this list.
     * A listener will then await a confirmation message.
     */
    static final List<Player> confirmRaceCancel = new ArrayList<>();

    /**
     * The list the player will be put into, to be able to identify if they are using this
     * class (RaceCreator) to create cars. This will automatically ask them if they want to
     * rerun the /car create command when they're done creating a car. This will allow
     * CarCommand to identify players who are defining cars while creating the race.
     * @apiNote Work in progress
     */
    public static final Map<Player, String> definingCars = new HashMap<>();

    // --------------------------------
    //              STEP 1
    // --------------------------------
    /**
     * Start the race creation process
     * @param p the player who will be guided through the process
     */
    public void createRace(Player p) {
        //Stage 1
        creatingRace.put(p, 1);
        if(!p.hasPermission("iceboatracing.race.create")) return;

        Title title = Title.title(
                getMessage("race.create.1.title"),
                getMessage("race.create.1.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("race.create.1.message"));
    }

    @EventHandler
    public void nameRace(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingRace.get(p);
        if (step == null || step != 1) return;
        if(confirmRaceCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            //Allow to cancel process
            if(message.equals(getStringMessage("race.create.cancel"))) {
                creatingRace.remove(p);
                p.sendMessage(getMessage("race.create.cancelled"));
                return;
            }

            //Check if a race with the same name already exists
            final boolean[] exists = {false};
            raceManager.getRaces().forEach(race -> {if(race.getName().equalsIgnoreCase(message)) exists[0]=true;});

            if(exists[0]) {
                p.sendMessage(getMessage("race.create.1.alreadyExists"));
                return;
            }

            // Save race name temporarily
            Race race = new Race(message, p.getWorld());
            raceManager.createRace(race);
            tempRace.put(p, raceManager.getRace(message));
            main.log("Created race " + race + " with name " + race.getName());

            p.sendMessage(getMessage(
                    "race.create.1.completed",
                    formatArguments("name", message)
            ));

            // STEP 2
            Bukkit.getScheduler().runTaskLater(main, () -> showStep2(p),40L);
        });
    }

    // --------------------------------
    //              STEP 2
    // --------------------------------
    public void showStep2(Player p) {
        creatingRace.replace(p, 2);

        Title title = Title.title(
                getMessage("race.create.2.title"),
                getMessage("race.create.2.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("race.create.2.message"));
    }

    @EventHandler
    public void defineLapCount(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingRace.get(p);
        if (step == null || step != 2) return;
        if(confirmRaceCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());
        int count;
        try {
             count = Integer.parseInt(message);
        } catch (NumberFormatException ex) {
            p.sendMessage(getMessage("race.invalidLapCount"));
            return;
        }

        // Save race lapCount temporarily
        Bukkit.getScheduler().runTask(main, () -> {tempRace.get(p).setLapCount(count);
            try {
                tempRace.get(p).saveConfig();
            } catch (IOException ignored) {
                main.warn("Could not save race config when defining lap count on race creation.");
            }
        });

        p.sendMessage(getMessage(
                "race.create.2.completed",
                formatArguments("lapCount", count)
        ));

        // STEP 3
        Bukkit.getScheduler().runTaskLater(main, () -> showStep3(p),40L);
    }

    // --------------------------------
    //              STEP 3
    // --------------------------------

    public void showStep3(Player p) {
        creatingRace.replace(p, 3);

        Title title = Title.title(
                getMessage("race.create.3.title"),
                getMessage("race.create.3.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("race.create.3.message", formatArguments(
                "start", getStringMessage("race.create.start"),
                "later", getStringMessage("race.create.later")
        )));
    }

    @EventHandler
    public void checkIfAutoTrace(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingRace.get(p);
        if (step == null || step != 3) return;
        if(confirmRaceCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());
        Bukkit.getScheduler().runTask(main, () -> {
            String basePath = "race.create.3.";
            if (message.equalsIgnoreCase(getStringMessage("race.create.start"))) {
                //STEP 4
                creatingRace.replace(p, 4);
                step4(p);
            } else if (message.equalsIgnoreCase(getStringMessage("race.create.later"))) {
                //SKIP STEP 4 -> STEP 5
                creatingRace.replace(p, 5);
                showStep5(p);
            } else if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
                confirmRaceCancel.add(p);
                cancel(p);
            } else {
                Title title = Title.title(
                        getMessage(basePath + "title"),
                        getMessage(basePath + "subtitle")
                );
                p.showTitle(title);
                p.sendMessage(getMessage(basePath + "message", formatArguments("start", getStringMessage("race.create.start"), "later", getStringMessage("race.create.later"))));
            }
        });
    }

    // --------------------------------
    //              STEP 4
    // --------------------------------

    public void step4(Player p) {
        if(confirmRaceCancel.contains(p)) return;
        //Tells player to go to the start of the race
        String basePath = "race.create.4.";
        Title title = Title.title(
                getMessage(basePath + "title"),
                getMessage(basePath + "subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage(basePath + "message"));
        Bukkit.getScheduler().runTask(main, () -> p.performCommand("checkpoint autotrace start " + tempRace.get(p).getName() + " --create"));
    }

    @EventHandler
    public void step4Chat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingRace.get(p);
        if (step == null || step != 4) return;
        if(confirmRaceCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());
        if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
            cancel(p);
            return;
        }
        step4(p);
    }

    public void goToStep5RaceCreation(Player p) {
        String basePath = "race.create.4.done.";
        Title title = Title.title(
                getMessage(basePath + "title"),
                getMessage(basePath + "subtitle")
        );
        p.showTitle(title);

        Bukkit.getScheduler().runTaskLater(main, () -> {
            creatingRace.replace(p, 5);

            showStep5(p);
        },100L);
    }

    // --------------------------------
    //              STEP 5
    // --------------------------------

    private static void showStep5(Player p) {
        String basePath = "race.create.5.";
        Title title = Title.title(
                getMessage(basePath + "title"),
                getMessage(basePath + "subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage(basePath + "message", formatArguments(
                "start", getStringMessage("race.create.start"),
                "later", getStringMessage("race.create.later")
        )));
    }


    @EventHandler
    public void checkIfCarCreation(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingRace.get(p);
        if (step == null || step != 5) return;
        if(confirmRaceCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());
        Bukkit.getScheduler().runTask(main, () -> {

            String basePath = "race.create.5";
            if (message.equalsIgnoreCase(getStringMessage("race.create.start"))) {
                //STEP 6
                step6(p);
            } else if (message.equalsIgnoreCase(getStringMessage("race.create.later"))) {
                //SKIP STEP 6 -> END
                endRaceCreation(p);
            } else if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
                cancel(p);
            } else {
                Title title = Title.title(
                        getMessage(basePath + ".title"),
                        getMessage(basePath + ".subtitle")
                );
                p.showTitle(title);
                p.sendMessage(getMessage(basePath + ".message", formatArguments(
                        "start", getStringMessage("race.create.start"),
                        "later", getStringMessage("race.create.later")
                )));
            }
        });
    }

    // --------------------------------
    //              STEP 6
    // --------------------------------

    public void step6(Player p) {
        creatingRace.replace(p, 6);

        Title title = Title.title(
                getMessage("race.create.6.title"),
                getMessage("race.create.6.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("race.create.6.message", formatArguments(
                "done", getStringMessage("race.create.6.carCreation.done")
        )));

        definingCars.put(p, tempRace.get(p).getName());
        Bukkit.getScheduler().runTaskLater(main, () -> carCreator.createCar(p) ,40L);
        Bukkit.getScheduler().runTaskTimer(main, task -> {
            if(!definingCars.containsKey(p)) task.cancel();

            p.sendActionBar(getMessage("race.create.6.feedback.endInstructions.actionBar", formatArguments(
                    "done", getStringMessage("race.create.6.carCreation.done")
            )));
        } ,100L, 40L);
    }

    @EventHandler
    public void onFinishDefiningCars(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingRace.get(p);
        if (step == null || step != 6) return;
        if(confirmRaceCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());
        if(!message.equalsIgnoreCase(getStringMessage("race.create.6.carCreation.done"))) return;

        Bukkit.getScheduler().runTask(main, () -> {
            Title title = Title.title(
                    getMessage("race.create.6.feedback.done.title"),
                    getMessage("race.create.6.feedback.done.subtitle")
            );
            p.showTitle(title);

            definingCars.remove(p);
            carCreator.cleanup(p);

            Bukkit.getScheduler().runTaskLater(main ,() -> endRaceCreation(p), 60L);
        });
    }

    // --------------------------------
    //               END
    // --------------------------------

    private void endRaceCreation(Player p) {
        creatingRace.remove(p);
        tempRace.remove(p);

        String donePath = "race.create.done.";
        Title title = Title.title(
                getMessage( donePath + "title"),
                getMessage(donePath + "subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage(donePath + "message"));
    }

    // --------------------------------
    //            UTILITIES
    // --------------------------------



    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (creatingRace.containsKey(p)) cancelCleanUp(p);
    }

    public void cancel(Player p) {
        confirmRaceCancel.add(p);
        p.sendMessage(getMessage("race.create.confirmCancel", formatArguments(
                "confirm", getStringMessage("race.create.confirm"),
                "cancel", getStringMessage("race.create.cancel")
        )));
    }

    @EventHandler
    public void confirmRaceCancel(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if(!confirmRaceCancel.contains(p)) return;
        e.setCancelled(true);

        String message = s(e.message());
        if(message.equalsIgnoreCase(getStringMessage("race.create.confirm"))) {
            cancelCleanUp(p);
        } else {
            confirmRaceCancel.remove(p);
            switch (creatingRace.get(p)) {
                case 1 -> createRace(p);
                case 2 -> showStep2(p);
                case 3 -> showStep3(p);
                case 4 -> step4(p);
                case 5 -> showStep5(p);
            }
        }
    }

    private void cancelCleanUp(Player p) {
        Race race = tempRace.get(p);
        creatingRace.remove(p);
        tempRace.remove(p);
        Bukkit.getScheduler().runTask(main, () -> {
            if (race == null) {
                p.sendMessage(getMessage("error.unknown"));
                main.warn("Couldn't delete race for " + p.getName() + " because it didn't exist (race creating process - cancel clean up)");
            } else {
                p.performCommand("checkpoint autotrace cancel --noRestart");
                raceManager.deleteRace(race);
            }

            p.sendMessage(getMessage("race.create.cancelled"));
            confirmRaceCancel.remove(p);
        });
    }
}
