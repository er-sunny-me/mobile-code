# Local Development Setup

## Prerequisites
- Android Studio Ladybug or later
- JDK 17
- Minimum SDK 21, Target SDK 28

## Building the App
To build the app and run it in an emulator or device:
1. Clone the repository.
2. Open in Android Studio.
3. Sync Project with Gradle Files.
4. Run the `app` configuration.

## Understanding Termux Bootstraps
The app downloads a "bootstrap" zip file containing the basic Linux filesystem. This happens automatically during the Gradle build via the `downloadBootstraps` task in `app/build.gradle`.

> [!WARNING]
> Do not manually edit the `src/main/cpp/bootstrap-*` zip files. They will be verified via SHA256 hashes during the build.
