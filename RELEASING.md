# Releasing to Maven Central

## One-Time Setup

### 1. Sonatype OSSRH Account

1. Create an account at https://issues.sonatype.org/
2. Open a JIRA ticket requesting access to `com.3eai` group ID
3. Sonatype will ask you to verify domain ownership — add a DNS TXT record to `3eai.com`
4. Wait for approval (usually 1-2 business days)

### 2. GPG Key

```bash
# Generate key (RSA 4096, no expiry recommended for CI)
gpg --full-generate-key

# List keys to find your KEY_ID
gpg --list-keys

# Upload public key to keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export private key for GitHub Actions
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
```

### 3. GitHub Repository Secrets

Go to **Settings > Secrets and variables > Actions** and add:

| Secret | Value |
|--------|-------|
| `OSSRH_USERNAME` | Your Sonatype OSSRH username |
| `OSSRH_TOKEN` | Your Sonatype OSSRH token |
| `GPG_PRIVATE_KEY` | Contents of `private-key.asc` |
| `GPG_PASSPHRASE` | Your GPG key passphrase |

Delete `private-key.asc` after adding the secret.

## Release Process

### 1. Prepare the release

```bash
# Update version (remove -SNAPSHOT)
mvn versions:set -DnewVersion=0.1.0
git commit -am "release: v0.1.0"
```

### 2. Tag and push

```bash
git tag v0.1.0
git push origin main --tags
```

This triggers the Release workflow which:
- Builds the project
- Signs artifacts with GPG
- Deploys to Sonatype OSSRH staging repository
- Auto-releases to Maven Central

### 3. Set next development version

```bash
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT
git commit -am "chore: set next development version 0.2.0-SNAPSHOT"
git push origin main
```

### 4. Create GitHub Release (optional)

```bash
gh release create v0.1.0 --title "v0.1.0" --notes "Initial release"
```

## Verify

After ~30 minutes, check Maven Central:
https://central.sonatype.com/artifact/com.3eai/flowable-nats-channel
