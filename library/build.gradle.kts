import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.zxhhyj.composefloatwindow"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.doFirst {
                    file("${project.buildDir}/test-user-home").mkdirs()
                }
                it.systemProperty(
                    "user.home",
                    file("${project.buildDir}/test-user-home").absolutePath
                )
                it.systemProperty(
                    "robolectric.dependency.repo.url",
                    "https://repo1.maven.org/maven2"
                )
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

configure<MavenPublishBaseExtension> {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.None(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        )
    )

    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "compose-float-window", version.toString())

    pom {
        name = "ComposeFloatWindow"
        description = "A Compose-based Android floating window library with smooth drag, snap and lifecycle handling."
        inceptionYear = "2024"
        url = "https://github.com/kaus-io/compose-float-window"
        licenses {
            license {
                name = "MIT"
                url = "https://github.com/kaus-io/compose-float-window/blob/main/LICENSE"
                distribution = "https://github.com/kaus-io/compose-float-window/blob/main/LICENSE"
            }
        }
        organization {
            name = "kaus-io"
            url = "https://github.com/kaus-io"
        }
        developers {
            developer {
                id = "kaus-io"
                name = "kaus-io"
                url = "https://github.com/kaus-io"
                organization = "kaus-io"
                organizationUrl = "https://github.com/kaus-io"
            }
        }
        scm {
            url = "https://github.com/kaus-io/compose-float-window"
            connection = "scm:git:git://github.com/kaus-io/compose-float-window.git"
            developerConnection = "scm:git:ssh://git@github.com/kaus-io/compose-float-window.git"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/kaus-io/compose-float-window/issues"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.androidx.lifecycle.viewmodel)
}