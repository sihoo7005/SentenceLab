"""Access-controlled private library tests. These do not load textbook content."""
from pathlib import Path
from tempfile import TemporaryDirectory

import app as backend
from fastapi.testclient import TestClient


def test_private_library_denies_unlisted_users():
    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "test.sqlite3"
        with TestClient(backend.app) as client:
            with backend.db() as conn:
                conn.execute("INSERT INTO users(google_sub,created_at) VALUES (?,0)", ("a",))
                conn.execute("INSERT INTO users(google_sub,created_at) VALUES (?,0)", ("b",))
                user_ids = [r["id"] for r in conn.execute("SELECT id FROM users ORDER BY id")]
                tokens = ["a" * 64, "b" * 64]
                for token, uid in zip(tokens, user_ids):
                    conn.execute(
                        "INSERT INTO sessions(token_hash,user_id,expires_at) VALUES (?,?,?)",
                        (backend.digest(token), uid, 9999999999),
                    )
                assert conn.execute("SELECT COUNT(*) FROM curated_sets").fetchone()[0] == 2
                assert conn.execute("SELECT COUNT(*) FROM curated_sentences").fetchone()[0] == 0
            assert client.get("/v1/library").status_code == 401
            headers_a = {"Authorization": "Bearer " + tokens[0]}
            headers_b = {"Authorization": "Bearer " + tokens[1]}
            assert client.get("/v1/library", headers=headers_a).json() == {"sets": []}
            assert client.get("/v1/library", headers=headers_b).json() == {"sets": []}

            with backend.db() as conn:
                conn.execute(
                    "INSERT INTO curated_grants(set_id,user_id) VALUES (?,?)",
                    ("ybm-english-ii-lesson-2", user_ids[0]),
                )
            visible = client.get("/v1/library", headers=headers_a).json()["sets"]
            assert len(visible) == 1 and visible[0]["id"] == "ybm-english-ii-lesson-2"
            assert visible[0]["cards"] == []
            assert client.get("/v1/library", headers=headers_b).json() == {"sets": []}

            # Personal snapshots remain separate from shared sets.
            assert client.get("/v1/snapshot", headers=headers_a).json()["sets"] == []
