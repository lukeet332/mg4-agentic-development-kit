# Signing

ADK must be signed with the **same platform key as the head unit** — that signature
is what lets it claim `sharedUserId="android.uid.system"` and hold signature
permissions (INSTALL_PACKAGES, READ_LOGS, INJECT_EVENTS, WRITE_SECURE_SETTINGS…).

`platform.pk8.ci` / `platform.x509.pem.ci` are the **AOSP test key** (SHA-256
`c8:a2:e9:bc:cf:59:7c:2f:b6:dc:66:be:e2:93:fc:13:f2:fc:47:ec:77:bc:6b:2b:0d:52:c1:1f:51:19:2a:b8`),
which is what SAIC ships in MG4 firmware and what AOSP emulator images use. It is
public: it lives in the AOSP source tree, so committing it leaks nothing and makes
CI releases installable out of the box on any MG4.

The security boundary for this app is **the pairing token**, not the certificate.

## Using your own key

Set repository secrets `PLATFORM_PK8_B64` and `PLATFORM_CERT_B64` (base64 of the
PKCS#8 private key and the X.509 certificate); CI prefers them over the committed
test key. Locally:

```bash
PLATFORM_KEY=/path/platform.pk8 PLATFORM_CERT=/path/platform.x509.pem tools/build.sh
```

A build signed with a different key will **not** install over an existing ADK or
beside the car's platform-signed apps — you would have to uninstall first, which
loses the stored pairing tokens.
