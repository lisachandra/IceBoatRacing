package fr.mattmunich.iceBoatRacing.checkpoint.autotrace;

import fr.mattmunich.iceBoatRacing.Main;
import fr.mattmunich.iceBoatRacing.checkpoint.Checkpoint;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointGeometry;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointManager;
import fr.mattmunich.iceBoatRacing.checkpoint.CheckpointRenderer;
import fr.mattmunich.iceBoatRacing.race.Race;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.mattmunich.iceBoatRacing.Main.warn;
import static fr.mattmunich.iceBoatRacing.Messages.formatArguments;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;

public class AutoTraceManager {

    private static final double RDP_EPSILON = 0.4;
    private static final double LOOP_CLOSE_THRESHOLD = 3.0;
    private static final double MIN_DISTANCE_BEFORE_LOOP_CHECK = 20.0;
    private static final double MAX_HALF_WIDTH_MULTIPLIER = 3.0;

    /** Redraw cadence for the preview, in ticks — short enough that particles never visibly lapse. */
    private static final long PREVIEW_PERIOD = 10L;

    private final Main main;
    private final CheckpointManager checkpointManager;
    private final Map<Player, PendingAutoTraceConfig> pendingAutoTraceConfigs = new HashMap<>();
    private final Map<Player, AutoTraceSession> sessions = new HashMap<>();
    private final Map<Player, org.bukkit.scheduler.BukkitTask> previewTasks = new HashMap<>();

    public AutoTraceManager(Main main, CheckpointManager checkpointManager) {
        this.main = main;
        this.checkpointManager = checkpointManager;
    }

    public boolean isRecording(Player p) {
        AutoTraceSession session = sessions.get(p);
        return session != null && session.samplingTask != null;
    }

    public PendingAutoTraceConfig getPendingConfig(Player player) {
        return pendingAutoTraceConfigs.get(player);
    }

    public void setPendingConfig(Player player, PendingAutoTraceConfig pendingConfig) {
        pendingAutoTraceConfigs.put(player, pendingConfig);
    }

