package fr.mattmunich.iceBoatRacing.cars;

import fr.mattmunich.iceBoatRacing.CommandUtil;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Messages.*;

public class CarCommand implements BasicCommand, Listener {

    private final CarManager carManager;
    private final RaceManager raceManager;
    private final CarCreator carCreator;

    public CarCommand(CarManager carManager, RaceManager raceManager, CarCreator carCreator) {
        this.carManager = carManager;
        this.raceManager = raceManager;
        this.carCreator = carCreator;
    }

    @Override
    public void execute(@NonNull CommandSourceStack source, String @NonNull [] args) {
        CommandSender sender = source.getSender();

        //create is the start of the interactive wizard and selectRace feeds it, so both need a player;
        //everything else works from the console too
        if (args.length >= 1 && args[0].equalsIgnoreCase("create")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(getMessage("error.playerToExecuteCommand"));
                return;
            }
            carCreator.createCar(p);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            listCommand(args, sender);
        } else if (args.length >= 3 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {
            // /car remove <race> <id>
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;

            Integer id = parseIntOrNull(args[2]);
            if (id == null) {
                sender.sendMessage(getMessage("error.invalidNumber"));
                return;
            }

            Car car = carManager.get(race, id);
            if (car == null) {
                sender.sendMessage(getMessage("car.invalidID", formatArguments("id", String.valueOf(id))));
                return;
            }

            if (!carManager.remove(race, car)) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("car.removed", formatArguments("id", String.valueOf(id))));
        } else if (args.length >= 4 && args[0].equalsIgnoreCase("changeOwner")) {
            // /car changeOwner <race> <id> <player>
            Race race = CommandUtil.getRaceOrError(raceManager, args[1], sender);
            if (race == null) return;

            Integer id = parseIntOrNull(args[2]);
            if (id == null) {
                sender.sendMessage(getMessage("error.invalidNumber"));
                return;
            }

            Car car = carManager.get(race, id);
            if (car == null) {
                sender.sendMessage(getMessage("car.invalidID", formatArguments("id", String.valueOf(id))));
                return;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[3]);
            if(!target.hasPlayedBefore()) {
                sender.sendMessage(getMessage("error.playerNotFound"));
                return;
            }

            if (!carManager.changeOwner(race, car, target.getUniqueId())) {
                sender.sendMessage(getMessage("error.unknown"));
                return;
            }
            sender.sendMessage(getMessage("car.changedOwner", formatArguments("owner", target.getName())));
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("selectRace")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(getMessage("error.playerToExecuteCommand"));
                return;
            }
            carCreator.selectRace(args, p);
        } else {
            sender.sendMessage(getMessage("car.help"));
        }
    }

    private static @Nullable Integer parseIntOrNull(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void listCommand(String @NonNull [] args, CommandSender sender) {
        Map<Race, List<Car>> all = carManager.getAll();

        if (all.values().stream().allMatch(List::isEmpty)) {
            sender.sendMessage(getMessage("car.noCars"));
            return;
        }

        // Flatten into ordered (Race, Car) pairs for pagination
        List<Map.Entry<Race, Car>> entries = new ArrayList<>();
        for (Map.Entry<Race, List<Car>> entry : all.entrySet()) {
            for (Car car : entry.getValue()) {
                entries.add(Map.entry(entry.getKey(), car));
            }
        }

        final int PAGE_SIZE = 10;
        int totalPages = (int) Math.ceil((double) entries.size() / PAGE_SIZE);

        int page = 1;
        if (args.length >= 2) {
            Integer parsed = parseIntOrNull(args[1]);
            if (parsed == null) {
                sender.sendMessage(getMessage("error.invalidNumber"));
                return;
            }
            page = parsed;
        }

        if (page < 1 || page > totalPages) {
            sender.sendMessage(c("§cInvalid page (1-" + totalPages + ")"));
            return;
        }

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());

        sender.sendMessage(c("§bCars §7(page §f" + page + "§7/§f" + totalPages + "§7):"));

        String lastRaceName = null;
        for (int i = start; i < end; i++) {
            Race race = entries.get(i).getKey();
            Car car = entries.get(i).getValue();

            // Print race header when the race changes
            if (!race.getName().equals(lastRaceName)) {
                lastRaceName = race.getName();
                sender.sendMessage(c("§3▌ §b" + race.getName()));
            }

            Location loc = car.getStartingLocation();

            String ownerName = "Unknown";
            OfflinePlayer owner = Bukkit.getOfflinePlayer(car.getOwner());
            if (owner.getName() != null) ownerName = owner.getName();

            Component removeText = c("§c[x]")
                    .hoverEvent(HoverEvent.showText(c("Click to delete")))
                    .clickEvent(ClickEvent.runCommand("/car remove " + race.getName() + " " + car.getId()));

            Component tpText = c("§a[→]")
                    .hoverEvent(HoverEvent.showText(c("Click to teleport")))
                    .clickEvent(ClickEvent.runCommand(
                            "/tp " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                    ));

            Component changeOwnerText = c("§e[✎]")
                    .hoverEvent(HoverEvent.showText(c("Click to change the car's owner")))
                    .clickEvent(ClickEvent.suggestCommand(
                            "/car changeOwner " + race.getName() + " " + car.getId() + " "
                    ));

            Component line = c(
                    "§3  #" + car.getId()
                            + " §7Owner: §f" + ownerName
                            + " §7Type: §f" + car.getBoatMaterial().name().replace("_BOAT", "").replace("_RAFT", "").toLowerCase()
                            + " §7[" + (loc.getWorld() == null ? "§8§oUnknown" : loc.getWorld().getName()) + "]"
                            + " §fX:" + loc.getBlockX()
                            + " Y:" + loc.getBlockY()
                            + " Z:" + loc.getBlockZ()
                            + " §7| §f"
            )
                    .append(removeText)
                    .append(c(" "))
                    .append(tpText)
                    .append(c(" "))
                    .append(changeOwnerText);

            sender.sendMessage(line);
        }

        // Page navigation row
        if (totalPages > 1) {
            Component prev = page > 1
                    ? c("§a[←]").clickEvent(ClickEvent.runCommand("/car list " + (page - 1)))
                    : c("§8[←]");
            Component next = page < totalPages
                    ? c("§a[→]").clickEvent(ClickEvent.runCommand("/car list " + (page + 1)))
                    : c("§8[→]");

            sender.sendMessage(prev.append(c("  §7Page §f" + page + " §7of §f" + totalPages + "  ")).append(next));
        }
    }

    /** Total list pages, for the page-number suggestions. */
    private int totalListPages() {
        int totalCars = carManager.getAll().values().stream().mapToInt(List::size).sum();
        if (totalCars == 0) return 1;
        return (int) Math.ceil((double) totalCars / 10);
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack source, String @NonNull [] args) {
        String token = args.length == 0 ? "" : args[0];

        if (args.length <= 1) {
            return CommandUtil.filter(List.of("create", "list", "remove", "changeOwner", "selectRace"), token);
        }

        switch (args[0].toLowerCase()) {
            case "list" -> {
                if (args.length == 2) return CommandUtil.suggestPages(totalListPages(), args[1]);
            }
            case "remove", "delete", "changeowner", "selectrace" -> {
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
                if (args.length == 3 && !args[0].equalsIgnoreCase("selectRace")) {
                    //Car IDs of the chosen race
                    Race race = raceManager.getRace(args[1]);
                    if (race == null) return List.of();
                    List<String> ids = race.getCars().stream()
                            .map(car -> String.valueOf(car.getId()))
                            .toList();
                    return CommandUtil.filter(ids, args[2]);
                }
                if (args.length == 4 && args[0].equalsIgnoreCase("changeOwner")) {
                    return CommandUtil.suggestOnlinePlayers(args[3]);
                }
            }
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return sender.hasPermission("iceboatracing.command.car");
    }

    @Override
    public @Nullable String permission() {
        return "iceboatracing.command.car";
    }


}
