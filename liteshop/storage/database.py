"""Atomic, checksummed migrations compatible with old SQLite syntax."""
from contextlib import contextmanager
from hashlib import sha256
from pathlib import Path
import sqlite3
import time

MIGRATIONS = Path(__file__).with_name("migrations")

class Database:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        self.connection.execute("PRAGMA journal_mode = WAL")
        self.connection.execute("PRAGMA synchronous = FULL")
        self.migrate()

    @contextmanager
    def atomic(self):
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            yield
            self.connection.execute("COMMIT")
        except BaseException:
            self.connection.execute("ROLLBACK")
            raise

    def migrate(self):
        with self.atomic():
            self.connection.execute("CREATE TABLE IF NOT EXISTS schema_migration (version INTEGER PRIMARY KEY, checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)")
            applied = dict(self.connection.execute("SELECT version, checksum FROM schema_migration"))
            scripts = sorted(MIGRATIONS.glob("[0-9]*.sql"))
            known = {int(p.name.split("_")[0]) for p in scripts}
            if set(applied) - known:
                raise RuntimeError("Database is newer than this application")
            for path in scripts:
                version = int(path.name.split("_")[0])
                script = path.read_text(encoding="utf-8")
                checksum = sha256(script.encode()).hexdigest()
                if version in applied:
                    if applied[version] != checksum:
                        raise RuntimeError("Applied migration was modified: " + path.name)
                    continue
                statement = ""
                for line in script.splitlines(True):
                    statement += line
                    if sqlite3.complete_statement(statement):
                        self.connection.execute(statement)
                        statement = ""
                if statement.strip():
                    raise RuntimeError("Incomplete migration: " + path.name)
                self.connection.execute("INSERT INTO schema_migration VALUES (?, ?, ?)", (version, checksum, int(time.time() * 1000)))

    def close(self):
        self.connection.close()
