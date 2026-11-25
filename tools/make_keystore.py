"""Create the Android signing key from a secret seed, the same key every time.

Android only installs an update if it is signed with the same key as the installed app.
Instead of storing a key file anywhere, the build machine re-creates it from the
APP_SIGNING_SEED GitHub secret: same seed -> same RSA key -> byte-identical certificate.

Usage: APP_SIGNING_SEED=... python tools/make_keystore.py <out.p12> <password>
"""

import base64
import hashlib
import os
import sys
from datetime import datetime, timezone

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID

SMALL_PRIMES = [p for p in range(3, 2000) if all(p % q for q in range(2, int(p ** 0.5) + 1))]


class Stream:
    """Deterministic random bytes from the seed (SHAKE-256 in counter mode)."""

    def __init__(self, seed: bytes, label: bytes):
        self.seed, self.label, self.counter = seed, label, 0

    def bytes(self, n: int) -> bytes:
        self.counter += 1
        return hashlib.shake_256(self.seed + self.label + self.counter.to_bytes(8, "big")).digest(n)

    def below(self, n: int) -> int:
        return int.from_bytes(self.bytes((n.bit_length() + 7) // 8 + 8), "big") % n


def is_probable_prime(n: int, rnd: Stream, rounds: int = 40) -> bool:
    if n < 2:
        return False
    for p in SMALL_PRIMES:
        if n % p == 0:
            return n == p
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    for _ in range(rounds):
        a = 2 + rnd.below(n - 3)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def prime(bits: int, rnd: Stream, e: int) -> int:
    while True:
        c = int.from_bytes(rnd.bytes(bits // 8), "big")
        c |= (3 << (bits - 2)) | 1  # top two bits set (so p*q has full size), odd
        if (c - 1) % e and is_probable_prime(c, rnd):
            return c


def key_from_seed(seed: bytes, bits: int = 3072) -> rsa.RSAPrivateKey:
    e = 65537
    p = prime(bits // 2, Stream(seed, b"ipo-helper/p"), e)
    q = prime(bits // 2, Stream(seed, b"ipo-helper/q"), e)
    if p < q:
        p, q = q, p
    phi = (p - 1) * (q - 1)
    d = pow(e, -1, phi)
    nums = rsa.RSAPrivateNumbers(
        p=p, q=q, d=d, dmp1=d % (p - 1), dmq1=d % (q - 1), iqmp=pow(q, -1, p),
        public_numbers=rsa.RSAPublicNumbers(e, p * q),
    )
    return nums.private_key()


def certificate(key: rsa.RSAPrivateKey) -> x509.Certificate:
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "IPO Helper"),
                      x509.NameAttribute(NameOID.COUNTRY_NAME, "NP")])
    return (x509.CertificateBuilder()
            .subject_name(name).issuer_name(name)
            .public_key(key.public_key())
            .serial_number(1)
            .not_valid_before(datetime(2026, 1, 1, tzinfo=timezone.utc))
            .not_valid_after(datetime(2076, 1, 1, tzinfo=timezone.utc))
            .sign(key, hashes.SHA256()))  # RSA PKCS#1 v1.5: deterministic, so the cert is identical every build


def main() -> None:
    out, password = sys.argv[1], sys.argv[2].encode()
    seed_text = os.environ.get("APP_SIGNING_SEED", "").strip()
    if len(seed_text) < 32:
        sys.exit("APP_SIGNING_SEED is missing. Open the IPO Helper web app once (it creates it), then build again.")
    seed = hashlib.sha256(b"ipo-helper-signing/" + seed_text.encode()).digest()
    key = key_from_seed(seed)
    cert = certificate(key)
    data = pkcs12.serialize_key_and_certificates(
        b"ipohelper", key, cert, None, serialization.BestAvailableEncryption(password))
    with open(out, "wb") as f:
        f.write(data)
    fp = hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()
    print("signing certificate SHA-256:", fp)  # public fingerprint, safe to show; must be the same every build


if __name__ == "__main__":
    main()
