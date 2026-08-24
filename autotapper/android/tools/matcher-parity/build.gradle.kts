plugins {
    kotlin("jvm") version "1.9.24"
    application
}
repositories { mavenCentral() }
dependencies { implementation("org.json:json:20240303") }
application { mainClass.set("VerifyKt") }

// Compile the SAME matcher sources the app ships - not a copy - so this tests
// exactly what runs on the phone. The rest of core/ needs the Android SDK.
sourceSets["main"].kotlin.apply {
    setSrcDirs(listOf("src/main/kotlin", "../../app/src/main/java/dev/autotapper/core"))
    include("Verify.kt", "PolicyCheck.kt", "AllocBench.kt",
            "**/Gray.kt", "**/Matcher.kt", "**/DismissPolicy.kt", "**/TextVision.kt")
}
