plugins {
    kotlin("jvm") version "1.9.24"
    application
}
repositories { mavenCentral() }
dependencies { implementation("org.json:json:20240303") }
application { mainClass.set("VerifyKt") }

// Compile the SAME matcher/policy/engine sources the app ships - not a copy -
// so this tests exactly what runs on the phone. Recipe.kt and Recipes.kt are
// excluded: they need android.content.Context and android.graphics.Bitmap to
// LOAD a recipe from disk, which this JVM-only harness has no stub for. The
// plain data types Engine.kt actually operates on (Gate, Settle, TapSpec,
// Nudge, Recipe) have no android dependency of their own, so
// EngineTestStubs.kt declares equivalents in the same package - Engine.kt
// itself is compiled unmodified against them. If those classes change shape in
// Recipe.kt, mirror the change in EngineTestStubs.kt or this link goes stale.
sourceSets["main"].kotlin.apply {
    setSrcDirs(listOf("src/main/kotlin", "../../app/src/main/java/dev/autotapper/core"))
    include("Verify.kt", "PolicyCheck.kt", "AllocBench.kt", "SettleInterruptCheck.kt", "InterruptRepeatCheck.kt",
            "EngineTestStubs.kt",
            "**/Gray.kt", "**/Matcher.kt", "**/DismissPolicy.kt", "**/TextVision.kt",
            "**/Engine.kt")
}
