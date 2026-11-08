package fr.mattmunich.iceBoatRacing.checkpoint;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public class Checkpoint {

    public enum Type {
        NORMAL,
        START_FINISH,
        SECTOR
    }

    public enum Shape {
        BOX,
        PLANE
    }

    /**
     * A secondary physical gate that counts as crossing this same logical checkpoint.
     * Used for alternate routes that rejoin the track without physically crossing the
     * primary checkpoint plane (e.g. a pit/stands lane that runs past the start/finish line).
     */
    public static class AlternateRoute {
        public final Checkpoint parent;
        public final Location center;
        public final Vector normal;
        public final Vector right;
        public final Vector up;
        public final double halfWidth;
        public final double halfHeight;

        public AlternateRoute(Checkpoint parent, Location center, Vector normal, double halfWidth, double halfHeight) {
            this.parent = parent;
            this.center = center;
            this.normal = normal.clone().normalize();

            Vector worldUp = new Vector(0, 1, 0);
            Vector r = this.normal.clone().crossProduct(worldUp);
            if (r.lengthSquared() < 1e-6) r = new Vector(1, 0, 0);
            this.right = r.normalize();
            this.up = this.right.clone().crossProduct(this.normal).normalize();

            this.halfWidth = halfWidth;
            this.halfHeight = halfHeight;
        }

        public Checkpoint getParent() {
            return parent;
        }

        public double getHalfHeight() {
            return halfHeight;
        }

        public double getHalfWidth() {
            return halfWidth;
        }

        public Location getCenter() {
            return center;
        }

// --Commented out by Inspection START (05/08/2026, 16:51):
//        public Vector getNormal() {
//            return normal;
//        }
// --Commented out by Inspection STOP (05/08/2026, 16:51)

        public Vector getRight() {
            return right;
        }

        public Vector getUp() {
            return up;
        }

        public void remove() {
            parent.removeAlternate(this);
        }
    }

    final int id; // checkpoint ID in the race order
    int sectorID = -1; // only used for sectors

    /**
     * The box's extent, as world coordinates. Both are exclusive/inclusive bounds of the volume the
     * two selected blocks occupy, not the blocks' corners: selecting blocks {@code x=0} and
     * {@code x=5} yields {@code min.x = 0} and {@code max.x = 6}, so a boat at {@code x=5.4} is inside.
     * {@link #boxMin(Location, Location)} / {@link #boxMax(Location, Location)} do that conversion.
     */
    final Location min;
    final Location max;
    final Type type;
    Shape shape = Shape.BOX;

    // PLANE-only fields
    Location center;
    Vector normal;
    Vector right;
    Vector up;
    double halfWidth;
    double halfHeight;

    final List<AlternateRoute> alternates = new ArrayList<>();

    /**
     * Type will be set to NORMAL
     * @param id the checkpoint ID
     * @param min position 1
     * @param max position 2
     */
    public Checkpoint(int id, Location min, Location max) {
        this(id, min, max, Type.NORMAL);
    }

    /**
     * @param id the checkpoint ID
     * @param min position 1
     * @param max position 2
     * @param type the checkpoint type from the Checkpoint.Type enum
     */
    public Checkpoint(int id, Location min, Location max, Type type) {
        this.id = id;
        this.min = boxMin(min, max);
        this.max = boxMax(min, max);
        this.type = type;
    }

    /**
     *  Use for new SECTOR checkpoint only
     * @param id the checkpoint ID
     * @param sectorID the sector ID
     * @param min position 1
     * @param max position 2
     */
    public Checkpoint(int id, int sectorID, Location min, Location max) {
        this.id = id;
        this.sectorID = sectorID;
        this.min = boxMin(min, max);
        this.max = boxMax(min, max);
        this.type = Type.SECTOR;
    }

    /**
     * Lower corner of a box selection, with the two corners sorted on every axis so the click order
     * doesn't matter.
     */
    public static Location boxMin(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    /**
     * Upper corner of a box selection. Block locations are the block's minimum corner, so the block
     * itself extends one full unit past it on each axis — add that back or the outermost block is
     * only half-covered by the trigger volume.
     */
    public static Location boxMax(Location a, Location b) {
        return new Location(a.getWorld(),
                Math.max(a.getX(), b.getX()) + 1,
                Math.max(a.getY(), b.getY()) + 1,
                Math.max(a.getZ(), b.getZ()) + 1);
    }

    /**
     * Oriented plane checkpoint. Used by the auto-trace generator, and by anything else that
     * wants a checkpoint that isn't forced to be axis-aligned (curved/diagonal track sections).
     * @param id the checkpoint ID
     * @param center the center point of the checkpoint plane
     * @param normal direction of travel through the checkpoint (does not need to be normalized)
     * @param halfWidth half-width of the checkpoint along its horizontal axis
     * @param halfHeight half-height of the checkpoint along its vertical axis
     * @param type the checkpoint type
     */
    public Checkpoint(int id, Location center, Vector normal, double halfWidth, double halfHeight, Type type) {
        this.id = id;
        this.type = type;
        this.shape = Shape.PLANE;
        this.center = center;
        this.normal = normal.clone().normalize();

        Vector worldUp = new Vector(0, 1, 0);
        Vector r = this.normal.clone().crossProduct(worldUp);
        if (r.lengthSquared() < 1e-6) {
            // normal is (near) vertical, fall back to a fixed horizontal right axis
            r = new Vector(1, 0, 0);
        }
        this.right = r.normalize();
        this.up = this.right.clone().crossProduct(this.normal).normalize();

        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;

        // Loose AABB around the plane, purely so getAt()/teleport/list still have something to work with.
        double r2 = Math.max(halfWidth, halfHeight) + 1;
        this.min = center.clone().subtract(r2, r2, r2);
        this.max = center.clone().add(r2, r2, r2);
    }

    public void addAlternate(Location center, Vector normal, double halfWidth, double halfHeight) {
        alternates.add(new AlternateRoute(this, center, normal, halfWidth, halfHeight));
    }

    public List<AlternateRoute> getAlternates() {
        return alternates;
    }

    /**
     * Detaches an alternate route from this checkpoint, in memory only. Callers that need the change
     * to survive a reload must also persist the list — see
     * {@code CheckpointManager.removeAlternate(Race, Checkpoint, AlternateRoute)}.
     * @return the index the route occupied, or -1 if it wasn't attached to this checkpoint
     */
    public int removeAlternate(AlternateRoute alternateRoute) {
        int index = alternates.indexOf(alternateRoute);
        if (index >= 0) alternates.remove(index);
        return index;
    }

    /**
     * True if the movement segment from -> to crosses this checkpoint, including any alternate
     * routes (e.g. a bypass lane that rejoins the track without crossing the primary plane).
     * Use this for race crossing detection instead of contains().
     */
    public boolean crosses(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) return false;

        boolean primaryCrossed = shape == Shape.PLANE ? crossesPlane(from, to) : crossesBox(from, to);
        if (primaryCrossed) return true;

        for (AlternateRoute alt : alternates) {
            if (planeCrossTest(from, to, alt.center, alt.normal, alt.right, alt.up, alt.halfWidth, alt.halfHeight)) {
                return true;
            }
        }
        return false;
    }

    private boolean crossesPlane(Location from, Location to) {
        return planeCrossTest(from, to, center, normal, right, up, halfWidth, halfHeight);
    }

    private static boolean planeCrossTest(Location from, Location to, Location planeCenter, Vector planeNormal,
                                          Vector planeRight, Vector planeUp, double hw, double hh) {
        if (!from.getWorld().equals(planeCenter.getWorld())) return false;

        Vector fromRel = from.toVector().subtract(planeCenter.toVector());
        Vector toRel = to.toVector().subtract(planeCenter.toVector());

        double d1 = fromRel.dot(planeNormal);
        double d2 = toRel.dot(planeNormal);

        if ((d1 > 0) == (d2 > 0)) return false; // no sign change -> didn't cross the plane this tick

        double t = d1 / (d1 - d2);
        Vector intersection = fromRel.clone().add(toRel.clone().subtract(fromRel).multiply(t));

        double w = intersection.dot(planeRight);
        double h = intersection.dot(planeUp);
        return Math.abs(w) <= hw && Math.abs(h) <= hh;
    }

    private boolean crossesBox(Location from, Location to) {
        if (!to.getWorld().equals(min.getWorld())) return false;
        if (containsBoxPoint(to)) return true;
        return segmentIntersectsBox(from.toVector(), to.toVector());
    }

    private boolean containsBoxPoint(Location loc) {
        return loc.getX() >= min.getX() && loc.getX() <= max.getX() &&
                loc.getY() >= min.getY() && loc.getY() <= max.getY() &&
                loc.getZ() >= min.getZ() && loc.getZ() <= max.getZ();
    }

    private boolean segmentIntersectsBox(Vector from, Vector to) {
        Vector dir = to.clone().subtract(from);
        double tMin = 0.0, tMax = 1.0;
        double[] fromArr = {from.getX(), from.getY(), from.getZ()};
        double[] dirArr = {dir.getX(), dir.getY(), dir.getZ()};
        double[] minArr = {min.getX(), min.getY(), min.getZ()};
        double[] maxArr = {max.getX(), max.getY(), max.getZ()};

        for (int i = 0; i < 3; i++) {
            if (Math.abs(dirArr[i]) < 1e-9) {
                if (fromArr[i] < minArr[i] || fromArr[i] > maxArr[i]) return false;
                continue;
            }
            double t1 = (minArr[i] - fromArr[i]) / dirArr[i];
            double t2 = (maxArr[i] - fromArr[i]) / dirArr[i];
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return false;
        }
        return true;
    }

    // Getters
    public int getId() { return id; }
    public int getSectorID() { return sectorID; }
    public void setSectorID(int sectorID) { this.sectorID = sectorID; }
    public Location getMin() { return min; }
    public Location getMax() { return max; }
    public Type getType() { return type; }
    public Shape getShape() { return shape; }
    public Location getCenter() {
        if (shape == Shape.PLANE) return center;
        return min.clone().add(max).multiply(0.5);
    }
    public Vector getNormal() { return normal; }
    public Vector getRight() { return right; }
    public Vector getUp() { return up; }
    public double getHalfWidth() { return halfWidth; }
    public double getHalfHeight() { return halfHeight; }

    @Override
    public String toString() {
        return """
                Checkpoint %s:
                - Type: %s (sector %s)
                - Shape: %s
                - Min: %s ; Max : %s
                - Center: %s ; Normal: %s
                - Right: %s ; Up: %s
                - HalfWidth: %s ; HalfHeight: %s
                """
                .formatted(
                        getId(),
                        getType(), getSectorID(),
                        getShape(),
                        getMin(), getMax(),
                        getCenter(), getNormal(),
                        getRight(), getUp(),
                        getHalfWidth(), getHalfHeight()
                );
    }
}