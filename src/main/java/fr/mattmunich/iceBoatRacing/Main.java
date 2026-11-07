package fr.mattmunich.iceBoatRacing;

import fr.mattmunich.iceBoatRacing.cars.*;
import fr.mattmunich.iceBoatRacing.checkpoint.autotrace.AutoTraceManager;
import fr.mattmunich.iceBoatRacing.listeners.Connection;
import fr.mattmunich.iceBoatRacing.listeners.WorldLoad;
import fr.mattmunich.iceBoatRacing.pitbox.PitBoxCommand;
import fr.mattmunich.iceBoatRacing.pitbox.PitBoxCreator;
import fr.mattmunich.iceBoatRacing.pitbox.PitBoxManager;
import fr.mattmunich.iceBoatRacing.race.*;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointCommand;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointManager;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public final class Main extends JavaPlugin {
    public static String version;

    CheckpointManager checkpointManager;
    CarManager carManager;
    CarCreator carCreator;
    Messages messages;
    RaceManager raceManager;
    RaceCreator raceCreator;
    AutoTraceManager autoTraceManager;
    PitBoxManager pitBoxManager;
    PitBoxCreator pitBoxCreator;
    LiveScoreManager liveScoreManager;

//    public Objective liveSidebar;

    //Config
    public static boolean REMOVE_CAR_ON_LOGOUT;
    public static boolean ALLOW_REJOIN;
    public static boolean TELEPORT_BACK_AFTER_RACE;
    public static int INTERMISSION_SECONDS;
    public static boolean INTERMISSION_AUTO_START;
    public static int INTERMISSION_MIN_RACERS;
    public static final List<Integer> INTERMISSION_REMINDERS = new ArrayList<>();

    @Override
    public void onEnable() {
        version = getPluginMeta().getVersion();
        Component name = MiniMessage.miniMessage().deserialize("<b><gradient:#8DABF3:#10CA9A>Ice Boat Racing</gradient></b>");
        String stringName = l(name);
        log("§bEnabling " + stringName + " §3v" + version + "§b...");

        loadPacketEvents();

        loadConfigs();

        loadMessages();

        loadManagers();

        loadCreators();

        registerCommands();

        registerListeners();

        log("Done enabling!");

    }

    private void loadPacketEvents() {
        if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
            BoatCollisionSuppressor.register(this, raceManager);
            log("PacketEvents detected — boat collision fix enabled.");
        } else {
            warn("PacketEvents not found — boat collision fix won't work!");
        }
    }

    public RaceManager getRaceManager() {
        return raceManager;
    }

    public RaceManager resetRaceManager() {
        raceManager = null;
        raceManager = new RaceManager(this, carManager, checkpointManager, pitBoxManager, liveScoreManager);
        return raceManager;
    }

    private void registerListeners() {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new CarListener(raceManager),this);
        pm.registerEvents(new CarCreator(this, raceManager, carManager), this);
        pm.registerEvents(new Connection(this, raceManager, carManager, liveScoreManager),this);
        pm.registerEvents(new RaceListener(raceManager, pitBoxManager, liveScoreManager),this);
        pm.registerEvents(new RaceCreator(this, raceManager,carCreator), this);
        pm.registerEvents(new WorldLoad(this, raceManager), this);
        pm.registerEvents(new PitBoxCreator(this, pitBoxManager), this);
        pm.registerEvents(new PosSelection(), this);
    }

    public void updateConstants() {
        //Allow a racer to rejoin after logging out mid-race
        ALLOW_REJOIN = getConfig().getBoolean("race.allowRejoin", true);

        //Remove car on logout
        REMOVE_CAR_ON_LOGOUT = getConfig().getBoolean("race.removeCarWhenLoggingOut");

        //Return players to where they joined once they leave or finish a race
        TELEPORT_BACK_AFTER_RACE = getConfig().getBoolean("race.teleportBackAfterRace", true);

        //Registration intermission
        INTERMISSION_SECONDS = Math.max(5, getConfig().getInt("race.intermission.seconds", 120));
        INTERMISSION_AUTO_START = getConfig().getBoolean("race.intermission.autoStart", true);
        INTERMISSION_MIN_RACERS = Math.max(1, getConfig().getInt("race.intermission.minRacers", 1));

        INTERMISSION_REMINDERS.clear();
        for (int value : getConfig().getIntegerList("race.intermission.reminderSeconds")) {
            if (value > 0) INTERMISSION_REMINDERS.add(value);
        }
        if (INTERMISSION_REMINDERS.isEmpty()) INTERMISSION_REMINDERS.addAll(List.of(60, 30, 10));
    }

    public void loadConfigs() {
        log("Configuring config files");
        saveDefaultConfig();
        reloadConfig();
        saveResource("lang/en_US.yml", true);
        saveResource("lang/fr_FR.yml", true);
        updateConstants();
        log("Done configuring config files!");
    }


    void loadMessages() {
        log("Loading messages...");
        messages = new Messages(this);
        log("Done loading messages!");
    }

    private void loadManagers() {
        log("Loading managers...");
        //Preload car and checkpoint manager for racemanager
        carManager = new CarManager();
        checkpointManager = new CheckpointManager(this);
        autoTraceManager = new AutoTraceManager(this, checkpointManager);
        pitBoxManager = new PitBoxManager(this);
        liveScoreManager = new LiveScoreManager();

        //Load race manager
        raceManager = new RaceManager(this,carManager, checkpointManager, pitBoxManager, liveScoreManager);
        log("Done loading managers!");

        //Initialize race manager in car and checkpoint manager
        carManager.setRaceManager(raceManager);
        checkpointManager.setRaceManager(raceManager);
        log("Initialized RaceManager for Car and Checkpoint managers");

        //Load races after CarManager and CheckpointManager have RaceManager set and after server startup, so that all worlds are loaded.
        Bukkit.getScheduler().runTask(this, () -> raceManager.loadAllRaces());
    }

    private void loadCreators() {
        log("Loading car and race creator...");
        carCreator = new CarCreator(this, raceManager, carManager);
        raceCreator = new RaceCreator(this,raceManager,carCreator);
        pitBoxCreator = new PitBoxCreator(this, pitBoxManager);
        log("Done loading car and race creator!");
    }

    private void registerCommands() {
        log("Registering commands...");
        //Register via the lifecycle API (available since 1.20.6) instead of
        //JavaPlugin#registerCommand (1.21.4+) so the plugin runs on Paper 1.21.3.
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands registrar = event.registrar();
            registrar.register("iceboatracing", "Command to manage the plugin", List.of("ibr"), new IBRCommand(this));
            registrar.register("race", "Command to manage the race", new RaceCommand(this, raceManager,raceCreator));
            registrar.register("checkpoint", "Command to manage checkpoints", new CheckpointCommand(checkpointManager, raceManager, autoTraceManager, new RaceCreator(this, raceManager, carCreator), liveScoreManager));
            registrar.register("car", "Command to manage cars", new CarCommand(carManager,raceManager,carCreator));
            registrar.register("pitbox", "Command to manage pit boxes", new PitBoxCommand(raceManager, pitBoxManager, pitBoxCreator));
        });
        log("Done registering commands!");
    }

    @Override
    public void onDisable() {
        super.onDisable();
        raceManager.saveAllRaces();
    }

    public void reload() {
        loadConfigs(); //reload configs
        loadMessages(); //reload lang config / change language

        // Reload in-memory data
        raceManager.updateAllRaces();
        liveScoreManager.clear();
        liveScoreManager = new LiveScoreManager();
    }

    public static void log(String message) {
        Bukkit.getConsoleSender().sendMessage("[IceBoatRacing] " + message);
    }

    public static void err(String message, Exception e) { Bukkit.getLogger().severe("[IceBoatRacing] " + message + "\nStacktrace: "  + e.getMessage() + "\n" + Arrays.toString(e.getStackTrace())); }

    public static void warn(String message) { Bukkit.getLogger().warning("[IceBoatRacing] " + message); }

    public static void severe(String message) { Bukkit.getLogger().severe("[IceBoatRacing] " + message); }

    public static Component c(String message) {
        try {
            return LegacyComponentSerializer.legacySection().deserialize(message);
        } catch (Exception e) {
            return LegacyComponentSerializer.legacySection().deserialize("");
        }
    }

    public static @NotNull String s(Component component) {
        try {
            return ((TextComponent) component).content();
        } catch (Exception e) {
            return "";
        }
    }

    public static String l(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public static String formatTime(long durationMs) {
        long minutes = durationMs / 60000;
        long seconds = (durationMs / 1000) % 60;
//        long milliseconds = durationMs % 1000;
        long centiseconds = (durationMs % 1000) / 10;
        return minutes + "m" + String.format("%02d", seconds) + "," + String.format("%02d", centiseconds);
    }
}
