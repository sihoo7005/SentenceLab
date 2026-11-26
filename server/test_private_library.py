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


def test_email_invitation_requires_verified_google_login(monkeypatch):
    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "test.sqlite3"
        monkeypatch.setattr(backend, "CLIENT_ID", "test-web-client")
        with TestClient(backend.app) as client:
            with backend.db() as conn:
                for set_id in ("ybm-english-ii-lesson-2", "ybm-english-ii-lesson-4"):
                    conn.execute(
                        "INSERT INTO curated_invites(set_id,email) VALUES (?,?)",
                        (set_id, "private@example.com"),
                    )

            def login(sub, email, verified):
                nonce = client.post("/v1/auth/challenge").json()["nonce"]
                monkeypatch.setattr(
                    backend.google_id_token,
                    "verify_oauth2_token",
                    lambda _token, _request, _client_id: {
                        "sub": sub, "nonce": nonce, "email": email, "email_verified": verified
                    },
                )
                response = client.post(
                    "/v1/auth/google",
                    json={"id_token": "dummy-token-for-test", "nonce": nonce},
                )
                assert response.status_code == 200
                return {"Authorization": "Bearer " + response.json()["session_token"]}

            not_verified = login("sub-a", "private@example.com", False)
            assert client.get("/v1/library", headers=not_verified).json() == {"sets": []}
            with backend.db() as conn:
                assert conn.execute(
                    "SELECT email FROM users WHERE google_sub=?", ("sub-a",)
                ).fetchone()["email"] is None

            other = login("sub-b", "another@example.com", True)
            assert client.get("/v1/library", headers=other).json() == {"sets": []}
            with backend.db() as conn:
                assert conn.execute(
                    "SELECT email FROM users WHERE google_sub=?", ("sub-b",)
                ).fetchone()["email"] == "another@example.com"

            authorized = login("sub-a", "private@example.com", True)
            with backend.db() as conn:
                assert conn.execute(
                    "SELECT email FROM users WHERE google_sub=?", ("sub-a",)
                ).fetchone()["email"] == "private@example.com"
            visible = client.get("/v1/library", headers=authorized).json()["sets"]
            assert {item["id"] for item in visible} == {
                "ybm-english-ii-lesson-2", "ybm-english-ii-lesson-4"
            }
            with backend.db() as conn:
                assert conn.execute("SELECT COUNT(*) FROM curated_invites").fetchone()[0] == 0
                assert conn.execute("SELECT COUNT(*) FROM curated_grants").fetchone()[0] == 2
            assert client.get("/v1/library", headers=other).json() == {"sets": []}


def test_existing_database_migrates_email_without_erasing_user():
    import sqlite3

    with TemporaryDirectory() as temp:
        backend.DB_PATH = Path(temp) / "existing.sqlite3"
        connection = sqlite3.connect(backend.DB_PATH)
        connection.execute(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, google_sub TEXT NOT NULL UNIQUE, "
            "revision INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"
        )
        connection.execute(
            "INSERT INTO users(id,google_sub,revision,created_at) VALUES (5,'sub-keep',7,123)"
        )
        connection.commit()
        connection.close()

        backend.init_db()
        with backend.db() as conn:
            stored = conn.execute(
                "SELECT id,google_sub,email,revision,created_at FROM users WHERE id=5"
            ).fetchone()
            assert dict(stored) == {
                "id": 5, "google_sub": "sub-keep", "email": None,
                "revision": 7, "created_at": 123,
            }
