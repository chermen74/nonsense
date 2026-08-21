"""
Print the certificate that actually signed an APK.

keytool only reads v1 JAR signatures, and AGP signs v2/v3 only for minSdk 24+,
so the certificate has to come out of the APK Signing Block by hand.

    python3 apk-signer.py app.apk            # prints scheme, sha256, subject
    python3 apk-signer.py app.apk EXPECTED   # and fails if it is not EXPECTED
"""

import struct, sys, hashlib, subprocess

def signer_cert(path):
    d = open(path, 'rb').read()
    # end of central directory -> central directory offset
    i = d.rfind(b'PK\x05\x06')
    cd_off = struct.unpack_from('<I', d, i + 16)[0]
    # APK Signing Block sits immediately before the central directory
    magic = d[cd_off - 16:cd_off]
    if magic != b'APK Sig Block 42':
        return None, 'no APK Signing Block (v1-only or unsigned)'
    size2 = struct.unpack_from('<Q', d, cd_off - 24)[0]
    start = cd_off - size2 - 8
    p = start + 8
    end = cd_off - 24
    blocks = {}
    while p < end:
        ln = struct.unpack_from('<Q', d, p)[0]
        bid = struct.unpack_from('<I', d, p + 8)[0]
        blocks[bid] = d[p + 12:p + 8 + ln]
        p += 8 + ln
    for bid in (0x7109871a, 0xf05368c0):        # v2, v3
        if bid not in blocks:
            continue
        v = blocks[bid]
        def seq(buf):
            out, q = [], 0
            while q + 4 <= len(buf):
                n = struct.unpack_from('<I', buf, q)[0]
                out.append(buf[q + 4:q + 4 + n]); q += 4 + n
            return out
        signers = seq(seq(v)[0]) if seq(v) else []
        if not signers:
            continue
        signed_data = seq(signers[0])[0]
        certs = seq(seq(signed_data)[1])
        if certs:
            return certs[0], ('v2' if bid == 0x7109871a else 'v3')
    return None, 'no v2/v3 signer found'

der, scheme = signer_cert(sys.argv[1])
if der is None:
    print('!!', scheme)
    sys.exit(1)
got = hashlib.sha256(der).hexdigest()
print('scheme:', scheme)
print('sha256:', got)
open('/tmp/cert.der', 'wb').write(der)
subj = subprocess.run(['openssl', 'x509', '-inform', 'DER', '-in', '/tmp/cert.der',
                       '-noout', '-subject', '-startdate'],
                      capture_output=True, text=True)
print(subj.stdout.strip() or subj.stderr.strip())

if len(sys.argv) > 2:
    want = sys.argv[2].strip().lower().replace(':', '')
    if got != want:
        print(f'!! signed by {got}, expected {want}')
        print('!! a build signed by a key that is not the committed one cannot')
        print('!! install over the last release, and trips Play Protect afresh.')
        sys.exit(1)
    print('signer matches the expected key')
