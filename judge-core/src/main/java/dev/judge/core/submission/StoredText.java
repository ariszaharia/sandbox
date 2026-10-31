package dev.judge.core.submission;

/**
 * Makes text a program produced safe to store. It controls these bytes, and Postgres rejects a NUL
 * or a surrogate pair cut in half; left alone, either would turn its own bad output into an
 * internal error.
 */
final class StoredText {

    private StoredText() {
    }

    static String of(String text, int maxChars) {
        String clean = text.replace("\0", "");
        if (clean.length() <= maxChars) {
            return clean;
        }
        int end = Character.isLowSurrogate(clean.charAt(maxChars)) ? maxChars - 1 : maxChars;
        return clean.substring(0, end);
    }
}
