#!/usr/bin/env bash
# Generates the demo RSA key pairs used by BlockchainIST (one per user + one for the sequencer).
# Private keys: PKCS#8 DER (.priv). Public keys: X.509 DER (.pub). Requires OpenSSL.
# Keys are NOT committed to git (see .gitignore) - run this once before building.
set -euo pipefail
cd "$(dirname "$0")"
USERS="Alice Bob Charlie David Emma Fred Ginger Henry Iris BC"
CLIENT=client/src/main/resources
NODE=node/src/main/resources
SEQ=sequencer/src/main/resources
mkdir -p "$CLIENT" "$NODE/seq" "$SEQ"
gen() { # name privOut pubOut
  local tmp; tmp=$(mktemp)
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp" 2>/dev/null
  openssl pkcs8 -topk8 -nocrypt -in "$tmp" -outform DER -out "$2"
  openssl pkey -in "$tmp" -pubout -outform DER -out "$3"
  rm -f "$tmp"
}
for u in $USERS; do gen "$u" "$CLIENT/$u.priv" "$NODE/$u.pub"; done
gen Seq "$SEQ/Seq.priv" "$NODE/seq/Seq.pub"
echo "Keys generated. Now run: mvn clean install"
