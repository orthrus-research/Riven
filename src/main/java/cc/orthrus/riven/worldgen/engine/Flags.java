package cc.orthrus.riven.worldgen.engine;

/**
 * The named facts a column states as yes or no. Operators set them (adding a flag is Java);
 * cavities read them by name, as the sky's roof band reads wet. Names resolve to bits when
 * definitions load.
 */
public final class Flags {
    private static final String[] NAMES = {"wet"};

    /** Under the sea or surface water: caves keep the thicker roof. */
    public static final int WET = 0;

    private Flags() {}

    /** The index of a flag a definition names; an unknown name fails. */
    public static int index(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return i;
        throw new IllegalStateException("Unknown flag " + name);
    }

    public static String name(int index) {
        return NAMES[index];
    }
}
