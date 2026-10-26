package fr.mattmunich.iceBoatRacing.pitbox;

import java.util.stream.Stream;

public enum PitBoxColor {
    //Basic colors
    RED("<bold><color:#FF5555>", "</color></bold>"),
    BLUE("<bold><color:#5599FF>", "</color></bold>"),
    GREEN("<bold><color:#55FF55>", "</color></bold>"),
    GOLD("<bold><color:#FFAA00>", "</color></bold>"),

    //Real racing team colors
    MCLAREN("<b><gradient:#FF4800:#000000>", "</gradient></b>"),
    MERCEDES("<b><gradient:#000000:#58916E>", "</gradient></b>"),
    RED_BULL("<b><gradient:#2200FF:#910000>", "</gradient></b>"),
    FERRARI("<b><gradient:#FF0000:#CED90E>", "</gradient></b>"),
    WILLIAMS("<b><gradient:#0E00FF:#0EB0D9>", "</gradient></b>"),
    VCARB("<b><gradient:#D09DB9:#4498DB>", "</gradient></b>"),
    ASTON_MARTIN("<b><gradient:#5BD28A:#045F4B>", "</gradient></b>"),
    HAAS("<b><gradient:#FFE8AB:#AAAAAA:#FF9191>", "</gradient></b>"),
    ALPINE("<b><gradient:#FD3DC1:#5AAEF1>", "</gradient></b>"),
    AUDI("<b><gradient:#555555:#910000>", "</gradient></b>"),
    CADILLAC("<b><gradient:#D2D2D2:#000000>", "</gradient></b>"),

    //Other gradients
    ICE("<bold><gradient:#8DABF3:#10CA9A>", "</gradient></bold>"),
    FIRE("<bold><gradient:#FF5F6D:#FFC371>", "</gradient></bold>"),
    RAINBOW("<bold><rainbow>", "</rainbow></bold>");

    private final String tagPrefix;
    private final String tagSuffix;

    PitBoxColor(String tagPrefix, String tagSuffix) {
        this.tagPrefix = tagPrefix;
        this.tagSuffix = tagSuffix;
    }

    // Source - https://stackoverflow.com/a/30587868
    // Posted by Kris Boyd
    // Retrieved 2026-10-24, License - CC BY-SA 3.0

    public static String[] names() {
        return Stream.of(PitBoxColor.values()).map(PitBoxColor::name).toArray(String[]::new);
    }


    public String getTagPrefix() { return tagPrefix; }
    public String getTagSuffix() { return tagSuffix; }
}