import http.client
import json
import os
import re
import socket
import socketserver
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ["STOG_FIXTURE_PROVIDER_CREDENTIAL"]
SUBJECT = os.environ["STOG_FIXTURE_SUBJECT"]
if not SUBJECT.isdigit():
    raise SystemExit("invalid subject")
lock = threading.Lock()
facts = {"token_info":0,"user_info":0,"search_requests":0,"save_requests":0,"save_response_drops":0,
         "dropped_status":None,"same_request_id":False,"same_expected_version":False,
         "same_place_id":False,"backend_responses_framed":0}
first_save = None

class Provider(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/facts":
            with lock: value=dict(facts)
            self.respond(200,value); return
        if self.headers.get("Authorization","") != "Bearer "+TOKEN:
            self.respond(401,{}); return
        if self.path == "/token-info":
            with lock: facts["token_info"] += 1
            self.respond(200,{"id":int(SUBJECT),"app_id":12345,"expires_in":3600})
        elif self.path == "/user-info":
            with lock: facts["user_info"] += 1
            self.respond(200,{"id":int(SUBJECT)})
        else: self.respond(404,{})
    def respond(self,status,value):
        body=json.dumps(value,separators=(",",":")).encode(); self.send_response(status); self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self,fmt,*args): pass

class Proxy(socketserver.ThreadingTCPServer):
    allow_reuse_address=True; daemon_threads=True

class ProxyHandler(socketserver.BaseRequestHandler):
    def handle(self):
        global first_save
        client=self.request; client.settimeout(20); request=read_request(client)
        head,body=request.split(b"\r\n\r\n",1); first=head.split(b"\r\n",1)[0].decode("ascii"); method,path,_=first.split(" ",2)
        save=method=="POST" and re.fullmatch(r"/api/trips/[0-9a-f-]{36}/trip-items",path) is not None
        if method == "GET" and path.startswith("/api/places?"):
            with lock: facts["search_requests"] += 1
        drop=False
        if save:
            payload=json.loads(body.decode("utf-8"))
            with lock:
                facts["save_requests"] += 1
                if first_save is None:
                    first_save=payload; facts["save_response_drops"] += 1; drop=True
                else:
                    facts["same_request_id"] = payload.get("request_id")==first_save.get("request_id")
                    facts["same_expected_version"] = payload.get("expected_plan_version")==first_save.get("expected_plan_version")
                    facts["same_place_id"] = payload.get("place_id")==first_save.get("place_id")
        with socket.create_connection(("127.0.0.1",8080),timeout=20) as backend:
            backend.sendall(request); response,status=read_response(backend,method)
        with lock: facts["backend_responses_framed"] += 1
        if drop:
            with lock: facts["dropped_status"]=status
        else: client.sendall(response)

def read_request(connection):
    data=bytearray()
    while b"\r\n\r\n" not in data:
        part=connection.recv(4096)
        if not part: raise ConnectionError("request headers ended")
        data.extend(part)
        if len(data)>65536: raise ConnectionError("headers too large")
    head,body=bytes(data).split(b"\r\n\r\n",1); match=re.search(br"(?im)^Content-Length:\s*(\d+)\s*$",head); length=int(match.group(1)) if match else 0
    while len(body)<length:
        part=connection.recv(min(65536,length-len(body)))
        if not part: raise ConnectionError("request body ended")
        body+=part
    head=re.sub(br"(?im)^Connection:\s*[^\r\n]+$",b"Connection: close",head)
    if not re.search(br"(?im)^Connection:",head): head+=b"\r\nConnection: close"
    return head+b"\r\n\r\n"+body[:length]

def read_response(connection,method):
    response=http.client.HTTPResponse(connection,method=method); response.begin(); body=response.read(1048577)
    if len(body)>1048576: raise ConnectionError("response too large")
    blocked={"connection","content-length","keep-alive","proxy-authenticate","proxy-authorization","te","trailer","transfer-encoding","upgrade"}
    lines=[f"HTTP/1.1 {response.status} {response.reason or ''}\r\n".encode("iso-8859-1")]
    for name,value in response.getheaders():
        if name.lower() not in blocked: lines.append(f"{name}: {value}\r\n".encode("iso-8859-1"))
    lines.append(f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n".encode()); status=response.status; response.close(); return b"".join(lines)+body,status

provider=ThreadingHTTPServer(("127.0.0.1",0),Provider); proxy=Proxy(("127.0.0.1",0),ProxyHandler)
threading.Thread(target=provider.serve_forever,daemon=True).start(); threading.Thread(target=proxy.serve_forever,daemon=True).start()
print(f"READY {provider.server_address[1]} {proxy.server_address[1]}",flush=True); threading.Event().wait()
