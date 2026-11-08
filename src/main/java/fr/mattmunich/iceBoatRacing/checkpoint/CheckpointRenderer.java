package fr.mattmunich.iceBoatRacing.checkpoint;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Draws a checkpoint's outline as particles, for one player at a time.
 * <p>
 * This is the only place that knows how a checkpoint's geometry is turned into particles, and the
 * only place that maps a checkpoint type to a particle. Both the saved-checkpoint preview
 * ({@code CheckpointPreview}) and the auto-trace preview ({@code AutoTraceManager}) go through it,
 * so the two cannot drift apart.
 * <p>
 * Particles are sent with {@link Player#spawnParticle} rather than
 * {@link World#spawnParticle} so that a survey never shows up for an unrelated player — on a
 * survival server, nobody should see gate markers unless they asked for them.
 */
public final class CheckpointRenderer {

    /** Particles are only sent for checkpoints within this many blocks of the viewer. */
    public static final double VIEW_DISTANCE = 64.0;

    /** One particle every this many blocks along an edge. */
    private static final double STEP = 0.5;

    private CheckpointRenderer() {
    }

    /**
     * Draws {@code checkpoint} and its alternate routes for {@code viewer}.
     * <p>
     * Silently does nothing when the checkpoint is in another world, further than
     * {@link #VIEW_DISTANCE}, or missing the geometry its shape needs — a malformed checkpoint must
     * not be able to break the caller's repeating task.
     */
    public static void render(Player viewer, Checkpoint checkpoint) {
        if (checkpoint == null || viewer == null || !viewer.isOnline()) return;

        Location viewerLocation = viewer.getLocation();
        if (checkpoint.getShape() == Checkpoint.Shape.BOX) {
            if (checkpoint.getMin() == null || checkpoint.getMax() == null) return;
        } else if (checkpoint.getCenter() == null) {
            return;
        }

        World world = checkpoint.getShape() == Checkpoint.Shape.PLANE
                ? checkpoint.getCenter().getWorld()
                : checkpoint.getMin().getWorld();
        if (world == null || !world.equals(viewerLocation.getWorld())) return;

        Location center = checkpoint.getCenter();
        if (center == null || center.distanceSquared(viewerLocation) > VIEW_DISTANCE * VIEW_DISTANCE) {
            return;
        }

        Particle particle = particleFor(checkpoint.getType());

        if (checkpoint.getShape() == Checkpoint.Shape.PLANE) {
            renderPlane(viewer, particle, center, checkpoint.getRight(), checkpoint.getUp(),
                    checkpoint.getHalfWidth(), checkpoint.getHalfHeight());
        } else {
            renderBox(viewer, particle, checkpoint.getMin(), checkpoint.getMax());
        }

        for (Checkpoint.AlternateRoute alt : checkpoint.getAlternates()) {
            renderPlane(viewer, Particle.CLOUD, alt.getCenter(), alt.getRight(), alt.getUp(),
                    alt.getHalfWidth(), alt.getHalfHeight());
        }
    }

    /** Vertical face of an oriented plane checkpoint, drawn as a 4-edge rectangle. */
    private static void renderPlane(Player viewer, Particle particle, Location center,
                                    Vector right, Vector up, double halfWidth, double halfHeight) {
        if (center == null || right == null || up == null) return;

        Vector rightHW = right.clone().multiply(halfWidth);
        Vector upHH = up.clone().multiply(halfHeight);
        Vector centerVector = center.toVector();

        Vector topRight = centerVector.clone().add(rightHW).add(upHH);
        Vector bottomRight = centerVector.clone().add(rightHW).subtract(upHH);
        Vector bottomLeft = centerVector.clone().subtract(rightHW).subtract(upHH);
        Vector topLeft = centerVector.clone().subtract(rightHW).add(upHH);

        line(viewer, particle, topRight, bottomRight);
        line(viewer, particle, bottomRight, bottomLeft);
        line(viewer, particle, bottomLeft, topLeft);
        line(viewer, particle, topLeft, topRight);
    }

    /**
     * Wireframe cuboid: all 12 edges of the trigger volume. Unlike a single face, this shows the full
     * volume the two shovel corners enclosed, which is what you need to confirm a placement.
     */
    private static void renderBox(Player viewer, Particle particle, Location min, Location max) {
        Vector a = min.toVector();
        Vector b = max.toVector();

        //Bottom and top rectangles, then the four vertical edges joining them.
        for (double y : new double[]{a.getY(), b.getY()}) {
            Vector c1 = new Vector(a.getX(), y, a.getZ());
            Vector c2 = new Vector(b.getX(), y, a.getZ());
            Vector c3 = new Vector(b.getX(), y, b.getZ());
            Vector c4 = new Vector(a.getX(), y, b.getZ());

            line(viewer, particle, c1, c2);
            line(viewer, particle, c2, c3);
            line(viewer, particle, c3, c4);
            line(viewer, particle, c4, c1);
        }

        line(viewer, particle, new Vector(a.getX(), a.getY(), a.getZ()), new Vector(a.getX(), b.getY(), a.getZ()));
        line(viewer, particle, new Vector(b.getX(), a.getY(), a.getZ()), new Vector(b.getX(), b.getY(), a.getZ()));
        line(viewer, particle, new Vector(b.getX(), a.getY(), b.getZ()), new Vector(b.getX(), b.getY(), b.getZ()));
        line(viewer, particle, new Vector(a.getX(), a.getY(), b.getZ()), new Vector(a.getX(), b.getY(), b.getZ()));
    }

    private static void line(Player viewer, Particle particle, Vector from, Vector to) {
        double length = from.distance(to);
        int steps = Math.max(1, (int) (length / STEP));
        Vector step = to.clone().subtract(from).multiply(1.0 / steps);

        Vector cursor = from.clone();
        for (int i = 0; i <= steps; i++) {
            viewer.spawnParticle(particle, cursor.getX(), cursor.getY(), cursor.getZ(), 1, 0, 0, 0, 0);
            cursor.add(step);
        }
    }

    private static Particle particleFor(Checkpoint.Type type) {
        return switch (type) {
            case START_FINISH -> Particle.FLAME;
            case SECTOR -> Particle.HAPPY_VILLAGER;
            default -> Particle.END_ROD;
        };
    }
}
