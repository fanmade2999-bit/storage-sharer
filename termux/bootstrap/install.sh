#!/data/data/com.termux/files/usr/bin/sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
DEST="$PREFIX/share/pocket"
BIN="$PREFIX/bin"
SOURCE="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

mkdir -p "$DEST" "$PREFIX/var/lib/pocket"
cp "$SOURCE/pocket_client.py" "$DEST/pocket_client.py"
cp "$SOURCE/pocket_web.py" "$DEST/pocket_web.py"
cp "$SOURCE/pocket" "$DEST/pocket"
chmod 700 "$DEST" "$DEST/pocket"
chmod 600 "$DEST/pocket_client.py" "$DEST/pocket_web.py"
ln -sf "$DEST/pocket" "$BIN/pocket"
chmod 700 "$PREFIX/var/lib/pocket"

echo "Pocket Termux components installed."
echo "Run: pocket ping"
echo "Then: pocket connect"
echo "Web: pocket web --host 127.0.0.1 --port 8787"
