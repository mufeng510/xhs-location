plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android") version "2.50"
    id("org.jetbrains.kotlin.kapt")
}

import java.util.Properties

android {
    namespace = "com.mufeng.xhs.location"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mufeng.xhs.location"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val signingPropsFile = rootProject.file("signing.properties")
            val props = Properties()
            if (signingPropsFile.exists()) {
                signingPropsFile.inputStream().use { props.load(it) }
            }
            storeFile = file(props.getProperty("storeFile", ""))
            storePassword = props.getProperty("storePassword", "")
            keyAlias = props.getProperty("keyAlias", "")
            keyPassword = props.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val signingPropsFile = rootProject.file("signing.properties")
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // CI 环境不存在 provider 专用 keystore（signing.properties 被 gitignore，
                // 且 storeFile 指向本地绝对路径）。回退到 AGP 自动创建的 debug 签名配置，
                // 使 release APK 在 CI 中仍能成功 assemble，仅用于构建 artifact，
                // 不用于实际生产发布。
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.12"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM manages all Compose library versions
    implementation(platform(libs.compose.bom))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.material3)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.container)
    implementation(libs.androidx.annotation)

    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.hilt.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)

    implementation(libs.maplibre.android)

    kapt(libs.hilt.compiler)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.google.truth:truth:1.1.5")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("com.google.truth:truth:1.1.5")
}

kapt {
    correctErrorTypes = true
}
