ALTER TABLE card_type ADD COLUMN category_code TEXT NOT NULL DEFAULT 'CUSTOM';
ALTER TABLE card_type ADD COLUMN discount_percent INTEGER NOT NULL DEFAULT 100;
ALTER TABLE card_type ADD COLUMN points_rate INTEGER NOT NULL DEFAULT 0;
ALTER TABLE card_type ADD COLUMN config_json TEXT NOT NULL DEFAULT '{}';
UPDATE card_type SET category_code='STORED' WHERE mode='STORED' AND category_code='CUSTOM' AND name IN ('Stored value','储值卡','储值会员');
UPDATE card_type SET category_code='COUNT' WHERE mode='COUNT' AND category_code='CUSTOM' AND name IN ('Visit card','次卡','计次会员');
CREATE INDEX idx_card_type_category ON card_type(shop_id, category_code);
