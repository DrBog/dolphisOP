package dev.autotapper.core

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * A recipe either shipped in the APK or saved by the user on the device.
 *
 * Bundled ones are read-only defaults. Saved ones live in external app storage,
 * so they survive an app update, are visible in a file manager, and can be added
 * without rebuilding anything. A saved recipe shadows a bundled one of the same
 * name, which is how you override a shipped default without losing it.
 */
data class RecipeRef(val name: String, val dir: File?) {
    val isUser: Boolean get() = dir != null
    val label: String get() = if (isUser) "$name  (saved)" else "$name  (built in)"
}

object Recipes {

    /** .../Android/data/dev.autotapper/files/recipes - no permission needed. */
    fun userRoot(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "recipes").apply { mkdirs() }

    fun bundled(ctx: Context): List<String> =
        (ctx.assets.list("recipes") ?: emptyArray()).toList()

    fun saved(ctx: Context): List<File> =
        userRoot(ctx).listFiles { f -> f.isDirectory && File(f, "recipe.json").isFile }
            ?.toList().orEmpty()

    fun list(ctx: Context): List<RecipeRef> {
        val user = saved(ctx).map { RecipeRef(it.name, it) }
        val taken = user.map { it.name }.toSet()
        val built = bundled(ctx).filter { it !in taken }.map { RecipeRef(it, null) }
        return (user + built).sortedBy { it.name.lowercase() }
    }

    fun find(ctx: Context, name: String, preferUser: Boolean): RecipeRef? {
        val all = list(ctx)
        return all.firstOrNull { it.name == name && it.isUser == preferUser }
            ?: all.firstOrNull { it.name == name }
    }

    /** Open a path relative to a recipe folder, from wherever that recipe lives. */
    fun opener(ctx: Context, ref: RecipeRef): (String) -> InputStream =
        if (ref.dir != null) {
            { rel -> File(ref.dir, rel).inputStream() }
        } else {
            { rel -> ctx.assets.open("recipes/${ref.name}/$rel") }
        }
}
