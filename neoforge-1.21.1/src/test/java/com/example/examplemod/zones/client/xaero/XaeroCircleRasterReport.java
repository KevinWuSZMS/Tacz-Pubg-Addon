package com.example.examplemod.zones.client.xaero;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import static com.example.examplemod.zones.client.xaero.XaeroOverlayGeometry.*;

/** Diagnostic raster of the actual shared map geometry; not a Minecraft/GPU screenshot. */
final class XaeroCircleRasterReport {
    public static void main(String[] args) { write(); }
    static void write() {
        BufferedImage image = new BufferedImage(1080, 660, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x182029)); g.fillRect(0, 0, 1080, 660);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        panel(g, 0, 0, "Waiting: fixed next circle", new Projection(0, 0, 1, 1, 0), new Point(20, -10), 65, false, 115);
        panel(g, 360, 0, "Late shrink: blue meets white", new Projection(0, 0, 1, 1, 0), new Point(0, 0), 65, false, 66);
        panel(g, 720, 0, "Rotating round minimap", new Projection(0, 0, 1, .6, .8), new Point(20, -10), 100, true, 115);
        panel(g, 0, 330, "Zoomed nearby arc: R=10,000", new Projection(10000, 0, 8, 1, 0), new Point(0, 0), 10000, false, 0);
        panel(g, 360, 330, "Tiny circle: continuous white edge", new Projection(0, 0, 1, 1, 0), new Point(0, 0), 4, false, 0);
        panel(g, 720, 330, "World-sized arc: R=60,000,000", new Projection(60000000, 0, 50, .6, .8), new Point(0, 0), 60000000, true, 0);
        g.dispose();
        try {
            Path path = Path.of("build/reports/xaero-circle-preview.png");
            Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
        } catch (java.io.IOException error) { throw new AssertionError("Cannot write circle geometry diagnostic", error); }
    }

    private static void panel(Graphics2D g, int x, int y, String label, Projection projection,
            Point center, double radius, boolean round, double blueRadius) {
        Graphics2D p = (Graphics2D) g.create();
        p.translate(x, y); p.setColor(Color.WHITE); p.drawString(label, 12, 24); p.translate(180, 180);
        Viewport view = new Viewport(160, 125, round);
        var mask = round ? new Ellipse2D.Double(-125, -125, 250, 250) : new Rectangle2D.Double(-160, -125, 320, 250);
        p.clip(mask); p.setColor(new Color(0x627264)); p.fill(mask);
        p.setColor(new Color(0x758775)); p.setStroke(new BasicStroke(1));
        for (int i = -160; i <= 160; i += 20) { p.drawLine(i, -125, i, 125); p.drawLine(-160, i, 160, i); }
        if (blueRadius > 0) {
            p.setColor(new Color(0x387FE8)); p.setStroke(new BasicStroke(1.5f));
            p.draw(new Ellipse2D.Double(-blueRadius, -blueRadius, blueRadius * 2, blueRadius * 2));
        }
        var segments = circleOutline(center, radius, projection, view);
        for (int pass = 0; pass < 2; pass++) {
            double width = pass == 0 ? 2.8 : 1.3;
            p.setStroke(new BasicStroke((float) width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            p.setColor(pass == 0 ? new Color(0xD0181818, true) : Color.WHITE);
            var inset = new Viewport(view.halfWidth() - width, view.halfHeight() - width, round);
            for (var segment : segments) {
                var a = projection.project(segment.start().x(), segment.start().y());
                var b = projection.project(segment.end().x(), segment.end().y());
                var line = clipViewport(a, b, inset);
                if (line != null) p.draw(new Line2D.Double(line[0].x(), line[0].y(), line[1].x(), line[1].y()));
            }
        }
        p.dispose();
    }
}
