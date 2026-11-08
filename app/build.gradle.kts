import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * リリース署名。鍵とパスワードはリポジトリに置かない。
 *
 * 次の順に探し、最初に見つかった値を使う。
 *   1. 環境変数 TESLASPOT_STORE_FILE / TESLASPOT_STORE_PASSWORD /
 *      TESLASPOT_KEY_ALIAS / TESLASPOT_KEY_PASSWORD（GitHub Actions はこちら）
 *   2. プロジェクト直下の keystore.properties（ローカル用、.gitignore 済み）
 *
 * 4 つ揃っていなければ署名設定を作らない（assembleRelease は未署名 APK になる）。
 *
 * GitHub から入れ直して「上書きインストール」するには、毎回同じ鍵で署名する
 * 必要がある。鍵をなくすと、アンインストールしてから入れ直すしかなくなる。
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

val signStoreFile = signingValue("TESLASPOT_STORE_FILE", "storeFile")
val signStorePassword = signingValue("TESLASPOT_STORE_PASSWORD", "storePassword")
val signKeyAlias = signingValue("TESLASPOT_KEY_ALIAS", "keyAlias")
val signKeyPassword = signingValue("TESLASPOT_KEY_PASSWORD", "keyPassword")
val canSign = listOf(signStoreFile, signStorePassword, signKeyAlias, signKeyPassword).all { it != null } &&
    rootProject.file(signStoreFile!!).exists()

android {
    namespace = "io.github.makiiii_git.teslaspot"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.makiiii_git.teslaspot"
        // CompanionDeviceService の onDeviceAppeared(AssociationInfo) と
        // Bluetooth Classic の接続検知が揃うのが Android 13。
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = rootProject.file(signStoreFile!!)
                storePassword = signStorePassword
                keyAlias = signKeyAlias
                keyPassword = signKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

if (!canSign) {
    logger.lifecycle("TeslaSpot: 署名情報が揃っていないため、release は未署名になります")
}
