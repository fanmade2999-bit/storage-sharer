#!/data/data/com.termux/files/usr/bin/sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
DEST="$PREFIX/share/pocket"
BIN="$PREFIX/bin"
SOURCE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if ! command -v python3 >/dev/null 2>&1; then
    if command -v pkg >/dev/null 2>&1; then
        echo "Python is missing; installing it with pkg..."
        pkg install -y python
    else
        echo "pocket bootstrap: python3 is required." >&2
        exit 1
    fi
fi

mkdir -p "$DEST" "$PREFIX/var/lib/pocket"

install -m 700 "$SOURCE/pocket" "$DEST/pocket"
install -m 600 "$SOURCE/pocket_client.py" "$DEST/pocket_client.py"
install -m 600 "$SOURCE/pocket_web.py" "$DEST/pocket_web.py"
install -m 600 "$SOURCE/pocket_version.py" "$DEST/pocket_version.py"

ln -sf "$DEST/pocket" "$BIN/pocket"
chmod 700 "$PREFIX/var/lib/pocket"

printf '%s\n' '0.1.0' > "$DEST/VERSION"
chmod 600 "$DEST/VERSION"

echo "Pocket Termux components installed."
echo
echo "Next:"
echo "  pocket ping"
echo "  pocket connect"
echo "  pocket web --host 127.0.0.1 --port 8787"
