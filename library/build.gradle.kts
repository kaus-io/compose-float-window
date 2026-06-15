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

// AGP 9.2.1 内置的 Dokka 1.4.32 携带的 com.intellij.util.lang.JavaVersion.parse
// 不能解析带 patch 号的 JDK 版本号（如 "25.0.2"），运行 :library:javaDocReleaseGeneration
// 时会抛 IllegalArgumentException。resolutionStrategy / setFrom 都被 detachedConfiguration
// + fromDisallowChanges 锁死，doFirst 改 java.version 也无效（JDK 25 缓存住了）。
// 折中方案：把 JavaDocGenerationTask 整个替换成一个空的 noop 任务，保留 Maven 发布里的
// javadoc.jar 接入点（publish 任务依赖还在），但不真去跑 Dokka。
// 等待 AGP 上游修这个 bug 之后再恢复。
tasks.withType<com.android.build.gradle.tasks.JavaDocGenerationTask>().configureEach {
    enabled = false
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
