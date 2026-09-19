CREATE TABLE shop (
    id TEXT PRIMARY KEY, singleton INTEGER NOT NULL DEFAULT 1 UNIQUE CHECK(singleton = 1),
    name TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE device (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id),
    name TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE staff (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id),
    name TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('OWNER','STAFF')),
    status INTEGER NOT NULL DEFAULT 1 CHECK(status IN (0,1)), created_at INTEGER NOT NULL
);
CREATE TABLE member (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id),
    member_no TEXT NOT NULL UNIQUE, name TEXT NOT NULL, phone TEXT,
    birthday TEXT, remark TEXT NOT NULL DEFAULT '',
    status INTEGER NOT NULL DEFAULT 1 CHECK(status IN (0,1)),
    version INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL, deleted_at INTEGER
);
CREATE INDEX idx_member_phone ON member(phone);
CREATE INDEX idx_member_name ON member(name);
CREATE TABLE member_tag (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id),
    name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL
);
CREATE TABLE member_tag_relation (
    member_id TEXT NOT NULL REFERENCES member(id), tag_id TEXT NOT NULL REFERENCES member_tag(id),
    created_at INTEGER NOT NULL, PRIMARY KEY(member_id, tag_id)
);
CREATE TABLE card_type (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id), name TEXT NOT NULL,
    mode TEXT NOT NULL CHECK(mode IN ('STORED','COUNT')),
    status INTEGER NOT NULL DEFAULT 1 CHECK(status IN (0,1)), created_at INTEGER NOT NULL
);
CREATE TABLE member_card (
    id TEXT PRIMARY KEY, member_id TEXT NOT NULL REFERENCES member(id),
    card_type_id TEXT NOT NULL REFERENCES card_type(id), card_no TEXT NOT NULL UNIQUE,
    balance INTEGER NOT NULL DEFAULT 0 CHECK(typeof(balance) = 'integer' AND balance BETWEEN 0 AND 9000000000000),
    remaining_times INTEGER NOT NULL DEFAULT 0 CHECK(typeof(remaining_times) = 'integer' AND remaining_times BETWEEN 0 AND 9000000000000),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','LOST','DISABLED')),
    expire_at INTEGER, version INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX idx_card_member ON member_card(member_id);
CREATE TABLE ledger_transaction (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id),
    member_id TEXT NOT NULL REFERENCES member(id), card_id TEXT NOT NULL REFERENCES member_card(id),
    kind TEXT NOT NULL CHECK(kind IN ('RECHARGE','CONSUME','REFUND','GIFT','ADJUST','CREDIT_TIMES','DEDUCT_TIMES')),
    amount INTEGER NOT NULL CHECK(typeof(amount) = 'integer'),
    times INTEGER NOT NULL CHECK(typeof(times) = 'integer'),
    balance_before INTEGER NOT NULL, balance_after INTEGER NOT NULL CHECK(balance_after >= 0),
    times_before INTEGER NOT NULL, times_after INTEGER NOT NULL CHECK(times_after >= 0),
    source_id TEXT REFERENCES ledger_transaction(id),
    operator_id TEXT NOT NULL REFERENCES staff(id), device_id TEXT NOT NULL REFERENCES device(id),
    remark TEXT NOT NULL, created_at INTEGER NOT NULL,
    CHECK(balance_before + amount = balance_after),
    CHECK(times_before + times = times_after),
    CHECK(amount != 0 OR times != 0),
    CHECK((kind = 'REFUND' AND source_id IS NOT NULL) OR (kind != 'REFUND' AND source_id IS NULL))
);
CREATE INDEX idx_ledger_member ON ledger_transaction(member_id, created_at);
CREATE INDEX idx_ledger_card ON ledger_transaction(card_id, created_at);
CREATE INDEX idx_ledger_source ON ledger_transaction(source_id);
CREATE TABLE transaction_item (
    id TEXT PRIMARY KEY, transaction_id TEXT NOT NULL REFERENCES ledger_transaction(id),
    name TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity > 0),
    unit_price INTEGER NOT NULL CHECK(unit_price >= 0), amount INTEGER NOT NULL CHECK(amount = quantity * unit_price)
);
CREATE TABLE points_account (
    member_id TEXT PRIMARY KEY REFERENCES member(id),
    balance INTEGER NOT NULL DEFAULT 0 CHECK(typeof(balance) = 'integer' AND balance BETWEEN 0 AND 9000000000000),
    updated_at INTEGER NOT NULL
);
CREATE TABLE points_transaction (
    id TEXT PRIMARY KEY, member_id TEXT NOT NULL REFERENCES member(id),
    points INTEGER NOT NULL CHECK(typeof(points) = 'integer' AND points != 0),
    balance_before INTEGER NOT NULL, balance_after INTEGER NOT NULL CHECK(balance_after >= 0),
    operator_id TEXT NOT NULL REFERENCES staff(id), device_id TEXT NOT NULL REFERENCES device(id),
    remark TEXT NOT NULL, created_at INTEGER NOT NULL,
    CHECK(balance_before + points = balance_after)
);
CREATE INDEX idx_points_member ON points_transaction(member_id, created_at);
CREATE TABLE service (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id), name TEXT NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK(duration_minutes > 0),
    price INTEGER NOT NULL CHECK(typeof(price) = 'integer' AND price >= 0),
    status INTEGER NOT NULL DEFAULT 1 CHECK(status IN (0,1))
);
CREATE TABLE resource (
    id TEXT PRIMARY KEY, shop_id TEXT NOT NULL REFERENCES shop(id), name TEXT NOT NULL,
    resource_type TEXT NOT NULL, staff_id TEXT REFERENCES staff(id),
    status INTEGER NOT NULL DEFAULT 1 CHECK(status IN (0,1))
);
CREATE TABLE appointment (
    id TEXT PRIMARY KEY, member_id TEXT NOT NULL REFERENCES member(id),
    service_id TEXT NOT NULL REFERENCES service(id), resource_id TEXT NOT NULL REFERENCES resource(id),
    start_at INTEGER NOT NULL, end_at INTEGER NOT NULL CHECK(end_at > start_at),
    status TEXT NOT NULL CHECK(status IN ('PENDING','CONFIRMED','COMPLETED','CANCELLED','NO_SHOW')),
    version INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX idx_appointment_resource ON appointment(resource_id, start_at, end_at);
CREATE TABLE operation_log (
    id TEXT PRIMARY KEY, operator_id TEXT NOT NULL REFERENCES staff(id), device_id TEXT NOT NULL REFERENCES device(id),
    action TEXT NOT NULL, entity_id TEXT NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TABLE sync_event (
    sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
    shop_id TEXT NOT NULL REFERENCES shop(id), device_id TEXT NOT NULL REFERENCES device(id),
    event_type TEXT NOT NULL, entity_id TEXT NOT NULL, payload TEXT NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','UPLOADING','SYNCED','FAILED')),
    retry_count INTEGER NOT NULL DEFAULT 0, next_retry_at INTEGER NOT NULL DEFAULT 0,
    last_error TEXT, uploaded_at INTEGER, created_at INTEGER NOT NULL
);
CREATE INDEX idx_sync_queue ON sync_event(status, sequence);
CREATE TABLE sync_state (
    device_id TEXT PRIMARY KEY REFERENCES device(id), last_server_cursor TEXT,
    last_uploaded_at INTEGER, last_downloaded_at INTEGER, updated_at INTEGER NOT NULL
);
CREATE TABLE command_receipt (
    request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,
    result TEXT NOT NULL, created_at INTEGER NOT NULL
);
CREATE TRIGGER ledger_no_update BEFORE UPDATE ON ledger_transaction BEGIN
    SELECT RAISE(ABORT, 'ledger is append-only');
END;
CREATE TRIGGER ledger_no_delete BEFORE DELETE ON ledger_transaction BEGIN
    SELECT RAISE(ABORT, 'ledger is append-only');
END;
CREATE TRIGGER points_no_update BEFORE UPDATE ON points_transaction BEGIN
    SELECT RAISE(ABORT, 'points ledger is append-only');
END;
CREATE TRIGGER points_no_delete BEFORE DELETE ON points_transaction BEGIN
    SELECT RAISE(ABORT, 'points ledger is append-only');
END;
CREATE TRIGGER audit_no_update BEFORE UPDATE ON operation_log BEGIN
    SELECT RAISE(ABORT, 'audit is append-only');
END;
CREATE TRIGGER audit_no_delete BEFORE DELETE ON operation_log BEGIN
    SELECT RAISE(ABORT, 'audit is append-only');
END;
