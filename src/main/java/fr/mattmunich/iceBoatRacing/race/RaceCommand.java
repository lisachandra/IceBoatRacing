package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.CommandUtil;
import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
import fr.mattmunich.iceBoatRacing.Result;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static fr.mattmunich.iceBoatRacing.Main.err;
import static fr.mattmunich.iceBoatRacing.Main.INTERMISSION_SECONDS;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;
import static fr.mattmunich.iceBoatRacing.PosSelection.pos1;
import static fr.mattmunich.iceBoatRacing.PosSelection.pos2;

public class RaceCommand implements BasicCommand {
    private final Main main;
    private final RaceManager raceManager;
    private final RaceCreator raceCreator;

    public RaceCommand(Main main, RaceManager raceManager, RaceCreator raceCreator) {
        this.main = main;
        this.raceManager = raceManager;
        this.raceCreator = raceCreator;
    }

    @Override
    public void execute(@NonNull CommandSourceStack source, String @NonNull [] args) {
        CommandSender sender = source.getSender();

        //Player-level subcommands: open to anyone with the join permission, no admin rights needed.
        //The race name is always required — the clickable Join button in the announcement supplies it.
        if (args.length >= 1 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("leave"))) {
            handleJoinLeave(sender, args);
            return;
        }

        //Everything below is administrative
        if (!sender.hasPermission("iceboatracing.command.race")) {
            sender.sendMessage(getMessage("error.noPermission"));
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("create")) {
            if(!(sender instanceof Player p)) {
                sender.sendMessage(getMessage("error.playerToExecuteCommand"));
                return;
            }
            raceCreator.createRace(p);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("start")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;

            //Only announce the start once we know it can actually start
            if (race.racers.isEmpty()) {
                sender.sendMessage(getMessage("race.intermission.cannotStartEmpty", Messages.formatArguments("race", race.getName())));
                return;
            }

            Result result = race.start();
            if(result == Result.ERROR) {
                sender.sendMessage(getMessage("error.unknown"));
            } else if (result == Result.WARN) {
                sender.sendMessage(getMessage("race.intermission.cannotStartEmpty", Messages.formatArguments("race", race.getName())));
            } else {
                sender.sendMessage(getMessage("race.start", Messages.formatArguments("race", race.getName())));
            }
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("end")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;

            //Still in registration: nothing has started, so just close it instead of trying to
            //send a ranking for an empty race.
            if (race.isPreparing()) {
                raceManager.closeRegistration(race);
                race.racers.clear();
                raceManager.activeRaces.remove(race);
                sender.sendMessage(getMessage("race.intermission.cancelled", Messages.formatArguments("race", race.getName())));
                return;
            }

            try {
                race.end(true);
            } catch (Exception e) {
                switch (e.getMessage()) {
                    case "NO_RANKING" -> sender.sendMessage(getMessage("race.onEnd.error.noRanking"));
                    case "NO_RACERS" -> sender.sendMessage(getMessage("race.onEnd.error.noRacers"));
                    default -> {
                        sender.sendMessage(getMessage("error.unknown"));
                        err("Could not send ranking on /race end", e);
                    }
                }
            }
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("prepare")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;

            //Optional duration: /race prepare <race> [seconds]
            int seconds = INTERMISSION_SECONDS;
            if (args.length >= 3) {
                try {
                    seconds = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(getMessage("error.invalidNumber"));
                    return;
                }
            }

            race.openRegistration(sender, seconds);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("info")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;
            sendRaceInfo(sender, race);
        } else if (args.length >= 2 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;

            String raceName = race.getName();
            Bukkit.getScheduler().runTask(main, () -> {
                boolean success = raceManager.deleteRace(race);
                if (success) sender.sendMessage(getMessage("race.deleted", Messages.formatArguments("race", raceName)));
                else sender.sendMessage(getMessage("error.unknown"));
            });
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("setLapCount")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;
            handleLapCount(args, 2, sender, race);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("setStartRotation")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;
            handleStartRotation(args, 2, sender, race);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("setPitStops")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;
            handlePitStops(args, 2, sender, race);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("setLights")) {
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;
            handleLights(sender, race);
        } else {
            sender.sendMessage(getMessage("race.help"));
        }
    }

    /**
     * /race join <raceName>
     * /race leave <raceName>
     * The opt-in entry point: players add themselves to an open race registration. The race name
     * is always required — the clickable Join button in the registration announcement supplies it,
     * and tab completion suggests the relevant races.
     */
    private void handleJoinLeave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(getMessage("error.playerToExecuteCommand"));
            return;
        }

        boolean join = args[0].equalsIgnoreCase("join");
        if (args.length < 2) {
            p.sendMessage(getMessage(join ? "race.intermission.specifyRace" : "race.intermission.notJoinedAny"));
            return;
        }

        Race race = raceManager.getRace(args[1]);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        if (join) raceManager.joinRace(p, race);
        else raceManager.leaveRace(p, race);
    }

    /** /race info <race> — one overview of the race's current configuration. */
    private void sendRaceInfo(CommandSender sender, Race race) {
        sender.sendMessage(getMessage("race.infoHeader", Messages.formatArguments("race", race.getName())));
        sender.sendMessage(getMessage("race.currentLapCount", Messages.formatArguments(
                "race", race.getName(),
                "lapCount", race.getLapCount()
        )));
        sender.sendMessage(getMessage("race.currentPitStops", Messages.formatArguments(
                "race", race.getName(),
                "pitStops", race.getRequiredPitStops()
        )));
        sender.sendMessage(getMessage("race.currentStartRotation", Messages.formatArguments(
                "race", race.getName(),
                "startRotation", race.getStartRotation()
        )));

        Race.Lights lights = race.getLights();
        if (lights == null) {
            sender.sendMessage(getMessage("race.lightsNotSet"));
        } else if (!lights.enabled()) {
            sender.sendMessage(getMessage("race.lightsDisabled"));
        } else {
            Location l1 = lights.from();
            Location l2 = lights.to();
            sender.sendMessage(getMessage("race.currentLights", Messages.formatArguments(
                    "race", race.getName(),
                    "x1", l1.getBlockX(),
                    "y1", l1.getBlockY(),
                    "z1", l1.getBlockZ(),
                    "x2", l2.getBlockX(),
                    "y2", l2.getBlockY(),
                    "z2", l2.getBlockZ()
            )));
        }

        sender.sendMessage(getMessage("race.infoCars", Messages.formatArguments(
                "race", race.getName(),
                "count", race.getCars().size()
        )));
        sender.sendMessage(getMessage("race.infoPitBoxes", Messages.formatArguments(
                "race", race.getName(),
                "count", race.getPitBoxes().size()
        )));
    }

    private void handlePitStops(String @NonNull [] args, int valueArgIndex, CommandSender sender, Race finalRace) {
        if (args.length <= valueArgIndex) {
            sender.sendMessage(getMessage("race.currentPitStops", Messages.formatArguments(
                    "race", finalRace.getName(),
                    "pitStops", finalRace.getRequiredPitStops()
            )));
            return;
        }

        int pitStops;
        try {
            pitStops = Integer.parseInt(args[valueArgIndex]);
        } catch (NumberFormatException e) {
            sender.sendMessage(getMessage("race.invalidPitStops"));
            return;
        }

        Bukkit.getScheduler().runTask(main, () -> {
            finalRace.setRequiredPitStops(pitStops);
            try {
                finalRace.saveConfig();
            } catch (IOException e) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("race.updatedPitStops", Messages.formatArguments(
                    "race", finalRace.getName(),
                    "pitStops", finalRace.getRequiredPitStops()
            )));
        });
    }

    private void handleLights(CommandSender sender, Race finalRace) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(getMessage("error.playerToExecuteCommand"));
            return;
        }

        if (pos1.get(p) == null || pos2.get(p) == null) {
            p.sendMessage(getMessage("pos.notSet"));
            Race.Lights lights = finalRace.getLights();

            if (lights == null) {
                p.sendMessage(getMessage("race.lightsNotSet"));
                return;
            }

            if (!lights.enabled()) {
                p.sendMessage(getMessage("race.lightsDisabled"));
                return;
            }

            Location l1 = lights.from();
            Location l2 = lights.to();

            p.sendMessage(getMessage("race.currentLights", Messages.formatArguments(
                    "race", finalRace.getName(),
                    "x1", l1.getBlockX(),
                    "y1", l1.getBlockY(),
                    "z1", l1.getBlockZ(),
                    "x2", l2.getBlockX(),
                    "y2", l2.getBlockY(),
                    "z2", l2.getBlockZ()
            )));
            return;
        }

        Location newPos1 = pos1.get(p);
        Location newPos2 = pos2.get(p);

        Bukkit.getScheduler().runTask(main, () -> {
            finalRace.setLights(new Race.Lights(true, newPos1, newPos2));
            try {
                finalRace.saveConfig();
            } catch (IOException e) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("race.updatedLights", Messages.formatArguments(
                    "race", finalRace.getName(),
                    "x1", newPos1.getBlockX(),
                    "y1", newPos1.getBlockY(),
                    "z1", newPos1.getBlockZ(),
                    "x2", newPos2.getBlockX(),
                    "y2", newPos2.getBlockY(),
                    "z2", newPos2.getBlockZ()
            )));
        });
    }

    private void handleLapCount(String @NonNull [] args, int valueArgIndex, CommandSender sender, Race race) {
        if(args.length == valueArgIndex) {
            sender.sendMessage(getMessage("race.currentLapCount", Messages.formatArguments(
                    "race", race.getName(),
                    "lapCount", race.getLapCount()
            )));
            return;
        }

        int lapCount;
        try {
            lapCount = Integer.parseInt(args[valueArgIndex]);
        } catch (NumberFormatException e) {
            sender.sendMessage(getMessage("race.invalidLapCount"));
            return;
        }

        Bukkit.getScheduler().runTask(main, () -> {
            race.setLapCount(lapCount);
            try {
                race.saveConfig();
            } catch (IOException e) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("race.updatedLapCount", Messages.formatArguments(
                    "race", race.getName(),
                    "lapCount", race.getLapCount()
            )));
        });
    }

    private void handleStartRotation(String @NonNull [] args, int valueArgIndex, CommandSender sender, Race race) {
        if(args.length == valueArgIndex) {
            sender.sendMessage(getMessage("race.currentStartRotation", Messages.formatArguments(
                    "race", race.getName(),
                    "startRotation", race.getStartRotation()
            )));
            return;
        }

        String value = args[valueArgIndex];
        Float parsed = null;
        try {
            parsed = Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
        }

        final float startRotation;
        if (parsed != null) {
            startRotation = parsed;
        } else {
            switch (value.toLowerCase()) {
                case "north", "n" -> startRotation = 180;
                case "west", "w" -> startRotation = 90;
                case "south", "s" -> startRotation = 0;
                case "east", "e" -> startRotation = 270;
                default -> {
                    sender.sendMessage(getMessage("race.invalidStartRotation"));
                    return;
                }
            }
        }

        if(startRotation < -180 || startRotation > 360) {
            sender.sendMessage(getMessage("race.invalidStartRotation"));
            return;
        }

        Bukkit.getScheduler().runTask(main, () -> {
            race.setStartRotation(startRotation);
            try {
                race.saveConfig();
            } catch (IOException e) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("race.updatedStartRotation", Messages.formatArguments(
                    "race", race.getName(),
                    "startRotation", race.getStartRotation()
            )));
        });
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack commandSourceStack, String @NonNull [] args) {
        CommandSender sender = commandSourceStack.getSender();
        boolean admin = sender.hasPermission("iceboatracing.command.race");
        String token = args.length == 0 ? "" : args[0];

        if (!admin) {
            //Non-admins can only join/leave; suggest the races relevant to the action
            if (args.length <= 1) return CommandUtil.filter(List.of("join", "leave"), token);
            if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("leave"))) {
                List<String> names = new ArrayList<>();
                for (Race race : raceManager.getRaces()) {
                    boolean relevant = args[0].equalsIgnoreCase("join")
                            ? (race.isPreparing() && !race.hasStarted())
                            : sender instanceof Player p && race.racers.containsKey(p.getUniqueId());
                    if (relevant) names.add(race.getName());
                }
                return CommandUtil.filter(names, args[1]);
            }
            return List.of();
        }

        List<String> subcommands = List.of("info", "start", "prepare", "end", "create", "remove", "join", "leave",
                "setLapCount", "setPitStops", "setStartRotation", "setLights");
        if (args.length <= 1) return CommandUtil.filter(subcommands, token);
        if (args.length == 2) {
            //create takes no race; every other subcommand targets one
            if (args[0].equalsIgnoreCase("create")) return List.of();
            return CommandUtil.suggestRaces(raceManager, args[1]);
        }
        if (args.length == 3) {
            return switch (args[0].toLowerCase()) {
                case "prepare" -> CommandUtil.filter(List.of("30", "60", "120", "300"), args[2]);
                case "setlapcount" -> CommandUtil.filter(List.of("3", "5", "10", "25"), args[2]);
                case "setpitstops" -> CommandUtil.filter(List.of("0", "1", "2", "3"), args[2]);
                case "setstartrotation" -> CommandUtil.filter(
                        List.of("0", "90", "180", "270", "north", "east", "south", "west"), args[2]);
                default -> List.of();
            };
        }
        return List.of();
    }

    /**
     * Both admins and ordinary players can run this command: /race join and /race leave are open
     * to everyone with the join permission, while every other subcommand re-checks
     * {@code iceboatracing.command.race} inside {@link #execute}.
     */
    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return sender.hasPermission("iceboatracing.command.race")
                || sender.hasPermission("iceboatracing.command.race.join");
    }

    /**
     * Must stay null: returning a permission here would gate the whole command root, which would
     * also lock ordinary players out of /race join. Admin subcommands check their own permission.
     */
    @Override
    public @Nullable String permission() {
        return null;
    }
}
