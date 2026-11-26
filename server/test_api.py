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
