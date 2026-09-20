ALTER TABLE member ADD COLUMN inviter_name TEXT;
ALTER TABLE member ADD COLUMN inviter_member_id TEXT;
ALTER TABLE shop ADD COLUMN settings_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE shop ADD COLUMN local_password_hash TEXT;
ALTER TABLE card_type ADD COLUMN gift_percent INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_member_inviter ON member(inviter_member_id);
