"""Regression tests for the owner-authorized, idempotent set-copy endpoint."""
import tempfile
import time
import unittest
from pathlib import Path

from fastapi.testclient import TestClient
import app as service


class CopySetsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous_db = service.DB_PATH
        self.previous_source = service.COPY_SOURCE_EMAIL
        service.DB_PATH = Path(self.tmp.name) / "data.sqlite3"
        service.COPY_SOURCE_EMAIL = "source@example.com"
        service.init_db()
        self.actor = 1
        service.app.dependency_overrides[service.session_user] = lambda: self.actor
        self.client = TestClient(service.app)
        with service.db() as con:
            for number, email in enumerate((
                "source@example.com", "recipient@example.com", "unrelated@example.com"
            ), start=1):
                con.execute(
                    "INSERT INTO users(id,google_sub,email,revision,created_at)"
                    " VALUES (?,?,?,?,?)",
                    (number, f"google-{number}", email, 0, int(time.time())),
                )
            con.execute(
                "INSERT INTO sets(id,user_id,title,position) VALUES (?,?,?,?)",
                ("src-set", 1, "영어 지문", 0)
            )
            con.execute(
                "INSERT INTO sentences (id,set_id,english,korean,chunks_json,"
                "attempts,correct,needs_review,completed,position)"
                " VALUES (?,?,?,?,?,?,?,?,?,?)",
                ("src-card", "src-set", "Hello world.", "안녕.",
                 '["Hello","world."]', 2, 1, 1, 1, 0),
            )
            con.execute(
                "INSERT INTO sets(id,user_id,title,position) VALUES (?,?,?,?)",
                ("existing", 2, "기존 문장", 0),
            )
            con.execute(
                "INSERT INTO sentences (id,set_id,english,korean,chunks_json,"
                "attempts,correct,needs_review,completed,position)"
                " VALUES (?,?,?,?,?,?,?,?,?,?)",
                ("old-card", "existing", "Goodbye.", "안녕히.",
                 '["Goodbye."]', 4, 2, 0, 1, 0),
            )

    def tearDown(self):
        self.client.close()
        service.app.dependency_overrides.clear()
        service.DB_PATH = self.previous_db
        service.COPY_SOURCE_EMAIL = self.previous_source
        self.tmp.cleanup()

    def test_source_owner_may_copy_without_overwriting_target(self):
        self.assertTrue(
            self.client.get("/v1/me").json()["can_copy_sets"]
        )
        response = self.client.post("/v1/sets/copy", json={
            "recipient_email": "recipient@example.com"
        })
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(1, response.json()["copied_sets"])
        self.assertEqual(1, response.json()["copied_sentences"])
        with service.db() as con:
            rows = con.execute(
                "SELECT title FROM sets WHERE user_id=2 ORDER BY position"
            ).fetchall()
            self.assertEqual(["기존 문장", "영어 지문"], [r["title"] for r in rows])
            original = con.execute("SELECT * FROM sentences WHERE id='old-card'").fetchone()
            self.assertEqual(4, original["attempts"])
            transferred = con.execute(
                "SELECT s.* FROM sentences s JOIN sets g ON g.id=s.set_id "
                "WHERE g.user_id=2 AND g.title='영어 지문'"
            ).fetchone()
            self.assertEqual(("Hello world.", "안녕.", 2, 1, 1, 1),
                (transferred["english"], transferred["korean"],
                 transferred["attempts"], transferred["correct"],
                 transferred["needs_review"], transferred["completed"]))
            self.assertEqual(1, con.execute("SELECT revision FROM users WHERE id=2").fetchone()[0])

        again = self.client.post("/v1/sets/copy", json={
            "recipient_email": "recipient@example.com"
        })
        self.assertEqual(200, again.status_code)
        self.assertEqual(0, again.json()["copied_sets"])
        self.assertEqual(1, again.json()["skipped_sets"])
        with service.db() as con:
            self.assertEqual(1, con.execute("SELECT revision FROM users WHERE id=2").fetchone()[0])
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM sets WHERE user_id=1").fetchone()[0])

    def test_unauthorized_account_cannot_copy_another_users_data(self):
        self.actor = 3
        self.assertFalse(self.client.get("/v1/me").json()["can_copy_sets"])
        denied = self.client.post("/v1/sets/copy", json={
            "recipient_email": "recipient@example.com"
        })
        self.assertEqual(403, denied.status_code)
        with service.db() as con:
            self.assertEqual(1, con.execute("SELECT COUNT(*) FROM sets WHERE user_id=2").fetchone()[0])

    def test_missing_recipient_and_self_copy_are_rejected(self):
        a = self.client.post("/v1/sets/copy", json={
            "recipient_email": "missing@example.com"
        })
        b = self.client.post("/v1/sets/copy", json={
            "recipient_email": "source@example.com"
        })
        c = self.client.post("/v1/sets/copy", json={
            "recipient_email": "not an email"
        })
        self.assertEqual(404, a.status_code)
        self.assertEqual(400, b.status_code)
        self.assertEqual(422, c.status_code)

    def test_different_content_with_same_title_gets_a_separate_copy(self):
        with service.db() as con:
            con.execute(
                "UPDATE sets SET title='영어 지문' WHERE id='existing'"
            )
        result = self.client.post("/v1/sets/copy", json={
            "recipient_email": "recipient@example.com"
        })
        self.assertEqual(200, result.status_code)
        with service.db() as con:
            titles = [r["title"] for r in con.execute(
                "SELECT title FROM sets WHERE user_id=2 ORDER BY position"
            )]
        self.assertEqual(["영어 지문", "영어 지문 (복사본)"], titles)

    def test_same_title_and_same_content_are_not_duplicated(self):
        with service.db() as con:
            con.execute("UPDATE sets SET title='영어 지문' WHERE id='existing'")
            con.execute(
                "UPDATE sentences SET english='Hello world.', korean='안녕.'"
                " WHERE id='old-card'"
            )
        result = self.client.post("/v1/sets/copy", json={
            "recipient_email": "recipient@example.com"
        })
        self.assertEqual(200, result.status_code)
        self.assertEqual(0, result.json()["copied_sets"])
        self.assertEqual(1, result.json()["skipped_sets"])


if __name__ == "__main__":
    unittest.main()
