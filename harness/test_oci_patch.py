"""Test if wire tap fires when OCI SDK makes an HTTP call."""
import requests, hashlib, json, os
from datetime import datetime

# Patch BEFORE importing oci
_original_send = requests.Session.send

def _logging_send(self, request, **kwargs):
    print(f"[WIRE TAP] {request.method} {request.url}", flush=True)
    response = _original_send(self, request, **kwargs)
    print(f"[WIRE TAP] response: {response.status_code}", flush=True)
    return response

requests.Session.send = _logging_send

# Now import oci and make a call
import oci
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import Encoding, PrivateFormat, NoEncryption, load_pem_private_key

priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
priv_pem = priv.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption())
priv_obj = load_pem_private_key(priv_pem, password=None)

token = "test-token"
signer = oci.auth.signers.SecurityTokenSigner(token, priv_obj)

# Make a GET call to OCI (will fail with 401 but should fire the patch)
idc = oci.identity.IdentityClient({'region': 'uk-london-1'}, signer=signer)
try:
    idc.list_region_subscriptions("REDACTED_OCID")
except Exception as e:
    print(f"Expected error: {type(e).__name__}: {e}")

print("Done.")