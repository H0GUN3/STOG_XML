import http.client
import json
import os
import re
import socket
import socketserver
import subprocess
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MEMBER_TOKEN = os.environ["STOG_FIXTURE_MEMBER_CREDENTIAL"]
MEMBER_UI_TOKEN = os.environ["STOG_FIXTURE_OWNER_UI_CREDENTIAL"]
NONMEMBER_TOKEN = os.environ["STOG_FIXTURE_NONMEMBER_CREDENTIAL"]
MEMBER_SUBJECT = os.environ["STOG_FIXTURE_MEMBER_SUBJECT"]
NONMEMBER_SUBJECT = os.environ["STOG_FIXTURE_NONMEMBER_SUBJECT"]
DB_ROLE = os.environ["STOG_FIXTURE_DB_ROLE"]
DB_SCHEMA = os.environ["STOG_FIXTURE_DB_SCHEMA"]
DB_PASSWORD = os.environ["STOG_FIXTURE_DB_PASSWORD"]
if not re.fullmatch(r"stog_mvp_trip_[0-9a-f]{32}", DB_ROLE) or DB_SCHEMA != DB_ROLE:
    raise SystemExit("invalid generated database fixture")
if not MEMBER_SUBJECT.isdigit() or not NONMEMBER_SUBJECT.isdigit() or MEMBER_SUBJECT == NONMEMBER_SUBJECT:
    raise SystemExit("invalid generated fixture subjects")

lock = threading.Lock()
facts = {"member_token_info": 0, "member_user_info": 0, "nonmember_token_info": 0,
         "nonmember_user_info": 0, "create_requests": 0, "create_responses_dropped": 0,
         "refresh_expirations": 0, "id_only_insertions": 0, "backend_responses_framed": 0,
         "backend_content_length_responses": 0, "backend_chunked_responses": 0,
         "cover_statuses": [], "cover_error_codes": [], "drop_next_cover_response": False,
         "dropped_cover_statuses": [], "denied_cover_probes": 0, "denied_cover_probe_errors": []}

def sql(statement):
    environment = dict(os.environ)
    environment["PGPASSWORD"] = DB_PASSWORD
    parts = [part for part in environment.get("WSLENV", "").split(":") if part and part.split("/")[0] != "PGPASSWORD"]
    environment["WSLENV"] = ":".join(parts + ["PGPASSWORD"])
    process = subprocess.run([
        "wsl.exe", "--", "psql", "-X", "-v", "ON_ERROR_STOP=1", "-Atq", "-h", "127.0.0.1",
        "-U", DB_ROLE, "-d", "stog_backend_test", "-f", "-"
    ], input=statement, text=True, capture_output=True, check=False, env=environment)
    if process.returncode != 0:
        raise RuntimeError("fixture database operation failed")
    return process.stdout.strip()

def identity(headers):
    bearer = headers.get("Authorization", "")
    if bearer == "Bearer " + MEMBER_TOKEN or bearer == "Bearer " + MEMBER_UI_TOKEN:
        return "member", MEMBER_SUBJECT
    if bearer == "Bearer " + NONMEMBER_TOKEN:
        return "nonmember", NONMEMBER_SUBJECT
    return None, None

class ProviderHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/facts":
            with lock:
                value = dict(facts)
            self.respond(200, value)
            return
        kind, subject = identity(self.headers)
        if kind is None:
            self.respond(401, {})
        elif self.path == "/token-info":
            with lock: facts[kind + "_token_info"] += 1
            self.respond(200, {"id": int(subject), "app_id": 12345, "expires_in": 3600})
        elif self.path == "/user-info":
            with lock: facts[kind + "_user_info"] += 1
            self.respond(200, {"id": int(subject)})
        else:
            self.respond(404, {})

    def do_POST(self):
        try:
            if self.path == "/probe-denied-cover":
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length))
                path = payload.get("path", "")
                bearer = payload.get("bearer", "")
                if not re.fullmatch(r"/api/trips/[0-9a-f-]{36}/covers/[0-9a-f-]{36}/(file|thumbnail)", path) or not bearer:
                    self.respond(400, {})
                    return
                connection = http.client.HTTPConnection("127.0.0.1", 8080, timeout=15)
                try:
                    connection.request("GET", path, headers={"Authorization": "Bearer " + bearer, "Accept": "image/jpeg"})
                    response = connection.getresponse()
                    body = response.read(1048577)
                    code = None
                    try: code = json.loads(body).get("error", {}).get("code")
                    except Exception: pass
                    with lock: facts["denied_cover_probes"] += 1
                    self.respond(200, {"status": response.status, "code": code})
                except Exception as exception:
                    with lock: facts["denied_cover_probe_errors"].append(type(exception).__name__ + ":" + str(exception)[:120])
                    self.respond(200, {"status": -1, "code": "PROBE_FAILED"})
                finally: connection.close()
                return
            if self.path == "/drop-next-cover-response":
                with lock:
                    if facts["drop_next_cover_response"]: raise RuntimeError("cover response drop already armed")
                    facts["drop_next_cover_response"] = True
                self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers()
                return
            if self.path == "/expire-member-access":
                changed = sql(f"""
WITH fixture_user AS (
  SELECT user_id FROM {DB_SCHEMA}.auth_identity WHERE provider='KAKAO' AND subject='{MEMBER_SUBJECT}'
), changed AS (
  UPDATE {DB_SCHEMA}.auth_session SET access_expires_at=clock_timestamp()-interval '1 second'
  WHERE user_id=(SELECT user_id FROM fixture_user) AND revoked_at IS NULL RETURNING id
)
SELECT count(*) FROM changed;
""")
                if changed != "1": raise RuntimeError("expected one member session")
                with lock: facts["refresh_expirations"] += 1
            else:
                match = re.fullmatch(r"/insert-id-only-basket/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", self.path)
                if match is None:
                    self.respond(404, {})
                    return
                trip_id = match.group(1)
                place_id = str(uuid.uuid4())
                item_id = str(uuid.uuid4())
                changed = sql(f"""
BEGIN;
WITH fixture_user AS (
  SELECT user_id FROM {DB_SCHEMA}.auth_identity WHERE provider='KAKAO' AND subject='{MEMBER_SUBJECT}'
), owned_trip AS (
  SELECT t.id, u.user_id FROM {DB_SCHEMA}.trip t
  JOIN {DB_SCHEMA}.trip_member tm ON tm.trip_id=t.id
  CROSS JOIN fixture_user u
  WHERE t.id='{trip_id}'::uuid AND tm.user_id=u.user_id
), inserted_place AS (
  INSERT INTO {DB_SCHEMA}.place(id) SELECT '{place_id}'::uuid FROM owned_trip RETURNING id
), inserted_item AS (
  INSERT INTO {DB_SCHEMA}.trip_item(id,trip_id,place_id,created_by_user_id)
  SELECT '{item_id}'::uuid,id,'{place_id}'::uuid,user_id FROM owned_trip RETURNING id
), changed AS (
  UPDATE {DB_SCHEMA}.trip SET plan_version=plan_version+1
  WHERE id IN (SELECT id FROM owned_trip) AND EXISTS (SELECT 1 FROM inserted_place) AND EXISTS (SELECT 1 FROM inserted_item)
  RETURNING id
)
SELECT count(*) FROM changed;
COMMIT;
""")
                if changed != "1": raise RuntimeError("expected one member-owned trip")
                with lock: facts["id_only_insertions"] += 1
            self.send_response(204); self.send_header("Content-Length", "0"); self.end_headers()
        except Exception:
            self.respond(500, {"error": "fixture operation failed"})

    def respond(self, status, value):
        body = json.dumps(value, separators=(",", ":")).encode()
        self.send_response(status); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, fmt, *args): pass

class LossProxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

class ProxyHandler(socketserver.BaseRequestHandler):
    def handle(self):
        client = self.request
        client.settimeout(15)
        request = read_http_request(client)
        first = request.split(b"\r\n", 1)[0].decode("ascii", "strict")
        method, path, _ = first.split(" ", 2)
        drop = False
        cover_request = method == "POST" and re.fullmatch(r"/api/trips/[0-9a-f-]{36}/cover", path)
        if cover_request:
            with lock: facts["create_requests"] += 1
        with socket.create_connection(("127.0.0.1", 8080), timeout=15) as backend:
            backend.sendall(request)
            response, framing, status, response_body = read_http_response(backend, method)
        with lock:
            facts["backend_responses_framed"] += 1
            if framing == "content-length": facts["backend_content_length_responses"] += 1
            if framing == "chunked": facts["backend_chunked_responses"] += 1
            if cover_request:
                facts["cover_statuses"].append(status)
                if status >= 400:
                    try: facts["cover_error_codes"].append(json.loads(response_body).get("error", {}).get("code"))
                    except Exception: facts["cover_error_codes"].append("UNPARSEABLE")
                if facts["drop_next_cover_response"] and status in (200, 201):
                    facts["drop_next_cover_response"] = False
                    facts["create_responses_dropped"] += 1
                    facts["dropped_cover_statuses"].append(status)
                    drop = True
        if not drop:
            client.sendall(response)

def read_http_response(connection, request_method):
    response = http.client.HTTPResponse(connection, method=request_method)
    response.begin()
    framing = "chunked" if response.chunked else (
        "content-length" if response.length is not None else "no-body")
    if response.length is not None and response.length > 1048576:
        raise ConnectionError("backend response exceeded fixture bound")
    body = response.read(1048577)
    if len(body) > 1048576:
        raise ConnectionError("backend response exceeded fixture bound")
    hop_by_hop = {"connection", "content-length", "keep-alive", "proxy-authenticate",
                  "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"}
    reason = response.reason or ""
    lines = [f"HTTP/1.1 {response.status} {reason}\r\n".encode("iso-8859-1")]
    for name, value in response.getheaders():
        if name.lower() not in hop_by_hop:
            lines.append(f"{name}: {value}\r\n".encode("iso-8859-1"))
    lines.append(f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode("ascii"))
    response.close()
    return b"".join(lines) + body, framing, response.status, body

def read_http_request(connection):
    data = bytearray()
    while b"\r\n\r\n" not in data:
        chunk = connection.recv(4096)
        if not chunk: raise ConnectionError("request ended before headers")
        data.extend(chunk)
        if len(data) > 65536: raise ConnectionError("headers too large")
    head, body = bytes(data).split(b"\r\n\r\n", 1)
    match = re.search(br"(?im)^Content-Length:\s*(\d+)\s*$", head)
    length = int(match.group(1)) if match else 0
    while len(body) < length:
        chunk = connection.recv(min(65536, length - len(body)))
        if not chunk: raise ConnectionError("request ended before body")
        body += chunk
    head = re.sub(br"(?im)^Connection:\s*[^\r\n]+$", b"Connection: close", head)
    if not re.search(br"(?im)^Connection:", head):
        head += b"\r\nConnection: close"
    return head + b"\r\n\r\n" + body[:length]

provider = ThreadingHTTPServer(("127.0.0.1", 0), ProviderHandler)
proxy = LossProxy(("127.0.0.1", 0), ProxyHandler)
threading.Thread(target=provider.serve_forever, daemon=True).start()
threading.Thread(target=proxy.serve_forever, daemon=True).start()
print(f"READY {provider.server_address[1]} {proxy.server_address[1]}", flush=True)
threading.Event().wait()
