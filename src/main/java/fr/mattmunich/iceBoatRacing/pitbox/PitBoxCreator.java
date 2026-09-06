package fr.mattmunich.iceBoatRacing.pitbox;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.PosSelection;
import fr.mattmunich.iceBoatRacing.race.Race;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.s;
import static fr.mattmunich.iceBoatRacing.Messages.formatArguments;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;
import static fr.mattmunich.iceBoatRacing.Messages.getStringMessage;

@SuppressWarnings("unused")
public class PitBoxCreator implements Listener {

    /*
     * -----------------------
     * |  Pit box creation   |
     * -----------------------
     * Stages/Steps:
     * 1. Name the pit box
     * 2. Define the trigger location (wand, reuses CheckpointCommand's pos1/pos2)
     * 3. Define the task duration (seconds)
     * 4. Define who can use it (player names or *)
     * 5. End (save)
     */

    private final Main main;
    private final PitBoxManager pitBoxManager;

    public PitBoxCreator(Main main, PitBoxManager pitBoxManager) {
        this.main = main;
        this.pitBoxManager = pitBoxManager;
    }

    public static final Map<Player, Integer> creatingPitBox = new HashMap<>();
    static final Map<Player, PendingPitBoxConfig> tempPitBox = new HashMap<>();
    static final List<Player> confirmCancel = new ArrayList<>();

    // --------------------------------
    //              STEP 1
    // --------------------------------
    public void createPitBox(Player p, Race race) {
        creatingPitBox.put(p, 1);
        tempPitBox.put(p, new PendingPitBoxConfig(race));

        Title title = Title.title(
                getMessage("pitbox.create.1.title"),
                getMessage("pitbox.create.1.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("pitbox.create.1.message"));
    }

    @EventHandler
    public void nameStep(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingPitBox.get(p);
        if (step == null || step != 1) return;
        if (confirmCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
                cancel(p);
                return;
            }

            tempPitBox.get(p).name = message;
            p.sendMessage(getMessage("pitbox.create.1.completed", formatArguments("name", message)));

            showStep2(p);
        });
    }

