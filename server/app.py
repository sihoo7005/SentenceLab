"""SentenceLab API: Google identity verification and SQLite-backed cloud snapshots.

Run behind an HTTPS reverse proxy. The application listens on 127.0.0.1 only.
Never commit credentials or the runtime database.
"""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import sqlite3
import time
import re
import uuid
from contextlib import contextmanager
from pathlib import Path
from threading import RLock
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, status
from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token
from pydantic import BaseModel, Field, field_validator

app = FastAPI(title="SentenceLab API", docs_url=None, redoc_url=None, openapi_url=None)
DB_PATH = Path(os.getenv("SENTENCELAB_DB", str(Path.home() / "sentencelab-data" / "sentencelab.sqlite3")))
CLIENT_ID = os.getenv("GOOGLE_WEB_CLIENT_ID", "")
_lock = RLock()
SESSION_TTL = 30 * 24 * 3600
CHALLENGE_TTL = 5 * 60
MAX_SETS = 200
MAX_CARDS = 20000
# Only this Google-verified account may send copies to other SentenceLab accounts.
COPY_SOURCE_EMAIL = os.getenv("SENTENCELAB_COPY_SOURCE_EMAIL", "").strip().lower()


@contextmanager
def db():
    DB_PATH.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with _lock:
        conn = sqlite3.connect(DB_PATH, timeout=10, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA busy_timeout=10000")
        try:
            yield conn
        finally:
            conn.close()


def init_db():
    # Create data with owner-only permissions; the DB is not a public web asset.
    old_umask = os.umask(0o077)
    try:
        with db() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY,
                    google_sub TEXT NOT NULL UNIQUE,
                    email TEXT,
                    revision INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    expires_at INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS sessions_expiration ON sessions(expires_at);
                CREATE TABLE IF NOT EXISTS login_challenges (
                    nonce_hash TEXT PRIMARY KEY,
                    expires_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS sets (
                    id TEXT PRIMARY KEY,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    title TEXT NOT NULL,
                    position INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS sets_user_order ON sets(user_id,position);
                CREATE TABLE IF NOT EXISTS sentences (
                    id TEXT PRIMARY KEY,
                    set_id TEXT NOT NULL REFERENCES sets(id) ON DELETE CASCADE,
                    english TEXT NOT NULL,
                    korean TEXT NOT NULL,
                    chunks_json TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    correct INTEGER NOT NULL DEFAULT 0,
                    needs_review INTEGER NOT NULL DEFAULT 0,
                    completed INTEGER NOT NULL DEFAULT 0,
                    mode_mask INTEGER NOT NULL DEFAULT 0,
                    position INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS sentences_set_order ON sentences(set_id,position);
                CREATE TABLE IF NOT EXISTS curated_sets (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    source_url TEXT NOT NULL DEFAULT '',
                    position INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS curated_sentences (
                    id TEXT PRIMARY KEY,
                    set_id TEXT NOT NULL REFERENCES curated_sets(id) ON DELETE CASCADE,
                    english TEXT NOT NULL,
                    korean TEXT NOT NULL,
                    chunks_json TEXT NOT NULL,
                    position INTEGER NOT NULL
                );
                CREATE INDEX IF NOT EXISTS curated_sentences_order ON curated_sentences(set_id,position);
                CREATE TABLE IF NOT EXISTS curated_grants (
                    set_id TEXT NOT NULL REFERENCES curated_sets(id) ON DELETE CASCADE,
                    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    PRIMARY KEY (set_id,user_id)
                );
                CREATE INDEX IF NOT EXISTS curated_grants_by_user ON curated_grants(user_id);
                CREATE TABLE IF NOT EXISTS curated_invites (
                    set_id TEXT NOT NULL REFERENCES curated_sets(id) ON DELETE CASCADE,
                    email TEXT NOT NULL,
                    PRIMARY KEY (set_id,email)
                );
            """)
            # Preserve existing study rows while adding the completion flag.
            sentence_fields = {row["name"] for row in conn.execute("PRAGMA table_info(sentences)")}
            if "completed" not in sentence_fields:
                conn.execute("ALTER TABLE sentences ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
                # Older clients recorded completed attempts without a separate flag.
                conn.execute("UPDATE sentences SET completed=1 WHERE attempts>0")
            if "mode_mask" not in sentence_fields:
                conn.execute("ALTER TABLE sentences ADD COLUMN mode_mask INTEGER NOT NULL DEFAULT 0")
            # Migrate existing accounts without changing stable IDs or study data.
            user_fields = {row["name"] for row in conn.execute("PRAGMA table_info(users)")}
            if "email" not in user_fields:
                conn.execute("ALTER TABLE users ADD COLUMN email TEXT")
            conn.execute("CREATE INDEX IF NOT EXISTS users_email_lookup ON users(email)")
            # Metadata only. No copyrighted textbook text is populated here.
            for set_id, title, order in [
                ("ybm-english-ii-lesson-2", "YBM 영어II 2과", 0),
                ("ybm-english-ii-lesson-4", "YBM 영어II 4과", 1),
            ]:
                conn.execute(
                    "INSERT INTO curated_sets(id,title,source_url,position) VALUES (?,?,?,?) "
                    "ON CONFLICT(id) DO NOTHING",
                    (set_id, title, "", order),
                )
    finally:
        os.umask(old_umask)


@app.on_event("startup")
def startup():
    init_db()


def digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class GoogleLogin(BaseModel):
    id_token: str = Field(min_length=10, max_length=8192)
    nonce: str = Field(min_length=30, max_length=256)


class SentenceIn(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    english: str = Field(min_length=1, max_length=2000)
    korean: str = Field(default="", max_length=2000)
    chunks: list[str] = Field(max_length=100)
    attempts: int = Field(ge=0, le=1_000_000)
    correct: int = Field(ge=0, le=1_000_000)
    needsReview: bool
    completed: bool | None = None
    modeMask: int | None = Field(default=None, ge=0, le=15)

    @field_validator("chunks")
    @classmethod
    def verify_chunks(cls, parts):
        if not parts or any(not v or len(v) > 2000 for v in parts):
            raise ValueError("Invalid sentence chunks")
        return parts


class SetIn(BaseModel):
    id: str = Field(min_length=1, max_length=100)
    title: str = Field(min_length=1, max_length=200)
    cards: list[SentenceIn] = Field(max_length=MAX_CARDS)


class SnapshotIn(BaseModel):
    base_revision: int = Field(ge=0)
    sets: list[SetIn] = Field(max_length=MAX_SETS)


class CopySetsIn(BaseModel):
    recipient_email: str = Field(min_length=5, max_length=320)

    @field_validator("recipient_email")
    @classmethod
    def check_email(cls, value: str) -> str:
        value = value.strip().lower()
        if not re.fullmatch(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", value):
            raise ValueError("Invalid recipient email")
        return value


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/v1/auth/challenge")
def new_challenge():
    value = secrets.token_urlsafe(32)
    now = int(time.time())
    with db() as conn:
        conn.execute("DELETE FROM login_challenges WHERE expires_at < ?", (now,))
        conn.execute(
            "INSERT INTO login_challenges(nonce_hash,expires_at) VALUES (?,?)",
            (digest(value), now + CHALLENGE_TTL),
        )
    return {"nonce": value}


@app.post("/v1/auth/google")
def google_login(body: GoogleLogin):
    if not CLIENT_ID:
        raise HTTPException(503, "Google login is not configured")
    try:
        payload = google_id_token.verify_oauth2_token(
            body.id_token, google_requests.Request(), CLIENT_ID
        )
    except Exception as exc:
        # Do not include the token or Google exception text in a response.
        raise HTTPException(401, "Invalid Google ID token") from exc

    if payload.get("nonce") != body.nonce:
        raise HTTPException(401, "Invalid login challenge")
    sub = payload.get("sub")
    if not isinstance(sub, str) or not sub:
        raise HTTPException(401, "Missing Google subject")

    now = int(time.time())
    session_token = secrets.token_urlsafe(48)
    with db() as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            match = conn.execute(
                "SELECT nonce_hash FROM login_challenges WHERE nonce_hash=? AND expires_at>=?",
                (digest(body.nonce), now),
            ).fetchone()
            if match is None:
                raise HTTPException(401, "Expired or already used login challenge")
            conn.execute("DELETE FROM login_challenges WHERE nonce_hash=?", (digest(body.nonce),))
            conn.execute(
                "INSERT INTO users(google_sub,created_at) VALUES (?,?) "
                "ON CONFLICT(google_sub) DO NOTHING",
                (sub, now),
            )
            user_id = conn.execute(
                "SELECT id FROM users WHERE google_sub=?", (sub,)
            ).fetchone()["id"]
            # Only store an email that Google has confirmed for this identity.
            # Unverified or absent emails do not overwrite a saved address.
            verified_email = payload.get("email")
            if payload.get("email_verified") is True and isinstance(verified_email, str):
                verified_email = verified_email.strip().lower()
                if 0 < len(verified_email) <= 320 and "@" in verified_email:
                    conn.execute(
                        "UPDATE users SET email=? WHERE id=?",
                        (verified_email, user_id),
                    )
                    # Claim pending access by email, but keep grants bound to Google sub.
                    conn.execute(
                        "INSERT OR IGNORE INTO curated_grants(set_id,user_id) "
                        "SELECT set_id,? FROM curated_invites WHERE email=?",
                        (user_id, verified_email),
                    )
                    conn.execute(
                        "DELETE FROM curated_invites WHERE email=?",
                        (verified_email,),
                    )
            conn.execute(
                "INSERT INTO sessions(token_hash,user_id,expires_at) VALUES (?,?,?)",
                (digest(session_token), user_id, now + SESSION_TTL),
            )
            conn.execute("COMMIT")
        except BaseException:
            conn.execute("ROLLBACK")
            raise
    return {"session_token": session_token, "expires_at": now + SESSION_TTL}


def session_user(authorization: Annotated[str | None, Header()] = None) -> int:
    if authorization is None or not authorization.startswith("Bearer "):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Missing session")
    token = authorization[7:]
    if len(token) < 40 or len(token) > 150:
        raise HTTPException(401, "Invalid session")
    with db() as conn:
        row = conn.execute(
            "SELECT user_id FROM sessions WHERE token_hash=? AND expires_at>?",
            (digest(token), int(time.time())),
        ).fetchone()
    if row is None:
        raise HTTPException(401, "Session expired")
    return int(row["user_id"])


@app.post("/v1/auth/logout")
def logout(
    authorization: Annotated[str | None, Header()] = None,
    user_id: int = Depends(session_user),
):
    token = authorization[7:]
    with db() as conn:
        conn.execute("DELETE FROM sessions WHERE user_id=? AND token_hash=?", (user_id, digest(token)))
    return {"ok": True}


@app.get("/v1/me")
def account_identity(user_id: int = Depends(session_user)):
    # Stable per-account identifier for client-side sync lineage.
    with db() as conn:
        row = conn.execute("SELECT email FROM users WHERE id=?", (user_id,)).fetchone()
    return {
        "account_id": str(user_id),
        "can_copy_sets": bool(row and (row["email"] or "").lower() == COPY_SOURCE_EMAIL),
    }


@app.get("/v1/snapshot")
def get_snapshot(user_id: int = Depends(session_user)):
    with db() as conn:
        revision = conn.execute(
            "SELECT revision FROM users WHERE id=?", (user_id,)
        ).fetchone()["revision"]
        set_rows = conn.execute(
            "SELECT id,title FROM sets WHERE user_id=? ORDER BY position",
            (user_id,),
        ).fetchall()
        result = []
        for group in set_rows:
            sentence_rows = conn.execute(
                "SELECT * FROM sentences WHERE set_id=? ORDER BY position",
                (group["id"],),
            ).fetchall()
            cards = [{
                "id": s["id"],
                "english": s["english"],
                "korean": s["korean"],
                "chunks": json.loads(s["chunks_json"]),
                "attempts": s["attempts"],
                "correct": s["correct"],
                "needsReview": bool(s["needs_review"]),
                "completed": bool(s["completed"]),
                "modeMask": s["mode_mask"],
            } for s in sentence_rows]
            result.append({"id": group["id"], "title": group["title"], "cards": cards})
    return {"revision": revision, "sets": result}


@app.get("/v1/library")
def get_curated_library(user_id: int = Depends(session_user)):
    """Return only sets explicitly granted to the logged-in Google account."""
    with db() as conn:
        groups = conn.execute(
            "SELECT s.id,s.title FROM curated_sets AS s "
            "JOIN curated_grants AS g ON g.set_id=s.id "
            "WHERE g.user_id=? ORDER BY s.position,s.id",
            (user_id,),
        ).fetchall()
        result = []
        for group in groups:
            cards = conn.execute(
                "SELECT id,english,korean,chunks_json FROM curated_sentences "
                "WHERE set_id=? ORDER BY position,id",
                (group["id"],),
            ).fetchall()
            result.append({
                "id": group["id"],
                "title": group["title"],
                "cards": [{
                    "id": card["id"],
                    "english": card["english"],
                    "korean": card["korean"],
                    "chunks": json.loads(card["chunks_json"]),
                    "attempts": 0, "correct": 0, "needsReview": False, "completed": False,
                } for card in cards],
            })
    return {"sets": result}



@app.post("/v1/sets/copy")
def copy_sets(body: CopySetsIn, user_id: int = Depends(session_user)):
    """Copy only from the designated, authenticated account; never overwrite recipients."""
    with db() as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            source = conn.execute("SELECT email FROM users WHERE id=?", (user_id,)).fetchone()
            if source is None or (source["email"] or "").lower() != COPY_SOURCE_EMAIL:
                raise HTTPException(403, "Only the source account can copy its sets")
            recipients = conn.execute(
                "SELECT id FROM users WHERE lower(email)=?", (body.recipient_email,)
            ).fetchall()
            if not recipients:
                raise HTTPException(404, "Recipient must sign in to SentenceLab first")
            if len(recipients) != 1:
                raise HTTPException(422, "Recipient account is ambiguous")
            recipient_id = recipients[0]["id"]
            if recipient_id == user_id:
                raise HTTPException(400, "Cannot copy to the same account")

            originals = conn.execute(
                "SELECT id,title FROM sets WHERE user_id=? ORDER BY position,id",
                (user_id,),
            ).fetchall()
            destinations = conn.execute(
                "SELECT id,title FROM sets WHERE user_id=? ORDER BY position,id",
                (recipient_id,),
            ).fetchall()
            dest_titles = {row["title"] for row in destinations}
            signatures = {}
            for row in destinations:
                cards = conn.execute(
                    "SELECT english,korean FROM sentences WHERE set_id=? ORDER BY position,id",
                    (row["id"],),
                ).fetchall()
                signatures.setdefault(row["title"], set()).add(
                    tuple((card["english"], card["korean"]) for card in cards)
                )
            dest_cards = conn.execute(
                "SELECT COUNT(*) FROM sentences c JOIN sets s ON s.id=c.set_id "
                "WHERE s.user_id=?", (recipient_id,),
            ).fetchone()[0]

            prepared = []
            skipped = 0
            for origin in originals:
                copy_id = str(uuid.uuid5(
                    uuid.NAMESPACE_URL, f"sentencelab:copy:{user_id}:{recipient_id}:{origin['id']}"
                ))
                if any(existing["id"] == copy_id for existing in destinations):
                    skipped += 1
                    continue
                source_cards = conn.execute(
                    "SELECT * FROM sentences WHERE set_id=? ORDER BY position,id",
                    (origin["id"],),
                ).fetchall()
                signature = tuple((row["english"], row["korean"]) for row in source_cards)
                if signature in signatures.get(origin["title"], set()):
                    skipped += 1
                    continue

                title = origin["title"]
                if title in dest_titles:
                    # Different content with the same name should not be discarded or overwritten.
                    suffix = 1
                    while True:
                        extra = " (복사본)" if suffix == 1 else f" (복사본 {suffix})"
                        candidate = title[:200 - len(extra)] + extra
                        if candidate not in dest_titles:
                            title = candidate
                            break
                        suffix += 1
                dest_titles.add(title)
                signatures.setdefault(origin["title"], set()).add(signature)
                prepared.append((copy_id, title, source_cards))

            add_count = sum(len(cards) for _, _, cards in prepared)
            if len(destinations) + len(prepared) > MAX_SETS or dest_cards + add_count > MAX_CARDS:
                raise HTTPException(413, "Recipient has reached the storage limit")

            for index, (new_set_id, title, cards) in enumerate(prepared):
                conn.execute(
                    "INSERT INTO sets(id,user_id,title,position) VALUES (?,?,?,?)",
                    (new_set_id, recipient_id, title, len(destinations) + index),
                )
                for position, card in enumerate(cards):
                    new_card_id = str(uuid.uuid5(
                        uuid.NAMESPACE_URL,
                        f"sentencelab:copycard:{user_id}:{recipient_id}:{card['id']}"
                    ))
                    conn.execute(
                        "INSERT INTO sentences(id,set_id,english,korean,chunks_json,"
                        "attempts,correct,needs_review,completed,mode_mask,position) "
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        (new_card_id, new_set_id, card["english"], card["korean"],
                         card["chunks_json"], card["attempts"], card["correct"],
                         card["needs_review"], card["completed"], card["mode_mask"], position),
                    )
            if prepared:
                conn.execute(
                    "UPDATE users SET revision=revision+1 WHERE id=?", (recipient_id,)
                )
            conn.execute("COMMIT")
        except BaseException:
            conn.execute("ROLLBACK")
            raise
    return {
        "copied_sets": len(prepared),
        "copied_sentences": add_count,
        "skipped_sets": skipped,
    }


@app.put("/v1/snapshot")
def put_snapshot(body: SnapshotIn, user_id: int = Depends(session_user)):
    if sum(len(s.cards) for s in body.sets) > MAX_CARDS:
        raise HTTPException(413, "Too many sentences")
    set_ids = [s.id for s in body.sets]
    card_ids = [c.id for s in body.sets for c in s.cards]
    if len(set(set_ids)) != len(set_ids) or len(set(card_ids)) != len(card_ids):
        raise HTTPException(422, "Duplicate set or sentence IDs")

    with db() as conn:
        try:
            conn.execute("BEGIN IMMEDIATE")
            current = conn.execute(
                "SELECT revision FROM users WHERE id=?", (user_id,)
            ).fetchone()["revision"]
            if current != body.base_revision:
                raise HTTPException(409, "Revision conflict; download latest snapshot first")
            # Older APKs may omit 'completed'. Preserve existing completion in that case.
            prior_completion = {
                row["id"]: (bool(row["completed"]), row["mode_mask"])
                for row in conn.execute(
                    "SELECT s.id,s.completed,s.mode_mask FROM sentences AS s "
                    "JOIN sets AS g ON g.id=s.set_id WHERE g.user_id=?", (user_id,)
                )
            }
            conn.execute("DELETE FROM sets WHERE user_id=?", (user_id,))
            for i, group in enumerate(body.sets):
                conn.execute(
                    "INSERT INTO sets(id,user_id,title,position) VALUES (?,?,?,?)",
                    (group.id, user_id, group.title, i),
                )
                for j, card in enumerate(group.cards):
                    conn.execute(
                        """INSERT INTO sentences
                        (id,set_id,english,korean,chunks_json,attempts,correct,needs_review,completed,mode_mask,position)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                        (card.id, group.id, card.english, card.korean,
                         json.dumps(card.chunks, ensure_ascii=False), card.attempts,
                         card.correct, int(card.needsReview), int(card.completed if card.completed is not None
                         else prior_completion.get(card.id, (card.attempts > 0, 0))[0]),
                         card.modeMask if card.modeMask is not None else
                         (0 if card.attempts == 0 and not card.completed and not card.needsReview
                          else prior_completion.get(card.id, (False, 0))[1]), j),
                    )
            conn.execute("UPDATE users SET revision=revision+1 WHERE id=?", (user_id,))
            conn.execute("COMMIT")
        except BaseException:
            conn.execute("ROLLBACK")
            raise
    return {"revision": current + 1}


@app.delete("/v1/account")
def delete_account(user_id: int = Depends(session_user)):
    with db() as conn:
        conn.execute("DELETE FROM users WHERE id=?", (user_id,))
    return {"ok": True}