    public void clearPendingConfig(Player p) {
        pendingAutoTraceConfigs.remove(p);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean hasSession(Player player) {
        return sessions.containsKey(player);
    }

    public AutoTraceSession getSession(Player player) {
        return sessions.get(player);
    }

    /**
     * Begins recording the player's location every 2 ticks. Auto-stops (and generates the preview)
     * once the recorded path travels back within LOOP_CLOSE_THRESHOLD of its starting point, after
     * at least MIN_DISTANCE_BEFORE_LOOP_CHECK blocks have been recorded so it can't trigger instantly.
     */
    public void start(Player p, Race race, double spacing, double halfWidth, double halfHeight, boolean loop, boolean isCreating) {
        cancel(p);

        AutoTraceSession session = new AutoTraceSession(p, race);
        session.spacing = spacing;
        session.halfWidth = halfWidth;
        session.halfHeight = halfHeight;
        session.loop = loop;
        session.isCreating = isCreating;
        sessions.put(p, session);

        session.samplingTask = Bukkit.getScheduler().runTaskTimer(main, () -> {
            if (!p.isOnline()) {
                cancel(p);
                return;
            }

            Location current = p.getLocation().clone();

            if (!session.rawPoints.isEmpty()) {
                Location last = session.rawPoints.getLast();
                session.traveledDistance += last.distance(current);
            }
            session.rawPoints.add(current);

            if (session.loop
                    && session.traveledDistance > MIN_DISTANCE_BEFORE_LOOP_CHECK
                    && session.rawPoints.size() > 1) {
                Location first = session.rawPoints.getFirst();
                if (current.getWorld().equals(first.getWorld()) && current.distance(first) <= LOOP_CLOSE_THRESHOLD) {
                    stop(p);
                    List<Checkpoint> generated = generatePreview(p);
                    p.sendMessage(getMessage("checkpoint.autotrace.loopClosed", formatArguments("count", "" + generated.size())));
                    togglePreview(p);
                }
            }
        }, 0L, 2L);
    }

    /** Stops sampling but keeps the recorded points so generatePreview() can still be called. */
    public void stop(Player p) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.samplingTask == null) return;
        session.samplingTask.cancel();
        session.samplingTask = null;
    }

    /** Discards the session entirely, recorded points and all. */
    public void cancel(Player p) {
        stop(p);
        sessions.remove(p);
        stopPreview(p);
    }

    /**
     * Runs simplify -> resample -> recenter-on-track -> plane generation on the recorded points
     * and stores the result as a preview. Does not touch storage.
     * @return the generated checkpoints, or an empty list if there weren't enough recorded points
     */
    public List<Checkpoint> generatePreview(Player p) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.rawPoints.size() < 2) return List.of();

        List<Location> simplified = CheckpointGeometry.simplify(session.rawPoints, RDP_EPSILON);
        List<Location> resampled = CheckpointGeometry.resample(simplified, session.spacing);

        double maxHalfWidth = session.halfWidth * MAX_HALF_WIDTH_MULTIPLIER;
        List<CheckpointGeometry.RecenterResult> recentered = CheckpointGeometry.recenterOnTrack(resampled, session.loop, session.halfWidth, maxHalfWidth);

        List<Location> centerline = new ArrayList<>();
        List<Double> halfWidths = new ArrayList<>();
        for (CheckpointGeometry.RecenterResult r : recentered) {
            centerline.add(r.center());
            halfWidths.add(r.halfWidth());
        }

        int startId = session.race.getCheckpoints().stream()
                .mapToInt(Checkpoint::getId)
                .max()
                .orElse(0) + 1;

        boolean firstIsStartFinish = session.race.getCheckpoints().isEmpty();

        session.preview = CheckpointGeometry.buildPlaneCheckpoints(
                centerline, halfWidths, session.halfHeight, startId, session.loop, firstIsStartFinish, maxHalfWidth
        );
        session.centerline = centerline;
        session.centerlineArcLengths = CheckpointGeometry.cumulativeArcLengths(centerline);

        return session.preview;
    }

    /**
     * Marks a sector checkpoint from two clicked block locations (exact position and width,
     * unlike normal/start-finish checkpoints which are auto-detected from the track surface).
     * Requires a generated preview to exist (run start then stop first).
     * @return true if the sector was added
     */
    public boolean addSectorMarker(Player p, Location l1, Location l2) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.centerline.isEmpty()) return false;

        Location center = CheckpointGeometry.midpoint(l1, l2);
        double halfWidth = CheckpointGeometry.spanHalfWidth(l1, l2, 0);
        double heightDiff = Math.abs(l1.getY() - l2.getY());
        double halfHeight = heightDiff > 0.5 ? heightDiff / 2.0 : session.halfHeight;

        int nearestIndex = CheckpointGeometry.nearestIndex(session.centerline, center);
        Vector tangent = CheckpointGeometry.tangentAt(session.centerline, nearestIndex, session.loop);
        if (tangent == null) return false;

        Vector normal = CheckpointGeometry.snapToCardinal(tangent);

        // ID is a placeholder — saveTracedCheckpoints() reassigns IDs sequentially by list order at commit time.
        Checkpoint sector = new Checkpoint(-1, center, normal, halfWidth, halfHeight, Checkpoint.Type.SECTOR);

        double arcLength = session.centerlineArcLengths[nearestIndex];
        insertByArcLength(session, sector, arcLength);

        ensurePreview(p);
        refreshPreview(p);
        return true;
    }

    private void insertByArcLength(AutoTraceSession session, Checkpoint newCheckpoint, double newArcLength) {
        int insertAt = session.preview.size();

        for (int i = 0; i < session.preview.size(); i++) {
            Checkpoint existing = session.preview.get(i);
            int existingIndex = CheckpointGeometry.nearestIndex(session.centerline, existing.getCenter());
            double existingArcLength = session.centerlineArcLengths[existingIndex];

            if (existingArcLength > newArcLength) {
                insertAt = i;
                break;
            }
        }

        session.preview.add(insertAt, newCheckpoint);
    }

    /** Info about which preview checkpoint an edit command (resize/delete) actually affected. */
    private static final double MAX_EDIT_DISTANCE = 15.0;

    public record EditInfo(int previewPosition, Checkpoint.Type type, double distance) {}

    private record NearestResult(int index, double distance) {}

    /**
     * Manually inserts a NORMAL checkpoint from two clicked points, using the exact clicked span
     * for orientation/width (not track-surface detection) — same derivation as saveAlternateRoute's
     * normal, but as a real sequential checkpoint rather than a bypass gate. Also inserts a matching
     * point into the centerline so later tangent calculations near this marker take it into account.
     * Requires a generated preview to exist (run start then stop first).
     * @return true if the marker was added
     */
    public boolean addManualMarker(Player p, Location l1, Location l2) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.centerline.isEmpty()) return false;

        Vector span = l2.toVector().subtract(l1.toVector());
        Vector rawNormal = new Vector(-span.getZ(), 0, span.getX());
        if (rawNormal.lengthSquared() < 1e-6) rawNormal = new Vector(1, 0, 0);

        Location center = CheckpointGeometry.midpoint(l1, l2);
        double halfWidth = CheckpointGeometry.spanHalfWidth(l1, l2, CheckpointGeometry.WIDTH_PADDING);
        double heightDiff = Math.abs(l1.getY() - l2.getY());
        double halfHeight = heightDiff > 0.5 ? heightDiff / 2.0 : session.halfHeight;

        int nearestIndex = CheckpointGeometry.nearestIndex(session.centerline, center);

        // Decide whether the new point belongs just before or just after nearestIndex along the
        // track direction, so the centerline stays in travel order rather than just distance order.
        int insertIndex = nearestIndex + 1;
        Vector tangent = CheckpointGeometry.tangentAt(session.centerline, nearestIndex, session.loop);
        if (tangent != null) {
            Vector toNew = center.toVector().subtract(session.centerline.get(nearestIndex).toVector());
            if (toNew.dot(tangent) < 0) insertIndex = nearestIndex; // new point is behind, not ahead
        }
        insertIndex = Math.min(insertIndex, session.centerline.size());

        session.centerline.add(insertIndex, center);
        session.centerlineArcLengths = CheckpointGeometry.cumulativeArcLengths(session.centerline);

        // ID is a placeholder — saveTracedCheckpoints() reassigns IDs sequentially by list order at commit time.
        Checkpoint marker = new Checkpoint(-1, center, rawNormal, halfWidth, halfHeight, Checkpoint.Type.NORMAL);

        double arcLength = session.centerlineArcLengths[insertIndex];
        insertByArcLength(session, marker, arcLength);

        ensurePreview(p);
        refreshPreview(p);
        return true;
    }

    /**
     * Replaces the geometry of the nearest preview checkpoint to the given click points, using
     * their exact projected span for the new width (and center, if the click wasn't centered on
     * the original checkpoint). Keeps the checkpoint's original orientation, type, and ID slot —
     * only its width/position change. Use this to fix checkpoints where track-surface detection
     * got the width wrong (e.g. bridges with no ice for the scanner to measure).
     * @return info about the checkpoint that was resized, or null if none was found nearby
     */
    public EditInfo resizeNearest(Player p, Location l1, Location l2) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.preview.isEmpty()) return null;

        Location center = CheckpointGeometry.midpoint(l1, l2);
        NearestResult nearest = findNearestPreviewCheckpoint(session, center);
        if (nearest.index() < 0) return null;

        Checkpoint original = session.preview.get(nearest.index());
        //The preview and the centerline are not 1:1 (sector markers are inserted into the preview
        //only), so locate the old gate on the centerline by position instead of reusing its index.
        int centerlineIndex = CheckpointGeometry.nearestIndex(session.centerline, original.getCenter());

        Vector normal = CheckpointGeometry.smoothTangentAt(session.centerline, centerlineIndex, session.loop, 2);
        if (normal == null) normal = original.getNormal();

        double newHalfWidth = CheckpointGeometry.spanHalfWidth(l1, l2, 0);

        Checkpoint replacement = new Checkpoint(original.getId(), center, normal, newHalfWidth, original.getHalfHeight(), original.getType());
        if (original.getType() == Checkpoint.Type.SECTOR) replacement.setSectorID(original.getSectorID());
        session.preview.set(nearest.index(), replacement);

        //Move the centerline point too, and keep the cached arc lengths in step so a sector added
        //afterwards is still inserted at the right lap position.
        if (!session.centerline.isEmpty()) {
            session.centerline.set(centerlineIndex, center);
            session.centerlineArcLengths = CheckpointGeometry.cumulativeArcLengths(session.centerline);
        }

        return new EditInfo(nearest.index() + 1, replacement.getType(), nearest.distance());
    }

    /**
     * Removes the preview checkpoint nearest to the given location (typically the player's current
     * position — walk up to the one you want gone and run the command). Useful for e.g. deleting a
     * redundant auto-generated checkpoint that ended up overlapping a manually-placed sector.
     * @return info about the checkpoint that was removed, or null if none was found nearby
     */
    public EditInfo deleteNearest(Player p, Location point) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.preview.isEmpty()) return null;

        NearestResult nearest = findNearestPreviewCheckpoint(session, point);
        if (nearest.index() < 0) return null;

        Checkpoint removed = session.preview.remove(nearest.index());
        int centerlineIndex = CheckpointGeometry.nearestIndex(session.centerline, removed.getCenter());
        if (!session.centerline.isEmpty()) {
            session.centerline.remove(centerlineIndex);
            session.centerlineArcLengths = CheckpointGeometry.cumulativeArcLengths(session.centerline);
        }

        return new EditInfo(nearest.index() + 1, removed.getType(), nearest.distance());
    }

    private NearestResult findNearestPreviewCheckpoint(AutoTraceSession session, Location point) {
        int best = -1;
        double bestDist = MAX_EDIT_DISTANCE;
        for (int i = 0; i < session.preview.size(); i++) {
            Location center = session.preview.get(i).getCenter();
            //The player may have wandered into another world since the recording.
            if (center.getWorld() == null || !center.getWorld().equals(point.getWorld())) continue;

            double d = center.distance(point);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return new NearestResult(best, bestDist);
    }

    /**
     * Toggles the particle preview on/off. While on, redraws on a short timer until toggled off,
     * the session ends, or the player disconnects.
     * @return true if the preview is now ON, false if it's now OFF (or couldn't start)
     */
    public boolean togglePreview(Player p) {
        BukkitTask existing = previewTasks.remove(p);
        if (existing != null) {
            existing.cancel();
            return false;
        }
        return ensurePreview(p) != null;
    }

    /**
     * Starts the preview if it isn't already running, so an edit is visible straight away without a
     * separate {@code /checkpoint autotrace preview}.
     * @return the running task, or null if there is no preview to show
     */
    public BukkitTask ensurePreview(Player p) {
        BukkitTask existing = previewTasks.get(p);
        if (existing != null) return existing;

        AutoTraceSession session = sessions.get(p);
        if (session == null || session.preview.isEmpty()) return null;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(main, () -> {
            AutoTraceSession s = sessions.get(p);
            if (!p.isOnline() || s == null || s.preview.isEmpty()) {
                BukkitTask t = previewTasks.remove(p);
                if (t != null) t.cancel();
                return;
            }

            for (Checkpoint cp : s.preview) {
                try {
                    CheckpointRenderer.render(p, cp);
                } catch (RuntimeException e) {
                    //One malformed gate must not kill the whole preview.
                    warn("Couldn't draw a preview checkpoint of race " + s.race.getName() + ": " + e.getMessage());
                }
            }

            //Draw center line (smooth)
            if (s.centerline.isEmpty()) return;
            ArrayList<Location> cl = new ArrayList<>(s.centerline);
            World world = cl.getFirst().getWorld();
            if (world == null) return;

            List<Location> smoothed = CheckpointGeometry.catmullRomSpline(cl, s.loop, 6); // 6 substeps ≈ smooth at typical spacing
            for (int i = 0; i < smoothed.size() - 1; i++) {
                line(p, world, Particle.TOTEM_OF_UNDYING, smoothed.get(i).clone().add(0, 1, 0).toVector(), smoothed.get(i+1).clone().add(0, 1, 0).toVector());
            }
            if (s.loop && smoothed.size() > 1) {
                line(p, world, Particle.TOTEM_OF_UNDYING, smoothed.getLast().clone().add(0, 1, 0).toVector(), smoothed.getFirst().clone().add(0, 1, 0).toVector());
            }
        }, 0L, PREVIEW_PERIOD);

        previewTasks.put(p, task);
        return task;
    }

    /** Redraws {@code p}'s preview immediately, if one is running. */
    public void refreshPreview(Player p) {
        BukkitTask task = previewTasks.get(p);
        if (task == null) return;

        AutoTraceSession s = sessions.get(p);
        if (s == null) return;

        for (Checkpoint cp : s.preview) {
            try {
                CheckpointRenderer.render(p, cp);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Stops the preview task and clears the session — used when the session is accepted or cancelled. */
    private void stopPreview(Player p) {
        BukkitTask task = previewTasks.remove(p);
        if (task != null) task.cancel();
    }

    private void line(Player viewer, World world, Particle particle, Vector from, Vector to) {
        if (!world.equals(viewer.getWorld())) return;

        double length = from.distance(to);
        int steps = Math.max(1, (int) (length / 0.5));
        Vector step = to.clone().subtract(from).multiply(1.0 / steps);

        Vector cursor = from.clone();
        for (int i = 0; i <= steps; i++) {
            viewer.spawnParticle(particle, cursor.getX(), cursor.getY(), cursor.getZ(), 1, 0, 0, 0, 0);
            cursor.add(step);
        }
    }

    /**
     * Commits the current preview to storage as real checkpoints. Clears the session and stops
     * the preview task (if any) on success.
     * @return true if the checkpoints were saved
     */
    public boolean accept(Player p) {
        AutoTraceSession session = sessions.get(p);
        if (session == null || session.preview.isEmpty()) return false;

        boolean success = checkpointManager.saveTracedCheckpoints(session.race, session.preview);
        if (success) {
            sessions.remove(p);
            stopPreview(p);
        }
        return success;
    }
}