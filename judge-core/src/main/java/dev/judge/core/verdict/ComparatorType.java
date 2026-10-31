package dev.judge.core.verdict;

public enum ComparatorType {
    EXACT,
    TOKEN,
    FLOAT;

    /** @param tolerance required for {@link #FLOAT}, ignored otherwise */
    public OutputComparator create(Double tolerance) {
        return switch (this) {
            case EXACT -> new ExactComparator();
            case TOKEN -> new TokenComparator();
            case FLOAT -> {
                if (tolerance == null) {
                    throw new IllegalArgumentException("A FLOAT comparator needs a tolerance");
                }
                yield new FloatComparator(tolerance);
            }
        };
    }
}
