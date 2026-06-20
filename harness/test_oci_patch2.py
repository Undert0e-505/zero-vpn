"""Test if patching oci._vendor.requests.sessions.Session.send works."""
import requests
import hashlib

# Standard requests patch
_original_send = requests.Session.send

def _logging_send(self, request, **kwargs):
    print(f"[WIRE TAP] {request.method} {request.url}", flush=True)
    response = _original_send(self, request, **kwargs)
    print(f"[WIRE TAP] response: {response.status_code}", flush=True)
    return response

requests.Session.send = _logging_send

# Import oci and patch the vendored requests
import oci
import oci._vendor.requests.sessions as _oci_vendor_sessions
_oci_vendor_sessions.Session.send = _logging_send
print(f"Patched vendored Session.send: {_oci_vendor_sessions.Session.send}")

from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption, load_pem_private_key

priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
priv_pem = priv.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
priv_obj = load_pem_private_key(priv_pem, password=None)

token = "test-token"
signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)

idc = oci.identity.IdentityClient({'region': 'uk-london-1'}, signer=signer)
print(f"Session type: {type(idc.base_client.session)}")
print(f"Session.send is our patch: {idc.base_client.session.send.__func__ is _logging_send}")

try:
    idc.list_region_subscriptions("REDACTED_OCID")
except Exception as e:
    print(f"Expected error: {type(e).__name__}")

print("Done.")