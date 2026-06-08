# Release signing keystore

Place the team release keystore here (`release.jks`). Do not commit passwords.

1. Copy `keystore.properties.example` → `keystore.properties`
2. Fill in `storePassword`, `keyAlias`, `keyPassword`
3. Build signed release: `./gradlew assembleRelease`

`keystore.properties` and `*.jks` are gitignored.
