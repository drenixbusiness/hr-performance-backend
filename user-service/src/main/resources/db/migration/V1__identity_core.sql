-- =====================================================================
-- Identity core: users, roles, permissions, assignments, audit.
-- Owned by user-service. No other service touches these tables.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Plain VARCHAR with functional indexes rather than CITEXT: Hibernate's schema validation
-- does not recognise the extension type, and lower(...) indexes give the same behaviour
-- with a type the JPA mapping can confirm.

-- ---------------------------------------------------------------------
-- permissions: the full vocabulary. Rows are inserted by migrations only,
-- never by the application, so the set of possible actions is reviewable
-- in git rather than editable at runtime.
-- ---------------------------------------------------------------------
CREATE TABLE permissions (
    code        VARCHAR(64) PRIMARY KEY,
    resource    VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    CONSTRAINT permissions_code_format CHECK (code = resource || ':' || action)
);

CREATE TABLE roles (
    code        VARCHAR(48) PRIMARY KEY,
    name        VARCHAR(96) NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    is_system   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT roles_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{2,47}$')
);

CREATE TABLE role_permissions (
    role_code       VARCHAR(48) NOT NULL REFERENCES roles(code) ON DELETE CASCADE,
    permission_code VARCHAR(64) NOT NULL REFERENCES permissions(code) ON DELETE RESTRICT,
    PRIMARY KEY (role_code, permission_code)
);

-- ---------------------------------------------------------------------
-- users. password_hash is Argon2id; the column never leaves this service.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id                   UUID         PRIMARY KEY,
    username             VARCHAR(64)  NOT NULL,
    password_hash        TEXT         NOT NULL,
    password_updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    full_name            VARCHAR(160) NOT NULL,
    email                VARCHAR(160),
    phone                VARCHAR(32),
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    failed_attempts      SMALLINT     NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by           UUID         REFERENCES users(id) ON DELETE SET NULL,
    deleted_at           TIMESTAMPTZ,
    CONSTRAINT users_status_valid CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    CONSTRAINT users_username_format CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$')
);

-- Soft-deleted accounts keep their row (audit trail) but free the username.
-- Case-insensitive, and matches the lower(username) predicate Spring Data generates for
-- findByUsernameIgnoreCase.
CREATE UNIQUE INDEX users_username_active_idx ON users (lower(username)) WHERE deleted_at IS NULL;
CREATE INDEX users_email_idx ON users (lower(email)) WHERE deleted_at IS NULL;
CREATE INDEX users_status_idx ON users (status) WHERE deleted_at IS NULL;
CREATE INDEX users_created_at_idx ON users (created_at DESC, id DESC);

-- ---------------------------------------------------------------------
-- Role assignment, optionally scoped to one company.
-- entity IS NULL  -> the role applies across every entity.
-- ---------------------------------------------------------------------
CREATE TABLE user_roles (
    -- Surrogate key: the JPA entity needs a single @Id, and a composite key here would buy
    -- nothing since the real constraint is the unique index below.
    id          BIGSERIAL   PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code   VARCHAR(48) NOT NULL REFERENCES roles(code) ON DELETE RESTRICT,
    entity      VARCHAR(16),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  UUID        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT user_roles_entity_valid CHECK (entity IS NULL OR entity IN ('JM', 'BP', 'WF', 'KAISEN'))
);

-- One row per (user, role, entity). COALESCE keeps the global assignment unique
-- too, which a plain UNIQUE would not do because NULL never equals NULL.
CREATE UNIQUE INDEX user_roles_unique_idx
    ON user_roles (user_id, role_code, COALESCE(entity, '*'));
CREATE INDEX user_roles_user_idx ON user_roles (user_id);

-- ---------------------------------------------------------------------
-- Append-only audit log. Every admin mutation and every auth decision.
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id             BIGSERIAL   PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id       UUID,
    actor_username VARCHAR(64),
    action         VARCHAR(64) NOT NULL,
    target_type    VARCHAR(32),
    target_id      VARCHAR(64),
    outcome        VARCHAR(16) NOT NULL,
    client_ip      INET,
    user_agent     TEXT,
    correlation_id VARCHAR(64),
    detail         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_outcome_valid CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
);

CREATE INDEX audit_log_occurred_idx ON audit_log (occurred_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id, occurred_at DESC);

-- The application role may insert but never update or delete audit rows.
CREATE RULE audit_log_no_update AS ON UPDATE TO audit_log DO INSTEAD NOTHING;
CREATE RULE audit_log_no_delete AS ON DELETE TO audit_log DO INSTEAD NOTHING;
