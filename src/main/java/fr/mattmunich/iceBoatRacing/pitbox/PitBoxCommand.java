package fr.mattmunich.iceBoatRacing.pitbox;

import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Messages.*;

public class PitBoxCommand implements BasicCommand {

    private final RaceManager raceManager;
    private final PitBoxManager pitBoxManager;
    private final PitBoxCreator pitBoxCreator;

    public PitBoxCommand(RaceManager raceManager, PitBoxManager pitBoxManager, PitBoxCreator pitBoxCreator) {
        this.raceManager = raceManager;
        this.pitBoxManager = pitBoxManager;
        this.pitBoxCreator = pitBoxCreator;
    }

    @Override
    public void execute(@NonNull CommandSourceStack source, String @NonNull [] args) {
        CommandSender sender = source.getSender();

        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(getMessage("error.playerToExecuteCommand"));
                return;
            }

            Race race = raceManager.getRace(args[1]);
            if (race == null) {
                sender.sendMessage(getMessage("race.notFound"));
                return;
            }

            pitBoxCreator.createPitBox(p, race);

        } else if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            Race race = raceManager.getRace(args[1]);
            if (race == null) {
                sender.sendMessage(getMessage("race.notFound"));
                return;
            }

            List<PitBox> boxes = race.getPitBoxes();
            if (boxes.isEmpty()) {
                sender.sendMessage(getMessage("pitbox.noPitBoxes"));
                return;
            }

            sender.sendMessage(c("§bPit boxes §7— §f" + race.getName() + "§7:"));
            for (PitBox box : boxes) {
                Component editText = Component.text("[✎]")
                        .clickEvent(ClickEvent.suggestCommand("/pitbox setAllowed " + race.getName() + " " + box.getId() + " "));

                Component colorText = Component.text("[🎨]")
                        .clickEvent(ClickEvent.suggestCommand("/pitbox setColor " + race.getName() + " " + box.getId() + " "));

                Component removeText = Component.text("[x]")
                        .clickEvent(ClickEvent.runCommand("/pitbox remove " + race.getName() + " " + box.getId()));

                Component tpText = Component.text("[→]")
                        .clickEvent(ClickEvent.runCommand("/tp " + box.getCenter().getBlockX() + " "
                                + box.getCenter().getBlockY() + " " + box.getCenter().getBlockZ()));

                List<String> allowedNames = new ArrayList<>();
                box.getAllowed().forEach(allowed -> allowedNames.add(Bukkit.getOfflinePlayer(allowed).getName() == null ? getStringMessage("playerNotFound") : Bukkit.getOfflinePlayer(allowed).getName()));

                sender.sendMessage(c(
                        "§e  #" + box.getId() + " §f" + box.getName()
                                + " §7— §f" + box.getDuration() + "s"
                                + " §7— §f" + String.join(",", allowedNames)
                                + " §7— §f" + box.getColor().name()
                                + " "
                ).append(editText).append(c(" ")).append(colorText).append(c(" ")).append(removeText).append(c(" ")).append(tpText));
            }

        } else if (args.length == 3 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {
            Race race = raceManager.getRace(args[1]);
            if (race == null) {
                sender.sendMessage(getMessage("race.notFound"));
                return;
            }

            PitBox box = getBox(args, race, sender);
            if (box == null) return;
            String name = box.getName();

            boolean success = pitBoxManager.remove(race, box);
            if (!success) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("pitbox.removed", formatArguments("name", name)));

        } else if (args.length >= 4 && args[0].equalsIgnoreCase("setAllowed")) {
            Race race = raceManager.getRace(args[1]);
            if (race == null) {
                sender.sendMessage(getMessage("race.notFound"));
                return;
            }

            PitBox box = getBox(args, race, sender);
            if (box == null) return;

            List<String> allowedNames = new ArrayList<>(Arrays.asList(args).subList(3, args.length));
            if(allowedNames.getFirst().equals("*")) allowedNames = new ArrayList<>();
            List<UUID> allowedUUIDs = new ArrayList<>();

            OfflinePlayer[] offlinePlayers = Bukkit.getOfflinePlayers();
            for  (String allowedName : allowedNames) {
                Optional<OfflinePlayer> result = Arrays.stream(offlinePlayers)
                        .filter(off -> Objects.requireNonNull(off.getName()).equalsIgnoreCase(allowedName))
                        .findFirst();
                if (result.isPresent()) {
                    allowedUUIDs.add(result.get().getUniqueId());
                } else {
                    sender.sendMessage(getMessage("playerNeverJoined", formatArguments("name",  allowedName)));
                    return;
                }
            }

            boolean success = pitBoxManager.setAllowed(race, box, allowedUUIDs);
            if (!success) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }

            sender.sendMessage(getMessage("pitbox.setAllowed.updated", formatArguments(
                    "name", box.getName(),
                    "players", allowedNames.isEmpty() ? "*" : String.join(", ", allowedNames))
            ));

        } else if (args.length == 4 && args[0].equalsIgnoreCase("setColor")) {
            Race race = raceManager.getRace(args[1]);
            if (race == null) {
                sender.sendMessage(getMessage("race.notFound"));
                return;
            }

            PitBox box = getBox(args, race, sender);
            if (box == null) return;

            PitBoxColor color;
            try {
                color = PitBoxColor.valueOf(args[3].toUpperCase());
            } catch (IllegalArgumentException e) {
                sender.sendMessage(getMessage("pitbox.setColor.invalid", formatArguments("availableColors", String.join(", ", PitBoxColor.names()))));
                return;
            }

            boolean success = pitBoxManager.setColor(race, box, color);
            if (!success) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }

            sender.sendMessage(getMessage("pitbox.setColor.updated", formatArguments("name", box.getName(), "color", color.name())));

        } else {
            sender.sendMessage(getMessage("pitbox.help"));
        }
    }

    private static @Nullable PitBox getBox(String @NonNull [] args, Race race, CommandSender sender) {
        PitBox box;
        try {
            int id = Integer.parseInt(args[2]);
            box = race.getPitBox(id);
        } catch (NumberFormatException e) {
            box = race.getPitBox(args[2]);
        }


        if (box == null) {
            sender.sendMessage(getMessage("pitbox.invalid"));
            return null;
        }
        return box;
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack source, String @NonNull [] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length <= 1) {
            suggestions.add("create");
            suggestions.add("list");
            suggestions.add("remove");
            suggestions.add("setAllowed");
            suggestions.add("setColor");
            return suggestions;
        }

        List<String> pitBoxList = new ArrayList<>();

        switch (args[0].toLowerCase()) {
            case "create", "list" -> {
                if (args.length == 2) for (Race race : raceManager.races) suggestions.add(race.getName());
            }
            case "remove", "delete" -> {
                if (args.length == 2) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                } else if (args.length == 3) {
                    suggestRaces(args, pitBoxList, suggestions);
                }
            }
            case "setallowed" -> {
                List<String> playerNames = new ArrayList<>();
                for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
                    playerNames.add(offlinePlayer.getName());
                }
                if (args.length == 2) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                } else if (args.length == 3) {
                    suggestRaces(args, pitBoxList, suggestions);
                } else {
                    suggestions.add("*");
                    if(args[args.length - 1] != null && args[args.length-1].isEmpty()) {
                        suggestions.addAll(playerNames);
                    } else {
                        StringUtil.copyPartialMatches(args[args.length-1].toLowerCase(), playerNames, suggestions);
                    }
                }
            }
            case "setcolor" -> {
                if (args.length == 2) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                } else if (args.length == 3) {
                    suggestRaces(args, pitBoxList, suggestions);
                } else if (args.length == 4 && args[3] != null) {
                    List<String> colorNames = Arrays.asList(PitBoxColor.names());
                    StringUtil.copyPartialMatches(args[3], colorNames, suggestions);
                }
            }
        }

        return suggestions.isEmpty() ? Collections.emptyList() : suggestions;
    }

    private void suggestRaces(String @NonNull [] args, List<String> pitBoxList, List<String> suggestions) {
        Race race = raceManager.getRace(args[1]);
        if (race != null) race.getPitBoxes().forEach(box -> {
            pitBoxList.add("" + box.getId());
            pitBoxList.add(box.getName());
        });

        if (args[2] != null) {
            StringUtil.copyPartialMatches(args[2], pitBoxList, suggestions);
        } else {
            suggestions.addAll(pitBoxList);
        }
    }

    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return sender instanceof Player && sender.hasPermission("iceboatracing.command.pitbox");
    }

    @Override
    public @Nullable String permission() {
        return "iceboatracing.command.pitbox";
    }
}