    // --------------------------------
    //              STEP 2
    // --------------------------------
    public void showStep2(Player p) {
        creatingPitBox.put(p, 2);

        Title title = Title.title(
                getMessage("pitbox.create.2.title"),
                getMessage("pitbox.create.2.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("pitbox.create.2.message", formatArguments(
                "confirm", getStringMessage("pitbox.create.confirm")
        )));
    }

    @EventHandler
    public void locationStep(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingPitBox.get(p);
        if (step == null || step != 2) return;
        if (confirmCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
                cancel(p);
                return;
            }

            if (!message.equalsIgnoreCase(getStringMessage("pitbox.create.confirm"))) {
                p.sendMessage(getMessage("pitbox.create.2.message", formatArguments(
                        "confirm", getStringMessage("pitbox.create.confirm")
                )));
                return;
            }

            Location l1 = PosSelection.pos1.get(p);
            Location l2 = PosSelection.pos2.get(p);
            if (l1 == null || l2 == null) {
                p.sendMessage(getMessage("pos.notSet"));
                return;
            }

            PendingPitBoxConfig config = tempPitBox.get(p);
            config.pos1 = l1;
            config.pos2 = l2;
            PosSelection.pos1.remove(p);
            PosSelection.pos2.remove(p);

            p.sendMessage(getMessage("pitbox.create.2.completed"));
            showStep3(p);
        });
    }

    // --------------------------------
    //              STEP 3
    // --------------------------------
    public void showStep3(Player p) {
        creatingPitBox.put(p, 3);

        Title title = Title.title(
                getMessage("pitbox.create.3.title"),
                getMessage("pitbox.create.3.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("pitbox.create.3.message"));
    }

    @EventHandler
    public void durationStep(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingPitBox.get(p);
        if (step == null || step != 3) return;
        if (confirmCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());

        if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
            Bukkit.getScheduler().runTask(main, () -> cancel(p));
            return;
        }

        int duration;
        try {
            duration = Integer.parseInt(message);
        } catch (NumberFormatException ex) {
            p.sendMessage(getMessage("error.invalidNumber"));
            return;
        }

        if (duration <= 0) {
            p.sendMessage(getMessage("error.invalidNumber"));
            return;
        }

        Bukkit.getScheduler().runTask(main, () -> {
            tempPitBox.get(p).duration = duration;
            p.sendMessage(getMessage("pitbox.create.3.completed", formatArguments("duration", duration)));
            showStep4(p);
        });
    }

    // --------------------------------
    //              STEP 4
    // --------------------------------
    public void showStep4(Player p) {
        creatingPitBox.put(p, 4);

        Title title = Title.title(
                getMessage("pitbox.create.4.title"),
                getMessage("pitbox.create.4.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("pitbox.create.4.message"));
    }

    @EventHandler
    public void allowedStep(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingPitBox.get(p);
        if (step == null || step != 4) return;
        if (confirmCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
                cancel(p);
                return;
            }

            List<String> allowedNames = message.equals("*")
                    ? new ArrayList<>() //No filter
                    : Arrays.asList(message.trim().split("\\s+"));

            List<UUID> allowedUUIDs = new ArrayList<>();
            OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();
            for  (String allowedName : allowedNames) {
                Optional<OfflinePlayer> result = Arrays.stream(offlinePlayers)
                        .filter(off -> Objects.requireNonNull(off.getName()).equalsIgnoreCase(allowedName))
                        .findFirst();
                if (result.isPresent()) {
                    allowedUUIDs.add(result.get().getUniqueId());
                } else {
                    p.sendMessage(getMessage("playerNeverJoined", formatArguments("name",  allowedName)));
                    return;
                }
            }

            tempPitBox.get(p).allowed = new ArrayList<>(allowedUUIDs);
            p.sendMessage(getMessage("pitbox.create.4.completed", formatArguments("players", String.join(", ", allowedNames.isEmpty() ? List.of("*") : allowedNames))));

            showStep5(p);
        });
    }

    public void showStep5(Player p) {
        creatingPitBox.put(p, 5);

        Title title = Title.title(
                getMessage("pitbox.create.5.title"),
                getMessage("pitbox.create.5.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("pitbox.create.5.message", formatArguments("availableColors", PitBoxColor.values())));
    }

    @EventHandler
    public void colorStep(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingPitBox.get(p);
        if (step == null || step != 5) return;
        if (confirmCancel.contains(p)) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            if (message.equalsIgnoreCase(getStringMessage("race.create.cancel"))) {
                cancel(p);
                return;
            }

            PitBoxColor color;
            try {
                color = PitBoxColor.valueOf(message.toUpperCase());
            } catch (IllegalArgumentException ex) {
                p.sendMessage(getMessage("pitbox.create.5.invalid", formatArguments("availableColors", PitBoxColor.values())));
                return;
            }

            tempPitBox.get(p).color = color;
            p.sendMessage(getMessage("pitbox.create.5.completed", formatArguments("color", color.name())));

            endPitBoxCreation(p);
        });
    }

    // --------------------------------
    //               END
    // --------------------------------
    private void endPitBoxCreation(Player p) {
        PendingPitBoxConfig config = tempPitBox.get(p);
        creatingPitBox.remove(p);
        tempPitBox.remove(p);

        PitBox box = pitBoxManager.savePitBox(
                config.race, config.name, config.pos1, config.pos2,
                PitBox.TaskType.TIMED, config.duration, config.allowed, config.color
        );

        if (box == null) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }

        Title title = Title.title(
                getMessage("pitbox.create.done.title"),
                getMessage("pitbox.create.done.subtitle")
        );
        p.showTitle(title);
        p.sendMessage(getMessage("pitbox.create.done.message", formatArguments(
                "name", box.getName(),
                "id", box.getId()
        )));
    }

    // --------------------------------
    //            UTILITIES
    // --------------------------------
    public void cancel(Player p) {
        confirmCancel.add(p);
        p.sendMessage(getMessage("race.create.confirmCancel", formatArguments(
                "confirm", getStringMessage("race.create.confirm"),
                "cancel", getStringMessage("race.create.cancel")
        )));
    }

    @EventHandler
    public void confirmCancelStep(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!confirmCancel.contains(p)) return;
        e.setCancelled(true);

        String message = s(e.message());
        if (message.equalsIgnoreCase(getStringMessage("race.create.confirm"))) {
            cancelCleanUp(p);
        } else {
            confirmCancel.remove(p);
            Integer step = creatingPitBox.get(p);
            if (step == null) return;
            switch (step) {
                case 1 -> createPitBox(p, tempPitBox.get(p).race);
                case 2 -> showStep2(p);
                case 3 -> showStep3(p);
                case 4 -> showStep4(p);
            }
        }
    }

    private void cancelCleanUp(Player p) {
        creatingPitBox.remove(p);
        tempPitBox.remove(p);
        PosSelection.pos1.remove(p);
        PosSelection.pos2.remove(p);
        confirmCancel.remove(p);
        p.sendMessage(getMessage("pitbox.create.cancelled"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (creatingPitBox.containsKey(p)) cancelCleanUp(p);
    }
}