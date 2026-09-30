# Release signing

Distributed APKs use the dedicated `globaltranslation` release key. The private
keystore is `keystore/globaltranslation-release.jks` and is ignored by Git. Its
password is stored in the macOS login Keychain under service
`GlobalTranslation Android Release` and account `zh0ngtian`.

Build and verify a distributable APK with:

```bash
scripts/build-signed-release.sh
```

The Gradle release build reads these environment variables:

- `GT_RELEASE_STORE_FILE`
- `GT_RELEASE_STORE_PASSWORD`
- `GT_RELEASE_KEY_ALIAS`
- `GT_RELEASE_KEY_PASSWORD`

All four must be present together. Without them, `assembleRelease` still creates
an unsigned artifact for static build verification, but it must not be
distributed. The delivery script refuses Android Debug certificates.

`globaltranslation-release-cert.pem` is the public certificate used to register
`io.github.zh0ngtian.globaltranslation` with Android Developer Console. It does
not contain the private key.

Back up the private keystore and its password independently. Losing the private
key prevents signing compatible app updates. Never commit the keystore or its
password, put either in build logs, or include either in an APK.
