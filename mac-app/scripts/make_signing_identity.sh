#!/usr/bin/env bash
# One-time: creates a self-signed code-signing identity "Agent HUD Local Signing" in the login
# keychain so build.sh can sign Agent HUD with a stable identity (privacy permissions then survive
# rebuilds). The private key never leaves the keychain; temp files are removed.
set -euo pipefail
NAME="Agent HUD Local Signing"
if security find-certificate -c "$NAME" >/dev/null 2>&1; then
  echo "\"$NAME\" already exists"; exit 0
fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/cert.cnf" <<CNF
[ req ]
distinguished_name = dn
prompt = no
x509_extensions = ext
[ dn ]
CN = $NAME
[ ext ]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
subjectKeyIdentifier = hash
CNF
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -days 3650 -config "$TMP/cert.cnf"
PASS="$(openssl rand -hex 12)"
openssl pkcs12 -export -legacy -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -name "$NAME" -out "$TMP/id.p12" -passout "pass:$PASS" 2>/dev/null \
  || openssl pkcs12 -export -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -name "$NAME" -out "$TMP/id.p12" -passout "pass:$PASS"
security import "$TMP/id.p12" -k "$HOME/Library/Keychains/login.keychain-db" -P "$PASS" -T /usr/bin/codesign
echo "Created \"$NAME\""
