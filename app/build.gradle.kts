// إعدادات وحدة التطبيق: Kotlin + Jetpack Compose، minSdk 26
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.oqod.textgrabber"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oqod.textgrabber"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // توقيع بمفتاح Debug القياسي لتسهيل تثبيت نسخة الإصدار (release) مباشرة
        // على الأجهزة الحقيقية دون الحاجة لإعداد مفتاح توقيع رسمي بعد.
        getByName("debug") {}
    }

    buildTypes {
        release {
            // تصغير الكود في الإصدار النهائي
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM يوحّد إصدارات كل مكتبات Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
