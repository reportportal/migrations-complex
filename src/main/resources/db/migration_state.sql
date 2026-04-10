CREATE TABLE IF NOT EXISTS public.migration_state (
    id              BIGSERIAL PRIMARY KEY,
    migration_type  VARCHAR(64)  NOT NULL,
    entity_type     VARCHAR(32)  NOT NULL,
    entity_id       BIGINT,
    source_bucket   VARCHAR(256),
    source_key      VARCHAR(1024),
    dest_bucket     VARCHAR(256),
    dest_key        VARCHAR(1024),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    error_message   TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX IF NOT EXISTS idx_migration_state_status ON public.migration_state(status);
CREATE INDEX IF NOT EXISTS idx_migration_state_type ON public.migration_state(migration_type, entity_type);
CREATE UNIQUE INDEX IF NOT EXISTS idx_migration_state_unique_key
    ON public.migration_state(migration_type, source_bucket, source_key);
