package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
import fr.mattmunich.iceBoatRacing.Result;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
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

        //Player-level subcommands: open to anyone with the join permission, no admin rights needed
        if (args.length >= 1 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("leave"))) {
            handleJoinLeave(sender, args);
            return;
        }

        //Everything below is administrative
        if (!sender.hasPermission("iceboatracing.command.race")) {
            sender.sendMessage(getMessage("error.noPermission"));
            return;
        }

        if(args.length >= 1 && args[0].equalsIgnoreCase("start")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;
            Race race = parsedResult.race();

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
        } else if(args.length >= 1 && args[0].equalsIgnoreCase("end")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;
            Race race = parsedResult.race();

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
        } else if (args.length >=1 && args[0].equalsIgnoreCase("prepare")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;
            Race race = parsedResult.race();

            //Optional duration: /race prepare [race] [seconds]
            int seconds = INTERMISSION_SECONDS;
            String durationArg = parsedResult.definedRaceName()
                    ? (args.length >= 3 ? args[2] : null)
                    : (args.length >= 2 ? args[1] : null);
            if (durationArg != null) {
                try {
                    seconds = Integer.parseInt(durationArg);
                } catch (NumberFormatException e) {
                    sender.sendMessage(getMessage("error.invalidNumber"));
                    return;
                }
            }

            race.openRegistration(sender, seconds);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("create")) {
            if(!(sender instanceof Player p)) {
                sender.sendMessage(getMessage("error.playerToExecuteCommand"));
                return;
            }
            raceCreator.createRace(p);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("delete")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;

            Race race = parsedResult.race();

            String raceName = race.getName();
            Bukkit.getScheduler().runTask(main, () -> {
                boolean success = raceManager.deleteRace(race);
                if (success) sender.sendMessage(getMessage("race.deleted", Messages.formatArguments("race", raceName)));
                else sender.sendMessage(getMessage("error.unknown"));
            });
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("setLapCount")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;

            int valueArgIndex = parsedResult.definedRaceName() ? 2 : 1;
            handleLapCount(args, valueArgIndex, sender, parsedResult.race());
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("setStartRotation")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;

            int valueArgIndex = parsedResult.definedRaceName() ? 2 : 1;
            handleStartRotation(args, valueArgIndex, sender, parsedResult.race());
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("setPitStops")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;

            Race finalRace = parsedResult.race();
            int valueArgIndex = parsedResult.definedRaceName() ? 2 : 1;

            handlePitStops(args, valueArgIndex, sender, finalRace);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("setLights")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;

            Race finalRace = parsedResult.race();

            handleLights(sender, finalRace);
        } else {
            sender.sendMessage(getMessage("race.help"));
        }
    }

    /**
     * /race join [raceName]
     * /race leave [raceName]
     * The opt-in entry point: players add themselves to an open race registration. If only one
     * race is loaded the name can be omitted.
     */
    private void handleJoinLeave(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(getMessage("error.playerToExecuteCommand"));
            return;
        }

        boolean join = args[0].equalsIgnoreCase("join");

        Race race;
        if (args.length >= 2) {
            race = raceManager.getRace(args[1]);
            if (race == null) {
                p.sendMessage(getMessage("race.notFound"));
                return;
            }
        } else {
            //No name given: only unambiguous when exactly one race exists or the player is registered somewhere
            List<Race> open = new ArrayList<>();
            for (Race candidate : raceManager.races) {
                if (join ? candidate.isPreparing() && !candidate.hasStarted() : candidate.racers.containsKey(p.getUniqueId())) {
                    open.add(candidate);
                }
            }

            if (open.size() == 1) {
                race = open.getFirst();
            } else if (open.isEmpty()) {
                p.sendMessage(getMessage(join ? "race.intermission.noneOpen" : "race.intermission.notJoinedAny"));
                return;
            } else {
                p.sendMessage(getMessage("race.intermission.specifyRace"));
                return;
            }
        }

        if (join) raceManager.joinRace(p, race);
        else raceManager.leaveRace(p, race);
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

    private @Nullable ParsedResult parseRace(String @NonNull [] args, CommandSender sender) {
        Race race;
        boolean definedRaceName = false;
        //The duration form of /race prepare takes one extra trailing argument
        boolean allowsExtraArg = args.length >= 1 && args[0].equalsIgnoreCase("prepare");
        int maxArgs = allowsExtraArg ? 4 : 3;

        if(raceManager.races.size() != 1 || args.length == 3 || args.length == 4) {
            if(args.length < 2 || args.length > maxArgs) {
                sender.sendMessage(getMessage("race.help"));
                return null;
            }
            String raceName = args[1];
            race = raceManager.getRace(raceName);
            definedRaceName = true;
        } else {
            race = raceManager.races.getFirst();
        }
        if(race == null) {
            sender.sendMessage(getMessage("race.notFound"));
            return null;
        }
        return new ParsedResult(race, definedRaceName);
    }

    private record ParsedResult(Race race, boolean definedRaceName) { }

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
                sender.sendMessage(getMessage("error.unkown"));
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

        float startRotation;
        String value = args[valueArgIndex];
        if (Character.isDigit(value.charAt(0))) {
            try {
                startRotation = Float.parseFloat(args[valueArgIndex]);
            } catch (NumberFormatException e) {
                sender.sendMessage(getMessage("race.invalidStartRotation"));
                return;
            }
        } else {
            switch (args[valueArgIndex]) {
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
                sender.sendMessage(getMessage("error.unkown"));
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
        List<String> suggestions = new ArrayList<>();

        CommandSender sender = commandSourceStack.getSender();
        boolean admin = sender.hasPermission("iceboatracing.command.race");

        if (!admin) {
            //Non-admins can only join/leave
            List<String> playerCommands = List.of("join", "leave");
            if (args.length == 0) return playerCommands;
            if (args.length == 1) {
                StringUtil.copyPartialMatches(args[0], playerCommands, suggestions);
                return suggestions;
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("leave"))) {
                List<String> raceList = new ArrayList<>();
                for (Race race : raceManager.getRaces()) raceList.add(race.getName());
                StringUtil.copyPartialMatches(args[1], raceList, suggestions);
            }
            return suggestions;
        }

        List<String> baseSuggestions = List.of("start","prepare","end","create","delete","join","leave", "setLapCount","setPitStops","setStartRotation", "setLights");

        List<Race> races =  raceManager.getRaces();
        List<String> raceList;
        if(!races.isEmpty()) {
            raceList = new ArrayList<>();
            races.forEach((race) -> raceList.add(race.getName()));
        } else raceList = List.of("§oNo races defined");

        if(args.length==0) {
            return baseSuggestions;
        } else if(args.length==1) {
            if(args[0] == null) return suggestions;
            StringUtil.copyPartialMatches(args[0], baseSuggestions, suggestions);
            return suggestions;
        } else if(args.length==2
                && (
                    args[0].equalsIgnoreCase("start")
                    || args[0].equalsIgnoreCase("prepare")
                    || args[0].equalsIgnoreCase("end")
                    || args[0].equalsIgnoreCase("delete")
                    || args[0].equalsIgnoreCase("join")
                    || args[0].equalsIgnoreCase("leave")
                    || args[0].equalsIgnoreCase("setLapCount")
                    || args[0].equalsIgnoreCase("setPitStops")
                    || args[0].equalsIgnoreCase("setStartRotation")
                    || args[0].equalsIgnoreCase("setLights")
            )
        ) {
            StringUtil.copyPartialMatches(args[1], raceList, suggestions);
        } else if (args.length==3) {
            if(args[0].equalsIgnoreCase("prepare")) {
                suggestions = List.of("§7<seconds>");
            } else if(args[0].equalsIgnoreCase("setLapCount")) {
                suggestions = List.of("§7<lapCount>");
            } else if(args[0].equalsIgnoreCase("setPitStops")) {
                suggestions = List.of("§7<requiredPitStops>");
            } else if(args[0].equalsIgnoreCase("setStartRotation")) {
                suggestions.add("§7<startRotation>");
                baseSuggestions = List.of("0-360", "north", "west", "south", "east");
                StringUtil.copyPartialMatches(args[2], baseSuggestions, suggestions);
            }
        }
        return suggestions;
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
