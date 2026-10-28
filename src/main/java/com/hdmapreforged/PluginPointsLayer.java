package com.hdmapreforged;

import static java.lang.Math.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import net.runelite.client.ui.overlay.worldmap.*;

/**
 * Other plugins' icons on the game's world map (RuneLite's WorldMapPointManager: slayer tasks, Quest Helper's step),
 * drawn at their own size on the game's spot, over the map's icons, hoverable and clickable: a click opens the card,
 * and centres the map there when the point asks for it (jump on click). A point is told where the game's map draws it,
 * so one moved there (Quest Helper's, in a dungeon drawn elsewhere) is put back on the game's own tile. One without a
 * picture is not drawn (Quest Helper hides its point so). Off the view, a point that snaps to the edge is shown at the
 * edge as the player is, a pointer with the icon, and told so as the game's map would. Swing thread.
 */
final class PluginPointsLayer implements MapView.Overlay, MapView.HitLayer {
    /** Snapped to the edge once the point's spot is this far out, so its picture stays in view. */
    static final int EDGE = 12;

    final MapView view;
    /** The points as of the last game tick. */
    final Supplier<List<WorldMapPoint>> points;
    final BooleanSupplier shown;
    /** The points as last drawn, by identity: a point is a value object comparing its picture too. */
    final Map<WorldMapPoint, Drawn> drawn = new IdentityHashMap<>();
    /** Those drawn in the last frame, in its order: the last is on top. */
    List<Drawn> frame = List.of();

    static final class Drawn {
        /** Its place on the map, or null while no map shows it. */
        Poi poi;
        /** Its picture while not snapped to the edge: a snapped point swaps it for an arrow. */
        BufferedImage icon;
        /** Where its picture is on the screen, or null when not drawn. */
        Rectangle2D at;
    }

    PluginPointsLayer(MapView view, Supplier<List<WorldMapPoint>> points, BooleanSupplier shown) {
        this.view = view;
        this.points = points;
        this.shown = shown;
        view.addOverlay(this);
        // Drawn over the game's icons, so hit first.
        view.hitLayers.add(0, this);
    }

    /** The manager's points: it has no getter for them, but removeIf shows each one; none is removed. Any thread. */
    static List<WorldMapPoint> points(WorldMapPointManager manager) {
        var points = new ArrayList<WorldMapPoint>();
        manager.removeIf(point -> {
            points.add(point);
            return false;
        });
        return points;
    }

    /** Changes when a point comes, goes, moves or changes its picture: compared each tick to repaint. */
    static int signature(List<WorldMapPoint> points) {
        int hash = points.size();
        for (var point : points) {
            hash = hash * 31 + System.identityHashCode(point);
            hash = hash * 31 + Objects.hashCode(point.getWorldPoint());
            hash = hash * 31 + System.identityHashCode(point.getImage());
        }
        return hash;
    }

    /** Its place, made again when the point moved or the map list changed. */
    Drawn drawnOf(WorldMapPoint point) {
        var d = drawn.computeIfAbsent(point, p -> new Drawn());
        var game = point.getWorldPoint() == null ? null : WorldMapMoves.fromDrawnAnywhere(point.getWorldPoint());
        var on = view.mapOf(game);
        if (on == null) {
            d.poi = null;
        } else if (d.poi == null || d.poi.map != on || !d.poi.location.equals(game)) {
            String name = point.getName() != null ? point.getName() : "Map icon";
            d.poi = new Poi(PoiType.PLUGIN_ICON, name, game, on, null, Needs.NONE, name, null, point.getTooltip());
        }
        return d;
    }

