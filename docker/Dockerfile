# DiPlay APK build environment: JDK 25 + Android SDK (platform 37, build-tools 36/37, NDK 28.2).
# Source, Gradle cache, runtime assets and signing key are mounted at run time, never baked in.
ARG BASE_IMAGE=eclipse-temurin:25-jdk-noble
FROM ${BASE_IMAGE}

ARG CMDLINE_TOOLS_VERSION=16111833
ARG ANDROID_PLATFORM=android-37.0
ARG ANDROID_BUILD_TOOLS=37.0.0
# AGP's default build-tools; installed up front so a build does not download it into the throwaway container.
ARG ANDROID_EXTRA_BUILD_TOOLS=36.0.0
# shared/build.gradle builds native code with ndkBuild and pins this NDK. Empty skips it.
ARG ANDROID_NDK=28.2.13676358

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    ANDROID_USER_HOME=/gradle/.android \
    HOME=/gradle \
    JAVA_TOOL_OPTIONS=-Duser.home=/gradle \
    LANG=C.UTF-8
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git unzip \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools \
 && curl -fsSL -o /tmp/cmdline-tools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm -rf /tmp/cmdline-tools /tmp/cmdline-tools.zip

RUN yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install \
    "platform-tools" \
    "platforms;${ANDROID_PLATFORM}" \
    "build-tools;${ANDROID_BUILD_TOOLS}" \
    ${ANDROID_EXTRA_BUILD_TOOLS:+"build-tools;${ANDROID_EXTRA_BUILD_TOOLS}"} \
    ${ANDROID_NDK:+"ndk;${ANDROID_NDK}"} \
 && chmod -R a+rwX ${ANDROID_HOME} \
 && mkdir -p /gradle /workspace \
 && chmod 1777 /gradle /workspace

WORKDIR /workspace
CMD ["./gradlew", "--no-daemon", ":mobile:assembleRelease"]
