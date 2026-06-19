#!/usr/bin/env python3
"""Step 2: Upload API key and test authenticated API calls using OCI config file."""
import json, os, base64, hashlib
from cryptography.hazmat.primitives import serialization
from datetime import datetime
import oci
from oci.identity import IdentityClient
from oci.core import ComputeClient
from oci.identity import models as id_models

DIR = os.path.dirname(os.path.abspath(__file__))

def log(msg):
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)

def compute_fingerprint(public_key):
    """OCI API key fingerprint = MD5 of the DER public key bytes (not SHA-256)."""
    pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )
    header = b'-----BEGIN PUBLIC KEY-----'
    footer = b'-----END PUBLIC KEY-----'
    pem_stripped = pem.replace(header, b'').replace(footer, b'').replace(b'\n', b'')
    der_bytes = base64.b64decode(pem_stripped)
    md5_hex = hashlib.md5(der_bytes).hexdigest()
    return ':'.join(a + b for a, b in zip(md5_hex[::2], md5_hex[1::2]))

def main():
    with open(os.path.join(DIR, "auth-state.json")) as f:
        state = json.load(f)
    with open(os.path.join(DIR, "token.txt")) as f:
        token = f.read()
    with open(os.path.join(DIR, "oci-key.pem"), 'rb') as f:
        priv_pem = f.read()
    from cryptography.hazmat.primitives.serialization import load_pem_private_key
    priv_key_obj = load_pem_private_key(priv_pem, password=None)

    user_ocid = state['user_ocid']
    tenancy_ocid = state['tenancy_ocid']
    region = state['region']

    # Compute the CORRECT fingerprint (MD5, not SHA-256)
    fingerprint = compute_fingerprint(priv_key_obj.public_key())
    log(f"Correct fingerprint (MD5): {fingerprint}")

    # Step 2a: Find home region
    log("")
    log("=== Finding home region ===")
    token_signer = oci.auth.signers.SecurityTokenSigner(token, priv_key_obj)
    id_client = IdentityClient({'region': region}, signer=token_signer)
    subs = id_client.list_region_subscriptions(tenancy_ocid)
    home_region = None
    for s in subs.data:
        log(f"  Region: {s.region_name} (home={s.is_home_region})")
        if s.is_home_region:
            home_region = s.region_name
    if not home_region:
        home_region = region
    log(f"  Home region: {home_region}")

    # Step 2b: Upload API key (using security token)
    log("")
    log("=== Uploading API key ===")
    home_client = IdentityClient({'region': home_region}, signer=token_signer)
    with open(os.path.join(DIR, "oci-key.pub"), 'r') as f:
        pub_pem = f.read()
    create_details = id_models.CreateApiKeyDetails()
    create_details.key = pub_pem
    try:
        home_client.upload_api_key(user_ocid, create_details)
        log(f"  API key uploaded! Fingerprint: {fingerprint}")
    except oci.exceptions.ServiceError as e:
        if e.status == 409:
            log(f"  API key already exists: {fingerprint}")
        else:
            raise

    # Step 2c: Write OCI config file for API key auth
    log("")
    log("=== Writing OCI config ===")
    config_path = os.path.join(DIR, "oci-config.txt")
    with open(config_path, 'w') as f:
        f.write(f"""[DEFAULT]
user = {user_ocid}
tenancy = {tenancy_ocid}
fingerprint = {fingerprint}
key_file = {os.path.join(DIR, 'oci-key.pem').replace(chr(92), '/')}
region = {home_region}
""")
    log(f"  Config: {config_path}")

    # Step 2d: Test API key auth — list ADs
    log("")
    log("=== Testing API key auth (list ADs) ===")
    config = oci.config.from_file(config_path)
    log(f"  Config region: {config['region']}")
    log(f"  Config fingerprint: {config['fingerprint']}")
    
    id_client = IdentityClient(config)
    ads = id_client.list_availability_domains(compartment_id=tenancy_ocid)
    for ad in ads.data:
        log(f"  AD: {ad.name}")

    # Step 2e: Find Ubuntu image
    log("")
    log("=== Finding Ubuntu image ===")
    compute = ComputeClient(config)
    images = compute.list_images(
        compartment_id=tenancy_ocid,
        operating_system="Canonical Ubuntu",
        operating_system_version="22.04",
        shape="VM.Standard.E2.1.Micro",
        sort_by="TIMECREATED",
        sort_order="DESC",
    )
    if images.data:
        image_id = images.data[0].id
        log(f"  Image: {image_id}")
        log(f"  OS: {images.data[0].operating_system} {images.data[0].operating_system_version}")
    else:
        log("  Ubuntu 22.04 not found, trying 24.04...")
        images = compute.list_images(
            compartment_id=tenancy_ocid,
            operating_system="Canonical Ubuntu",
            operating_system_version="24.04",
            shape="VM.Standard.E2.1.Micro",
            sort_by="TIMECREATED",
            sort_order="DESC",
        )
        if images.data:
            image_id = images.data[0].id
            log(f"  Image: {image_id}")
        else:
            log("  FAILED: No Ubuntu image found")
            return

    # Save state
    state['home_region'] = home_region
    state['fingerprint'] = fingerprint
    state['image_id'] = image_id
    state['ad_name'] = ads.data[0].name
    with open(os.path.join(DIR, "auth-state.json"), 'w') as f:
        json.dump(state, f, indent=2)

    log("")
    log("=== Step 2 complete ===")
    log(f"  Home region: {home_region}")
    log(f"  AD: {ads.data[0].name}")
    log(f"  Image: {image_id}")
    log(f"  Fingerprint: {fingerprint}")
    log(f"  API key auth working!")

if __name__ == '__main__':
    main()