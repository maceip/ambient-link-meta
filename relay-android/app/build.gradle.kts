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
  namespace = "com.lowkey.ambientlink"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.lowkey.ambientlink"
    minSdk = 31
    targetSdk = 35
    versionCode = 4
    versionName = "0.1.3-agent-voice"

    manifestPlaceholders["mwdat_application_id"] =
      providers.gradleProperty("mwdat_application_id").orNull
        ?: localProperties.getProperty("mwdat_application_id", "")
    manifestPlaceholders["mwdat_client_token"] =
      providers.gradleProperty("mwdat_client_token").orNull
        ?: localProperties.getProperty("mwdat_client_token", "")

    // String resources so PackageManager returns APPLICATION_ID as String, not coerced Long.
    resValue("string", "mwdat_application_id", manifestPlaceholders["mwdat_application_id"] as String)
    resValue("string", "mwdat_client_token", manifestPlaceholders["mwdat_client_token"] as String)
    val defaultRelay = providers.gradleProperty("relay_url").orNull
      ?: localProperties.getProperty("relay_url", "wss://agent.public.computer/ambient-link/ws")
    buildConfigField("String", "DEFAULT_RELAY_URL", "\"" + defaultRelay + "\"")
    buildConfigField("String", "SODA_PACK_CPU_SHA256", "\"fac23ca956f473c5025621784a1657a1663a16b0754886b975f3cde3f1345f04\"")
    buildConfigField("long", "SODA_PACK_CPU_SIZE_BYTES", "56458465L")

    ndk {
      abiFilters += setOf("arm64-v8a")
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    jniLibs { useLegacyPackaging = true }
  }
}

dependencies {
  // Shared vendor-neutral contract + primitives (SttEngine implemented by
  // SodaDictationEngine; also GlassLink/EphemeralBuffer/Throttle/Session/RelayClient/
  // WearPaths). Resolved via the composite build wired in settings.gradle.kts.
  implementation("com.ambientlink:core-android:0.1.0")

  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.browser)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.haze)
  implementation(libs.haze.materials)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.display)
  implementation(libs.okhttp)
  implementation("com.getkeepsafe.relinker:relinker:1.4.5")
  implementation("com.google.guava:guava:33.6.0-android")
  implementation("com.google.protobuf:protobuf-javalite:4.34.1")
  implementation(files("libs/recovered-soda.jar"))
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("junit:junit:4.13.2")
}
