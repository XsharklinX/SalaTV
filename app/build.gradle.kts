import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// El token de TMDB vive en local.properties (fuera del repositorio): tmdb.token=eyJ...
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}

android {
    namespace = "com.salatv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.salatv.app"
        // Fire OS 7 (Android 9) en adelante; la tele del salón lleva Fire OS 8 (Android 11).
        minSdk = 28
        // 30 = Android 11, el de Fire OS 8. Subirlo cerraría la lectura de ajustes como el DNS privado.
        targetSdk = 30
        versionCode = 3
        versionName = "0.3.0"
        resValue("string", "tmdb_token", local.getProperty("tmdb.token", ""))
    }

    testOptions {
        // Las pruebas son de lógica pura; lo poco de Android que tocan devuelve valores por defecto.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Se instala por ADB, no va a ninguna tienda: basta con la clave de pruebas.
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
    lint {
        // targetSdk 30 es a propósito (ver arriba); lint lo marca como error para Play.
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += listOf("kotlin/**", "META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
        }
    }
}

dependencies {
    // Solo para las pruebas en el PC; no entra en el APK.
    testImplementation("junit:junit:4.13.2")
}
