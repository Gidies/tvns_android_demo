plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.wecare.tvns.sdkdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wecare.tvns.sdkdemo"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // SDK 只提供这两个 ABI，x86 模拟器跑不起来，请用真机或 arm 模拟器。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // 与 SDK 的交付态一致：不开混淆。这个 Demo 是用来验证集成的，
            // 混淆与否不影响 AAR 能不能用 —— 客户那边开不开是他的事。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // 用 AAR 而不是源码模块 —— 这一步本身就是在验证「客户拿到 AAR 能不能用」。
    implementation(files("libs/tvns-sdk-release.aar"))

    // files(...) 引入的 AAR，Gradle 不会去读它的 pom，传递依赖得自己补。
    // SDK 唯一的传递依赖就是这个。
    implementation(libs.androidx.annotation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.recyclerview)
}
