plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
}

android {
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "com.swordfish.lemuroid.ext"
}

dependencies {
    implementation(project(":retrograde-util"))
    implementation(project(":retrograde-app-shared"))

    implementation(deps.libs.retrofit)
    implementation(deps.libs.kotlinxCoroutinesAndroid)

    // Retro TV: kopie zapisów w Google Drive (przeniesione z wariantu Play).
    implementation(deps.libs.gdrive.apiClient)
    implementation(deps.libs.gdrive.apiClientAndroid)
    implementation(deps.libs.gdrive.apiServicesDrive)
    implementation(deps.libs.play.playServices)
    implementation(deps.libs.play.coroutine)
    implementation(deps.libs.androidx.lifecycle.commonJava8)
    implementation(deps.libs.androidx.appcompat.constraintLayout)
    implementation(deps.libs.material)
}
