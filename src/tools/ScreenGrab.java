package tools;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Captures a screen region to a PNG. Args: out [x y w h]. */
public final class ScreenGrab {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "screen.png";
        Rectangle r;
        if (args.length >= 5) {
            r = new Rectangle(Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                              Integer.parseInt(args[3]), Integer.parseInt(args[4]));
        } else {
            r = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        BufferedImage img = new Robot().createScreenCapture(r);
        ImageIO.write(img, "png", new File(out));
        System.out.println("Saved " + out + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
