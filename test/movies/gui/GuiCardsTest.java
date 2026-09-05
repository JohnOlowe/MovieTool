package movies.gui;

import movies.core.Options;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.List;

/** Headless validation that every card builds, lays out, paints and collects. */
public class GuiCardsTest {
    public static void main(String[] args) throws Exception {
        List<OpCard> cards = Cards.all();
        System.out.println("cards: " + cards.size());
        for (OpCard card : cards) {
            JComponent comp = card.component();
            comp.setPreferredSize(new Dimension(640, 300));
            comp.setSize(640, 300);
            comp.doLayout();
            BufferedImage img = new BufferedImage(640, 300, BufferedImage.TYPE_INT_RGB);
            comp.paint(img.createGraphics());
            Options options = new Options();
            card.collect(options);
            System.out.println("OK " + card.getTitle() + " | " + comp.getComponentCount() + " widgets | folder='"
                    + options.getFolder() + "'");
        }
        System.out.println("ALL CARDS BUILD AND PAINT");
    }
}
