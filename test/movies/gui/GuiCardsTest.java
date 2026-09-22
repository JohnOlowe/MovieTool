package movies.gui;

import movies.core.Options;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Headless validation that every card builds, lays out, paints, collects AND
 * actually offers input widgets (a card whose form never made it onto the
 * panel used to pass silently - the "no way to input anything" bug).
 */
public class GuiCardsTest {
    public static void main(String[] args) throws Exception {
        List<OpCard> cards = Cards.all();
        System.out.println("cards: " + cards.size());
        if (cards.size() < 10) throw new AssertionError("cards went missing: " + cards.size());

        Set<String> titles = new HashSet<String>();
        int withNoInputs = 0;
        boolean shiftHasSpinner = false;
        for (OpCard card : cards) {
            if (!titles.add(card.getTitle())) throw new AssertionError("duplicate card title: " + card.getTitle());
            JComponent comp = card.component();
            comp.setPreferredSize(new Dimension(640, 300));
            comp.setSize(640, 300);
            comp.doLayout();
            BufferedImage img = new BufferedImage(640, 300, BufferedImage.TYPE_INT_RGB);
            comp.paint(img.createGraphics());
            Options options = new Options();
            card.collect(options);
            int inputs = countInputs(comp);
            System.out.println("OK " + card.getTitle() + " | " + inputs + " input widget(s) | folder='"
                    + options.getFolder() + "'");
            if (inputs == 0) {
                System.out.println("  !! card has NO input widgets");
                withNoInputs++;
            }
            if ("Shift timing".equals(card.getTitle()) && hasSpinner(comp)) shiftHasSpinner = true;
        }
        if (withNoInputs > 0) throw new AssertionError(withNoInputs + " card(s) without any input widget");
        if (!shiftHasSpinner) throw new AssertionError("Shift timing card has no seconds spinner");
        if (HelpBook.html().length() < 1000) throw new AssertionError("help text missing");
        System.out.println("ALL CARDS BUILD, PAINT AND TAKE INPUT");
    }

    private static int countInputs(Component c) {
        int n = 0;
        if (c instanceof JTextField || c instanceof JSpinner || c instanceof JCheckBox || c instanceof JComboBox) n++;
        if (c instanceof Container) for (Component child : ((Container) c).getComponents()) n += countInputs(child);
        return n;
    }

    private static boolean hasSpinner(Component c) {
        if (c instanceof JSpinner) return true;
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                if (hasSpinner(child)) return true;
            }
        }
        return false;
    }
}
