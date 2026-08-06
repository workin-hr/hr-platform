-- Individual audit attribution for platform-admin activity -- the
-- second half of F-26's remaining closure criteria (hr-legacy#11: the
-- shared-password model had no audit trail at all). Every event is
-- attributed to a real platform_admins row; deliberately no free-text
-- principal column, so an unattributable event cannot be recorded here
-- (unknown-phone login probes stay in structured logs, ADR-0008).
-- Platform-global, not tenant data: no RLS, same as platform_admins.
CREATE TABLE platform_admin_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    platform_admin_id BIGINT NOT NULL REFERENCES platform_admins(id),
    event_type VARCHAR(64) NOT NULL,
    detail TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX platform_admin_audit_events_admin_id_idx
    ON platform_admin_audit_events (platform_admin_id);
