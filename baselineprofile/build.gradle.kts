plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "io.github.mangi.eta.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        // 与 app 模块保持一致：toolchain 25。
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    // 生成基准配置文件的被测应用。
    targetProjectPath = ":app"
}

baselineProfile {
    // 默认在本地连接的实体机 / 模拟器上生成（Gradle 管理的设备需要 aosp 镜像与 root）。
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
}
