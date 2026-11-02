import json
import os
import re
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ["STOG_FIXTURE_CREDENTIAL"]
SUBJECT = os.environ["STOG_FIXTURE_SUBJECT"]
TRIP = os.environ["STOG_FIXTURE_TRIP"]
ITEM = os.environ["STOG_FIXTURE_ITEM"]
PLACE = os.environ["STOG_FIXTURE_PLACE"]
NONMEMBER_TRIP = os.environ["STOG_FIXTURE_NONMEMBER_TRIP"]
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
if not SUBJECT.isdigit() or SUBJECT.startswith("0") or any(not UUID_RE.match(v) for v in (TRIP, ITEM, PLACE, NONMEMBER_TRIP)):
    raise SystemExit("invalid generated fixture identifiers")

lock = threading.Lock()
counts = {"token_info": 0, "user_info": 0, "seed": 0, "expire": 0}

def sql(statement):
    process = subprocess.run(
        ["wsl.exe", "--", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-Atq", "-h", "127.0.0.1",
         "-U", "stog_backend_app", "-d", "stog_backend_dev", "-f", "-"],
        input=statement, text=True, capture_output=True, check=False)
    if process.returncode != 0:
        raise RuntimeError("fixture database operation failed")
    return process.stdout.strip()

def seed():
    result = sql(f"""
BEGIN;
DO $$
DECLARE fixture_user uuid;
BEGIN
  SELECT user_id INTO STRICT fixture_user FROM stog_app.auth_identity
    WHERE provider='KAKAO' AND subject='{SUBJECT}';
  IF EXISTS (SELECT 1 FROM stog_app.trip WHERE id IN ('{TRIP}'::uuid,'{NONMEMBER_TRIP}'::uuid))
     OR EXISTS (SELECT 1 FROM stog_app.trip_item WHERE id='{ITEM}'::uuid)
     OR EXISTS (SELECT 1 FROM stog_app.place WHERE id='{PLACE}'::uuid) THEN
    RAISE EXCEPTION 'fixture identifier collision';
  END IF;
  INSERT INTO stog_app.place(id) VALUES ('{PLACE}');
  INSERT INTO stog_app.trip(id,start_date,end_date,plan_version)
    VALUES ('{TRIP}', current_date, current_date + 1, 13),
           ('{NONMEMBER_TRIP}', current_date, current_date + 1, 2);
  INSERT INTO stog_app.trip_member(trip_id,user_id,role) VALUES ('{TRIP}',fixture_user,'ADMIN');
  INSERT INTO stog_app.trip_item(id,trip_id,place_id,created_by_user_id)
    VALUES ('{ITEM}','{TRIP}','{PLACE}',fixture_user);
END $$;
COMMIT;
SELECT 'seeded';
""")
    if result != "seeded":
        raise RuntimeError("fixture seed acknowledgement missing")

def expire_access():
    result = sql(f"""
WITH owned AS (
  SELECT user_id FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='{SUBJECT}'
), changed AS (
  UPDATE stog_app.auth_session SET access_expires_at=clock_timestamp()-interval '1 second'
  WHERE user_id=(SELECT user_id FROM owned) AND revoked_at IS NULL RETURNING id
)
SELECT count(*) FROM changed;
""")
    if result != "1":
        raise RuntimeError("expected exactly one fixture access session")

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/facts":
            with lock: value = dict(counts)
            self.respond(200, value)
        elif self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.respond(401, {})
        elif self.path == "/token-info":
            with lock: counts["token_info"] += 1
            self.respond(200, {"id": int(SUBJECT), "app_id": 12345, "expires_in": 3600})
        elif self.path == "/user-info":
            with lock: counts["user_info"] += 1
            self.respond(200, {"id": int(SUBJECT)})
        else:
            self.respond(404, {})

    def do_POST(self):
        try:
            if self.path == "/seed":
                seed()
                with lock: counts["seed"] += 1
                self.empty(204)
            elif self.path == "/expire-access":
                expire_access()
                with lock: counts["expire"] += 1
                self.empty(204)
            else:
                self.respond(404, {})
        except Exception:
            self.respond(500, {"error": "fixture operation failed"})

    def empty(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def respond(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass

server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print("READY " + str(server.server_address[1]), flush=True)
server.serve_forever()
