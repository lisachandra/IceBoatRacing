package fr.mattmunich.iceBoatRacing.checkpoint;

import fr.mattmunich.iceBoatRacing.CommandUtil;
import fr.mattmunich.iceBoatRacing.LiveScoreManager;
import fr.mattmunich.iceBoatRacing.PosSelection;
import fr.mattmunich.iceBoatRacing.checkpoint.autotrace.AutoTraceManager;
import fr.mattmunich.iceBoatRacing.checkpoint.autotrace.AutoTraceSession;
import fr.mattmunich.iceBoatRacing.checkpoint.autotrace.PendingAutoTraceConfig;
import fr.mattmunich.iceBoatRacing.race.Race;
import fr.mattmunich.iceBoatRacing.race.RaceCreator;
import fr.mattmunich.iceBoatRacing.race.RaceManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Messages.*;

public class CheckpointCommand implements BasicCommand {

    private final CheckpointManager checkpointManager;
    private final CheckpointPreview checkpointPreview;
    private final RaceManager raceManager;
    private final AutoTraceManager autoTraceManager;
    private final RaceCreator raceCreator;
    private final LiveScoreManager liveScoreManager;


    public CheckpointCommand(CheckpointManager checkpointManager, CheckpointPreview checkpointPreview, RaceManager raceManager, AutoTraceManager autoTraceManager, RaceCreator raceCreator, LiveScoreManager liveScoreManager) {
        this.checkpointManager = checkpointManager;
        this.checkpointPreview = checkpointPreview;
        this.raceManager = raceManager;
        this.autoTraceManager = autoTraceManager;
        this.raceCreator = raceCreator;
        this.liveScoreManager = liveScoreManager;
    }


    private final Map<Player, PendingClear> pendingClears = new HashMap<>();

    private record PendingClear(String raceName, long timestamp) {}

