package fr.mattmunich.iceBoatRacing.race;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.Messages;
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
import java.util.Collections;
import java.util.List;

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
        if(args.length >= 1 && args[0].equalsIgnoreCase("start")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;
            Race race = parsedResult.race();

            sender.sendMessage(getMessage("race.start", Messages.formatArguments("race", race.getName())));
            race.start();
        } else if(args.length >= 1 && args[0].equalsIgnoreCase("end")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;
            Race race = parsedResult.race();

            try {
                race.end(true);
            } catch (Exception e) {
                switch (e.getMessage()) {
                    case "NO_RANKING" -> sender.sendMessage(getMessage("race.onEnd.error.noRanking"));
                    case "NO_RACERS" -> sender.sendMessage(getMessage("race.onEnd.error.noRacers"));
                    default -> {
                        sender.sendMessage(getMessage("error.unknown"));
                        main.err("Could not send ranking on /race end", e);
                    }
                }
            }
        } else if (args.length >=1 && args[0].equalsIgnoreCase("prepare")) {
            ParsedResult parsedResult = parseRace(args, sender);
            if (parsedResult == null) return;
            Race race = parsedResult.race();

            race.togglePrepare(sender);
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
        if(raceManager.races.size() != 1 || args.length == 3) {
            if(args.length < 2 || args.length > 3) {
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
        List<Race> races =  raceManager.getRaces();
        List<String> raceList;
        if(!races.isEmpty()) {
            raceList = new ArrayList<>();
            races.forEach((race) -> raceList.add(race.getName()));
        } else raceList = List.of("§oNo races defined");

        List<String> baseList = List.of("start","prepare","end","create","delete", "setLapCount","setPitStops","setStartRotation", "setLights");
        if(args.length==0) {
            return baseList;
        } else if(args.length==1) {
            List<String> suggestions = new ArrayList<>();
            baseList.forEach((suggestion) -> {
                if(args[0] != null && suggestion.contains(args[0])) {
                    suggestions.add(suggestion);
                }
            });
            return suggestions;
        } else if(args.length==2
                && (
                    args[0].equalsIgnoreCase("start")
                    || args[0].equalsIgnoreCase("prepare")
                    || args[0].equalsIgnoreCase("end")
                    || args[0].equalsIgnoreCase("delete")
                    || args[0].equalsIgnoreCase("setLapCount")
                    || args[0].equalsIgnoreCase("setPitStops")
                    || args[0].equalsIgnoreCase("setStartRotation")
                    || args[0].equalsIgnoreCase("setLights")
            )
        ) {
            return raceList;
        } else if (args.length==3) {
            if(args[0].equalsIgnoreCase("setLapCount")) {
                return List.of("§7<lapCount>");
            } else if(args[0].equalsIgnoreCase("setPitStops")) {
                return List.of("§7<requiredPitStops>");
            } else if(args[0].equalsIgnoreCase("setStartRotation")) {
                return List.of("§7<startRotation>", "0-360", "north", "west", "south", "east");
            } else {
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return BasicCommand.super.canUse(sender);
    }

    @Override
    public @Nullable String permission() {
        return "iceboatracing.command.race";
    }
}
