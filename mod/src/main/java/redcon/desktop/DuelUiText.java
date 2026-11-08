package redcon.desktop;

/** Cached native-font text; no engine randomness or simulation callbacks. */
final class DuelUiText {
    private final c3.c font;
    private final c3.h glyphs;
    private String previous = "";
    private final e3.g transform = new e3.g();

    DuelUiText(c3.c font) {
        this.font = font;
        glyphs = font.h();
    }

    void draw(String text, int x, int y, int width, int colour) {
        draw(text, x, y, width, colour, 1f);
    }

    void draw(String text, int x, int y, int width, int colour, float scale) {
        String fitted = fit(font, text, (int) (width / scale));
        if (!fitted.equals(previous)) {
            glyphs.o(0, fitted);
            previous = fitted;
        }
        glyphs.l(colour);
        if (scale == 1f) glyphs.c(x, y, 0);
        else {
            transform.c(scale, scale);
            transform.d = x;
            transform.g = y;
            glyphs.d(transform);
        }
    }

    static String fit(c3.c font, String text, int width) {
        if (font.m(text) <= width) return text;
        int end = text.length();
        while (end > 0 && font.m(text.substring(0, end) + "...") > width) end--;
        return end == 0 ? "" : text.substring(0, end) + "...";
    }
}
