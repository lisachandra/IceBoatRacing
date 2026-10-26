package fr.mattmunich.iceBoatRacing.checkpoint;

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
    private final RaceManager raceManager;
    private final AutoTraceManager autoTraceManager;
    private final RaceCreator raceCreator;
    private final LiveScoreManager liveScoreManager;


    public CheckpointCommand(CheckpointManager checkpointManager, RaceManager raceManager, AutoTraceManager autoTraceManager, RaceCreator raceCreator, LiveScoreManager liveScoreManager) {
        this.checkpointManager = checkpointManager;
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

            if (checkpoint != null) checkpointManager.remove(race, checkpoint);
            else alt.remove();
            p.sendMessage(getMessage("checkpoint.removed",formatArguments("id",checkpoint == null ? alt.getParent().getId() + " - ALT" : "" + checkpoint.getId())));
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("delete"))) {

            int checkpointNum;
            try {
                checkpointNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                p.sendMessage(getMessage("error.invalidNumber"));
                return;
            }

            String raceName = args[2];
            Race race = raceManager.getRace(raceName);
            if (race == null) {
                source.getSender().sendMessage(getMessage("race.notFound"));
                return;
            }

            Checkpoint checkpoint = race.getCheckpoint(checkpointNum);

            if (checkpoint == null) {
                p.sendMessage(getMessage("checkpoint.invalid"));
                return;
            }

            boolean success = checkpointManager.remove(race, checkpoint);
            if (!success) {
                p.sendMessage(getMessage("error.unknown"));
                return;
            }
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

            boolean nowOn = checkpointManager.toggleViewCheckpoints(p, race);
            p.sendMessage(getMessage(
                    nowOn ? "checkpoint.view.on" : "checkpoint.view.off",
                    formatArguments("race", race.getName())
            ));
        } else if (args.length <= 2 && args[0].equalsIgnoreCase("list")) {
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

                Component removeText = Component.text("[x]")
                        .clickEvent(ClickEvent.runCommand("/checkpoint remove " + checkpoint.getId() + " " + race.getName()));

                Component tpText = Component.text("[→]")
                        .clickEvent(ClickEvent.runCommand("/tp " + min.getBlockX() + " " + min.getBlockY() + " " + min.getBlockZ()));

                String shapeTag = checkpoint.getShape() == Checkpoint.Shape.PLANE ? getStringMessage("checkpoint.list.planeTag") : "";
                String altTag = checkpoint.getAlternates().isEmpty() ? "" :
                        getStringMessage("checkpoint.list.altTag").replace("%count%", "" + checkpoint.getAlternates().size());

                p.sendMessage(c(
                        "§e  #" + checkpoint.getId()
                                + " §7[" + race.getName() + "]"
                                + shapeTag
                                + altTag
                                + " §fX:" + min.getBlockX() + " Y:" + min.getBlockY() + " Z:" + min.getBlockZ()
                                + " §7→"
                                + " §fX:" + max.getBlockX() + " Y:" + max.getBlockY() + " Z:" + max.getBlockZ()
                                + " "
                ).append(removeText).append(c(" ")).append(tpText));
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
            Player target = Bukkit.getPlayer(args[1]);
            if(target == null) {
                p.sendMessage(getMessage("error.playerNotFound"));
                return;
            }

            String raceName = args[2];
            Race race = raceManager.getRace(raceName);
            if(race == null) {
                source.getSender().sendMessage(getMessage("race.notFound"));
                return;
            }

            //Remove data
            race.racers.remove(target.getUniqueId());

            //Reset player's data on live scoreboard
//            main.liveSidebar.getScore(target.getName()).resetScore();
            liveScoreManager.removeRacer(target);
            p.sendMessage(getMessage("checkpoint.resetPlayerScore",formatArguments("player",p.getName())));
        } else if (args[0].equalsIgnoreCase("autotrace")) {
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
     * /checkpoint create <raceName>          - NORMAL (auto-promotes to START_FINISH if the race is empty)
     * /checkpoint create sector <raceName>   - SECTOR
     * /checkpoint create finish <raceName>   - START_FINISH (explicit)
     * Also accepts the legacy "/checkpoint create <raceName> SECTOR" form for muscle-memory compat.
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

        String raceName;
        Checkpoint.Type explicitType = null;

        if (args.length >= 3 && (args[1].equalsIgnoreCase("sector") || args[1].equalsIgnoreCase("finish"))) {
            explicitType = args[1].equalsIgnoreCase("sector") ? Checkpoint.Type.SECTOR : Checkpoint.Type.START_FINISH;
            raceName = args[2];
        } else if (args.length >= 3 && args[2].equalsIgnoreCase("SECTOR")) {
            // legacy form
            explicitType = Checkpoint.Type.SECTOR;
            raceName = args[1];
        } else {
            raceName = args[1];
        }

        Race race = raceManager.getRace(raceName);
        if (race == null) {
            p.sendMessage(getMessage("race.notFound"));
            return;
        }

        PosSelection.pos1.remove(p);
        PosSelection.pos2.remove(p);

        if (explicitType == Checkpoint.Type.SECTOR) {
            Checkpoint checkpoint = checkpointManager.saveSectorCheckpoint(race, l1, l2);
            if (checkpoint == null) { p.sendMessage(getMessage("error.unknown")); return; }
            p.sendMessage(getMessage("checkpoint.sectorSaved", formatArguments("sectorID", "" + checkpoint.getSectorID())));
            return;
        }

        if (explicitType == Checkpoint.Type.START_FINISH) {
            Checkpoint checkpoint = checkpointManager.saveCheckpoint(race, l1, l2, Checkpoint.Type.START_FINISH);
            if (checkpoint == null) { p.sendMessage(getMessage("error.unknown")); return; }
            p.sendMessage(getMessage("checkpoint.finishLineSaved"));
            return;
        }

        if (race.getCheckpoints().isEmpty()) {
            checkpointManager.saveCheckpoint(race, l1, l2, Checkpoint.Type.START_FINISH);
            p.sendMessage(getMessage("checkpoint.startLineSaved"));
        }

        Checkpoint checkpoint = checkpointManager.saveCheckpoint(race, l1, l2, Checkpoint.Type.NORMAL);
        if (checkpoint == null) { p.sendMessage(getMessage("error.unknown")); return; }
        p.sendMessage(getMessage("checkpoint.saved", formatArguments("id", String.valueOf(checkpoint.getId()))));
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

            PosSelection.pos1.remove(player);
            PosSelection.pos2.remove(player);

            boolean success = autoTraceManager.addSectorMarker(player, l1, l2);
            if (!success) {
                player.sendMessage(getMessage("checkpoint.autotrace.sectorFailed"));
                return;
            }

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

            PosSelection.pos1.remove(player);
            PosSelection.pos2.remove(player);

            boolean success = autoTraceManager.addManualMarker(player, l1, l2);
            if (!success) {
                player.sendMessage(getMessage("checkpoint.autotrace.addFailed"));
                return;
            }

            player.sendMessage(getMessage("checkpoint.autotrace.added"));

        } else if (sub.equalsIgnoreCase("resize")) {
            Location l1 = PosSelection.pos1.get(player);
            Location l2 = PosSelection.pos2.get(player);

            if (l1 == null || l2 == null) {
                player.sendMessage(getMessage("pos.notSet"));
                return;
            }

            PosSelection.pos1.remove(player);
            PosSelection.pos2.remove(player);

            AutoTraceManager.EditInfo info = autoTraceManager.resizeNearest(player, l1, l2);
            if (info == null) {
                player.sendMessage(getMessage("checkpoint.autotrace.resizeFailed"));
                return;
            }

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

        PosSelection.pos1.remove(player);
        PosSelection.pos2.remove(player);

        Vector span = l2.toVector().subtract(l1.toVector());
        Vector rawNormal = new Vector(-span.getZ(), 0, span.getX());
        if (rawNormal.lengthSquared() < 1e-6) rawNormal = new Vector(1, 0, 0);
//        Vector normal = CheckpointGeometry.snapToCardinal(rawNormal);

        Location center = CheckpointGeometry.midpoint(l1, l2);
        double halfWidth = Math.max(l1.distance(l2) / 2.0 + CheckpointGeometry.WIDTH_PADDING, 0.5);
        double heightDiff = Math.abs(l1.getY() - l2.getY());
        double halfHeight = heightDiff > 0.5 ? heightDiff / 2.0 : 2.5;

        boolean success = checkpointManager.saveAlternateRoute(race, target, center, rawNormal, halfWidth, halfHeight);
        if (!success) {
            player.sendMessage(getMessage("error.unknown"));
            return;
        }

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
        List<String> suggestions = new ArrayList<>();

        if (args.length <= 1) {
            suggestions.add("list");
            suggestions.add("count");
            suggestions.add("create");
            suggestions.add("view");
            suggestions.add("remove");
            suggestions.add("delete");
            suggestions.add("resetData");
            suggestions.add("autotrace");
            suggestions.add("addAlternate");
            suggestions.add("clearAll");
            suggestions.add("getID");
            return suggestions;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (args.length == 2) {
                    suggestions.add("sector");
                    suggestions.add("finish");
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                } else if (args.length == 3) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                }
            }
            case "remove", "delete" -> {
                if (args.length == 2) {
                    for (int i = 0; i < checkpointManager.count(); i++) suggestions.add("" + i);
                } else if (args.length == 3) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                }
            }
            case "view", "clearall", "count", "getid", "list" -> {
                if (args.length == 2) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                }
            }
            case "resetdata" -> {
                if (args.length == 2) {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) suggestions.add(onlinePlayer.getName());
                } else if (args.length == 3) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                }
            }
            case "autotrace" -> {
                if (args.length == 2) {
                    suggestions.add("start");
                    suggestions.add("stop");
                    suggestions.add("preview");
                    suggestions.add("sector");
                    suggestions.add("add");
                    suggestions.add("resize");
                    suggestions.add("delete");
                    suggestions.add("accept");
                    suggestions.add("cancel");
                } else if (args.length == 3 && args[1].equalsIgnoreCase("start")) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                } else if (args.length == 7 && args[1].equalsIgnoreCase("start")) {
                    suggestions.add("loop");
                    suggestions.add("noloop");
                }
            }
            case "addalternate" -> {
                if (args.length == 2) {
                    for (Race race : raceManager.races) suggestions.add(race.getName());
                } else if (args.length == 3) {
                    Race race = raceManager.getRace(args[1]);
                    if (race != null) {
                        for (Checkpoint cp : race.getCheckpoints()) suggestions.add("" + cp.getId());
                    }
                }
            }
        }

        return suggestions;
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