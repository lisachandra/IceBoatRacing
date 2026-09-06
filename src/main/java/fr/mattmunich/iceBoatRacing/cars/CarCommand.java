package fr.mattmunich.iceBoatRacing.cars;

import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
        if(!(source.getSender() instanceof Player p)) {
            source.getSender().sendMessage(getMessage("error.playerToExecuteCommand"));
            return;
        }

        if(args.length == 1 && args[0].equalsIgnoreCase("create")) {
            carCreator.createCar(p);
        } else if (args.length <= 2 && args[0].equalsIgnoreCase("list")) {
            listCommand(args, p);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("changeOwner")) {
            changeOwner(args, p);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("remove")) {
            int id;
            try {
                id = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage(getMessage("error.invalidNumber"));
                return;
            }

            String raceName = args[2];

            Race race = raceManager.getRace(raceName);
            if(race == null) {
                p.sendMessage(getMessage("race.notFound"));
                return;
            }

            Car car = carManager.get(race,id);
            if(car == null) {
                p.sendMessage(getMessage("car.invalidID",formatArguments("id", String.valueOf(id))));
                return;
            }

            boolean success = carManager.remove(race,car);
            if(!success) {
                p.sendMessage(getMessage("error.unknown"));
                return;
            }
            p.sendMessage(getMessage("car.removed",formatArguments("id", String.valueOf(id))));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("selectRace")) {
            carCreator.selectRace(args, p);
        } else {
            p.sendMessage(getMessage("car.help"));
        }
    }

    private void changeOwner(String @NonNull [] args, Player p) {
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            p.sendMessage(getMessage("error.invalidNumber"));
            return;
        }


        Race race = raceManager.getRace(args[2]);
        if(race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        Car car = carManager.get(race,id);
        if(car == null) {
            p.sendMessage(getMessage("car.invalidID",formatArguments("id", String.valueOf(id))));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[3]);
        if(!target.hasPlayedBefore()) {
            p.sendMessage(getMessage("error.playerNotFound"));
            return;
        }

        boolean success = carManager.changeOwner(race,car,target.getUniqueId());
        if(!success) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }
        p.sendMessage(getMessage("car.changedOwner",formatArguments("owner", target.getName())));
    }

    private void listCommand(String @NonNull [] args, Player p) {
        Map<Race, List<Car>> all = carManager.getAll();

        if (all.values().stream().allMatch(List::isEmpty)) {
            p.sendMessage(getMessage("car.noCars"));
            return;
        }

        // Flatten into ordered (Race, Car) pairs for pagination
        List<Map.Entry<Race, Car>> entries = new ArrayList<>();
        for (Map.Entry<Race, List<Car>> entry : all.entrySet()) {
            for (Car car : entry.getValue()) {
                entries.add(Map.entry(entry.getKey(), car));
            }
        }

        final int PAGE_SIZE = 5;
        int totalPages = (int) Math.ceil((double) entries.size() / PAGE_SIZE);

        int page = 1;
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage(getMessage("error.invalidNumber"));
                return;
            }
        }

        if (page < 1 || page > totalPages) {
            p.sendMessage(c("§cInvalid page (1-" + totalPages + ")"));
            return;
        }

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());

        p.sendMessage(c("§bCars §7(page §f" + page + "§7/§f" + totalPages + "§7):"));

        String lastRaceName = null;
        for (int i = start; i < end; i++) {
            Race race = entries.get(i).getKey();
            Car car = entries.get(i).getValue();

            // Print race header when the race changes
            if (!race.getName().equals(lastRaceName)) {
                lastRaceName = race.getName();
                p.sendMessage(c("§3▌ §b" + race.getName()));
            }

            Location loc = car.getStartingLocation();

            String ownerName = "Unknown";
            OfflinePlayer owner = Bukkit.getOfflinePlayer(car.getOwner());
            if (owner.getName() != null) ownerName = owner.getName();

            Component removeText = c("§c[x]")
                    .clickEvent(ClickEvent.runCommand("/car remove " + car.getId() + " " + race.getName()));

            Component tpText = c("§a[→]")
                    .clickEvent(ClickEvent.runCommand(
                            "/tp " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ()
                    ));

            Component changeOwnerText = c("§e[✎]")
                    .clickEvent(ClickEvent.suggestCommand(
                            "/car changeOwner " + car.getId() + " " + race.getName() + " "
                    ));

            Component line = c(
                    "§e  #" + car.getId()
                            + " §7Owner: §f" + ownerName
                            + " §7Type: §f" + car.getBoatMaterial().name().replace("_BOAT", "").replace("_RAFT", "").toLowerCase()
                            + " §7[" + (loc.getWorld() == null ? "§7§oUnknown" : loc.getWorld().getName()) + "]"
                            + " §fX:" + loc.getBlockX()
                            + " Y:" + loc.getBlockY()
                            + " Z:" + loc.getBlockZ()
                            + " "
            )
                    .append(removeText)
                    .append(c(" "))
                    .append(tpText)
                    .append(c(" "))
                    .append(changeOwnerText);

            p.sendMessage(line);
        }

        // Page navigation row
        if (totalPages > 1) {
            Component prev = page > 1
                    ? c("§a[←]").clickEvent(ClickEvent.runCommand("/car list " + (page - 1)))
                    : c("§8[←]");
            Component next = page < totalPages
                    ? c("§a[→]").clickEvent(ClickEvent.runCommand("/car list " + (page + 1)))
                    : c("§8[→]");

            p.sendMessage(prev.append(c("  §7Page §f" + page + " §7of §f" + totalPages + "  ")).append(next));
        }
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack source, String @NonNull [] args) {
        ArrayList<String> suggestions = new ArrayList<>();

        if(args.length <= 1) {
            suggestions.add("create");
            suggestions.add("list");
            suggestions.add("changeOwner");
            suggestions.add("remove");
        }
        if(args.length != 0 && args[0] != null && !suggestions.isEmpty() && !args[0].isEmpty()) {
            suggestions.removeIf(s -> !s.startsWith(args[0]));
        }
        return suggestions;
    }

    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return sender.hasPermission("iceboatracing.command.car") && sender instanceof Player;
    }

    @Override
    public @Nullable String permission() {
        return "iceboatracing.command.car";
    }


}
