# Overhand release key (OWNER)

The unique release key is `overhand-release.jks` in this directory, alias
`overhand-release`. Never commit it or replace it for an update of this app.
Generated using RSA 3072, SHA384withRSA, validity 10,000 days.

Passwords are randomly generated and stored only in the ignored
`native/overhand/local.properties`, under `RELEASE_STORE_PASSWORD` and
`RELEASE_KEY_PASSWORD`. OWNER: retrieve them privately from that file; do not paste
them into chat, reports, shell history, or tracked documentation. The alias is
`RELEASE_KEY_ALIAS`. No Android SDK path is required when ANDROID_HOME is set.

Gradle reads each setting from the environment first, then local.properties.
Optional `RELEASE_KEYSTORE` is an absolute path or relative to `native/overhand`;
the default is `keystore/overhand-release.jks`. Release signing explicitly enables
APK v2. Debug uses Android's separate debug signer and needs no release passwords.

OWNER by Nov 10: back up the JKS **and both passwords/alias** in an encrypted
off-machine vault; confirm recovery before this task worktree is removed. Backup
is not performed by this task. Ignored files may be moved to autoflow's task
artifact archive on completion; copy the key and credentials into the main
checkout's matching ignored paths for subsequent builds, without overwriting
any existing key. Compare the certificate SHA256 against the release report.
The APK and key are private local artifacts, not source-control deliverables.
