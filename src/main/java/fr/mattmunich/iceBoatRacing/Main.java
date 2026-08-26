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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static fr.mattmunich.iceBoatRacing.Messages.getMessage;

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

    public Objective liveSidebar;

    //Config
    public static GameMode RACING_GAMEMODE;
    public static boolean ENFORCE_RACING_GAMEMODE;
    public static boolean SPECTATE_ON_FINISH;
    public static boolean REMOVE_CAR_ON_LOGOUT;

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

        if (registerScoreboard()) return;

        registerCommands();

        registerListeners();

        log("Done enabling!");

    }

    private void loadPacketEvents() {
        if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
            BoatCollisionSuppressor.register(raceManager);
            log("PacketEvents detected — boat collision fix enabled.");
        } else {
            warn("PacketEvents not found — boat collision fix won't work!");
        }
    }

    private void registerListeners() {
        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new CarListener(raceManager),this);
        pm.registerEvents(new CarCreator(this, raceManager, carManager), this);
        pm.registerEvents(new Connection(this, raceManager, carManager),this);
        pm.registerEvents(new CheckpointCommand(checkpointManager, raceManager, autoTraceManager, new RaceCreator(this, raceManager, carManager, carCreator), this),this);
        pm.registerEvents(new RaceListener(this, raceManager, pitBoxManager),this);
        pm.registerEvents(new RaceCreator(this, raceManager,carManager,carCreator), this);
        pm.registerEvents(new WorldLoad(this, raceManager), this);
        pm.registerEvents(new PitBoxCreator(this, pitBoxManager), this);
    }

    public void updateConstants() {
        //Racing game mode
        try {
            RACING_GAMEMODE = GameMode.valueOf(getConfig().getString("race.racingGameMode"));
        } catch (IllegalArgumentException e) {
            warn("Could not parse racingGameMode from config file, set to default ADVENTURE");
            RACING_GAMEMODE = GameMode.ADVENTURE;
        }

        //Enforce racing game mode
        ENFORCE_RACING_GAMEMODE = getConfig().getBoolean("race.enforceRacingGameMode");

        //Spectate on finish
        SPECTATE_ON_FINISH = getConfig().getBoolean("race.spectateOnFinish");

        //Remove car on logout
        REMOVE_CAR_ON_LOGOUT = getConfig().getBoolean("race.removeCarWhenLoggingOut");
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
        carManager = new CarManager(this);
        checkpointManager = new CheckpointManager(this);
        autoTraceManager = new AutoTraceManager(this, checkpointManager);
        pitBoxManager = new PitBoxManager(this);

        //Load race manager
        raceManager = new RaceManager(this,carManager, checkpointManager, pitBoxManager);
        log("Done loading managers!");

        //Initalise race manager in car and checkpoint manager
        carManager.setRaceManager(raceManager);
        checkpointManager.setRaceManager(raceManager);
        log("Initiazed RaceManager for Car and Checkpoint managers");

        //Load races after CarManager and CheckpointManager have RaceManager set and after server startup, so that all worlds are loaded.
        Bukkit.getScheduler().runTask(this, () -> raceManager.loadAllRaces());
    }

    private void loadCreators() {
        log("Loading car and race creator...");
        carCreator = new CarCreator(this, raceManager, carManager);
        raceCreator = new RaceCreator(this,raceManager,carManager,carCreator);
        pitBoxCreator = new PitBoxCreator(this, pitBoxManager);
        log("Done loading car and race creator!");
    }

    public boolean registerScoreboard() {
        log("Registering scoreboard...");
        ScoreboardManager scoreboardManager = Bukkit.getScoreboardManager();
        Scoreboard scoreboard = scoreboardManager.getMainScoreboard();
        Objective tempSidebar = scoreboard.getObjective("live_ice_boat_racing");
        if(tempSidebar != null) tempSidebar.unregister();
        try {
            liveSidebar = scoreboard.registerNewObjective("live_ice_boat_racing", Criteria.DUMMY,getMessage("noPrefix.liveSidebarTitle"));
        } catch (IllegalArgumentException ignored) {
            log("Live Sidebar was found, although it should have been unregistered.");
            liveSidebar = scoreboard.getObjective("live_ice_boat_racing");
            if(liveSidebar == null) {
                log("Could not find live sidebar!");
                Bukkit.getPluginManager().disablePlugin(this);
                return true;
            }
            liveSidebar.displayName(getMessage("noPrefix.liveSidebarTitle"));
        }
        if(liveSidebar == null) {
            log("Could not find live sidebar!");
            Bukkit.getPluginManager().disablePlugin(this);
            return true;
        }
        liveSidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        log("Done registering scoreboard!");
        return false;
    }

    private void registerCommands() {
        log("Registering commands...");
        registerCommand("iceboatracing", "Command to manage the plugin", List.of("ibr"), new IBRCommand(this));
        registerCommand("race", "Command to manage the race", new RaceCommand(this, raceManager,raceCreator));
        registerCommand("checkpoint", "Command to manage checkpoints", new CheckpointCommand(checkpointManager, raceManager, autoTraceManager, new RaceCreator(this, raceManager, carManager, carCreator), this));
        registerCommand("car", "Command to manage cars", new CarCommand(carManager,raceManager,carCreator));
        registerCommand("pitbox", "Command to manage pit boxes", new PitBoxCommand(raceManager, pitBoxManager, pitBoxCreator));
        log("Done registering commands!");
    }

    @Override
    public void onDisable() {
        super.onDisable();
        raceManager.saveAllRaces();
    }

    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage("[IceBoatRacing] " + message);
    }

    public void err(String message, Exception e) { getLogger().severe("[IceBoatRacing] " + message + "\nStacktrace: "  + e.getMessage() + "\n" + Arrays.toString(e.getStackTrace())); }

    public void warn(String message) { getLogger().warning("[IceBoatRacing] " + message); }

    public void severe(String message) { getLogger().severe("[IceBoatRacing] " + message); }

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
