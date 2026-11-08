package fr.mattmunich.iceBoatRacing.pitbox;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;

public class PitBox {

    public enum TaskType {
        /** The boat is held in place for the box's duration. */
        TIMED,
        /** The driver hops out and loots a chest inside the box to finish the stop. */
        CHEST
    }

    private final int id;
    private final String name;
    private PitBoxColor color;
    private final Location min;
    private final Location max;
    private TaskType taskType;
    private final int duration; // seconds, only meaningful for TIMED
    private int lootCount; // items to loot, only meaningful for CHEST
    private Material lootItem; // item to loot, only meaningful for CHEST
    private List<UUID> allowed; // player UUIDs

    public PitBox(int id, String name, Location min, Location max, TaskType taskType, int duration, List<UUID> allowed, PitBoxColor color) {
        this(id, name, min, max, taskType, duration, 5, Material.SNOWBALL, allowed, color);
    }

    public PitBox(int id, String name, Location min, Location max, TaskType taskType, int duration, int lootCount, Material lootItem, List<UUID> allowed, PitBoxColor color) {
        this.id = id;
        this.name = name;
        this.min = min;
        this.max = max;
        this.taskType = taskType;
        this.duration = duration;
        this.lootCount = lootCount;
        this.lootItem = lootItem;
        this.allowed = allowed;
        this.color = color;
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(min.getWorld())) return false;
        return loc.getX() >= min.getX() && loc.getX() <= max.getX() &&
                loc.getY() >= min.getY() && loc.getY() <= max.getY() &&
                loc.getZ() >= min.getZ() && loc.getZ() <= max.getZ();
    }

    public String getName() {
        return name;
    }

    public void setAllowed(List<UUID> allowed) {
        this.allowed = allowed;
    }

    public boolean isAllowed(OfflinePlayer player) {
        if(allowed.isEmpty()) return true;
        for (UUID allowed : allowed) {
            if (allowed.equals(player.getUniqueId())) return true;
        }
        return false;
    }

    public PitBoxColor getColor() {
        return color;
    }

    public void setColor(PitBoxColor color) {
        this.color = color;
    }

    public int getId() {
        return id;
    }

    public Location getMin() {
        return min;
    }

    public Location getMax() {
        return max;
    }

    public Location getCenter() {
        return min.clone().add(max).multiply(0.5);
    }

    public TaskType getTaskType() {
        return taskType;
    }

    public void setTaskType(TaskType taskType) {
        this.taskType = taskType;
    }

    public int getDuration() {
        return duration;
    }

    public int getLootCount() {
        return lootCount;
    }

    public void setLootCount(int lootCount) {
        this.lootCount = lootCount;
    }

    public Material getLootItem() {
        return lootItem;
    }

    public void setLootItem(Material lootItem) {
        this.lootItem = lootItem;
    }

    public List<UUID> getAllowed() {
        return allowed;
    }
}