# Source this before building: tools live on /data, not the home disk.
export JAVA_HOME=/data/jpl/jdk/jdk-21.0.13+9
export ANDROID_HOME=/data/jpl/android-sdk
export ANDROID_USER_HOME=/data/jpl/android-user
export GRADLE_USER_HOME=/data/jpl/gradle-home
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export ANDROID_AVD_HOME=/data/jpl/android-user/avd
