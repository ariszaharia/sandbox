package dev.judge.core.verdict;

/** Decides whether a program's output is correct. Chosen per problem; see {@link ComparatorType}. */
public interface OutputComparator {

    boolean matches(String expected, String actual);
}