    @Override
    public void execute(CommandSourceStack source, String @NonNull [] args) {
        if(!(source.getSender() instanceof Player p)) {
            return;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("create")) {
            handleCreate(p, args);
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("override")) {
            handleOverride(p, args);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("renumber")) {
            handleRenumber(p, args[1]);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("renumber")) {
            p.sendMessage(getMessage("checkpoint.renumber.usage"));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("shape")) {
            handleShape(p, args);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("type")) {
            handleType(p, args);
        } else if (args.length == 1 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {

            CheckpointManager.NearestCheckpointOutput output = checkpointManager.getNearest(p.getLocation());
            Race race = output.bestRace();
            Checkpoint checkpoint = output.checkpoint();
            Checkpoint.AlternateRoute alt = output.alt();
            double dist = output.distance();

            if ((checkpoint == null && alt == null) || dist > 50) {
                p.sendMessage(getMessage("checkpoint.noCheckpointFound"));
                return;
            }

            if (race != null && !canEditOrder(p, race)) return;

            if (checkpoint != null) {
                if (!checkpointManager.remove(race, checkpoint)) {
                    p.sendMessage(getMessage("error.unknown"));
                    return;
                }
                p.sendMessage(getMessage("checkpoint.removed", formatArguments("id", "" + checkpoint.getId())));
            } else {
                if (!checkpointManager.removeAlternate(race, alt.getParent(), alt)) {
                    p.sendMessage(getMessage("error.unknown"));
                    return;
                }
                p.sendMessage(getMessage("checkpoint.removed", formatArguments("id", alt.getParent().getId() + " - ALT")));
            }

            checkpointPreview.refresh(race);
        } else if (args.length >= 3 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {

            String raceName = args[1];
            Race race = raceManager.getRace(raceName);
            if (race == null) {
                source.getSender().sendMessage(getMessage("race.notFound"));
                return;
            }

            int checkpointNum;
            try {
                checkpointNum = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                p.sendMessage(getMessage("error.invalidNumber"));
                return;
            }

            if (!canEditOrder(p, race)) return;

            Checkpoint checkpoint = race.getCheckpoint(checkpointNum);

            if (checkpoint == null) {
                p.sendMessage(getMessage("checkpoint.invalid"));
                return;
            }

            if (!checkpointManager.remove(race, checkpoint)) {
                p.sendMessage(getMessage("error.unknown"));
                return;
            }

            checkpointPreview.refresh(race);
            p.sendMessage(getMessage("checkpoint.removed",
                    formatArguments("id", "" + checkpointNum)
            ));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            String raceName = args[1];
            Race race = raceManager.getRace(raceName);
            if(race == null) {
                source.getSender().sendMessage(getMessage("race.notFound"));
                return;
            }

            CheckpointPreview.ToggleResult result = checkpointPreview.toggle(p, race);
            String messageKey = switch (result) {
                case ON -> "checkpoint.view.on";
                case OFF -> "checkpoint.view.off";
                case SWITCHED -> "checkpoint.view.switched";
            };
            p.sendMessage(getMessage(messageKey, formatArguments("race", race.getName())));
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            Map<Race, List<Checkpoint>> all = checkpointManager.getAll();

            if (all.values().stream().allMatch(List::isEmpty)) {
                p.sendMessage(getMessage("checkpoint.noCheckpoints"));
                return;
            }

            // Flatten into ordered (Race, Checkpoint) pairs for pagination
            List<Map.Entry<Race, Checkpoint>> entries = new ArrayList<>();
            for (Map.Entry<Race, List<Checkpoint>> entry : all.entrySet()) {
                for (Checkpoint cp : entry.getValue()) {
                    entries.add(Map.entry(entry.getKey(), cp));
                }
            }

            final int PAGE_SIZE = 10;
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

            p.sendMessage(c("§bCheckpoints §7(page §f" + page + "§7/§f" + totalPages + "§7):"));

            String lastRaceName = null;
            for (int i = start; i < end; i++) {
                Race race = entries.get(i).getKey();
                Checkpoint checkpoint = entries.get(i).getValue();

                // Print race header when the race changes
                if (!race.getName().equals(lastRaceName)) {
                    lastRaceName = race.getName();
                    p.sendMessage(c("§3▌ §b" + race.getName()));
                }

                Location min = checkpoint.getMin();
                Location max = checkpoint.getMax();

                //A BOX stores the exclusive extent of the selected blocks, so show the blocks themselves.
                int maxX = max.getBlockX();
                int maxY = max.getBlockY();
                int maxZ = max.getBlockZ();
                if (checkpoint.getShape() == Checkpoint.Shape.BOX) {
                    maxX -= 1;
                    maxY -= 1;
                    maxZ -= 1;
                }

                Component minPos = Component.text(" X:" + min.getBlockX() + " Y:" + min.getBlockY() + " Z:" + min.getBlockZ())
                        .hoverEvent(HoverEvent.showText(c("Click to copy to clipboard")))
                        .clickEvent(ClickEvent.copyToClipboard(min.getBlockX() + " " + min.getBlockY() + " " + min.getBlockZ()));

                Component maxPos = Component.text(" X:" + maxX + " Y:" + maxY + " Z:" + maxZ)
                        .hoverEvent(HoverEvent.showText(c("Click to copy to clipboard")))
                        .clickEvent(ClickEvent.copyToClipboard(maxX + " " + maxY + " " + maxZ));

                Component removeText = Component.text("[x]")
                        .hoverEvent(HoverEvent.showText(c("Click to delete checkpoint")))
                        .clickEvent(ClickEvent.runCommand("/checkpoint remove " + race.getName() + " " + checkpoint.getId()));

                //Teleport to the gate itself: the plane center for a PLANE checkpoint, or the inside of
                //the near corner for a BOX one (its min/max are extents, not block corners).
                String tpTarget = checkpoint.getShape() == Checkpoint.Shape.PLANE
                        ? checkpoint.getCenter().getX() + " " + checkpoint.getCenter().getY() + " " + checkpoint.getCenter().getZ()
                        : (min.getBlockX() + 0.5) + " " + min.getBlockY() + " " + (min.getBlockZ() + 0.5);

                Component tpText = Component.text("[→]")
                        .hoverEvent(HoverEvent.showText(c("Click to teleport")))
                        .clickEvent(ClickEvent.runCommand("/tp " + tpTarget));

                String shapeTag = checkpoint.getShape() == Checkpoint.Shape.PLANE ? getStringMessage("checkpoint.list.planeTag") : "";
                String altTag = checkpoint.getAlternates().isEmpty() ? "" :
                        getStringMessage("checkpoint.list.altTag").replace("%count%", "" + checkpoint.getAlternates().size());

                p.sendMessage(c(
                        "§e  #" + checkpoint.getId()
                                + " §7[" + race.getName() + "]"
                                + shapeTag
                                + altTag
                ).append(minPos).append(c(" §7→")).append(maxPos).append(c("§7 | §f")).append(removeText).append(c(" ")).append(tpText));
            }

            // Page navigation row
            if (totalPages > 1) {
                Component prev = page > 1
                        ? c("§a[←]").clickEvent(ClickEvent.runCommand("/checkpoint list " + (page - 1)))
                        : c("§8[←]");
                Component next = page < totalPages
                        ? c("§a[→]").clickEvent(ClickEvent.runCommand("/checkpoint list " + (page + 1)))
                        : c("§8[→]");

                p.sendMessage(prev.append(c("  §7Page §f" + page + " §7of §f" + totalPages + "  ")).append(next));
            }
        } else if (args.length == 1 && args[0].equalsIgnoreCase("count")) {
            int count = checkpointManager.getAllNoRaceInfo().size();

            p.sendMessage(getMessage("checkpoint.count",formatArguments("count", "" + count)));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("resetData")) {
            String raceName = args[1];
            Race race = raceManager.getRace(raceName);
            if(race == null) {
                source.getSender().sendMessage(getMessage("race.notFound"));
                return;
            }

            Player target = Bukkit.getPlayer(args[2]);
            if(target == null) {
                p.sendMessage(getMessage("error.playerNotFound"));
                return;
            }

            //Remove data
            race.racers.remove(target.getUniqueId());

            //Reset player's data on live scoreboard
//            main.liveSidebar.getScore(target.getName()).resetScore();
            liveScoreManager.removeRacer(target);
            p.sendMessage(getMessage("checkpoint.resetPlayerScore",formatArguments("player",p.getName())));
        } else if (args.length >= 1 && args[0].equalsIgnoreCase("autotrace")) {
            handleAutoTrace(p, args);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("addAlternate")) {
            handleAddAlternate(p, args);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("clearAll")) {
            handleClearAll(p, args[1]);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("getID")) {
            String raceName = args[1];
            Race race = raceManager.getRace(raceName);
            if(race == null) {
                source.getSender().sendMessage(getMessage("race.notFound"));
                return;
            }
            int id = checkpointManager.getNearestCheckpoint(race, p.getLocation());
            p.sendMessage(getMessage("checkpoint.nearestCheckpointID",formatArguments(
                    "race",race.getName(),
                    "id", "" + id
            )));
        } else {
            p.sendMessage(getMessage("checkpoint.help"));
        }
    }

    /**
     * /checkpoint create <raceName> [id]          - NORMAL (auto-promotes to START_FINISH if the race is empty)
     * /checkpoint create sector <raceName> [id]   - SECTOR
     * /checkpoint create finish <raceName> [id]   - START_FINISH (explicit)
     * Also accepts the legacy "/checkpoint create <raceName> SECTOR" form for muscle-memory compat.
     *
     * When an ID is given the checkpoint is inserted at that position and every existing
     * checkpoint at or after it is shifted up by one. Without an ID it is appended.
     */
    private void handleCreate(Player p, String[] args) {
        if (args.length < 2) {
            p.sendMessage(getMessage("checkpoint.create.usage"));
            return;
        }

        Location l1 = PosSelection.pos1.get(p);
        Location l2 = PosSelection.pos2.get(p);
        if (l1 == null || l2 == null) {
            p.sendMessage(getMessage("pos.notSet"));
            return;
        }

        String raceName = null;
        Checkpoint.Type explicitType = null;
        int idArgIndex = -1;

        if (args[1].equalsIgnoreCase("sector") || args[1].equalsIgnoreCase("finish")) {
            explicitType = args[1].equalsIgnoreCase("sector") ? Checkpoint.Type.SECTOR : Checkpoint.Type.START_FINISH;
            if (args.length >= 3) {
                raceName = args[2];
                idArgIndex = 3;
            }
        } else {
            raceName = args[1];
            idArgIndex = 2;
            // legacy form: /checkpoint create <raceName> SECTOR [id]
            if (args.length >= 3 && args[2].equalsIgnoreCase("SECTOR")) {
                explicitType = Checkpoint.Type.SECTOR;
                idArgIndex = 3;
            }
        }

        if (raceName == null) {
            p.sendMessage(getMessage("checkpoint.create.usage"));
            return;
        }

        Race race = raceManager.getRace(raceName);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        //An explicit ID means we're inserting into the order, which is only safe on a race that isn't moving
        Integer id = null;
        if (args.length > idArgIndex) {
            try {
                id = Integer.parseInt(args[idArgIndex]);
            } catch (NumberFormatException e) {
                p.sendMessage(getMessage("error.invalidNumber"));
                return;
            }

            if (!canEditOrder(p, race)) return;

            int maxId = race.getCheckpoints().stream().mapToInt(Checkpoint::getId).max().orElse(0);
            if (id < 1 || id > maxId + 1) {
                p.sendMessage(getMessage("checkpoint.idOutOfRange", formatArguments(
                        "min", "1",
                        "max", "" + (maxId + 1)
                )));
                return;
            }
        } else if (!canEditOrder(p, race)) {
            return;
        }

        //An empty race has no start line yet, so the first checkpoint is always the start/finish.
        //This has to include the explicit-type forms: a SECTOR at ID 1 would leave the race with no
        //START_FINISH at all, and a lap can only be completed by crossing one.
        boolean raceIsEmpty = race.getCheckpoints().isEmpty();
        if (raceIsEmpty) {
            if (explicitType != null && explicitType != Checkpoint.Type.START_FINISH) {
                p.sendMessage(getMessage("checkpoint.startFinishRequired"));
                return;
            }
            explicitType = Checkpoint.Type.START_FINISH;
        }

        Checkpoint.Type type = explicitType == null ? Checkpoint.Type.NORMAL : explicitType;

        //ID 1 must stay the start line: a racer's lap begins by crossing it. Checked after the type is
        //resolved, so a plain /checkpoint create <race> 1 can't push the start line down to ID 2.
        if (id != null && id == 1 && type != Checkpoint.Type.START_FINISH) {
            p.sendMessage(getMessage("checkpoint.startFinishRequired"));
            return;
        }

        //A lap ends by crossing the start line, and the racer is put back on ID 1 afterwards, so a
        //second START_FINISH would be a gate you can never legitimately cross. An insert never replaces
        //the existing one, so any other start line is a conflict here.
        if (type == Checkpoint.Type.START_FINISH && wouldDuplicateStartFinish(race, null)) {
            p.sendMessage(getMessage("checkpoint.finishAlreadyExists", formatArguments("race", race.getName())));
            return;
        }

        //Only drop the player's shovel selection once the save is certain to happen.
        Checkpoint checkpoint = id == null
                ? checkpointManager.saveCheckpoint(race, l1, l2, type)
                : checkpointManager.saveCheckpointAt(race, l1, l2, type, id, true);

        if (checkpoint == null) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }

        PosSelection.pos1.remove(p);
        PosSelection.pos2.remove(p);

        checkpointPreview.ensureViewing(p, race);
        checkpointPreview.refresh(race);

        if (raceIsEmpty) {
            p.sendMessage(getMessage("checkpoint.startLineSaved"));
        } else {
            reportSaved(p, checkpoint, type, id != null);
        }
    }

    /**
     * /checkpoint override [sector|finish] <raceName> <id>
     * Replaces the checkpoint at {@code id} in place: same position in the lap order, new
     * geometry. The old checkpoint's alternate routes and sector ID are discarded.
     */
    private void handleOverride(Player p, String[] args) {
        Location l1 = PosSelection.pos1.get(p);
        Location l2 = PosSelection.pos2.get(p);
        if (l1 == null || l2 == null) {
            p.sendMessage(getMessage("pos.notSet"));
            return;
        }

        String raceName;
        Checkpoint.Type explicitType = null;
        int idArgIndex;

        if (args.length >= 2 && (args[1].equalsIgnoreCase("sector") || args[1].equalsIgnoreCase("finish"))) {
            explicitType = args[1].equalsIgnoreCase("sector") ? Checkpoint.Type.SECTOR : Checkpoint.Type.START_FINISH;
            if (args.length < 4) {
                p.sendMessage(getMessage("checkpoint.override.usage"));
                return;
            }
            raceName = args[2];
            idArgIndex = 3;
        } else {
            if (args.length < 3) {
                p.sendMessage(getMessage("checkpoint.override.usage"));
                return;
            }
            raceName = args[1];
            idArgIndex = 2;
        }

        Race race = raceManager.getRace(raceName);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        int id;
        try {
            id = Integer.parseInt(args[idArgIndex]);
        } catch (NumberFormatException e) {
            p.sendMessage(getMessage("error.invalidNumber"));
            return;
        }

        if (!canEditOrder(p, race)) return;

        Checkpoint existing = race.getCheckpoint(id);
        if (existing == null) {
            p.sendMessage(getMessage("checkpoint.invalid"));
            return;
        }

        //Keep the existing type unless one was given explicitly, so an override only moves geometry.
        Checkpoint.Type type = explicitType == null ? existing.getType() : explicitType;

        if (id == 1 && type != Checkpoint.Type.START_FINISH) {
            p.sendMessage(getMessage("checkpoint.startFinishRequired"));
            return;
        }

        //Replacing ID 1 with the start line is fine; turning a *different* gate into a second start
        //line is not, because a racer reset to ID 1 after a lap could never cross it.
        if (type == Checkpoint.Type.START_FINISH && wouldDuplicateStartFinish(race, id)) {
            p.sendMessage(getMessage("checkpoint.finishAlreadyExists", formatArguments("race", race.getName())));
            return;
        }

        Checkpoint checkpoint = checkpointManager.saveCheckpointAt(race, l1, l2, type, id, false);
        if (checkpoint == null) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }

        //Only clear the selection once the save succeeded — a rejected command must not eat the
        //player's pos1/pos2 and force them to re-click both blocks.
        PosSelection.pos1.remove(p);
        PosSelection.pos2.remove(p);

        checkpointPreview.ensureViewing(p, race);
        checkpointPreview.refresh(race);
        reportSaved(p, checkpoint, type, false, true);
    }

