# Build Instructions

## Local Command Line Build
To build an unsigned debug APK:
```bash
./gradlew assembleDebug
```
The APK will be located at `app/build/outputs/apk/debug/`.

To build a release APK:
```bash
export KEYSTORE_FILE=/path/to/keystore
export KEY_ALIAS=alias
export KEYSTORE_PASSWORD=password
export KEY_PASSWORD=password
./gradlew assembleRelease
```

## Running Tests
Run unit tests with:
```bash
./gradlew test
```
