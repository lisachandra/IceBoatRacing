package fr.mattmunich.iceBoatRacing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Main.severe;

public class Messages {

    private static YamlConfiguration langConfig;
    private static Main main;

    private static String currentLang;

    public Messages(Main main) {
        Messages.main = main;

        loadLanguageFile();
    }

    /**
     * Loads the selected language file from /lang/
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void loadLanguageFile() {

        // Get language from config.yml
        currentLang = main.getConfig().getString("language", "en_US");

        File pluginFolder = main.getDataFolder();
        File langFolder = new File(pluginFolder, "lang");

        if (!langFolder.exists()) {
            main.log("Creating lang folder...");
            langFolder.mkdirs();
        }

        File langFile = new File(langFolder, currentLang + ".yml");

        // If language file doesn't exist, create it
        if (!langFile.exists()) {
            main.log("Creating default language file: " + langFile.getName());

            try {
                langFile.createNewFile();
            } catch (IOException e) {
                main.getLogger().severe("Could not create language file!");
                Bukkit.getPluginManager().disablePlugin(main);
                return;
            }
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        main.log("Loaded language: " + currentLang);
    }

    public static Component getMessage(String identifier) {

        if (!langConfig.contains(identifier)) {
            main.getLogger().severe("Missing message: " + identifier +
                    " in lang/" + currentLang + ".yml");
            return c("§cMESSAGE NOT FOUND");
        }

        // Prefix special case
        if (identifier.equalsIgnoreCase("prefix")) {
            return MiniMessage.miniMessage().deserialize(
                    Objects.requireNonNull(langConfig.getString("prefix"))
            );
        }

        Component message = c("§r" + langConfig.getString(identifier));

        // Add prefix automatically
        if (addPrefix(identifier)) {
            message = getMessage("prefix").append(c("§r ")).append(message);
        }

        return message;
    }

    public static String getStringMessage(String identifier) {
        if (langConfig == null || !langConfig.contains(identifier)) {
            main.getLogger().severe("Missing message string: " + identifier + " in lang/" + currentLang + ".yml");
            return "MESSAGE NOT FOUND";
        }

        return langConfig.getString(identifier);
    }


    /**
     * Formats placeholders like %player%
     * @param args Format: "key1", "value1", "key2", "value2"
     */
    public static Map<String, Object> formatArguments(Object... args) {
        Map<String, Object> map = new HashMap<>();

        for (int i = 0; i < args.length; i += 2) {
            String key = args[i].toString();
            Object value = args[i + 1];
            map.put(key, value);
        }

        return map;
    }

    public static Map<String, Component> formatComponentArguments(Object... args) {

        Map<String, Component> map = new HashMap<>();

        try {
            for (int i = 0; i < args.length; i += 2) {
                map.put((String) args[i], (Component) args[i + 1]);
            }
        } catch (Exception e) {
            return null;
        }

        return map;
    }

    public static Component getMessage(String identifier, Map<String, Object> arguments) {

//        Trying without that
//        if (!langConfig.contains(identifier)) {
//            severe("Missing message: " + identifier);
//            return c("§cMESSAGE NOT FOUND");
//        }

        String message = langConfig.getString(identifier);

        if (message == null) {
            severe("Missing message: " + identifier);
            return c("§cMESSAGE NOT FOUND");
        }

        // Replace placeholders
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            //Ignore unset arguments
            if(!message.contains("%" + entry.getKey() + "%")) continue;
            //Actually replace
            message = message.replace("%" + entry.getKey() + "%", entry.getValue().toString());
        }

        //Convert to component for PaperMC
        Component component = c(message);

        //Add the prefix if required
        if (addPrefix(identifier)) {
            component = getMessage("prefix").append(c("§r ")).append(component);
        }

        return component;
    }

    public static Component getMessage(String identifier, Map<String, Object> arguments, Map<String,Component> componentArguments) {

        if (!langConfig.contains(identifier)) {
            main.getLogger().severe("Missing message: " + identifier);
            return c("§cMESSAGE NOT FOUND");
        }

        String message = langConfig.getString(identifier);

        if (message == null) return c("§cMESSAGE NOT FOUND");

        // Replace placeholders
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            //Ignore unset arguments
            if(!message.contains("%" + entry.getKey() + "%")) continue;
            //Actually replace
            message = message.replace("%" + entry.getKey() + "%", entry.getValue().toString());
        }


        //Convert to component for PaperMC
        Component component = c(message);
        for (Map.Entry<String, Component> entry : componentArguments.entrySet()) {
            //Ignore unset arguments
            if(!message.contains("%" + entry.getKey() + "%")) continue;

            //Actually replace
            TextReplacementConfig replacementConfig = TextReplacementConfig.builder()
                    .match("%" + entry.getKey() + "%")
                    .replacement(entry.getValue())
                    .build();

            component = component.replaceText(replacementConfig);
        }
        //Add the prefix if required
        if (addPrefix(identifier)) {
            component = getMessage("prefix").append(c("§r ")).append(component);
        }

        return component;
    }

    private static boolean addPrefix(String identifier) {
        return
        (
                !identifier.contains("noPrefix") //Currently used for join/quit and liveSidebarTitle
                && !identifier.equals("prefix") //Don't add prefix after prefix
                && !identifier.contains("title") //=> titles and subtitles
                && !identifier.contains("liveScore") //Live Score sidebar
                && !identifier.contains("countdown") //sector countdown
                && !identifier.contains("actionBar") //actionBar messages
                //Commands when creating race, checkpoints or cars
                && !identifier.contains("check")
                && !identifier.contains("start")
                && !identifier.contains("later")
                && !identifier.contains("checkpointCreation")
                && !identifier.contains("carCreation")
                //For race.onEnd messages
                && !identifier.contains("playerFormat")
                && !identifier.contains("winnerFormat")
                && !identifier.contains("disqualifiedFormat")
                && !identifier.contains("highlights")
                && !identifier.contains("bestSectorTimesTop")
                && !identifier.contains("bottom")
                && !identifier.contains("sectorFormat")
        )
                || identifier.contains("checkpoint.");
    }
}