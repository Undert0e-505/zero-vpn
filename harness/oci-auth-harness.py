#!/usr/bin/env python3
"""
OCI Auth Harness — standalone test of the Oracle browser-auth/bootstrap flow.

This script replicates what `oci setup bootstrap` and `oci session authenticate`
do, but in an instrumented, debuggable way. It runs on the Windows machine with
a real browser — no Android, no WebView.

Steps:
1. Generate RSA keypair locally
2. Encode public key as JWK (exactly as CLI does)
3. Print the decoded JWK for verification
4. Start a local HTTP listener on port 8181
5. Open the system browser to the Oracle OAuth2 authorize URL
6. Log every redirect/callback received
7. Extract the security token from the callback
8. Decode the JWT and show user/tenancy OCIDs
9. (Optional) Upload the public key as an API key

Usage:
    D:\\Python310\\python.exe oci-auth-harness.py
    D:\\Python310\\python.exe oci-auth-harness.py --region uk-london-1
    D:\\Python310\\python.exe oci-auth-harness.py --region uk-london-1 --upload-api-key
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, urlencode

# Use the oci SDK's crypto utilities (same as the CLI does)
import oci
from oci.regions import REGIONS, REALMS, REGION_REALMS, REGIONS_SHORT_NAMES, is_region

# --- Config ---
BOOTSTRAP_PORT = 8181
CONSOLE_AUTH_URL_FORMAT = "https://login.{region}.{realm}/v1/oauth2/authorize"


def generate_keypair():
    """Generate an RSA 2048-bit keypair using the oci SDK's crypto."""
    from oci.signer import Signer
    # The CLI uses oci's internal key generation, which uses cryptography library
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives import serialization
    
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_key = private_key.public_key()
    
    return private_key, public_key


def public_key_to_jwk(public_key):
    """
    Convert an RSA public key to JWK format, then base64url-encode it.
    This replicates exactly what the OCI CLI does in cli_setup_bootstrap.py.
    """
    from cryptography.hazmat.primitives.asymmetric import rsa as rsa_mod
    
    # Get the public numbers
    numbers = public_key.public_numbers()
    
    # Encode modulus and exponent as base64url
    # The CLI uses: base64.urlsafe_b64encode (WITH padding)
    # But for the JWK itself, we need unsigned big-endian bytes
    
    # modulus as unsigned bytes
    n_int = numbers.n
    n_bytes = n_int.to_bytes((n_int.bit_length() + 7) // 8, 'big')
    
    # exponent as unsigned bytes
    e_int = numbers.e
    e_bytes = e_int.to_bytes((e_int.bit_length() + 7) // 8, 'big')
    
    # base64url encode (Python's urlsafe_b64encode includes padding)
    n_b64 = base64.urlsafe_b64encode(n_bytes).decode('UTF-8')
    e_b64 = base64.urlsafe_b64encode(e_bytes).decode('UTF-8')
    
    jwk = {
        "kty": "RSA",
        "e": e_b64,
        "n": n_b64,
        "alg": "RS256",
        "use": "sig"
    }
    
    jwk_json = json.dumps(jwk, separators=(',', ':'))
    
    # CLI does: base64.urlsafe_b64encode(jwk_content.encode('UTF-8')).decode('UTF-8')
    jwk_b64 = base64.urlsafe_b64encode(jwk_json.encode('UTF-8')).decode('UTF-8')
    
    return jwk_json, jwk_b64


def public_key_fingerprint(public_key):
    """Compute the SHA-256 fingerprint of a public key (OCI format)."""
    from cryptography.hazmat.primitives import serialization
    der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
    digest = hashlib.sha256(der).digest()
    return ':'.join(f'{b:02x}' for b in digest)


def build_authorize_url(region, jwk_b64, redirect_uri, tenancy_name=None):
    """Build the Oracle OAuth2 authorize URL."""
    if region in REGIONS_SHORT_NAMES:
        region = REGIONS_SHORT_NAMES[region]
    
    if not is_region(region):
        raise ValueError(f"Invalid region: {region}")
    
    realm = REALMS[REGION_REALMS[region]]
    console_url = CONSOLE_AUTH_URL_FORMAT.format(region=region, realm=realm)
    
    query = {
        'action': 'login',
        'client_id': 'iaas_console',
        'response_type': 'token id_token',
        'nonce': str(uuid.uuid4()),
        'scope': 'openid',
        'public_key': jwk_b64,
        'redirect_uri': redirect_uri,
    }
    
    if tenancy_name:
        query['tenant'] = tenancy_name
    
    url = f"{console_url}?{urlencode(query)}"
    return url


class CallbackHandler(BaseHTTPRequestHandler):
    """HTTP handler that captures the OAuth2 callback."""
    
    captured_token = None
    
    def log_message(self, format, *args):
        # Log everything for debugging
        print(f"  [HTTP] {format % args}")
    
    def do_GET(self):
        print(f"\n  >>> CALLBACK RECEIVED: {self.path}")
        print(f"  >>> Headers: {dict(self.headers)}")
        
        if self.path == '/' or self.path.startswith('/?'):
            # This is the initial redirect from Oracle
            # The token is in the URL fragment (#security_token=...)
            # But the browser sends only the path, not the fragment
            # The CLI handles this with a JS page that reads the fragment
            # and sends it as a query param
            javascript = """
            <script type='text/javascript'>
                hash = window.location.hash
                if (hash[0] === '#') {
                    hash = hash.substr(1)
                }
                console.log(hash)
                function reqListener () {
                    console.log(this.responseText);
                    document.write('Authorization completed! Please close this window and return to the terminal.');
                }
                var oReq = new XMLHttpRequest();
                oReq.addEventListener("load", reqListener);
                oReq.open("GET", "/token?" + hash);
                oReq.send();
            </script>
            """
            self.send_response(200)
            self.end_headers()
            try:
                self.wfile.write(javascript.encode('UTF-8'))
            except Exception:
                pass
        elif self.path.startswith('/token?'):
            # The JS on the redirect page sends the token here
            query_components = parse_qs(urlparse(self.path).query)
            print(f"\n  >>> TOKEN QUERY PARAMS: {list(query_components.keys())}")
            
            if 'security_token' in query_components:
                token = query_components['security_token'][0]
                CallbackHandler.captured_token = token
                print(f"\n  >>> SECURITY TOKEN CAPTURED!")
                print(f"  >>> Token length: {len(token)} chars")
                print(f"  >>> Token prefix: {token[:50]}...")
                
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'OK')
            else:
                print(f"  >>> No security_token in query params")
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'No token found')
        else:
            print(f"  >>> Unknown path: {self.path}")
            self.send_response(404)
            self.end_headers()
    
    def do_POST(self):
        print(f"\n  >>> POST RECEIVED: {self.path}")
        self.send_response(200)
        self.end_headers()


