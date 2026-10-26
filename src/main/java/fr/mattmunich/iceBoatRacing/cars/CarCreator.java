package fr.mattmunich.iceBoatRacing.cars;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Main.s;
import static fr.mattmunich.iceBoatRacing.Messages.formatArguments;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;
import static fr.mattmunich.iceBoatRacing.Messages.getStringMessage;
import static fr.mattmunich.iceBoatRacing.race.RaceCreator.definingCars;

public class CarCreator implements Listener {

    private final Main main;
    private final RaceManager raceManager;
    private final CarManager carManager;

    private static final Map<Player, Integer> creatingCar = new HashMap<>();
    private static final Map<Player, Location> brokenBlock = new HashMap<>();
    private static final Map<Player, Location> carLocation = new HashMap<>();
    private static final Map<Player, UUID> tempOwner = new HashMap<>();
    private static final Map<Player, ItemStack> tempCar = new HashMap<>();

    public CarCreator(Main main, RaceManager raceManager, CarManager carManager) {
        this.main = main;
        this.raceManager = raceManager;
        this.carManager = carManager;
    }

    public void createCar(Player p) {
        Title title = Title.title(
                getMessage("car.create.1.title"),
                getMessage("car.create.1.subtitle",formatArguments(
                        "check",
                        getStringMessage("car.create.1.check")
                ))
        );
        p.showTitle(title);
        p.sendMessage(getMessage("car.create.1.message",formatArguments("check", getStringMessage("car.create.1.check"))));
        creatingCar.put(p,1);
    }

    @EventHandler
    public void setCarSpawnLocation(PlayerInteractEvent e) {
        if(!e.getAction().equals(Action.LEFT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        Integer step = creatingCar.get(p);
        if (step == null || step != 1) return;
        e.setCancelled(true);
        if(e.getClickedBlock() == null) return;
        brokenBlock.put(p, e.getClickedBlock().getLocation().add(0,1,0));
        p.sendMessage(getMessage("car.create.1.selected",formatArguments("check",getStringMessage("car.create.1.check"))));
    }

    @EventHandler
    public void confirmCarSpawnLocation(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingCar.get(p);
        if (step == null || step != 1) return;
        e.setCancelled(true);
        String message = s(e.message());
        Bukkit.getScheduler().runTask(main, () -> {
            if(!message.equalsIgnoreCase(getStringMessage("car.create.1.check"))) {
                Title title = Title.title(
                        getMessage("car.create.1.title"),
                        getMessage("car.create.1.subtitle",formatArguments(
                                "check",
                                getStringMessage("car.create.1.check")
                        ))
                );
                p.showTitle(title);
                p.sendMessage(getMessage("car.create.1.message",formatArguments("check", getStringMessage("car.create.1.check"))));
                return;
            }

            if(brokenBlock.get(p)==null) {
                p.sendMessage(getMessage("car.create.1.noSelection"));
            }

            carLocation.put(p, brokenBlock.get(p));
            brokenBlock.remove(p);
            p.sendMessage(getMessage("car.create.1.completed"));

            //STEP 2
            Title title = Title.title(getMessage("car.create.2.title"),c(""));
            p.showTitle(title);
            p.sendMessage(getMessage("car.create.2.message"));
            creatingCar.replace(p, 2);
        });
    }

    @EventHandler
    public void setOwnerName(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingCar.get(p);
        if (step == null || step != 2) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(message);
            if (!owner.hasPlayedBefore()) {
                p.sendMessage(getMessage("error.playerNotFound"));
                return;
            }

            p.sendMessage(getMessage(
                    "car.create.2.completed",
                    formatArguments("owner", owner.getName())
            ));

            // Save owner temporarily
            creatingCar.replace(p, 3);
            tempOwner.put(p, owner.getUniqueId());

            // STEP 3
            Title title = Title.title(
                    getMessage("car.create.3.title"),
                    getMessage("car.create.3.subtitle", formatArguments("check",getStringMessage("car.create.3.check")))
            );
            p.showTitle(title);
            p.sendMessage(getMessage("car.create.3.message", formatArguments("check",getStringMessage("car.create.3.check"))));
        });
    }

    @EventHandler
    public void selectBoatType(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Integer step = creatingCar.get(p);
        if (step == null || step != 3) return;

        e.setCancelled(true);
        String message = s(e.message());

        Bukkit.getScheduler().runTask(main, () -> {
            if (!message.equalsIgnoreCase(
                    getStringMessage("car.create.3.check")
            )) {
                p.sendMessage(getMessage("car.create.3.message", formatArguments("check",getStringMessage("car.create.3.check"))));
                return;
            }

            ItemStack held = p.getInventory().getItemInMainHand();

            if (!held.getType().name().endsWith("_BOAT") && !held.getType().name().endsWith("_RAFT")) {
                p.sendMessage(getMessage("car.create.3.invalidItem"));
                return;
            }

            tempCar.put(p,held);

            if(definingCars.containsKey(p)) {
                save(p,definingCars.get(p));
                return;
            }

            //Select the car's race
            p.sendMessage(getMessage("race.select"));
            for (Race race : raceManager.races) {
                Component raceLine = c("§3▌ §e" + race.getName())
                        .clickEvent(ClickEvent.runCommand("/car selectRace " + race.getName())
                        )
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                c("§7Click to select §b" + race.getName())
                        ));
                p.sendMessage(raceLine);
            }
        });
    }

    public void selectRace(String @NonNull [] args, Player p) {
        String raceName = args[1];

        save(p, raceName);
    }

    private void save(Player p, String raceName) {
        Race race = raceManager.getRace(raceName);
        if(race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        boolean alreadyHasCar = false;
        for (Car car : race.getCars()) {
            if(car.getOwner().equals(tempOwner.get(p))) {
                alreadyHasCar = true;
            }
        }

        if(alreadyHasCar) {
            p.sendMessage(getMessage("car.create.error.ownerAlreadyHasCar"));
            cleanup(p);
            return;
        }

        if (tempCar.get(p) == null) {
            p.sendMessage(getMessage("car.create.completed.carIsNull"));
            cleanup(p);
            return;
        }

        // FINAL SAVE
        carManager.saveCar(
                race,
                tempOwner.get(p),
                carLocation.get(p),
                tempCar.get(p)
        );

        p.sendMessage(getMessage("car.create.completed.message"));
        Title title = Title.title(
                getMessage("car.create.completed.title"),
                getMessage("car.create.completed.subtitle")
        );
        p.showTitle(title);

        // cleanup
        cleanup(p);

        if(definingCars.containsKey(p)) {
            Bukkit.getScheduler().runTaskLater(main, () -> createCar(p),60L);
        }
    }

    public void cleanup(Player p) {
        try {
            creatingCar.remove(p);
            tempOwner.remove(p);
            carLocation.remove(p);
            tempCar.remove(p);
            brokenBlock.remove(p);
        } catch (Exception ignored) {}
    }
}
