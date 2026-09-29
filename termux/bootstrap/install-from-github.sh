#!/data/data/com.termux/files/usr/bin/sh
set -eu

REPO_URL="${POCKET_REPO_URL:-https://raw.githubusercontent.com/fanmade2999-bit/storage-sharer/main/termux}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TMP="$PREFIX/tmp/pocket-bootstrap"
INSTALL="$TMP/install.sh"

command -v curl >/dev/null 2>&1 || {
    echo "curl is required. Install it with: pkg install curl" >&2
    exit 1
}

rm -rf "$TMP"
mkdir -p "$TMP"

curl -fsSL "$REPO_URL/pocket_client.py" -o "$TMP/pocket_client.py"
curl -fsSL "$REPO_URL/pocket_web.py" -o "$TMP/pocket_web.py"
curl -fsSL "$REPO_URL/pocket" -o "$TMP/pocket"
curl -fsSL "$REPO_URL/bootstrap/install.sh" -o "$INSTALL"

chmod 700 "$TMP/pocket" "$INSTALL"
sh "$INSTALL"

rm -rf "$TMP"
