#!/usr/bin/env python3
import argparse
import json
import os
import re
import subprocess
import shutil
import sys
import urllib.parse
from pathlib import Path

AUTHORITY = "com.pocket.storage.provider"
BASE_URI = f"content://{AUTHORITY}"
PREFIX = Path(os.environ.get("PREFIX", Path.home()))
STATE_FILE = PREFIX / "var/lib/pocket/session"


def content_bin():
    for p in ("/system/bin/content", "content"):
        if p == "content" or os.access(p, os.X_OK):
            return p
    raise RuntimeError("Android content command is unavailable")


def run_content(*args, stdin=None, capture=True):
    proc = subprocess.run(
        [content_bin(), *args],
        stdin=stdin,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE,
    )
    if proc.returncode:
        raise RuntimeError(proc.stderr.decode(errors="replace").strip() or "Android content command failed")
    return proc.stdout if capture else b""


def parse_bundle(data):
    text = data.decode(errors="replace").strip()
    if text.startswith("Bundle[") and text.endswith("]"):
        text = text[7:-1]
    result = {}
    for m in re.finditer(r"([A-Za-z0-9_]+)=((?:(?!, [A-Za-z0-9_]+=).)*)", text):
        k, v = m.groups()
        v = v.strip()
        if v.lower() in ("true", "false"):
            result[k] = v.lower() == "true"
        else:
            try:
                result[k] = int(v)
            except ValueError:
                result[k] = v
    return result


def parse_rows(data):
    rows = []
    for line in data.decode(errors="replace").splitlines():
        if not line.startswith("Row:"):
            continue
        row = {}
        for m in re.finditer(r"(?:^|\\s)([A-Za-z0-9_]+)=((?:(?!\\s[A-Za-z0-9_]+=).)*)", line[4:].strip()):
            row[m.group(1)] = m.group(2).strip()
        if row:
            row["is_directory"] = row.get("is_directory") == "true"
            try:
                row["size"] = int(row.get("size", "0"))
            except ValueError:
                row["size"] = 0
            rows.append(row)
    return rows


def call(method, extras=()):
    args = ["call", "--uri", BASE_URI, "--method", method]
    for key, kind, value in extras:
        args += ["--extra", f"{key}:{kind}:{value}"]
    return parse_bundle(run_content(*args))


def require_session():
    if not STATE_FILE.exists():
        raise RuntimeError("not connected; run: pocket connect")
    token = STATE_FILE.read_text(encoding="utf-8").strip()
    if not token:
        raise RuntimeError("session is empty; run: pocket connect")
    return token


def save_session(token):
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(token + "\n", encoding="utf-8")
    os.chmod(STATE_FILE, 0o600)


def clear_session():
    try:
        STATE_FILE.unlink()
    except FileNotFoundError:
        pass


def normalize(path):
    path = path.strip().replace("\\", "/")
    if not path.startswith("/"):
        path = "/" + path
    parts = [p for p in path.split("/") if p not in ("", ".")]
    if any(p == ".." for p in parts):
        raise ValueError("path traversal is not allowed")
    return "/" + "/".join(parts) if parts else "/"


def rel(path):
    value = normalize(path)
    return value[1:]


def files_uri(path, token):
    return uri("files", path, token)


def file_uri(path, token, append=False):
    return uri("file", path, token, append=append)


def uri(kind, path, token, append=False):
    encoded = urllib.parse.quote(rel(path), safe="/")
    params = {"session": token}
    if kind == "files":
        params["path"] = "" if normalize(path) == "/" else rel(path)
    if append:
        params["append"] = "true"
    return f"{BASE_URI}/{kind}/{encoded}?{urllib.parse.urlencode(params)}"


def connect(password):
    r = call("connect", (("password", "s", password),))
    token = r.get("session")
    if not token:
        raise RuntimeError("Pocket did not return a session")
    save_session(token)
    if r.get("must_change_password"):
        print("Connected. Change the bootstrap password before using storage.", file=sys.stderr)
    else:
        print("Connected.", file=sys.stderr)


