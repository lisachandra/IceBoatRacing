package fr.mattmunich.iceBoatRacing.checkpoint;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pure geometry helpers used by the auto-trace checkpoint generator.
 * No Bukkit scheduling or persistence here on purpose — keep this class trivially testable.
 */
@SuppressWarnings("ALL")
public class CheckpointGeometry {

    /**
     * Track surface materials used for recentering/width detection.
     * Deliberately narrow — glazed terracotta trim and other decorative blocks near the track
     * should never be mistaken for track surface.
     */
    public static final Set<Material> TRACK_SURFACE = EnumSet.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE
    );

    /**
     * Extra half-width added beyond the detected/clicked span on every checkpoint gate.
     * Package-visible so CheckpointCommand can apply the same margin to manually-placed
     * alternate routes, where a missed crossing means a stranded lap rather than just a
     * slightly-off timing split.
     */
    public static final double WIDTH_PADDING = 0.75;

    /**
     * Half-width of the gate implied by two clicked blocks, measured across the ground.
     * <p>
     * Each click is a block's <em>minimum</em> corner, so the selected span is one block longer than
     * the coordinate difference. Using the raw 3D distance instead (as this used to) makes a gate too
     * short by half a block per axis, inflates it when the two clicks differ in height, and collapses
     * to zero when both clicks land on the same block.
     *
     * @param padding extra half-width added on top of the clicked span
     */
    public static double spanHalfWidth(Location a, Location b, double padding) {
        double dx = Math.abs(a.getX() - b.getX()) + 1;
        double dz = Math.abs(a.getZ() - b.getZ()) + 1;
        return Math.max(Math.hypot(dx, dz) / 2.0 + padding, 0.5);
    }

    // ---------------------------------------------------------------
    // Simplification / resampling
    // ---------------------------------------------------------------

    /**
     * Ramer-Douglas-Peucker simplification of a recorded polyline.
     * Strips points that don't meaningfully change the shape of the path.
     * @param points the raw recorded points, in order
     * @param epsilon max perpendicular distance (in blocks) a point can deviate before being kept
     */
    public static List<Location> simplify(List<Location> points, double epsilon) {
        if (points.size() < 3) return new ArrayList<>(points);

        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        rdp(points, 0, points.size() - 1, epsilon, keep);

        List<Location> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) result.add(points.get(i));
        }
        return result;
    }

    private static void rdp(List<Location> points, int start, int end, double epsilon, boolean[] keep) {
        if (end <= start + 1) return;

        Location a = points.get(start);
        Location b = points.get(end);

        double maxDist = -1;
        int idx = -1;

        for (int i = start + 1; i < end; i++) {
            double dist = perpendicularDistance(points.get(i), a, b);
            if (dist > maxDist) {
                maxDist = dist;
                idx = i;
            }
        }

        if (maxDist > epsilon) {
            keep[idx] = true;
            rdp(points, start, idx, epsilon, keep);
            rdp(points, idx, end, epsilon, keep);
        }
    }

    private static double perpendicularDistance(Location p, Location a, Location b) {
        Vector ab = b.toVector().subtract(a.toVector());
        Vector ap = p.toVector().subtract(a.toVector());

        double abLenSq = ab.lengthSquared();
        if (abLenSq < 1e-9) return ap.length();

        double t = ap.dot(ab) / abLenSq;
        t = Math.clamp(t, 0, 1);

        Vector closest = a.toVector().add(ab.clone().multiply(t));
        return p.toVector().subtract(closest).length();
    }

    /**
     * Resamples a simplified polyline at even arc-length intervals.
     * @param points the simplified polyline
     * @param spacing distance in blocks between consecutive output points
     */
    public static List<Location> resample(List<Location> points, double spacing) {
        List<Location> result = new ArrayList<>();
        if (points.size() < 2) return new ArrayList<>(points);
        if (spacing < 0.5) spacing = 0.5; // guard against pathological/zero spacing

        result.add(points.getFirst());
        double distanceSinceLastPoint = 0.0;

        for (int i = 1; i < points.size(); i++) {
            Location segStart = points.get(i - 1);
            Location segEnd = points.get(i);
            double segLength = segStart.distance(segEnd);
            if (segLength < 1e-6) continue;

            double segPos = 0.0;

            while (true) {
                double distanceToNextPoint = spacing - distanceSinceLastPoint;
                if (segPos + distanceToNextPoint > segLength) {
                    distanceSinceLastPoint += (segLength - segPos);
                    break;
                }
                segPos += distanceToNextPoint;
                double t = segPos / segLength;
                result.add(interpolate(segStart, segEnd, t));
                distanceSinceLastPoint = 0.0;
            }
        }

        Location last = points.getLast();
        Location lastAdded = result.getLast();
        if (lastAdded.distance(last) > spacing * 0.25) {
            result.add(last);
        }

        return result;
    }

    private static Location interpolate(Location a, Location b, double t) {
        Vector av = a.toVector();
        Vector bv = b.toVector();
        Vector interp = av.clone().add(bv.clone().subtract(av).multiply(t));
        return new Location(a.getWorld(), interp.getX(), interp.getY(), interp.getZ());
    }

    // ---------------------------------------------------------------
    // Tangent / axis helpers
    // ---------------------------------------------------------------

    /**
     * Direction of travel at a centerline point, derived from its neighbors.
     * @return normalized tangent, or null if neighbors coincide (degenerate point)
     */
    public static Vector tangentAt(List<Location> centerline, int i, boolean loop) {
        int n = centerline.size();
        Location prev, next;

        if (loop) {
            prev = centerline.get((i - 1 + n) % n);
            next = centerline.get((i + 1) % n);
        } else if (i == 0) {
            prev = centerline.getFirst();
            next = centerline.get(Math.min(1, n - 1));
        } else if (i == n - 1) {
            prev = centerline.get(n - 2);
            next = centerline.get(n - 1);
        } else {
            prev = centerline.get(i - 1);
            next = centerline.get(i + 1);
        }

        Vector t = next.toVector().subtract(prev.toVector());
        if (t.lengthSquared() < 1e-9) return null;
        return t.normalize();
    }

    /**
     * Tangent estimated from points further out (±windowSize) rather than immediate neighbors,
     * so a single resized/deleted point doesn't dominate its own tangent direction — the points
     * bordering an edit are the least reliable ones to estimate that edit's direction from.
     */
    public static Vector smoothTangentAt(List<Location> centerline, int i, boolean loop, int windowSize) {
        int n = centerline.size();
        if (n < 2) return null;

        int backIdx, fwdIdx;
        if (loop) {
            backIdx = ((i - windowSize) % n + n) % n;
            fwdIdx  = (i + windowSize) % n;
        } else {
            backIdx = Math.max(0, i - windowSize);
            fwdIdx  = Math.min(n - 1, i + windowSize);
        }

        if (backIdx == fwdIdx) return tangentAt(centerline, i, loop); // fallback for tiny centerlines

        Vector t = centerline.get(fwdIdx).toVector().subtract(centerline.get(backIdx).toVector());
        if (t.lengthSquared() < 1e-9) return null;
        return t.normalize();
    }

    /**
     * Catmull-Rom interpolation through a polyline, purely for smooth rendering — does not
     * change the actual centerline data used for tangent/normal calculations.
     * @param points the raw centerline points
     * @param loop whether the track wraps around
     * @param stepsPerSegment how many interpolated points to insert between each pair of real points
     */
    public static List<Location> catmullRomSpline(List<Location> points, boolean loop, int stepsPerSegment) {
        List<Location> result = new ArrayList<>();
        int n = points.size();
        if (n < 3) return new ArrayList<>(points);

        int segments = loop ? n : n - 1;

        for (int i = 0; i < segments; i++) {
            Location p0 = points.get(loop ? ((i - 1 + n) % n) : Math.max(i - 1, 0));
            Location p1 = points.get(i);
            Location p2 = points.get(loop ? ((i + 1) % n) : Math.min(i + 1, n - 1));
            Location p3 = points.get(loop ? ((i + 2) % n) : Math.min(i + 2, n - 1));

            for (int s = 0; s < stepsPerSegment; s++) {
                double t = (double) s / stepsPerSegment;
                result.add(catmullRomPoint(p0, p1, p2, p3, t));
            }
        }

        if (!loop) result.add(points.getLast());
        return result;
    }

    private static Location catmullRomPoint(Location p0, Location p1, Location p2, Location p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;

        double x = 0.5 * ((2 * p1.getX()) + (-p0.getX() + p2.getX()) * t
                + (2 * p0.getX() - 5 * p1.getX() + 4 * p2.getX() - p3.getX()) * t2
                + (-p0.getX() + 3 * p1.getX() - 3 * p2.getX() + p3.getX()) * t3);
        double y = 0.5 * ((2 * p1.getY()) + (-p0.getY() + p2.getY()) * t
                + (2 * p0.getY() - 5 * p1.getY() + 4 * p2.getY() - p3.getY()) * t2
                + (-p0.getY() + 3 * p1.getY() - 3 * p2.getY() + p3.getY()) * t3);
        double z = 0.5 * ((2 * p1.getZ()) + (-p0.getZ() + p2.getZ()) * t
                + (2 * p0.getZ() - 5 * p1.getZ() + 4 * p2.getZ() - p3.getZ()) * t2
                + (-p0.getZ() + 3 * p1.getZ() - 3 * p2.getZ() + p3.getZ()) * t3);

        return new Location(p1.getWorld(), x, y, z);
    }

    /**
     * Horizontal axis perpendicular to a tangent (ignores any vertical component of the tangent —
     * checkpoint gates are vertical planes facing a horizontal direction of travel).
     */
    public static Vector horizontalRight(Vector tangent) {
        Vector flat = new Vector(tangent.getX(), 0, tangent.getZ());
        if (flat.lengthSquared() < 1e-9) flat = new Vector(1, 0, 0);
        flat.normalize();
        return new Vector(-flat.getZ(), 0, flat.getX());
    }

    /**
     * Snaps a direction to the nearest cardinal horizontal axis (N/S/E/W). Used for start/finish
     * and sector gates so they sit flush with the block grid instead of at whatever diagonal
     * angle the recorded path happened to produce.
     */
    public static Vector snapToCardinal(Vector direction) {
        double x = direction.getX();
        double z = direction.getZ();
        if (Math.abs(x) >= Math.abs(z)) {
            return new Vector(Math.signum(x == 0 ? 1 : x), 0, 0);
        } else {
            return new Vector(0, 0, Math.signum(z == 0 ? 1 : z));
        }
    }

    // ---------------------------------------------------------------
    // Arc length / nearest-point helpers (used to place sectors correctly in sequence)
    // ---------------------------------------------------------------

    public static double[] cumulativeArcLengths(List<Location> centerline) {
        double[] arc = new double[centerline.size()];
        for (int i = 1; i < centerline.size(); i++) {
            arc[i] = arc[i - 1] + centerline.get(i - 1).distance(centerline.get(i));
        }
        return arc;
    }

    public static int nearestIndex(List<Location> centerline, Location point) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < centerline.size(); i++) {
            double d = centerline.get(i).distance(point);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    public static Location midpoint(Location a, Location b) {
        Vector mid = a.toVector().add(b.toVector()).multiply(0.5);
        return new Location(a.getWorld(), mid.getX(), mid.getY(), mid.getZ());
    }

    // ---------------------------------------------------------------
    // Track surface detection / recentering
    // ---------------------------------------------------------------

    public record RecenterResult(Location center, double halfWidth) {
    }

    /**
     * Recenters each centerline point onto the actual track surface and measures the real
     * track width there, instead of trusting the raw recorded position (which drifts, especially
     * when the recording was done by boat) and a single fixed width for the whole track.
     * @param centerline the resampled points
     * @param loop whether the track is a closed loop
     * @param defaultHalfWidth fallback half-width used where no track surface is detected
     * @param maxHalfWidth cap on how far to scan outward before giving up (open areas, bridges)
     */
    public static List<RecenterResult> recenterOnTrack(
            List<Location> centerline, boolean loop, double defaultHalfWidth, double maxHalfWidth
    ) {
        List<RecenterResult> results = new ArrayList<>();

        for (int i = 0; i < centerline.size(); i++) {
            Location point = centerline.get(i);
            Vector tangent = tangentAt(centerline, i, loop);

            if (tangent == null) {
                results.add(new RecenterResult(point, defaultHalfWidth));
                continue;
            }

            Vector right = horizontalRight(tangent);
            double leftDist = scanEdge(point, right.clone().multiply(-1), maxHalfWidth);
            double rightDist = scanEdge(point, right, maxHalfWidth);

            if (leftDist < 0 || rightDist < 0) {
                // starting point itself isn't on track surface — keep the raw recorded point as-is
                results.add(new RecenterResult(point, defaultHalfWidth));
                continue;
            }

            double newHalfWidth = Math.max((leftDist + rightDist) / 2.0 + WIDTH_PADDING, 0.5);
            Vector offset = right.clone().multiply((rightDist - leftDist) / 2.0);
            Location recentered = point.clone().add(offset);

            results.add(new RecenterResult(recentered, newHalfWidth));
        }

        return results;
    }

    /**
     * Re-derives a checkpoint's center/width along a cardinal-snapped normal instead of its
     * original (possibly diagonal) one. Used for start/finish and sector gates after snapping,
     * so the measured width matches the axis the gate actually sits on.
     */
    public static Checkpoint rebuildWithCardinalWidth(Checkpoint original, double maxHalfWidth) {
        Vector snappedNormal = snapToCardinal(original.getNormal());
        Vector right = horizontalRight(snappedNormal);
        Location center = original.getCenter();

        double leftDist = scanEdge(center, right.clone().multiply(-1), maxHalfWidth);
        double rightDist = scanEdge(center, right, maxHalfWidth);

        double halfWidth = original.getHalfWidth();
        Location finalCenter = center;

        if (leftDist >= 0 && rightDist >= 0) {
            halfWidth = Math.max((leftDist + rightDist) / 2.0 + WIDTH_PADDING, 0.5);
            Vector offset = right.clone().multiply((rightDist - leftDist) / 2.0);
            finalCenter = center.clone().add(offset);
        }

        return new Checkpoint(original.getId(), finalCenter, snappedNormal, halfWidth, original.getHalfHeight(), original.getType());
    }

    private static double scanEdge(Location start, Vector direction, double maxDist) {
        if (!isTrackSurfaceAt(start)) return -1;

        Vector dir = direction.clone().normalize();
        double step = 0.5;
        double dist = 0;

        while (dist < maxDist) {
            double nextDist = dist + step;
            Location probe = start.clone().add(dir.clone().multiply(nextDist));
            if (!isTrackSurfaceAt(probe)) {
                return dist;
            }
            dist = nextDist;
        }
        return maxDist;
    }

    /// @param loc The location to check for the track
    /// @return Whether the track surface is at {@code loc}
    private static boolean isTrackSurfaceAt(Location loc) {
        Block below = loc.clone().subtract(0, 1, 0).getBlock();
        Block at = loc.getBlock();
        return TRACK_SURFACE.contains(below.getType()) || TRACK_SURFACE.contains(at.getType());
    }

    // ---------------------------------------------------------------
    // Checkpoint generation
    // ---------------------------------------------------------------

    /**
     * Builds oriented plane checkpoints from a (recentered) centerline.
     * @param centerline evenly-spaced points along the track, in travel order
     * @param halfWidths per-point half-width, same size and order as centerline
     * @param halfHeight checkpoint half-height along its vertical axis (constant across the track)
     * @param startId ID to assign to the first generated checkpoint (subsequent ones increment from here)
     * @param loop true if the track is a closed loop (tangent wraps around at the ends)
     * @param firstIsStartFinish true to mark the first generated checkpoint as START_FINISH, cardinal-snapped
     * @param maxHalfWidth cap used when rescanning the start/finish width post-snap
     */
    public static List<Checkpoint> buildPlaneCheckpoints(
            List<Location> centerline,
            List<Double> halfWidths,
            double halfHeight,
            int startId,
            boolean loop,
            boolean firstIsStartFinish,
            double maxHalfWidth
    ) {
        List<Checkpoint> checkpoints = new ArrayList<>();
        int n = centerline.size();
        if (n < 2) return checkpoints;

        for (int i = 0; i < n; i++) {
            Vector tangent = tangentAt(centerline, i, loop);
            if (tangent == null) continue; // duplicate/stationary point, skip

            Checkpoint.Type type = (i == 0 && firstIsStartFinish) ? Checkpoint.Type.START_FINISH : Checkpoint.Type.NORMAL;
            double hw = i < halfWidths.size() ? halfWidths.get(i) : halfWidths.getLast();

            Checkpoint cp = new Checkpoint(startId + checkpoints.size(), centerline.get(i), tangent, hw, halfHeight, type);
            checkpoints.add(cp);
        }

        if (firstIsStartFinish && !checkpoints.isEmpty()) {
            checkpoints.set(0, rebuildWithCardinalWidth(checkpoints.getFirst(), maxHalfWidth));
        }

        return checkpoints;
    }
}