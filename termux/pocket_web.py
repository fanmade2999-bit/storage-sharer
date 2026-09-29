#!/usr/bin/env python3
import argparse
import json
import secrets
import subprocess
import time
import urllib.parse
from http import cookies
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import PurePosixPath

from pocket_client import (
    BASE_URI,
    content_bin,
    file_uri,
    files_uri,
    normalize,
    parse_rows,
    call,
)

MAX_BODY = 64 * 1024 * 1024
BROWSER_SESSIONS = {}
TTL = 30 * 60

INDEX = """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pocket Web</title>
<style>body{font-family:system-ui;max-width:900px;margin:2rem auto;padding:0 1rem}table{width:100%;border-collapse:collapse}td,th{padding:.6rem;border-bottom:1px solid #ddd;text-align:left}.actions{display:flex;gap:.5rem;flex-wrap:wrap}button,input{font:inherit;padding:.45rem}.muted{opacity:.65}</style>
</head><body><div id="app"></div>
<script>
async function api(url,opt={}){const r=await fetch(url,opt);if(r.status===401){location='/';return null}if(!r.ok)throw new Error(await r.text());return r}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function q(s){return encodeURIComponent(s)}
async function load(path='/'){
 const r=await api('/api/list?path='+q(path));if(!r)return;const j=await r.json();
 const parent=path==='/'?'/':path.split('/').slice(0,-1).join('/')||'/';
 document.getElementById('app').innerHTML='<h1>Pocket Web</h1><p class="muted">Path: '+esc(path)+'</p>'+
 '<div class="actions"><button onclick="load(\''+parent.replace(/'/g,"\\'")+'\')">Up</button><button onclick="mk(\''+path.replace(/'/g,"\\'")+'\')">New folder</button><button onclick="up(\''+path.replace(/'/g,"\\'")+'\')">Upload</button><button onclick="location=\'/logout\'">Lock</button></div>'+
 '<table><tr><th>Name</th><th>Size</th><th></th></tr>'+j.items.map(x=>{
 const name=esc(x.name), p=q(x.path);
 const display=x.is_directory?name+'/':'<a href="/download?path='+p+'">'+name+'</a>';
 return '<tr><td>'+ (x.is_directory?'<a href="#" onclick="load(\''+x.path.replace(/'/g,"\\'")+'\');return false">'+display+'</a>':display) +
 '<td>'+(x.is_directory?'—':x.size)+'</td><td><button onclick="ren(\''+x.path.replace(/'/g,"\\'")+'\')">Rename</button> <button onclick="del(\''+x.path.replace(/'/g,"\\'")+'\')">Delete</button></td></tr>'
 }).join('')+'</table>';
}
async function mk(path){const n=prompt('Folder name');if(!n)return;await api('/api/mkdir',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path:(path==='/'?'':path)+'/'+n})});load(path)}
async function ren(path){const n=prompt('New path',path);if(!n||n===path)return;await api('/api/rename',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path,rename_to:n})});load(path.split('/').slice(0,-1).join('/')||'/')}
async function del(path){if(!confirm('Delete '+path+'?'))return;await api('/api/delete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({path})});load(path.split('/').slice(0,-1).join('/')||'/')}
function up(path){const i=document.createElement('input');i.type='file';i.onchange=async()=>{const f=i.files[0];if(!f)return;const target=(path==='/'?'':path)+'/'+f.name;await api('/api/upload?path='+q(target),{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});load(path)};i.click()}
load();
</script></body></html>"""

LOGIN = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Pocket Login</title></head>
<body style="font-family:system-ui;max-width:420px;margin:4rem auto;padding:1rem">
<h1>Pocket</h1><form method="post" action="/login"><input name="password" type="password" placeholder="Password" autofocus required><button>Connect</button></form>{error}</body></html>"""

CHANGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Change password</title></head>
<body style="font-family:system-ui;max-width:420px;margin:4rem auto;padding:1rem">
<h1>Change password</h1><p>Bootstrap password must be changed.</p>
<form method="post" action="/change-password"><input name="password" type="password" minlength="8" placeholder="New password" required><button>Change</button></form>
</body></html>"""


def session_token(handler):
    jar = cookies.SimpleCookie(handler.headers.get("Cookie", ""))
    c = jar.get("pocket_web")
    if not c:
        return None
    item = BROWSER_SESSIONS.get(c.value)
    if not item:
        return None
    expires, token = item
    if expires <= time.time():
        BROWSER_SESSIONS.pop(c.value, None)
        return None
    BROWSER_SESSIONS[c.value] = (time.time() + TTL, token)
    return token


def set_cookie(token):
    jar = cookies.SimpleCookie()
    jar["pocket_web"] = token
    jar["pocket_web"]["httponly"] = True
    jar["pocket_web"]["samesite"] = "Strict"
    jar["pocket_web"]["path"] = "/"
    return jar.output(header="").strip()


def expire_cookie():
    jar = cookies.SimpleCookie()
    jar["pocket_web"] = ""
    jar["pocket_web"]["expires"] = "Thu, 01 Jan 1970 00:00:00 GMT"
    jar["pocket_web"]["path"] = "/"
    return jar.output(header="").strip()