def disconnect():
    token = require_session()
    call("disconnect", (("session", "s", token),))
    clear_session()
    print("Disconnected.", file=sys.stderr)


def lock():
    token = require_session()
    call("lock", (("session", "s", token),))
    clear_session()
    print("Locked.", file=sys.stderr)


def change_password(password):
    token = require_session()
    call("change_password", (("session", "s", token), ("new_password", "s", password)))
    print("Password changed.", file=sys.stderr)


def list_files(path):
    token = require_session()
    data = run_content(
        "query",
        "--uri", uri("files", path, token),
        "--projection", "path:name:is_directory:size:modified",
        "--sort", "name ASC",
    )
    for row in parse_rows(data):
        print(f"{row.get('name','')}{'/' if row.get('is_directory') else ''}\t{row.get('size',0)}")


def stat(path):
    token = require_session()
    data = run_content("query", "--uri", uri("files", path, token),
                       "--projection", "path:name:is_directory:size:modified")
    rows = parse_rows(data)
    if not rows:
        raise RuntimeError("path does not exist")
    print(json.dumps(rows[0], indent=2))


def mutate(command, path, **extra_values):
    token = require_session()
    args = [command, "--uri", BASE_URI, "--bind", f"session:s:{token}"]
    for k, v in extra_values.items():
        args += ["--bind", f"{k}:s:{v}"]
    run_content(*args)


