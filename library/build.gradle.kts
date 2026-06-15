import com.vanniktech.maven.publish.MavenPublishBaseExtension
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
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), project.name, version.toString())

    pom {
        name = project.name.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        description = "A Compose-based Android floating window library with smooth drag, snap and lifecycle handling."
        inceptionYear = "2024"
        url = "https://github.com/zxhhyj/compose-float-window"
        licenses {
            license {
                name = "MIT"
                url = "https://github.com/zxhhyj/compose-float-window/blob/main/LICENSE"
                distribution = "https://github.com/zxhhyj/compose-float-window/blob/main/LICENSE"
            }
        }
        organization {
            name = "zxhhyj"
            url = "https://github.com/zxhhyj"
        }
        developers {
            developer {
                id = "zxhhyj"
                name = "zxhhyj"
                url = "https://github.com/zxhhyj"
                organization = "zxhhyj"
                organizationUrl = "https://github.com/zxhhyj"
            }
        }
        scm {
            url = "https://github.com/zxhhyj/compose-float-window"
            connection = "scm:git:git://github.com/zxhhyj/compose-float-window.git"
            developerConnection = "scm:git:ssh://git@github.com/zxhhyj/compose-float-window.git"
        }
        issueManagement {
            system = "GitHub"
            url = "https://github.com/zxhhyj/compose-float-window/issues"
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
