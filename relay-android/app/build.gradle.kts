import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties = Properties().apply {
  val f = rootProject.file("local.properties")
  if (f.exists()) f.inputStream().use(::load)
}

android {
  namespace = "com.lowkey.facechat"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.lowkey.facechat"
    minSdk = 31
    targetSdk = 35
    versionCode = 1
    versionName = "0.1"

    manifestPlaceholders["mwdat_application_id"] =
      providers.gradleProperty("mwdat_application_id").orNull
        ?: localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
      providers.gradleProperty("mwdat_client_token").orNull
        ?: localProperties.getProperty("mwdat_client_token", "")

    val defaultRelay = providers.gradleProperty("relay_url").orNull
      ?: localProperties.getProperty("relay_url", "wss://example.com/face-chat/ws")
    buildConfigField("String", "DEFAULT_RELAY_URL", "\"" + defaultRelay + "\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true; buildConfig = true }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.display)
  implementation(libs.okhttp)
}