    /**
     * /checkpoint renumber <raceName>
     * Closes the gaps left by /checkpoint remove, which deletes an ID without shifting the rest.
     * A gap makes the race impossible to finish, since the next checkpoint is looked up by ID.
     */
    private void handleRenumber(Player p, String raceName) {
        Race race = raceManager.getRace(raceName);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        if (!canEditOrder(p, race)) return;

        int count = checkpointManager.renumber(race);
        if (count < 0) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }

        checkpointPreview.ensureViewing(p, race);
        checkpointPreview.refresh(race);

        p.sendMessage(getMessage("checkpoint.renumbered", formatArguments(
                "count", "" + count,
                "race", race.getName()
        )));
    }

    /**
     * /checkpoint shape <raceName> <checkpointID> <plane|box>
     * Converts a checkpoint between an axis-aligned box and an oriented plane, keeping its ID,
     * type, sector ID and alternate routes. Lets a hand-placed box be promoted to the same kind of
     * gate the auto-tracer generates, without re-recording the track.
     */
    private void handleShape(Player p, String[] args) {
        Race race = raceManager.getRace(args[1]);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        int checkpointID;
        try {
            checkpointID = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            p.sendMessage(getMessage("error.invalidNumber"));
            return;
        }

        Checkpoint.Shape target = switch (args[3].toLowerCase()) {
            case "plane" -> Checkpoint.Shape.PLANE;
            case "box" -> Checkpoint.Shape.BOX;
            default -> null;
        };
        if (target == null) {
            p.sendMessage(getMessage("checkpoint.shape.usage"));
            return;
        }

        Checkpoint checkpoint = race.getCheckpoint(checkpointID);
        if (checkpoint == null) {
            p.sendMessage(getMessage("checkpoint.invalid"));
            return;
        }

        if (!canEditOrder(p, race)) return;

        if (checkpoint.getShape() == target) {
            p.sendMessage(getMessage("checkpoint.shape.already", formatArguments(
                    "id", "" + checkpointID,
                    "shape", target.name()
            )));
            return;
        }

        Checkpoint converted = checkpointManager.convertShape(race, checkpoint, target);
        if (converted == null) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }

        checkpointPreview.ensureViewing(p, race);
        checkpointPreview.refresh(race);

        p.sendMessage(getMessage("checkpoint.shape.converted", formatArguments(
                "id", "" + converted.getId(),
                "shape", converted.getShape().name()
        )));
    }

    /**
     * /checkpoint type <raceName> <checkpointID> <normal|sector|finish>
     * Converts a checkpoint's type in place, keeping its shape, geometry and alternate routes.
     * The same two invariants as {@code /checkpoint create} apply: ID 1 has to stay the start line,
     * and a race can only have one of them.
     */
    private void handleType(Player p, String[] args) {
        Race race = raceManager.getRace(args[1]);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        int checkpointID;
        try {
            checkpointID = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            p.sendMessage(getMessage("error.invalidNumber"));
            return;
        }

        Checkpoint.Type target = switch (args[3].toLowerCase()) {
            case "normal" -> Checkpoint.Type.NORMAL;
            case "sector" -> Checkpoint.Type.SECTOR;
            case "finish" -> Checkpoint.Type.START_FINISH;
            default -> null;
        };
        if (target == null) {
            p.sendMessage(getMessage("checkpoint.type.usage"));
            return;
        }

        Checkpoint checkpoint = race.getCheckpoint(checkpointID);
        if (checkpoint == null) {
            p.sendMessage(getMessage("checkpoint.invalid"));
            return;
        }

        if (!canEditOrder(p, race)) return;

        if (checkpoint.getType() == target) {
            p.sendMessage(getMessage("checkpoint.type.already", formatArguments(
                    "id", "" + checkpointID,
                    "type", target.name()
            )));
            return;
        }

        //The lap starts by crossing ID 1, so it can't be demoted to a mid-lap gate.
        if (checkpointID == 1 && target != Checkpoint.Type.START_FINISH) {
            p.sendMessage(getMessage("checkpoint.startFinishRequired"));
            return;
        }

        //A second START_FINISH would be a gate a lap can never legitimately be credited through.
        if (target == Checkpoint.Type.START_FINISH && wouldDuplicateStartFinish(race, checkpointID)) {
            p.sendMessage(getMessage("checkpoint.finishAlreadyExists", formatArguments("race", race.getName())));
            return;
        }

        Checkpoint converted = checkpointManager.convertType(race, checkpoint, target);
        if (converted == null) {
            p.sendMessage(getMessage("error.unknown"));
            return;
        }

        checkpointPreview.ensureViewing(p, race);
        checkpointPreview.refresh(race);

        p.sendMessage(getMessage("checkpoint.type.converted", formatArguments(
                "id", "" + converted.getId(),
                "type", converted.getType().name(),
                "sectorID", converted.getType() == Checkpoint.Type.SECTOR
                        ? "" + converted.getSectorID()
                        : "-"
        )));
    }

    /**
     * Checkpoint ID surgery rewrites the lap order, so refuse it while a race is using that order.
     * @return true if the race is idle and the edit can go ahead
     */
    private boolean canEditOrder(Player p, Race race) {
        if (race.isPreparing() || race.isStarting() || race.hasStarted()) {
            p.sendMessage(getMessage("checkpoint.raceBusy", formatArguments("race", race.getName())));
            return false;
        }
        return true;
    }

    /**
     * True if creating a START_FINISH checkpoint this way would give the race a second one.
     * <p>
     * An insert shifts every checkpoint at or after the target up, so an existing start line can never
     * be "replaced" that way — any START_FINISH at all conflicts. An override replaces the target in
     * place, so the target itself doesn't conflict but every other start line does.
     *
     * @param replacingId the ID being overwritten, or null when inserting
     */
    private boolean wouldDuplicateStartFinish(Race race, Integer replacingId) {
        for (Checkpoint checkpoint : race.getCheckpoints()) {
            if (checkpoint.getType() != Checkpoint.Type.START_FINISH) continue;
            if (replacingId != null && checkpoint.getId() == replacingId) continue;
            return true;
        }
        return false;
    }

    private void reportSaved(Player p, Checkpoint checkpoint, Checkpoint.Type type, boolean inserted) {
        reportSaved(p, checkpoint, type, inserted, false);
    }

    private void reportSaved(Player p, Checkpoint checkpoint, Checkpoint.Type type, boolean inserted, boolean overridden) {
        if (overridden) {
            p.sendMessage(getMessage("checkpoint.overridden", formatArguments("id", "" + checkpoint.getId())));
        } else if (inserted) {
            p.sendMessage(getMessage("checkpoint.inserted", formatArguments("id", "" + checkpoint.getId())));
        } else if (type == Checkpoint.Type.SECTOR) {
            p.sendMessage(getMessage("checkpoint.sectorSaved", formatArguments("sectorID", "" + checkpoint.getSectorID())));
        } else if (type == Checkpoint.Type.START_FINISH) {
            p.sendMessage(getMessage("checkpoint.finishLineSaved"));
        } else {
            p.sendMessage(getMessage("checkpoint.saved", formatArguments("id", "" + checkpoint.getId())));
        }
    }

    /**
     * /checkpoint autotrace start <raceName> [spacing] [halfWidth] [halfHeight] [loop|noloop]
     * /checkpoint autotrace stop
     * /checkpoint autotrace preview
     * /checkpoint autotrace sector
     * /checkpoint autotrace accept
     * /checkpoint autotrace cancel
     */
    private void handleAutoTrace(Player player, String[] args) {
        if (args.length < 2) {
            sendAutoTraceHelp(player);
            return;
        }

        String sub = args[1];

        if (sub.equalsIgnoreCase("start")) {
            if (args.length < 3) {
                player.sendMessage(getMessage("checkpoint.autotrace.usageStart"));
                return;
            }

            Race race = raceManager.getRace(args[2]);
            if (race == null) {
                player.sendMessage(getMessage("race.notFound"));
                return;
            }

            boolean isCreating = args.length == 4 && args[3].equalsIgnoreCase("--create");

            PendingAutoTraceConfig pendingAutoTraceConfig = new PendingAutoTraceConfig(race);
            pendingAutoTraceConfig.isCreatingRace = isCreating;
            autoTraceManager.setPendingConfig(player, pendingAutoTraceConfig);
            player.sendMessage(buildAutoTracePanel(pendingAutoTraceConfig));
        } else if (sub.equalsIgnoreCase("confirm")) {
            PendingAutoTraceConfig pendingConfig = autoTraceManager.getPendingConfig(player);
            if(pendingConfig == null) {
                player.sendMessage(getMessage("checkpoint.autotrace.config.error.noneDefined")); //tells that player should use the start command first
                return;
            }

            Race race = pendingConfig.race;
            double spacing = pendingConfig.spacing;
            double halfWidth = pendingConfig.width/2;
            double halfHeight = pendingConfig.height/2;
            boolean loop = pendingConfig.loop;
            boolean isCreating = pendingConfig.isCreatingRace;

            autoTraceManager.start(player, race, spacing, halfWidth, halfHeight, loop, isCreating);
            player.sendMessage(getMessage("checkpoint.autotrace.started", formatArguments("race", race.getName())));
            autoTraceManager.clearPendingConfig(player);
        } else if (sub.equalsIgnoreCase("configure")) {
            /*
            /checkpoint autotrace configure <param> <value>
            */
            if (args.length != 4) {
                player.sendMessage(getMessage("checkpoint.autotrace.usageConfigure"));
                return;
            }

            String param = args[2];
            String value = args[3];
            PendingAutoTraceConfig pendingConfig = autoTraceManager.getPendingConfig(player);
            if(pendingConfig == null) {
                player.sendMessage(getMessage("checkpoint.autotrace.config.error.noneDefined")); //tells that player should use the start command first
                return;
            }

            switch (param.toLowerCase()) {
                case "width" -> {
                    double numValue;
                    try {
                        numValue = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        player.sendMessage(getMessage("checkpoint.autotrace.config.error.invalidNumerical"));
                        return;
                    }

                    pendingConfig.width = numValue;
                    player.sendMessage(getMessage("checkpoint.autotrace.config.defined", formatArguments(
                            "param", param.toLowerCase(),
                            "value", String.valueOf(numValue)
                    )));
                }
                case "height" -> {
                    double numValue;
                    try {
                        numValue = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        player.sendMessage(getMessage("checkpoint.autotrace.config.error.invalidNumerical"));
                        return;
                    }

                    pendingConfig.height = numValue;
                    player.sendMessage(getMessage("checkpoint.autotrace.config.defined", formatArguments(
                            "param", param.toLowerCase(),
                            "value", String.valueOf(numValue)
                    )));
                }
                case "spacing" -> {
                    double numValue;
                    try {
                        numValue = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        player.sendMessage(getMessage("checkpoint.autotrace.config.error.invalidNumerical"));
                        return;
                    }

                    pendingConfig.spacing = numValue;
                    player.sendMessage(getMessage("checkpoint.autotrace.config.defined", formatArguments(
                            "param", param.toLowerCase(),
                            "value", String.valueOf(numValue)
                    )));
                }
                case "loop" -> {
                    pendingConfig.loop = !pendingConfig.loop;
                    player.sendMessage(getMessage("checkpoint.autotrace.config.defined", formatArguments(
                            "param", param.toLowerCase(),
                            "value", String.valueOf(pendingConfig.loop)
                    )));
                }
                default -> player.sendMessage(getMessage("checkpoint.autotrace.config.error.invalidParam")); //sends param list
            }

            player.sendMessage(buildAutoTracePanel(pendingConfig));

        } else if (sub.equalsIgnoreCase("stop")) {
            if (!autoTraceManager.hasSession(player)) {
                player.sendMessage(getMessage("checkpoint.autotrace.noSession"));
                return;
            }

            autoTraceManager.stop(player);
            List<Checkpoint> generated = autoTraceManager.generatePreview(player);

            if (generated.isEmpty()) {
                player.sendMessage(getMessage("checkpoint.autotrace.notEnoughPoints"));
                return;
            }

            player.sendMessage(getMessage("checkpoint.autotrace.stopped", formatArguments("count", "" + generated.size())));

        } else if (sub.equalsIgnoreCase("preview")) {
            if (!autoTraceManager.hasSession(player) || autoTraceManager.getSession(player).preview.isEmpty()) {
                player.sendMessage(getMessage("checkpoint.autotrace.noPreview"));
                return;
            }

            boolean nowOn = autoTraceManager.togglePreview(player);
            player.sendMessage(getMessage(nowOn ? "checkpoint.autotrace.previewOn" : "checkpoint.autotrace.previewOff"));

        } else if (sub.equalsIgnoreCase("sector")) {
            Location l1 = PosSelection.pos1.get(player);
            Location l2 = PosSelection.pos2.get(player);

            if (l1 == null || l2 == null) {
                player.sendMessage(getMessage("pos.notSet"));
                return;
            }

            if (!autoTraceManager.hasSession(player) || autoTraceManager.getSession(player).preview.isEmpty()) {
                player.sendMessage(getMessage("checkpoint.autotrace.noPreviewForSector"));
                return;
            }

            boolean success = autoTraceManager.addSectorMarker(player, l1, l2);
            if (!success) {
                player.sendMessage(getMessage("checkpoint.autotrace.sectorFailed"));
                return;
            }

            PosSelection.pos1.remove(player);
            PosSelection.pos2.remove(player);
            player.sendMessage(getMessage("checkpoint.autotrace.sectorMarked"));

        } else if (sub.equalsIgnoreCase("add")) {
            Location l1 = PosSelection.pos1.get(player);
            Location l2 = PosSelection.pos2.get(player);

            if (l1 == null || l2 == null) {
                player.sendMessage(getMessage("pos.notSet"));
                return;
            }

            if (!autoTraceManager.hasSession(player) || autoTraceManager.getSession(player).preview.isEmpty()) {
                player.sendMessage(getMessage("checkpoint.autotrace.noPreviewForSector"));
                return;
            }

            boolean success = autoTraceManager.addManualMarker(player, l1, l2);
            if (!success) {
                player.sendMessage(getMessage("checkpoint.autotrace.addFailed"));
                return;
            }

            PosSelection.pos1.remove(player);
            PosSelection.pos2.remove(player);
            player.sendMessage(getMessage("checkpoint.autotrace.added"));

        } else if (sub.equalsIgnoreCase("resize")) {
            Location l1 = PosSelection.pos1.get(player);
            Location l2 = PosSelection.pos2.get(player);

            if (l1 == null || l2 == null) {
                player.sendMessage(getMessage("pos.notSet"));
                return;
            }

            AutoTraceManager.EditInfo info = autoTraceManager.resizeNearest(player, l1, l2);
            if (info == null) {
                player.sendMessage(getMessage("checkpoint.autotrace.resizeFailed"));
                return;
            }

            PosSelection.pos1.remove(player);
            PosSelection.pos2.remove(player);

            //An edit should always be visible, even if the preview wasn't running.
            autoTraceManager.ensurePreview(player);
            autoTraceManager.refreshPreview(player);

            player.sendMessage(getMessage("checkpoint.autotrace.resized", formatArguments(
                    "position", "" + info.previewPosition(),
                    "type", info.type().name(),
                    "distance", String.format("%.1f", info.distance())
            )));

        } else if (sub.equalsIgnoreCase("delete")) {
            AutoTraceManager.EditInfo info = autoTraceManager.deleteNearest(player, player.getLocation());
            if (info == null) {
                player.sendMessage(getMessage("checkpoint.autotrace.deleteFailed"));
                return;
            }

            autoTraceManager.ensurePreview(player);
            autoTraceManager.refreshPreview(player);

            player.sendMessage(getMessage("checkpoint.autotrace.deleted", formatArguments(
                    "position", "" + info.previewPosition(),
                    "type", info.type().name(),
                    "distance", String.format("%.1f", info.distance())
            )));

        } else if (sub.equalsIgnoreCase("accept")) {
            AutoTraceSession session = autoTraceManager.getSession(player);
            boolean isCreating = session != null && session.isCreating;

            boolean success = autoTraceManager.accept(player);
            if (!success) {
                player.sendMessage(getMessage("checkpoint.autotrace.acceptFailed"));
                if (isCreating) raceCreator.step4(player);
                return;
            }
            player.sendMessage(getMessage("checkpoint.autotrace.accepted"));
            if(isCreating) raceCreator.goToStep5RaceCreation(player);

        } else if (sub.equalsIgnoreCase("cancel")) {
            AutoTraceSession session = autoTraceManager.getSession(player);
            boolean isCreating = session != null && session.isCreating && !(args.length == 3 && args[2].equals("--noRestart"));

            autoTraceManager.cancel(player);
            player.sendMessage(getMessage("checkpoint.autotrace.cancelled"));
            if (isCreating) raceCreator.step4(player);
        } else {
            sendAutoTraceHelp(player);
        }
    }

    /**
     * Builds the clickable auto-trace settings panel: current values plus buttons to edit
     * each one (via SUGGEST_COMMAND, so the player just types the number and hits enter) and
     * a button to confirm and start recording.
     */
    private Component buildAutoTracePanel(PendingAutoTraceConfig config) {
        Component header = c(getStringMessage("checkpoint.autotrace.config.panel.header")
                .replace("%race%", config.race.getName()));

        Component spacingLine = c(getStringMessage("checkpoint.autotrace.config.panel.spacing")
                .replace("%value%", formatNum(config.spacing)))
                .append(c(" ")).append(editButton("spacing"));

        Component widthLine = c(getStringMessage("checkpoint.autotrace.config.panel.width")
                .replace("%value%", formatNum(config.width)))
                .append(c(" ")).append(editButton("width"));

        Component heightLine = c(getStringMessage("checkpoint.autotrace.config.panel.height")
                .replace("%value%", formatNum(config.height)))
                .append(c(" ")).append(editButton("height"));

        String loopKey = config.loop
                ? "checkpoint.autotrace.config.panel.loopOn"
                : "checkpoint.autotrace.config.panel.loopOff";
        Component loopLine = c(getStringMessage(loopKey))
                .append(c(" "))
                .append(c(getStringMessage("checkpoint.autotrace.config.panel.toggle"))
                        .clickEvent(ClickEvent.runCommand("/checkpoint autotrace configure loop toggle")));

        Component startButton = c(getStringMessage("checkpoint.autotrace.config.panel.start"))
                .clickEvent(ClickEvent.runCommand("/checkpoint autotrace confirm"));

        return header
                .append(c("\n")).append(spacingLine)
                .append(c("\n")).append(widthLine)
                .append(c("\n")).append(heightLine)
                .append(c("\n")).append(loopLine)
                .append(c("\n")).append(startButton);
    }

    private Component editButton(String param) {
        return c(getStringMessage("checkpoint.autotrace.config.panel.edit"))
                .clickEvent(ClickEvent.suggestCommand("/checkpoint autotrace configure " + param + " "));
    }

    /** Whole numbers print without a trailing ".0"; anything else keeps one decimal place. */
    private String formatNum(double value) {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format("%.1f", value);
    }

    /**
     * /checkpoint addAlternate <raceName> <checkpointID>
     * Uses the wand (pos1/pos2) to define an alternate gate for an existing checkpoint — crossing
     * this gate will count as crossing the target checkpoint. Used for bypass routes (e.g. a
     * stands/pit lane) that rejoin the track without physically crossing the original checkpoint.
     */
    private void handleAddAlternate(Player player, String[] args) {
        Location l1 = PosSelection.pos1.get(player);
        Location l2 = PosSelection.pos2.get(player);

        if (l1 == null || l2 == null) {
            player.sendMessage(getMessage("pos.notSet"));
            return;
        }

        Race race = raceManager.getRace(args[1]);
        if (race == null) {
            player.sendMessage(getMessage("race.notFound"));
            return;
        }

        int checkpointID;
        try {
            checkpointID = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(getMessage("error.invalidNumber"));
            return;
        }

        Checkpoint target = race.getCheckpoint(checkpointID);
        if (target == null) {
            player.sendMessage(getMessage("checkpoint.invalid"));
            return;
        }

        if (!canEditOrder(player, race)) return;

        Vector span = l2.toVector().subtract(l1.toVector());
        Vector rawNormal = new Vector(-span.getZ(), 0, span.getX());
        if (rawNormal.lengthSquared() < 1e-6) rawNormal = new Vector(1, 0, 0);
//        Vector normal = CheckpointGeometry.snapToCardinal(rawNormal);

        Location center = CheckpointGeometry.midpoint(l1, l2);
        double halfWidth = CheckpointGeometry.spanHalfWidth(l1, l2, CheckpointGeometry.WIDTH_PADDING);
        double heightDiff = Math.abs(l1.getY() - l2.getY());
        double halfHeight = heightDiff > 0.5 ? heightDiff / 2.0 : 2.5;

        boolean success = checkpointManager.saveAlternateRoute(race, target, center, rawNormal, halfWidth, halfHeight);
        if (!success) {
            player.sendMessage(getMessage("error.unknown"));
            return;
        }

        PosSelection.pos1.remove(player);
        PosSelection.pos2.remove(player);

        checkpointPreview.ensureViewing(player, race);
        checkpointPreview.refresh(race);
        player.sendMessage(getMessage("checkpoint.addAlternate.added", formatArguments("id", "" + target.getId())));
    }

    /**
     * /checkpoint clearAll <raceName>
     * Destructive — requires the command to be run twice within 10 seconds to actually clear.
     * The first run just warns and starts the confirmation window.
     */
    private void handleClearAll(Player player, String raceName) {
        Race race = raceManager.getRace(raceName);
        if (race == null) {
            player.sendMessage(getMessage("race.notFound"));
            return;
        }

        //Checkpoint ID surgery like any other: refuse while a race is using that order
        if (!canEditOrder(player, race)) return;

        long now = System.currentTimeMillis();
        PendingClear pending = pendingClears.get(player);

        if (pending != null && pending.raceName().equalsIgnoreCase(raceName) && now - pending.timestamp() <= 10_000) {
            pendingClears.remove(player);

            boolean success = checkpointManager.clearAllCheckpoints(race);
            if (!success) {
                player.sendMessage(getMessage("error.unknown"));
                return;
            }

            player.sendMessage(getMessage("checkpoint.clearAll.cleared", formatArguments("race", race.getName())));
            checkpointPreview.refresh(race);
            return;
        }

        pendingClears.put(player, new PendingClear(raceName, now));
        int count = race.getCheckpoints().size();
        player.sendMessage(getMessage("checkpoint.clearAll.warning", formatArguments("count", "" + count, "race", race.getName())));
        player.sendMessage(getMessage("checkpoint.clearAll.confirmHint", formatArguments("race", raceName)));
    }

    private void sendAutoTraceHelp(Player player) {
        player.sendMessage(getMessage("checkpoint.autotrace.help"));
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack source, String @NonNull [] args) {
        String token = args.length == 0 ? "" : args[0];

        if (args.length <= 1) {
            return CommandUtil.filter(List.of("list", "count", "create", "override", "renumber", "shape", "type",
                    "view", "remove", "resetData", "autotrace", "addAlternate", "clearAll", "getID"), token);
        }

        switch (args[0].toLowerCase()) {
            case "create", "override" -> {
                //create [sector|finish] <race> [id] — override is the same with a mandatory id
                boolean keywordForm = args[1].equalsIgnoreCase("sector") || args[1].equalsIgnoreCase("finish");
                boolean isCreate = args[0].equalsIgnoreCase("create");
                if (args.length == 2) {
                    //The next token is either the type keyword or the race of the plain form
                    List<String> options = new ArrayList<>(List.of("sector", "finish"));
                    raceManager.getRaces().forEach(race -> options.add(race.getName()));
                    return CommandUtil.filter(options, token);
                }
                if (args.length == 3) {
                    if (keywordForm) return CommandUtil.suggestRaces(raceManager, args[2]);
                    return suggestIds(args[1], isCreate, args[2]);
                }
                if (args.length == 4 && keywordForm) {
                    return suggestIds(args[2], isCreate, args[3]);
                }
            }
            case "renumber", "view", "clearall", "getid" -> {
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
            }
            case "shape" -> {
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
                if (args.length == 3) return suggestIds(args[1], false, args[2]);
                if (args.length == 4) return CommandUtil.filter(List.of("plane", "box"), args[3]);
            }
            case "type" -> {
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
                if (args.length == 3) return suggestIds(args[1], false, args[2]);
                if (args.length == 4) return CommandUtil.filter(List.of("normal", "sector", "finish"), args[3]);
            }
            case "remove", "delete" -> {
                //remove <race> <id> — the 1-arg nearest form has nothing to complete
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
                if (args.length == 3) return suggestIds(args[1], false, args[2]);
            }
            case "resetdata" -> {
                //resetData <race> <player>
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
                if (args.length == 3) return CommandUtil.suggestOnlinePlayers(args[2]);
            }
            case "addalternate" -> {
                if (args.length == 2) return CommandUtil.suggestRaces(raceManager, args[1]);
                if (args.length == 3) return suggestIds(args[1], false, args[2]);
            }
            case "list" -> {
                if (args.length == 2) return CommandUtil.suggestPages(totalListPages(), args[1]);
            }
            //count takes no arguments
            case "autotrace" -> {
                if (args.length == 2) {
                    return CommandUtil.filter(List.of("start", "stop", "preview", "sector", "add", "resize", "delete",
                            "accept", "cancel", "confirm", "configure"), args[1]);
                }
                if (args.length == 3) {
                    return switch (args[1].toLowerCase()) {
                        case "start" -> CommandUtil.suggestRaces(raceManager, args[2]);
                        case "cancel" -> CommandUtil.filter(List.of("--noRestart"), args[2]);
                        case "configure" -> CommandUtil.filter(List.of("width", "height", "spacing", "loop"), args[2]);
                        default -> List.of();
                    };
                }
                if (args.length == 4) {
                    if (args[1].equalsIgnoreCase("start")) {
                        return CommandUtil.filter(List.of("--create"), args[3]);
                    }
                    if (args[1].equalsIgnoreCase("configure")) {
                        return CommandUtil.filter(
                                args[2].equalsIgnoreCase("loop") ? List.of("toggle") : List.of("2", "4", "8", "16"),
                                args[3]);
                    }
                }
            }
        }
        return List.of();
    }

    /** Total list pages, for the page-number suggestions. */
    private int totalListPages() {
        int total = checkpointManager.getAll().values().stream().mapToInt(List::size).sum();
        if (total == 0) return 1;
        return (int) Math.ceil((double) total / 10);
    }

    /**
     * Checkpoint IDs of {@code raceName}, prefix-filtered. {@code includeNextId} offers the next
     * free ID as well, since {@code create} accepts inserting at {@code maxId+1}.
     */
    private List<String> suggestIds(String raceName, boolean includeNextId, String token) {
        Race race = raceManager.getRace(raceName);
        if (race == null) return List.of();
        List<String> ids = new ArrayList<>();
        int maxId = 0;
        for (Checkpoint cp : race.getCheckpoints()) {
            ids.add("" + cp.getId());
            if (cp.getId() > maxId) maxId = cp.getId();
        }
        if (includeNextId) ids.add("" + (maxId + 1));
        return CommandUtil.filter(ids, token);
    }

    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return sender instanceof Player p && p.hasPermission("iceboatracing.command.checkpoint");
    }

    @Override
    public @Nullable String permission() {
        return "iceboatracing.command.checkpoint";
    }
}