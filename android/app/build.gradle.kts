plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose"); kotlin("plugin.serialization") }
android {
    namespace = "com.blackbox.diary"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.blackbox.diary"
        minSdk = 34
        targetSdk = 35
        versionCode = 6
        versionName = "1.3.2-daily-summary"
        buildConfigField("boolean","FAULT_TESTING","false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        getByName("debug") { buildConfigField("boolean","FAULT_TESTING","false") }
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        debug { applicationIdSuffix = ".debug" }
        create("fault") { initWith(getByName("debug"));applicationIdSuffix=".fault";matchingFallbacks+=listOf("debug");buildConfigField("boolean","FAULT_TESTING","true") }
        create("benchmark") { initWith(getByName("release"));applicationIdSuffix=".benchmark";signingConfig=signingConfigs.getByName("debug");matchingFallbacks+=listOf("release") }
    }
    testBuildType=if(providers.gradleProperty("faultTests").isPresent)"fault" else "debug"
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    packaging { jniLibs.useLegacyPackaging=true;jniLibs.pickFirsts+=setOf("**/libc++_shared.so") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core"))
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom); androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
    implementation("androidx.sqlite:sqlite:2.5.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))
    implementation(files("libs/onnxruntime-ocr-isolated-1.21.0.aar"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
