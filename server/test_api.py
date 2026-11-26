"""Pure SQLite tests; Google API credentials/network are not required."""
import os
from pathlib import Path
from tempfile import TemporaryDirectory

# The test file assumes this module runs with cwd=server.
import app as backend

from fastapi.testclient import TestClient


def test_snapshot_isolated_by_account():
    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "test.sqlite3"
        with TestClient(backend.app) as client:
            with backend.db() as db:
                db.execute("INSERT INTO users(google_sub,created_at) VALUES ('a',0)")
                db.execute("INSERT INTO users(google_sub,created_at) VALUES ('b',0)")
                token_a, token_b = "a" * 64, "b" * 64
                db.execute("INSERT INTO sessions VALUES (?,?,?)", (backend.digest(token_a), 1, 9999999999))
                db.execute("INSERT INTO sessions VALUES (?,?,?)", (backend.digest(token_b), 2, 9999999999))
            headers = {"Authorization": "Bearer " + token_a}
            payload = {"base_revision": 0, "sets": [{"id": "s1", "title": "Study", "cards": [
                {"id": "c1", "english": "Hello!", "korean": "", "chunks": ["Hello!"],
                 "attempts": 0, "correct": 0, "needsReview": False}
            ]}]}
            assert client.put("/v1/snapshot", headers=headers, json=payload).status_code == 200
            assert client.put("/v1/snapshot", headers=headers, json=payload).status_code == 409
            assert len(client.get("/v1/snapshot", headers=headers).json()["sets"]) == 1
            assert client.get("/v1/me", headers=headers).json() == {"account_id": "1"}
            assert client.get("/v1/me").status_code == 401
            assert client.get("/v1/snapshot", headers=headers).json()["sets"][0]["cards"][0]["korean"] == ""
            assert client.get("/v1/snapshot", headers={"Authorization": "Bearer " + token_b}).json()["sets"] == []
            assert client.get("/v1/snapshot").status_code == 401


def test_account_deletion_cascades():
    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "test.sqlite3"
        with TestClient(backend.app) as client:
            with backend.db() as db:
                db.execute("INSERT INTO users(google_sub,created_at) VALUES ('unique',0)")
                db.execute("INSERT INTO sessions VALUES (?,?,?)", (backend.digest("x" * 64), 1, 9999999999))
            headers = {"Authorization": "Bearer " + "x" * 64}
            assert client.delete("/v1/account", headers=headers).status_code == 200
            assert client.get("/v1/snapshot", headers=headers).status_code == 401


def test_completed_flags_sync_and_legacy_clients_do_not_clear_them():
    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "progress.sqlite3"
        with TestClient(backend.app) as client:
            with backend.db() as conn:
                conn.execute("INSERT INTO users(google_sub,created_at) VALUES ('progress',0)")
                token = "p" * 64
                conn.execute("INSERT INTO sessions VALUES (?,?,?)", (backend.digest(token), 1, 9999999999))
            headers = {"Authorization": "Bearer " + token}
            card = {
                "id": "progress-card", "english": "Learning is complete.",
                "korean": "학습 완료", "chunks": ["Learning", "is", "complete."],
                "attempts": 1, "correct": 1, "needsReview": False,
                "completed": True,
            }
            payload = {"base_revision": 0, "sets": [{
                "id": "set", "title": "progress", "cards": [card],
            }]}
            assert client.put("/v1/snapshot", headers=headers, json=payload).status_code == 200
            result = client.get("/v1/snapshot", headers=headers).json()
            assert result["sets"][0]["cards"][0]["completed"] is True

            # An older installed APK may omit the new field.
            old_card = {key: value for key, value in card.items() if key != "completed"}
            old_payload = {"base_revision": 1, "sets": [{
                "id": "set", "title": "progress", "cards": [old_card],
            }]}
            assert client.put("/v1/snapshot", headers=headers, json=old_payload).status_code == 200
            result = client.get("/v1/snapshot", headers=headers).json()
            assert result["sets"][0]["cards"][0]["completed"] is True


def test_existing_sentence_rows_are_migrated_to_completed():
    import sqlite3

    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "legacy.sqlite3"
        backend.init_db()
        with backend.db() as conn:
            conn.execute("INSERT INTO users(id,google_sub,created_at) VALUES (1,'legacy',0)")
            conn.execute("INSERT INTO sets(id,user_id,title,position) VALUES ('s1',1,'Test',0)")
            conn.execute("INSERT INTO sentences "
                         "(id,set_id,english,korean,chunks_json,attempts,correct,needs_review,position)"
                         "VALUES ('c1','s1','Hello','','[\"Hello\"]',2,1,1,0)")
            conn.execute("INSERT INTO sentences "
                         "(id,set_id,english,korean,chunks_json,attempts,correct,needs_review,position)"
                         "VALUES ('c2','s1','World','','[\"World\"]',0,0,0,1)")
            # Simulate a legacy schema by rebuilding only the affected sentences table.
            conn.execute("ALTER TABLE sentences RENAME TO sentences_with_completed")
            conn.execute("DROP INDEX IF EXISTS sentences_set_order")
            conn.execute("""
                CREATE TABLE sentences (
                    id TEXT PRIMARY KEY,
                    set_id TEXT NOT NULL REFERENCES sets(id) ON DELETE CASCADE,
                    english TEXT NOT NULL, korean TEXT NOT NULL, chunks_json TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0, correct INTEGER NOT NULL DEFAULT 0,
                    needs_review INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL
                )
            """)
            conn.execute(
                "INSERT INTO sentences SELECT id,set_id,english,korean,chunks_json,"
                "attempts,correct,needs_review,position FROM sentences_with_completed"
            )
            conn.execute("DROP TABLE sentences_with_completed")
        backend.init_db()
        with backend.db() as conn:
            result = conn.execute(
                "SELECT id,completed FROM sentences ORDER BY id"
            ).fetchall()
            assert [(r["id"], r["completed"]) for r in result] == [
                ("c1", 1), ("c2", 0)
            ]
            backend.init_db()
            unchanged = conn.execute("SELECT COUNT(*) FROM sentences").fetchone()[0]
            assert unchanged == 2
