import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * NetPilot icon "Route N": the letter N as a network path between two nodes. Designed on a 128 grid.
 * Regenerates every icon: java packaging/brand/MakeIcon.java src/main/resources/org/netpilot/icons
 * packaging/windows/NetPilot.ico packaging/brand
 */
public class MakeIcon {

    static final Color TILE = new Color(0x1E5FB4);
    static final Color NODE = new Color(0x34D1A6);

    static BufferedImage draw(int size) {
        return draw(size, null);
    }

    /** mono == null: colour tile; otherwise only the mark, in that one colour, on transparent. */
    static BufferedImage draw(int size, Color mono) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double k = size / 128.0;
        boolean small = size <= 24;

        g.setColor(TILE);
        double inset = small ? 1 : 4;
        double radius = small ? 40 : 30;
        if (mono == null) {
            g.fill(new RoundRectangle2D.Double(inset * k, inset * k, (128 - 2 * inset) * k, (128 - 2 * inset) * k,
                2 * radius * k, 2 * radius * k));
        }

        // small sizes: a bolder N that uses more of the square
        double lo = small ? 34 : 38;
        double hi = small ? 94 : 90;
        double top = small ? 34 : 38;
        double bottom = small ? 94 : 90;
        Path2D n = new Path2D.Double();
        n.moveTo(lo * k, bottom * k);
        n.lineTo(lo * k, top * k);
        n.lineTo(hi * k, bottom * k);
        n.lineTo(hi * k, top * k);
        g.setColor(mono == null ? Color.WHITE : mono);
        g.setStroke(new BasicStroke((float) ((small ? 15 : 11) * k), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(n);

        double r = (small ? 13 : 11) * k;
        double ring = 0;
        for (double[] c : new double[][] {{lo, bottom}, {hi, top}}) {
            double x = c[0] * k;
            double y = c[1] * k;
            if (mono == null) {
                g.setColor(TILE);
            } else {
                g.setComposite(AlphaComposite.Clear);
            }
            g.fill(new Ellipse2D.Double(x - r - ring, y - r - ring, 2 * (r + ring), 2 * (r + ring)));
            g.setComposite(AlphaComposite.SrcOver);
            g.setColor(mono == null ? NODE : mono);
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
        g.dispose();
        return img;
    }

    public static void main(String[] args) throws Exception {
        File pngDir = new File(args[0]);
        pngDir.mkdirs();
        int[] icoSizes = {16, 20, 24, 32, 40, 48, 64, 128, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int size : icoSizes) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", out);
            pngs.add(out.toByteArray());
        }
        for (int size : new int[] {16, 32, 48, 64, 128, 256}) {
            ImageIO.write(draw(size), "png", new File(pngDir, "netpilot-" + size + ".png"));
        }
        ImageIO.write(draw(512), "png", new File(args[2], "netpilot-logo-512.png"));
        ImageIO.write(draw(512, new Color(0x111827)), "png", new File(args[2], "netpilot-mono-dark-512.png"));
        ImageIO.write(draw(512, Color.WHITE), "png", new File(args[2], "netpilot-mono-light-512.png"));
        try (DataOutputStream ico = new DataOutputStream(new FileOutputStream(args[1]))) {
            le16(ico, 0);
            le16(ico, 1);
            le16(ico, icoSizes.length);
            int offset = 6 + 16 * icoSizes.length;
            for (int i = 0; i < icoSizes.length; i++) {
                int size = icoSizes[i];
                ico.writeByte(size >= 256 ? 0 : size);
                ico.writeByte(size >= 256 ? 0 : size);
                ico.writeByte(0);
                ico.writeByte(0);
                le16(ico, 1);
                le16(ico, 32);
                le32(ico, pngs.get(i).length);
                le32(ico, offset);
                offset += pngs.get(i).length;
            }
            for (byte[] png : pngs) {
                ico.write(png);
            }
        }
    }

    static void le16(DataOutputStream out, int v) throws Exception {
        out.writeByte(v & 0xff);
        out.writeByte((v >> 8) & 0xff);
    }

    static void le32(DataOutputStream out, int v) throws Exception {
        le16(out, v & 0xffff);
        le16(out, (v >> 16) & 0xffff);
    }
}
