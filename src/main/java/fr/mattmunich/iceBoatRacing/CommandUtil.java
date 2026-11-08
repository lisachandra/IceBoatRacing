package fr.mattmunich.iceBoatRacing;

import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared helpers for the command classes, so every command root follows the same conventions:
 * one race-lookup behavior, one material parsing behavior and one suggestion-filtering behavior.
 */
public final class CommandUtil {

    private CommandUtil() {}

    /**
     * Resolves a race by name (case-insensitive). Race names are always required — there is no
     * single-race fallback. On a miss the standard "race not found" message is sent to the
     * sender and null is returned, so callers can just early-return.
     */
    public static Race getRaceOrError(RaceManager raceManager, String raceName, CommandSender sender) {
        Race race = raceManager.getRace(raceName);
        if (race == null) {
            sender.sendMessage(Messages.getMessage("race.notFound"));
            return null;
        }
        return race;
    }

    /**
     * Parses a material from any common spelling: the Bukkit constant ("WHITE_WOOL"), a
     * namespaced id ("minecraft:white_wool") or spaced words ("white wool").
     * @return the material, or null when nothing matches or it is not obtainable as an item
     */
    public static Material parseMaterialToken(String token) {
        if (token == null || token.isBlank()) return null;

        String cleaned = token.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        if (cleaned.startsWith("MINECRAFT:")) cleaned = cleaned.substring("MINECRAFT:".length());

        Material material = Material.matchMaterial(cleaned);
        if (material == null || !material.isItem()) return null;
        return material;
    }

    /** Filters {@code options} against what the player already typed. */
    public static List<String> filter(List<String> options, String token) {
        List<String> out = new ArrayList<>();
        StringUtil.copyPartialMatches(token == null ? "" : token, options, out);
        return out;
    }

    public static List<String> suggestRaces(RaceManager raceManager, String token) {
        List<String> names = new ArrayList<>();
        for (Race race : raceManager.getRaces()) names.add(race.getName());
        return filter(names, token);
    }

    private static final List<String> ITEM_MATERIAL_NAMES = buildItemMaterialNames();

    private static List<String> buildItemMaterialNames() {
        List<String> names = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem()) names.add(material.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Every obtainable item material name (Bukkit constant form), prefix-filtered. */
    public static List<String> suggestItemMaterials(String token) {
        return filter(ITEM_MATERIAL_NAMES, token);
    }

    public static List<String> suggestOnlinePlayers(String token) {
        List<String> names = new ArrayList<>();
        Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
        return filter(names, token);
    }

    public static List<String> suggestPages(int totalPages, String token) {
        List<String> pages = new ArrayList<>();
        for (int i = 1; i <= totalPages; i++) pages.add(String.valueOf(i));
        return filter(pages, token);
    }
}