    public void paint(Graphics2D g, Projection p) {
        var current = Collections.newSetFromMap(new IdentityHashMap<WorldMapPoint, Boolean>());
        var now = new ArrayList<Drawn>();
        if (shown.getAsBoolean()) {
            for (var point : points.get()) {
                current.add(point);
                var d = drawnOf(point);
                d.at = null;
                if (point.getImage() == null || d.poi == null || !d.poi.isOn(p.map())) {
                    continue;
                }
                var at = p.shown(d.poi.location);
                double x = p.screenX(at.getX() + 0.5), y = p.screenY(at.getY() + 0.5);
                boolean off = x < EDGE || y < EDGE || x > p.width() - EDGE || y > p.height() - EDGE;
                if (point.isSnapToEdge() && off) {
                    if (!point.isCurrentlyEdgeSnapped()) {
                        point.setCurrentlyEdgeSnapped(true);
                        point.onEdgeSnap();
                    }
                    paintSnapped(g, d, point, x, y);
                } else {
                    if (point.isCurrentlyEdgeSnapped()) {
                        point.setCurrentlyEdgeSnapped(false);
                        point.onEdgeUnsnap();
                    }
                    paintAt(g, d, point, x, y);
                }
                if (d.at != null) {
                    now.add(d);
                }
            }
        }
        drawn.keySet().retainAll(current);
        frame = now;
    }

    /** Its picture with its anchor (the image point, else its middle) on the spot. */
    void paintAt(Graphics2D g, Drawn d, WorldMapPoint point, double x, double y) {
        var image = point.getImage();
        if (image == null) {
            return;
        }
        d.icon = image;
        var anchor = point.getImagePoint();
        double left = anchor != null ? x - anchor.getX() : x - image.getWidth() / 2.0;
        double top = anchor != null ? y - anchor.getY() : y - image.getHeight() / 2.0;
        g.drawImage(image, (int) round(left), (int) round(top), null);
        d.at = new Rectangle2D.Double(left, top, image.getWidth(), image.getHeight());
    }

    /** At the view's edge: a pointer at the spot, as the player's, its picture just inside. */
    void paintSnapped(Graphics2D g, Drawn d, WorldMapPoint point, double x, double y) {
        var edge = view.edge(x, y);
        double angle = atan2(y - edge.getY(), x - edge.getX());
        PlayerMarker.pointer(g, view.text, edge.getX(), edge.getY(), angle, 0.8, d.poi.type.color, null);
        var icon = d.icon != null ? d.icon : point.getImage();
        if (icon == null) {
            return;
        }
        double w = icon.getWidth(), h = icon.getHeight();
        double cx = edge.getX() - cos(angle) * (12 + w / 2), cy = edge.getY() - sin(angle) * (12 + h / 2);
        g.drawImage(icon, (int) round(cx - w / 2), (int) round(cy - h / 2), null);
        d.at = new Rectangle2D.Double(cx - w / 2, cy - h / 2, w, h);
    }

    public Poi hit(Point point, Projection p) {
        for (int i = frame.size() - 1; i >= 0; i--) {
            var d = frame.get(i);
            if (d.at.contains(point)) {
                return d.poi;
            }
        }
        return null;
    }

    Drawn drawnOf(Poi poi) {
        for (var d : frame) {
            if (d.poi == poi) {
                return d;
            }
        }
        return null;
    }

    public Point2D labelAnchor(Poi poi, Projection p) {
        var d = drawnOf(poi);
        return d == null ? null : new Point2D.Double(d.at.getCenterX(), d.at.getMaxY() + 3);
    }

    public Point2D iconCenter(Poi poi, Projection p) {
        var d = drawnOf(poi);
        return d == null ? null : new Point2D.Double(d.at.getCenterX(), d.at.getCenterY());
    }

    /** A point that jumps on click centres the map on its target, else its spot, as the game's map does. */
    public void clicked(Poi poi) {
        for (var entry : drawn.entrySet()) {
            var point = entry.getKey();
            if (entry.getValue().poi == poi && point.isJumpOnClick()) {
                view.focus(point.getTarget() != null ? WorldMapMoves.fromDrawnAnywhere(point.getTarget()) : poi.location);
                return;
            }
        }
    }
}
