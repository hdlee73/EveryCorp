plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 서명키·서버 주소·앱 토큰은 모두 환경변수(GitHub Secrets/Variables)로만 받는다. 레포에는 값이 없다.
fun env(name: String): String = System.getenv(name).orEmpty()

android {
    namespace = "io.github.hdlee73.everycorp"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.hdlee73.everycorp"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("String", "API_BASE_URL", "\"${env("API_BASE_URL").ifBlank { "http://10.0.2.2:8000" }.trimEnd('/')}\"")
        buildConfigField("String", "APP_TOKEN", "\"${env("APP_TOKEN")}\"")
        buildConfigField("String", "AUTHOR_NAME", "\"이현덕\"")
        buildConfigField("String", "AUTHOR_EMAIL", "\"hdlee73@gmail.com\"")
        buildConfigField("String", "GITHUB_REPO", "\"hdlee73/EveryCorp\"")
    }

    signingConfigs {
        // 릴리스 서명키는 GitHub Secrets에서 CI가 파일로 풀어 경로를 넘긴다. 없으면 서명하지 않는다(로컬/포크 빌드).
        if (env("ANDROID_KEYSTORE_PATH").isNotBlank()) {
            create("release") {
                storeFile = file(env("ANDROID_KEYSTORE_PATH"))
                storePassword = env("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = env("ANDROID_KEY_ALIAS")
                keyPassword = env("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.browser:browser:1.8.0")

    // 네트워크 (Step 3)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
