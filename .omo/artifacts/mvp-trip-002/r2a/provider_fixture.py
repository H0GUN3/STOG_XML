import json, os, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
owner_token=os.environ['STOG_R2A_OWNER_CREDENTIAL']; owner_ui_token=os.environ['STOG_R2A_OWNER_UI_CREDENTIAL']; owner_member_ui_token=os.environ['STOG_R2A_OWNER_MEMBER_UI_CREDENTIAL']; member_token=os.environ['STOG_R2A_MEMBER_CREDENTIAL']
owner_subject=os.environ['STOG_R2A_OWNER_SUBJECT']; member_subject=os.environ['STOG_R2A_MEMBER_SUBJECT']
if owner_subject==member_subject or not owner_subject.isdigit() or not member_subject.isdigit(): raise SystemExit('invalid fixture subjects')
lock=threading.Lock(); facts={'owner_token_info':0,'owner_user_info':0,'member_token_info':0,'member_user_info':0}
def identity(headers):
 b=headers.get('Authorization','')
 if b in ('Bearer '+owner_token,'Bearer '+owner_ui_token,'Bearer '+owner_member_ui_token):return 'owner',owner_subject
 if b=='Bearer '+member_token:return 'member',member_subject
 return None,None
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  if self.path=='/facts': return self.respond(200,dict(facts))
  kind,subject=identity(self.headers)
  if kind is None:return self.respond(401,{})
  if self.path=='/token-info':
   with lock:facts[kind+'_token_info']+=1
   return self.respond(200,{'id':int(subject),'app_id':12345,'expires_in':3600})
  if self.path=='/user-info':
   with lock:facts[kind+'_user_info']+=1
   return self.respond(200,{'id':int(subject)})
  self.respond(404,{})
 def respond(self,status,value):
  body=json.dumps(value,separators=(',',':')).encode();self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
 def log_message(self,fmt,*args):pass
server=ThreadingHTTPServer(('127.0.0.1',0),Handler);print('READY '+str(server.server_address[1]),flush=True);server.serve_forever()