def read_file(path, output):
    token = require_session()
    proc = subprocess.Popen(
        [content_bin(), "read", "--uri", uri("file", path, token)],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    data, err = proc.communicate()
    if proc.returncode:
        raise RuntimeError(err.decode(errors="replace").strip() or "read failed")
    if output == "-":
        sys.stdout.buffer.write(data)
    else:
        Path(output).write_bytes(data)


def write_file(path, source, append=False):
    token = require_session()
    target = uri("file", path, token, append=append)
    handle = sys.stdin.buffer if source == "-" else open(source, "rb")
    try:
        proc = subprocess.run([content_bin(), "write", "--uri", target],
                              stdin=handle, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    finally:
        if source != "-":
            handle.close()
    if proc.returncode:
        raise RuntimeError(proc.stderr.decode(errors="replace").strip() or "write failed")


def doctor():
    checks = []

    if shutil.which("python3"):
        checks.append(("python3", True, shutil.which("python3")))
    else:
        checks.append(("python3", False, "missing"))

    try:
        result = call("ping")
        checks.append(("Pocket provider", result.get("ok") is True, result.get("service", "unreachable")))
    except Exception as e:
        checks.append(("Pocket provider", False, str(e)))

    if STATE_FILE.exists():
        checks.append(("Session file", True, str(STATE_FILE)))
    else:
        checks.append(("Session file", False, "not connected"))

    installed_root = Path(__file__).resolve().parent
    web_file = installed_root / "pocket_web.py"
    checks.append(("Pocket Web", web_file.exists(), str(web_file)))

    termux_props = Path.home() / ".termux" / "termux.properties"
    external = False
    if termux_props.exists():
        try:
            for line in termux_props.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line == "allow-external-apps=true":
                    external = True
                    break
        except OSError:
            pass
    checks.append(("Termux external apps", external, str(termux_props)))

    ok = all(value for _, value, _ in checks)
    for name, passed, detail in checks:
        print(f"[{'OK' if passed else '!!'}] {name}: {detail}")
    if not external:
        print("      Set allow-external-apps=true in ~/.termux/termux.properties")
    return 0 if ok else 1


def launch_setup(action, extras=()):
    command = ["/system/bin/am", "start", "-n", "com.pocket.storage/.PocketSetupActivity", "-a", action]
    for key, value in extras:
        command += ["--ei", key, str(value)]
    subprocess.run(command, check=True)


def main():
    p = argparse.ArgumentParser(prog="pocket")
    s = p.add_subparsers(dest="cmd", required=True)
    fellow = s.add_parser("fellow")
    fs = fellow.add_subparsers(dest="fellow_cmd", required=True)
    fs.add_parser("register")
    fs.add_parser("advertise")
    fs.add_parser("list")
    fr = fs.add_parser("remove")
    fr.add_argument("association_id", type=int)
    fc = fs.add_parser("connect")
    fc.add_argument("association_id", type=int)
    fs.add_parser("disconnect")
    s.add_parser("ping")
    s.add_parser("doctor")
    c = s.add_parser("connect"); c.add_argument("password", nargs="?")
    s.add_parser("disconnect")
    s.add_parser("lock")
    c = s.add_parser("change-password"); c.add_argument("password")
    c = s.add_parser("ls"); c.add_argument("path", nargs="?", default="/")
    c = s.add_parser("stat"); c.add_argument("path")
    c = s.add_parser("mkdir"); c.add_argument("path")
    c = s.add_parser("touch"); c.add_argument("path")
    c = s.add_parser("delete"); c.add_argument("path")
    c = s.add_parser("rename"); c.add_argument("source"); c.add_argument("destination")
    c = s.add_parser("read"); c.add_argument("path"); c.add_argument("output", nargs="?", default="-")
    c = s.add_parser("write"); c.add_argument("path"); c.add_argument("source", nargs="?", default="-")
    c = s.add_parser("append"); c.add_argument("path"); c.add_argument("source", nargs="?", default="-")
    a = p.parse_args()
    try:
        if a.cmd == "ping":
            print(json.dumps(call("ping"), indent=2))
        elif a.cmd == "doctor":
            return doctor()
        elif a.cmd == "connect":
            connect(a.password or input("Pocket password: "))
        elif a.cmd == "disconnect":
            disconnect()
        elif a.cmd == "lock":
            lock()
        elif a.cmd == "change-password":
            change_password(a.password)
        elif a.cmd == "ls":
            list_files(a.path)
        elif a.cmd == "stat":
            stat(a.path)
        elif a.cmd == "mkdir":
            mutate("insert", a.path, kind="directory", path=rel(a.path))
        elif a.cmd == "touch":
            mutate("insert", a.path, kind="file", path=rel(a.path))
        elif a.cmd == "delete":
            token = require_session(); run_content("delete", "--uri", uri("files", a.path, token))
        elif a.cmd == "rename":
            token = require_session()
            mutate("update", a.source, path=rel(a.source), rename_to=rel(a.destination))
        elif a.cmd == "read":
            read_file(a.path, a.output)
        elif a.cmd == "write":
            write_file(a.path, a.source)
        elif a.cmd == "append":
            write_file(a.path, a.source, append=True)
        elif a.cmd == "fellow":
            if a.fellow_cmd == "register":
                launch_setup("com.pocket.storage.action.REGISTER")
            elif a.fellow_cmd == "advertise":
                launch_setup("com.pocket.storage.action.ADVERTISE")
            elif a.fellow_cmd == "list":
                token = require_session()
                result = call("fellow_list", (("session", "s", token),))
                print(json.dumps(json.loads(result.get("fellows_json", "[]")), indent=2))
            elif a.fellow_cmd == "remove":
                token = require_session()
                call(
                    "fellow_remove",
                    (("session", "s", token), ("association_id", "i", a.association_id)),
                )
                print(f"Removed fellow sharer #{a.association_id}.")
            elif a.fellow_cmd == "connect":
                launch_setup(
                    "com.pocket.storage.action.CONNECT",
                    (("association_id", a.association_id),),
                )
            elif a.fellow_cmd == "disconnect":
                launch_setup("com.pocket.storage.action.DISCONNECT")
    except (OSError, RuntimeError, ValueError) as e:
        print(f"pocket: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
