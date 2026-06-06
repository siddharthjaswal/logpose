# Releasing LogPose

How to sign and publish the plugin to the JetBrains Marketplace.

## 1. Generate a signing key (once)

The Marketplace serves plugins over a signed chain so users know the artifact wasn't
tampered with. Generate your own key + self-signed certificate ([docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

```bash
# 1. Encrypted RSA private key (you'll be asked for a password — remember it)
openssl genpkey -aes-256-cbc -algorithm RSA \
  -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2. Decrypted key used by the signer
openssl rsa -in private_encrypted.pem -out private.pem

# 3. Self-signed certificate chain (valid 365 days)
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

> Keep `private.pem` / `private_encrypted.pem` **out of git** (they're not tracked).
> Treat them like any other signing secret.

## 2. Provide secrets via environment variables

`build.gradle.kts` reads these (the Gradle plugin auto-decodes Base64):

| Variable | Value |
|---|---|
| `CERTIFICATE_CHAIN` | contents of `chain.crt` |
| `PRIVATE_KEY` | contents of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | the password from step 1 |
| `PUBLISH_TOKEN` | a [Marketplace token](https://plugins.jetbrains.com/author/me/tokens) |

Locally you can export them; in CI add them as repository secrets. Example:

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD="…"
export PUBLISH_TOKEN="…"
```

## 3. Build, sign, verify

```bash
./gradlew buildPlugin     # unsigned zip (for Install-from-Disk)
./gradlew signPlugin      # signed zip in build/distributions/
./gradlew verifyPlugin    # JetBrains Plugin Verifier (compatibility check)
```

## 4. Publish

**First release is a manual upload** (JetBrains reviews new plugins):

1. Create a vendor account at <https://plugins.jetbrains.com>.
2. Upload the **signed** zip, fill the listing (description, screenshots, the demo GIF,
   license), and submit for review (≈1–3 business days).

**Subsequent releases** can be automated:

```bash
./gradlew publishPlugin   # uses PUBLISH_TOKEN; uploads to the 'default' channel
```

## Interceptor library (logpose-android)

So consumers don't need `mavenLocal`, publish the interceptor too. Quickest path is
**JitPack**: add a `jitpack.yml` that builds the `logpose-android` subproject, then tag a
release — consumers add the JitPack repo and `com.github.siddharthjaswal:logpose:<tag>`.
(Maven Central is the more "official" route but needs Sonatype + GPG signing.)
