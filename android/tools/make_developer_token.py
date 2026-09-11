#!/usr/bin/env python3
"""Sign a MusicKit developer JWT locally; the private key never enters the app."""
import argparse
import base64
import json
import os
from pathlib import Path
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils


def encoded(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=")


def make_token(private_key, team_id, key_id, days):
    if not 1 <= days <= 180:
        raise ValueError("Validity must be between 1 and 180 days")
    key = serialization.load_pem_private_key(private_key, password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("MusicKit requires an ES256 (P-256) private key")
    now = int(time.time())
    header = {"alg": "ES256", "kid": key_id}
    claims = {"iss": team_id, "iat": now - 60, "exp": now + days * 86400}
    signing_input = b".".join(encoded(json.dumps(value, separators=(",", ":")).encode()) for value in (header, claims))
    r, s = utils.decode_dss_signature(key.sign(signing_input, ec.ECDSA(hashes.SHA256())))
    return (signing_input + b"." + encoded(r.to_bytes(32, "big") + s.to_bytes(32, "big"))).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-key", required=True, type=Path)
    parser.add_argument("--team-id", required=True)
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--days", type=int, default=180)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "musickit.properties")
    args = parser.parse_args()
    token = make_token(args.private_key.read_bytes(), args.team_id, args.key_id, args.days)
    # Refuse to overwrite an existing configuration, including any custom token endpoint.
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        output.write("developerToken=" + token + "\n")
    print(f"Wrote {args.output}. Token valid for {args.days} days. No private key was copied.")


if __name__ == "__main__":
    main()
