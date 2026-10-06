package movies;

import movies.cli.Cli;
import movies.gui.MovieToolGui;

import java.awt.GraphicsEnvironment;

/**
 * Entry point. With no arguments the graphical interface starts (double
 * clicking the jar); with arguments the command line runs.
 */
public final class Main {

    static {
        // BEFORE anything loads AWT/Swing/Java2D: the Windows Direct3D
        // pipeline is the cause of stale and blank panels after native
        // dialogs on Windows 10 + Java 21. Setting the properties here (a
        // static block) guarantees they exist before the graphics pipeline
        // initialises - launch() ran them only AFTER GraphicsEnvironment
        // had already touched AWT, which can be too late.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            System.setProperty("sun.java2d.noddraw", "true");
            System.setProperty("sun.java2d.d3d", "false");
        }
    }

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            launchGui();
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            launchGui();
            return;
        }
        System.exit(Cli.run(args));
    }

    private static void launchGui() {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display is available, so the graphical interface cannot open.");
            System.err.println("Run 'movietool help' to use the command line instead.");
            System.exit(2);
        }
        MovieToolGui.launch();
    }
}
