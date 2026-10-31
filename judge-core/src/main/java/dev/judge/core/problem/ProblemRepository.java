package dev.judge.core.problem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProblemRepository {

    /** @throws ProblemAlreadyExistsException if the slug is taken */
    void insert(Problem problem);

    List<ProblemSummary> list();

    Optional<Problem> find(UUID id);

    boolean exists(UUID id);
}