class Handler(BaseHTTPRequestHandler):
    server_version = "PocketWeb/0.1"

    def send_body(self, status, body, content_type="text/html; charset=utf-8", extra=None):
        body = body.encode() if isinstance(body, str) else body
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def json(self, value, status=200):
        self.send_body(status, json.dumps(value), "application/json; charset=utf-8")

    def require(self):
        token = session_token(self)
        if not token:
            self.send_body(401, b"unauthorized")
            return None
        return token

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/":
            token = session_token(self)
            if not token:
                self.send_body(200, LOGIN.format(error=""))
                return
            try:
                who = call("whoami", (("session", "s", token),))
            except Exception:
                self.send_body(401, LOGIN.format(error="<p>Session expired.</p>"))
                return
            if who.get("must_change_password"):
                self.send_body(200, CHANGE)
            else:
                self.send_body(200, INDEX)
            return
        if parsed.path == "/logout":
            token = session_token(self)
            if token:
                try: call("disconnect", (("session", "s", token),))
                except Exception: pass
            for key in list(BROWSER_SESSIONS):
                if self.headers.get("Cookie", "").find(key) >= 0:
                    BROWSER_SESSIONS.pop(key, None)
            self.send_body(303, b"", extra={"Location":"/", "Set-Cookie":expire_cookie()})
            return

        token = self.require()
        if not token:
            return
        qs = urllib.parse.parse_qs(parsed.query)
        path = normalize(qs.get("path", ["/"])[0])
        if parsed.path == "/api/list":
            try:
                raw = subprocess.run(
                    [content_bin(), "query", "--uri", files_uri(path, token),
                     "--projection", "path:name:is_directory:size:modified", "--sort", "name ASC"],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE
                )
                if raw.returncode:
                    raise RuntimeError(raw.stderr.decode(errors="replace").strip() or "query failed")
                self.json({"path": path, "items": parse_rows(raw.stdout)})
            except Exception as e:
                self.json({"error": str(e)}, 400)
            return
        if parsed.path == "/download":
            self.download(token, path)
            return
        self.send_body(404, b"not found")

    def read_form(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > MAX_BODY:
            raise ValueError("request too large")
        return urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"))

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length > MAX_BODY:
            raise ValueError("request too large")
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/login":
            try:
                form = self.read_form()
                result = call("connect", (("password", "s", form.get("password", [""])[0]),))
                token = result.get("session")
                if not token: raise RuntimeError("Pocket did not return a session")
                browser = secrets.token_urlsafe(32)
                BROWSER_SESSIONS[browser] = (time.time() + TTL, token)
                self.send_body(303, b"", extra={"Location":"/", "Set-Cookie":set_cookie(browser)})
            except Exception as e:
                self.send_body(401, LOGIN.format(error=f"<p style='color:#b00020'>{e}</p>"))
            return

        token = self.require()
        if not token:
            return
        try:
            if parsed.path == "/change-password":
                form = self.read_form()
                new_password = form.get("password", [""])[0]
                call("change_password", (("session", "s", token), ("new_password", "s", new_password)))
                self.send_body(303, b"", extra={"Location":"/"})
                return
            if parsed.path == "/api/mkdir":
                data = self.read_json()
                self.insert(token, normalize(data["path"]), "directory")
                self.json({"ok": True})
            elif parsed.path == "/api/rename":
                data = self.read_json()
                self.rename(token, normalize(data["path"]), normalize(data["rename_to"]))
                self.json({"ok": True})
            elif parsed.path == "/api/delete":
                data = self.read_json()
                self.delete(token, normalize(data["path"]))
                self.json({"ok": True})
            elif parsed.path == "/api/upload":
                qs = urllib.parse.parse_qs(parsed.query)
                path = normalize(qs.get("path", ["/"])[0])
                length = int(self.headers.get("Content-Length", "0"))
                if length > MAX_BODY:
                    raise ValueError("upload too large")
                data = self.rfile.read(length)
                import tempfile
                with tempfile.NamedTemporaryFile() as f:
                    f.write(data); f.flush()
                    self.write_file(token, path, f.name)
                self.json({"ok": True, "bytes": len(data)})
            else:
                self.send_body(404, b"not found")
        except Exception as e:
            self.json({"error": str(e)}, 400)

    def insert(self, token, path, kind):
        subprocess.check_call([content_bin(), "insert", "--uri", BASE_URI,
                               "--bind", f"session:s:{token}",
                               "--bind", f"path:s:{path.lstrip('/')}",
                               "--bind", f"kind:s:{kind}"])

    def rename(self, token, source, dest):
        subprocess.check_call([content_bin(), "update", "--uri", BASE_URI,
                               "--bind", f"session:s:{token}",
                               "--bind", f"path:s:{source.lstrip('/')}",
                               "--bind", f"rename_to:s:{dest.lstrip('/')}"])

    def delete(self, token, path):
        subprocess.check_call([content_bin(), "delete", "--uri", files_uri(path, token)])

    def write_file(self, token, path, source):
        with open(source, "rb") as f:
            p = subprocess.run([content_bin(), "write", "--uri", file_uri(path, token)],
                               stdin=f, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if p.returncode:
            raise RuntimeError(p.stderr.decode(errors="replace").strip() or "write failed")

    def download(self, token, path):
        raw = subprocess.run([content_bin(), "query", "--uri", files_uri(path, token),
                              "--projection", "path:name:is_directory:size:modified"],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        rows = parse_rows(raw.stdout)
        if raw.returncode or not rows:
            self.send_body(404, b"file not found")
            return
        row = rows[0]
        if row.get("is_directory"):
            self.send_body(400, b"cannot download a directory")
            return
        size = row.get("size", 0)
        name = PurePosixPath(path).name or "download"
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", 'attachment; filename="' + name.replace('"', "") + '"')
        self.end_headers()
        p = subprocess.Popen([content_bin(), "read", "--uri", file_uri(path, token)],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        while True:
            chunk = p.stdout.read(1024 * 1024)
            if not chunk: break
            self.wfile.write(chunk)
        p.wait()

    def log_message(self, fmt, *args):
        print(f"{self.address_string()} - {fmt % args}")


def main():
    p = argparse.ArgumentParser(prog="pocket-web")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8787)
    a = p.parse_args()
    server = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f"Pocket Web listening on http://{a.host}:{a.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
