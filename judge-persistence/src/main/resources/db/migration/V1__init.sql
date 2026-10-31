CREATE TABLE problem (
    id              UUID PRIMARY KEY,
    slug            TEXT NOT NULL UNIQUE,
    title           TEXT NOT NULL,
    statement       TEXT NOT NULL DEFAULT '',
    time_limit_ms   INT  NOT NULL,
    memory_limit_mb INT  NOT NULL,
    comparator      TEXT NOT NULL DEFAULT 'EXACT',
    tolerance       DOUBLE PRECISION
);

CREATE TABLE test_case (
    id         UUID PRIMARY KEY,
    problem_id UUID NOT NULL REFERENCES problem(id) ON DELETE CASCADE,
    idx        INT  NOT NULL,
    input      TEXT NOT NULL,
    expected   TEXT NOT NULL,
    hidden     BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (problem_id, idx)
);

CREATE TABLE submission (
    id             UUID PRIMARY KEY,
    problem_id     UUID NOT NULL REFERENCES problem(id),
    language       TEXT NOT NULL,
    source_code    TEXT NOT NULL,
    status         TEXT NOT NULL,
    verdict        TEXT,
    compile_output TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ
);

CREATE TABLE test_result (
    submission_id  UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    idx            INT  NOT NULL,
    verdict        TEXT NOT NULL,
    runtime_ms     INT,
    peak_memory_kb INT,
    stdout_preview TEXT,
    PRIMARY KEY (submission_id, idx)
);

CREATE INDEX ON submission (status, created_at);
