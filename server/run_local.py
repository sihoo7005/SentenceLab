"""Start the SentenceLab API locally for testing, without public exposure."""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
VENV = HERE / ".venv" / "bin" / "python"
PID = HERE / "api.pid"
LOG = HERE / "api.log"
PUBLIC_CONFIG = HERE / "public-config.json"
HOST = "127.0.0.1"
PORT = 8765


def running() -> bool:
    try:
        with socket.create_connection((HOST, PORT), timeout=0.5):
            return True
    except OSError:
        return False


def health() -> bool:
    try:
        with urllib.request.urlopen(f"http://{HOST}:{PORT}/health", timeout=1) as response:
            return response.status == 200 and response.read(256) == b'{"ok":true}'
    except (OSError, urllib.error.URLError):
        return False


def main() -> int:
    if running():
        print("port_in_use=true")
        print("health=" + str(health()).lower())
        return 0 if health() else 1
    oldmask = os.umask(0o077)
    try:
        process_env = os.environ.copy()
        if PUBLIC_CONFIG.is_file():
            oauth = json.loads(PUBLIC_CONFIG.read_text(encoding="utf-8"))
            client_id = oauth.get("google_web_client_id", "").strip()
            if client_id:
                process_env["GOOGLE_WEB_CLIENT_ID"] = client_id
        with LOG.open("ab") as log:
            process = subprocess.Popen(
                [str(VENV), "-m", "uvicorn", "app:app", "--host", HOST,
                 "--port", str(PORT), "--workers", "1", "--no-access-log"],
                cwd=HERE,
                stdin=subprocess.DEVNULL,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                close_fds=True,
                env=process_env,
            )
        PID.write_text(str(process.pid) + "\n")
        for _ in range(18):
            if health():
                print("health=ok")
                print("port=" + str(PORT))
                print("pid=" + str(process.pid))
                return 0
            if process.poll() is not None:
                print("process_exit=" + str(process.returncode))
                return 1
            time.sleep(0.3)
        print("health=unavailable")
        return 1
    finally:
        os.umask(oldmask)


if __name__ == "__main__":
    sys.exit(main())
