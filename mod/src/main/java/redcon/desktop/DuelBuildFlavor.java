package redcon.desktop;

/** Build-time policy boundary between local development and distributable APKs. */
public final class DuelBuildFlavor {
    /**
     * The release builder replaces this exact constant in its isolated source
     * copy. Keeping the checked-in value false preserves existing developer
     * workflows while making the released DEX independent of intent extras or
     * mutable system properties.
     */
    public static final boolean RELEASE = false;

    private DuelBuildFlavor() { }
}
