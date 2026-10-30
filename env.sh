# Source this before building. Defaults are this build machine's; override any of them first.
export JAVA_HOME=${JAVA_HOME:-/data/jpl/jdk/jdk-21.0.13+9}
export ANDROID_HOME=${ANDROID_HOME:-/data/jpl/android-sdk}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-/data/jpl/android-user}
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/data/jpl/gradle-home}
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
