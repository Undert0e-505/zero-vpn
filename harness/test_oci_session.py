"""Check what type of session OCI uses and if our patch is visible."""
import requests

# Patch BEFORE importing oci
_original_send = requests.Session.send

def _logging_send(self, request, **kwargs):
    print(f"[WIRE TAP] {request.method} {request.url}", flush=True)
    response = _original_send(self, request, **kwargs)
    print(f"[WIRE TAP] response: {response.status_code}", flush=True)
    return response

requests.Session.send = _logging_send

import oci
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption, load_pem_private_key

priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
priv_pem = priv.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
priv_obj = load_pem_private_key(priv_pem, password=None)

token = "test-token"
signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)

idc = oci.identity.IdentityClient({'region': 'uk-london-1'}, signer=signer)

# Check the session type
print(f"Session type: {type(idc.base_client.session)}")
print(f"Session MRO: {type(idc.base_client.session).__mro__}")
print(f"Session.send is our patch: {idc.base_client.session.send.__func__ is _logging_send}")
print(f"requests.Session.send is our patch: {requests.Session.send.__func__ is _logging_send}")

# Try calling session.send directly
import urllib3
class FakePreparedRequest:
    method = "GET"
    url = "https://identity.uk-london-1.oci.oraclecloud.com/20160918/tenancies/test/regionSubscriptions"
    headers = {}
    body = None

# This won't actually work but will show if our patch is called
try:
    print("About to call session.send directly...")
    # Can't easily fake it, let's just check the method resolution
    print(f"send method: {idc.base_client.session.send}")
    print(f"send method class: {idc.base_client.session.send.__func__}")
except Exception as e:
    print(f"Error: {e}")