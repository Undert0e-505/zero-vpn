#!/usr/bin/env python3
"""
OCI Auth Step 1 — Browser auth, save token + keys to files.
"""
import base64, json, os, uuid, webbrowser, hashlib, time, threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from datetime import datetime

import oci
from oci.regions import REGIONS, REALMS, REGION_REALMS, REGIONS_SHORT_NAMES, is_region

OUT_DIR = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(OUT_DIR, "auth-step.log")

def log(msg):
    line = f"[{datetime.now().strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    with open(LOG, 'a') as f:
        f.write(line + '\n')

PORT = 8181

def bytes_from_int(val):
    remaining = val; bl = 0
    while remaining != 0: remaining >>= 8; bl += 1
    return val.to_bytes(bl, 'big', signed=False)

def b64url(data):
    return base64.urlsafe_b64encode(data).replace(b'=', b'')

def b64url_uint(val):
    b = bytes_from_int(val)
    if not b: b = b'\x00'
    return b64url(b)

def main():
    region = "uk-london-1"
    
    # Clear log
    if os.path.exists(LOG): os.remove(LOG)
    log("=== OCI Auth Step ===")
    
    # Generate keypair
    log("Generating RSA 2048 keypair...")
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pub = priv.public_key()
    numbers = pub.public_numbers()
    
    # JWK (exact CLI format)
    n = b64url_uint(numbers.n).decode()
    e = b64url_uint(numbers.e).decode()
    jwk_json = json.dumps({"kty":"RSA","n":n,"e":e,"kid":"Ignored"})
    jwk_b64 = base64.urlsafe_b64encode(jwk_json.encode()).decode()
    log(f"JWK: {jwk_json[:80]}...")
    
    # Fingerprint
    der = pub.public_bytes(encoding=serialization.Encoding.DER, format=serialization.PublicFormat.SubjectPublicKeyInfo)
    fp = ':'.join(f'{b:02x}' for b in hashlib.sha256(der).digest())
    log(f"Fingerprint: {fp}")
    
    # Build auth URL
    if region in REGIONS_SHORT_NAMES: region = REGIONS_SHORT_NAMES[region]
    realm = REALMS[REGION_REALMS[region]]
    url = f"https://login.{region}.{realm}/v1/oauth2/authorize?" + urlencode({
        'action': 'login', 'client_id': 'iaas_console',
        'response_type': 'token id_token', 'nonce': str(uuid.uuid4()),
        'scope': 'openid', 'public_key': jwk_b64,
        'redirect_uri': f'http://localhost:{PORT}',
    })
    log(f"Auth URL: {url[:100]}...")
    
    # Local HTTP server
    token_holder = {'token': None}
    
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            log(f"  HTTP: {fmt % args}")
        def do_GET(self):
            log(f"  HTTP GET: {self.path}")
            if self.path == '/' or self.path.startswith('/?'):
                self.send_response(200); self.end_headers()
                self.wfile.write(b"""<script>h=window.location.hash;if(h[0]==='#')h=h.substr(1);
                var r=new XMLHttpRequest();r.onload=function(){document.write('OK')};
                r.open('GET','/token?'+h);r.send();</script>""")
            elif self.path.startswith('/token?'):
                p = parse_qs(urlparse(self.path).query)
                if 'security_token' in p:
                    token_holder['token'] = p['security_token'][0]
                    log("  Token captured!")
                self.send_response(200); self.end_headers()
                self.wfile.write(b'OK')
            else:
                self.send_response(404); self.end_headers()
    
    server = HTTPServer(('', PORT), Handler)
    
    def timeout():
        time.sleep(300)
        if token_holder['token'] is None:
            log("TIMEOUT"); server.shutdown()
    threading.Thread(target=timeout, daemon=True).start()
    
    log(f"Opening browser (port {PORT})...")
    webbrowser.open_new(url)
    log("Waiting for callback...")
    
    server.handle_request()  # first request (redirect page)
    server.handle_request()  # second request (/token?...)
    server.server_close()
    
    token = token_holder['token']
    if not token:
        log("FAILED: no token"); return
    
    # Decode JWT
    stc = oci.auth.security_token_container.SecurityTokenContainer(None, security_token=token)
    jwt = stc.get_jwt()
    user_ocid = jwt['sub']
    tenancy_ocid = jwt['tenant']
    
    log(f"User OCID: {user_ocid}")
    log(f"Tenancy OCID: {tenancy_ocid}")
    log("AUTH SUCCESS")
    
    # Save everything to files for subsequent steps
    priv_pem = priv.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    pub_pem = pub.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    
    with open(os.path.join(OUT_DIR, "auth-state.json"), 'w') as f:
        json.dump({
            'user_ocid': user_ocid,
            'tenancy_ocid': tenancy_ocid,
            'fingerprint': fp,
            'region': region,
        }, f, indent=2)
    with open(os.path.join(OUT_DIR, "oci-key.pem"), 'wb') as f:
        f.write(priv_pem)
    with open(os.path.join(OUT_DIR, "oci-key.pub"), 'wb') as f:
        f.write(pub_pem)
    with open(os.path.join(OUT_DIR, "token.txt"), 'w') as f:
        f.write(token)
    
    log(f"Saved: auth-state.json, oci-key.pem, oci-key.pub, token.txt")

if __name__ == '__main__':
    main()