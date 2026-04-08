-- V10: Add operator labels and prompt version tracking to AI filter decisions.
-- Supports ground-truth labelling for accuracy scoring and A/B split testing attribution.

ALTER TABLE routify_audit.ai_filter_decision
    ADD COLUMN IF NOT EXISTS operator_label     VARCHAR(20),
    ADD COLUMN IF NOT EXISTS prompt_version_id  UUID;

CREATE INDEX IF NOT EXISTS idx_ai_decision_prompt_version
    ON routify_audit.ai_filter_decision(prompt_version_id);

COMMENT ON COLUMN routify_audit.ai_filter_decision.operator_label
    IS 'Ground-truth label: CORRECT | INCORRECT | UNCLEAR. NULL until labelled by operator.';
COMMENT ON COLUMN routify_audit.ai_filter_decision.prompt_version_id
    IS 'FK to ai_prompt_version.id — identifies which prompt version produced this decision (for A/B testing).';