class StoppableHTTPServer(HTTPServer):
    def serve_forever(self):
        self.stop = False
        self.ret_value = None
        while not self.stop:
            self.handle_request()
        self.server_close()
        return self.ret_value


def main():
    parser = argparse.ArgumentParser(description='OCI Auth Harness')
    parser.add_argument('--region', default='uk-london-1',
                       help='OCI region (default: uk-london-1)')
    parser.add_argument('--upload-api-key', action='store_true',
                       help='Upload the public key as an API key after auth')
    parser.add_argument('--tenancy-name', default=None,
                       help='Tenancy name (optional)')
    args = parser.parse_args()
    
    print("=" * 60)
    print("  OCI AUTH HARNESS")
    print("=" * 60)
    print()
    
    # Step 1: Generate RSA keypair
    print("[1] Generating RSA 2048-bit keypair...")
    private_key, public_key = generate_keypair()
    print(f"    Private key: {private_key.key_size} bits")
    print(f"    Public key: {public_key.public_numbers().n.bit_length()} bits")
    print()
    
    # Step 2: Encode public key as JWK
    print("[2] Building JWK from public key...")
    jwk_json, jwk_b64 = public_key_to_jwk(public_key)
    print(f"    JWK JSON: {jwk_json}")
    print()
    
    # Step 3: Verify the JWK
    print("[3] Verifying JWK...")
    jwk_obj = json.loads(jwk_json)
    assert jwk_obj['kty'] == 'RSA', f"kty should be RSA, got {jwk_obj['kty']}"
    assert jwk_obj['e'] == 'AQAB', f"e should be AQAB, got {jwk_obj['e']}"
    assert 'n' in jwk_obj, "n field missing"
    
    # Verify n is valid base64url
    n_bytes = base64.urlsafe_b64decode(jwk_obj['n'])
    assert len(n_bytes) == 256, f"n should be 256 bytes for 2048-bit RSA, got {len(n_bytes)}"
    
    # Verify no + or / in n (should be base64url, not base64)
    assert '+' not in jwk_obj['n'], "n contains + (should be base64url)"
    assert '/' not in jwk_obj['n'], "n contains / (should be base64url)"
    
    print(f"    kty: {jwk_obj['kty']} OK")
    print(f"    e: {jwk_obj['e']} OK")
    print(f"    n: {len(jwk_obj['n'])} chars, {len(n_bytes)} bytes OK")
    print(f"    No + or / in n: OK")
    print(f"    base64url(JWK): {jwk_b64[:60]}...")
    print()
    
    # Step 4: Compute fingerprint
    fingerprint = public_key_fingerprint(public_key)
    print(f"[4] Fingerprint: {fingerprint}")
    print()
    
    # Step 5: Build authorize URL
    redirect_uri = f"http://localhost:{BOOTSTRAP_PORT}"
    print(f"[5] Building authorize URL...")
    print(f"    Region: {args.region}")
    print(f"    Redirect URI: {redirect_uri}")
    auth_url = build_authorize_url(args.region, jwk_b64, redirect_uri, args.tenancy_name)
    print(f"    URL: {auth_url[:100]}...")
    print()
    
    # Step 6: Start local HTTP listener
    print(f"[6] Starting local HTTP listener on port {BOOTSTRAP_PORT}...")
    server = StoppableHTTPServer(('', BOOTSTRAP_PORT), CallbackHandler)
    print(f"    Listener running")
    print()
    
    # Step 7: Open browser
    print(f"[7] Opening browser to Oracle login...")
    print(f"    Please complete login in the browser.")
    print(f"    The harness will capture the callback automatically.")
    print()
    
    try:
        webbrowser.open_new(auth_url)
    except Exception as e:
        print(f"    Could not open browser: {e}")
        print(f"    Open this URL manually: {auth_url}")
    
    # Step 8: Wait for callback
    print(f"[8] Waiting for callback (timeout: 5 minutes)...")
    print(f"    Every redirect will be logged below.")
    print()
    
    import threading
    def timeout():
        import time
        time.sleep(300)
        if CallbackHandler.captured_token is None:
            print("\n  >>> TIMEOUT: No callback received within 5 minutes.")
            server.stop = True
    
    timer = threading.Thread(target=timeout, daemon=True)
    timer.start()
    
    token = server.serve_forever()
    
    if token:
        print()
        print("=" * 60)
        print("  AUTH SUCCESSFUL!")
        print("=" * 60)
        print()
        
        # Decode the JWT
        print("[9] Decoding security token (JWT)...")
        try:
            from oci.auth.security_token_container import SecurityTokenContainer
            stc = SecurityTokenContainer(None, security_token=token)
            jwt_data = stc.get_jwt()
            user_ocid = jwt_data.get('sub', 'unknown')
            tenancy_ocid = jwt_data.get('tenant', 'unknown')
            exp = jwt_data.get('exp', 'unknown')
            
            print(f"    User OCID: {user_ocid}")
            print(f"    Tenancy OCID: {tenancy_ocid}")
            print(f"    Expires: {exp}")
            print()
        except Exception as e:
            print(f"    JWT decode error: {e}")
            print(f"    Token (first 200 chars): {token[:200]}...")
            print()
        
        # Optional: Upload API key
        if args.upload_api_key:
            print("[10] Uploading public key as API key...")
            from oci.signer import Signer
            from oci import identity
            from cryptography.hazmat.primitives import serialization
            
            # Create signer with the security token
            priv_pem = private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption()
            )
            signer = oci.auth.signers.SecurityTokenSigner(token, priv_pem)
            
            # Find home region
            client = identity.IdentityClient({'region': args.region}, signer=signer)
            subscriptions = client.list_region_subscriptions(tenancy_ocid)
            home_region = None
            for sub in subscriptions.data:
                if sub.is_home_region:
                    home_region = sub.region_name
                    break
            print(f"    Home region: {home_region}")
            
            # Use home region client
            client = identity.IdentityClient({'region': home_region}, signer=signer)
            
            # Upload API key
            pub_pem = public_key.public_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PublicFormat.SubjectPublicKeyInfo
            ).decode('UTF-8')
            
            create_details = identity.models.CreateApiKeyDetails()
            create_details.key = pub_pem
            
            try:
                result = client.upload_api_key(user_ocid, create_details)
                print(f"    API key uploaded! Fingerprint: {fingerprint}")
                print(f"    User OCID: {user_ocid}")
                print(f"    Tenancy OCID: {tenancy_ocid}")
                print(f"    Region: {home_region}")
                print()
                print("    You can now use API key signing for all OCI API calls.")
            except oci.exceptions.ServiceError as e:
                if e.status == 409 and e.code == 'ApiKeyLimitExceeded':
                    print(f"    API key limit exceeded. Existing keys:")
                    keys = client.list_api_keys(user_ocid)
                    for k in keys.data:
                        print(f"      {k.fingerprint} (created {k.time_created})")
                else:
                    raise
        else:
            print("[10] Skipping API key upload (use --upload-api-key to enable)")
            print(f"     Token is valid for ~60 minutes.")
            print(f"     Fingerprint: {fingerprint}")
    else:
        print()
        print("=" * 60)
        print("  AUTH FAILED — no token received")
        print("=" * 60)
    
    print()
    print("Done.")


if __name__ == '__main__':
    main()