package cc.orthrus.riven;

import cc.orthrus.riven.content.RivenContent;
import cc.orthrus.riven.worldgen.Rules;
import cc.orthrus.riven.worldgen.SeededNoise;

import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Side elevations of every tree species as the production shapes draw them (runDiagnostics with
 * -PrivenDiagnosticGameBootstrap=true). Usage: TreeGallery <out.png> [trees per species]. Each
 * species gets one row of sample trees across its own height band, shortest to tallest, seen from
 * the south with depth shading: logs brown, leaves green, darker further back.
 */
public final class TreeGallery {
    private static final int PX = 6, CELL = 34, ROW = 34;

    private TreeGallery() {}

    public static void main(String[] args) throws Exception {
        ModMetadata metadata = new ModMetadata();
        metadata.modId = "riven";
        metadata.name = "Riven";
        metadata.version = "0.0.1";
        Bootstrap.register();
        Loader.instance().setupTestHarness(new DummyModContainer(metadata));
        RivenContent.registerBlocks(
                new RegistryEvent.Register<>(
                        new ResourceLocation("minecraft", "blocks"), ForgeRegistries.BLOCKS));
        int per = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        Map<String, TreeShapes.Species> all =
                TreeShapes.species(Rules.load("riven-trees.json", 1).json, Mappings.resolve());
        List<TreeShapes.Species> list = new ArrayList<>(all.values());
        int labelW = 150, w = labelW + per * CELL * PX, h = list.size() * ROW * PX;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(236, 240, 236));
        g.fillRect(0, 0, w, h);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        TreeShapes.Shape shape = new TreeShapes.Shape();
        for (int r = 0; r < list.size(); r++) {
            TreeShapes.Species s = list.get(r);
            int y0 = r * ROW * PX, ground = y0 + (ROW - 2) * PX;
            g.setColor(new Color(200, 205, 200));
            g.fillRect(0, ground, w, 2);
            g.setColor(Color.DARK_GRAY);
            g.drawString(s.id, 8, y0 + 22);
            g.drawString(s.family.name().toLowerCase(), 8, y0 + 40);
            g.drawString(s.height[0] + "-" + s.height[1], 8, y0 + 58);
            for (int k = 0; k < per; k++) {
                int stem =
                        s.height[0]
                                + (int)
                                        Math.round(
                                                (s.height[1] - s.height[0])
                                                        * k
                                                        / Math.max(1.0, per - 1));
                TreeShapes.shape(s, SeededNoise.mix(0x51ab1eL + 31L * r + k), stem, shape);
                int cx = labelW + k * CELL * PX + CELL * PX / 2;
                // Paint back to front so nearer blocks cover farther ones.
                for (int depth = shape.maxZ; depth >= shape.minZ; depth--)
                    for (int i = 0; i < shape.count; i++) {
                        int p = shape.blocks[i];
                        if (TreeShapes.dz(p) != depth) continue;
                        double shade = 1 - 0.07 * (depth - shape.minZ);
                        int kind = TreeShapes.kind(p);
                        Color c =
                                kind == TreeShapes.LEAVES
                                        ? new Color(70, 140, 60)
                                        : new Color(110, 75, 45);
                        g.setColor(
                                new Color(
                                        (int) Math.max(0, c.getRed() * shade),
                                        (int) Math.max(0, c.getGreen() * shade),
                                        (int) Math.max(0, c.getBlue() * shade)));
                        int x = cx + TreeShapes.dx(p) * PX,
                                y = ground - (TreeShapes.dy(p) + 1) * PX;
                        if (y >= y0) g.fillRect(x, y, PX, PX);
                    }
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
        System.out.println("TreeGallery wrote " + args[0] + " (" + list.size() + " species)");
    }
}
