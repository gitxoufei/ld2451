plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "top.xfwork.fogradar"; compileSdk = 35
    defaultConfig { applicationId = "top.xfwork.fogradar"; minSdk = 26; targetSdk = 32; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
