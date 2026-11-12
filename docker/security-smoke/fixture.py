"""Isolated test signer/JWKS and API verifier. Never included in the application image."""

import base64
import json
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ISSUER = "http://security-fixture:8089/realms/smoke"
STAFF = ["inventory:read", "inventory:list", "inventory:manage", "inventory:history"]
IDENTITIES = {
    "staff": ("staff", "inventory-swagger", STAFF),
    "staff-a": ("staff-a", "inventory-swagger", STAFF),
    "staff-b": ("staff-b", "inventory-swagger", STAFF),
    "pkce-a": ("staff-a", "rentflow-pkce-check", STAFF),
    "reduced-a": ("staff-a", "inventory-swagger", ["inventory:read"]),
    "customer": ("customer", "inventory-swagger", ["inventory:read", "inventory:list"]),
    "pricing": ("pricing", "pricing-service", ["inventory:read"]),
    "reservation": ("reservation", "reservation-service", ["inventory:reserve"]),
}


def encoded(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


class Fixture(BaseHTTPRequestHandler):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    kid = str(uuid.uuid4())

    def log_message(self, *_):
        pass

    @classmethod
    def token(cls, role):
        subject, client, roles = IDENTITIES[role]
        now = int(time.time())
        header = {"alg": "RS256", "typ": "JWT", "kid": cls.kid}
        claims = {"iss": ISSUER, "sub": subject, "azp": client, "aud": ["inventory-api"],
                  "iat": now, "exp": now + 300, "typ": "Bearer",
                  "resource_access": {"inventory-api": {"roles": roles}}}
        payload = ".".join(encoded(json.dumps(v, separators=(",", ":")).encode()) for v in (header, claims))
        signature = cls.key.sign(payload.encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
        return payload + "." + encoded(signature)

    def do_GET(self):
        if self.path == "/health":
            body = b"ok"
        elif self.path == "/realms/smoke/protocol/openid-connect/certs":
            numbers = self.key.public_key().public_numbers()
            body = json.dumps({"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": self.kid,
                                        "n": encoded(numbers.n.to_bytes(256, "big")),
                                        "e": encoded(numbers.e.to_bytes(3, "big"))}]}).encode()
        elif self.path.startswith("/header/") and self.path[8:] in IDENTITIES:
            body = ('header = "Authorization: Bearer ' + self.token(self.path[8:]) + '"\n').encode()
        elif self.path.startswith("/token/") and self.path[7:] in IDENTITIES:
            body = self.token(self.path[7:]).encode()
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def credential(role):
    with urlopen("http://localhost:8089/token/" + role, timeout=2) as response:
        return response.read().decode()


def api(role, method, path, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if role:
        headers["Authorization"] = "Bearer " + credential(role)
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    request = Request("http://inventory:8080" + path, data=data, headers=headers, method=method)
    try:
        response = urlopen(request, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        content = response.read()
        return response.code, response.headers, json.loads(content) if content else None


def check(condition, name):
    if not condition:
        raise AssertionError(name)


def verify():
    for suffix in ("", "/api"):
        metadata = api(None, "GET", "/.well-known/oauth-protected-resource" + suffix)
        check(metadata[0] == 200 and metadata[2] == {
            "resource": "http://inventory:8080" + suffix,
            "authorization_servers": [ISSUER],
            "bearer_methods_supported": ["header"],
            "tls_client_certificate_bound_access_tokens": False,
        }, "accurate public resource metadata")
    path = "/api/v1/inventory"
    check(api(None, "GET", path)[0] == 401, "anonymous denied")
    for role in ("staff", "customer", "pricing"):
        check(api(role, "GET", path + "/SMOKE-001")[0] == 200, role + " lookup")
    check(api("customer", "GET", path)[0] == 200, "customer list")
    check(api("pricing", "GET", path)[0] == 403, "pricing list denied")
    for role in ("customer", "pricing", "reservation"):
        check(api(role, "POST", path, {})[0] == 403, role + " create denied")
        check(api(role, "GET", "/api/v1/inventory-history")[0] == 403, role + " history denied")
    for serial in ("SECURITY-SMOKE-A", "SECURITY-SMOKE-B"):
        check(api("staff", "POST", path, {"serialNumber": serial, "type": "Drill", "name": "Security smoke", "status": "AVAILABLE"})[0] == 201, "staff create")
    status = path + "/status"
    batch = [{"serialNumber": "SECURITY-SMOKE-A", "status": "RESERVED"}]
    key = str(uuid.uuid4())
    check(api("reservation", "PATCH", status, batch, key)[0] == 204, "reservation claim")
    check(api("reservation", "PATCH", status, [{**batch[0], "status": "AVAILABLE"}], key)[0] == 403, "forbidden target before replay")
    check(api("staff", "GET", path + "/SECURITY-SMOKE-A")[2]["status"] == "RESERVED", "denied write unchanged")
    check(api("reservation", "PATCH", status, batch, str(uuid.uuid4()))[0] == 409, "lifecycle conflict")
    batch[0]["serialNumber"] = "SECURITY-SMOKE-B"
    key = str(uuid.uuid4())
    check(api("staff-a", "PATCH", status, batch, key)[0] == 204, "first subject claim")
    check(api("staff-b", "PATCH", status, batch, key)[0] == 409, "second subject isolated")
    replay = api("pkce-a", "PATCH", status, batch, key)
    check(replay[0] == 204 and replay[1].get("Idempotency-Replayed") == "true", "same subject cross-client replay")
    check(api("reduced-a", "PATCH", status, batch, key)[0] == 403, "permission reduced replay denied")
    check(len(api("staff", "GET", "/api/v1/inventory-history?serialNumber=SECURITY-SMOKE-B")[2]["content"]) == 1, "replay creates no extra history")
    print("Signed-token container security checks passed")


if __name__ == "__main__":
    try:
        command = sys.argv[1]
        if command == "serve":
            ThreadingHTTPServer(("0.0.0.0", 8089), Fixture).serve_forever()
        elif command == "health":
            with urlopen("http://localhost:8089/health", timeout=1) as response:
                check(response.status == 200, "fixture health")
        elif command == "header":
            with urlopen("http://localhost:8089/header/" + sys.argv[2], timeout=2) as response:
                sys.stdout.write(response.read().decode())
        elif command == "verify":
            verify()
        else:
            raise AssertionError("unknown fixture operation")
    except AssertionError as error:
        print("Security fixture assertion failed: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        # Exception text/HTTP bodies may contain credentials; expose only an operation label.
        print("Security fixture operation failed", file=sys.stderr)
        sys.exit(1)
