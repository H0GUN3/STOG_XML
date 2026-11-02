import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

credential = os.environ["STOG_FIXTURE_CREDENTIAL"]

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") != "Bearer " + credential:
            self.respond(401, {})
        elif self.path == "/token-info":
            self.respond(200, {"id": 991001, "app_id": 12345, "expires_in": 3600})
        elif self.path == "/user-info":
            self.respond(200, {"id": 991001})
        else:
            self.respond(404, {})

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
