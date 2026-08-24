package dev.autotapper.app

import android.content.Context
import android.net.Uri
import dev.autotapper.core.Recipe
import dev.autotapper.core.RecipeRef
import dev.autotapper.core.Recipes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Import and export saved recipes as .zip, so a loadout can be backed up or shared. */
object RecipeStore {

    class ImportError(msg: String) : Exception(msg)

    /**
     * Unpack a recipe zip into saved storage. Accepts either a zip of the recipe
     * folder's contents, or one containing a single top-level folder.
     *
     * The unpacked recipe is loaded before being accepted, so a zip missing a
     * template fails here with a message rather than halfway through a grind.
     */
    fun import(ctx: Context, uri: Uri, fallbackName: String): RecipeRef {
        val staging = File(ctx.cacheDir, "import-${System.currentTimeMillis()}")
        staging.mkdirs()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zin ->
                    var e: ZipEntry? = zin.nextEntry
                    var files = 0
                    while (e != null) {
                        val out = File(staging, e.name)
                        // Zip-slip: an entry named ../../something would otherwise
                        // write outside the staging directory.
                        if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            throw ImportError("zip contains an entry outside the archive: ${e.name}")
                        }
                        if (e.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zin.copyTo(it) }
                            files++
                        }
                        zin.closeEntry()
                        e = zin.nextEntry
                    }
                    if (files == 0) throw ImportError("zip is empty")
                }
            } ?: throw ImportError("could not read the file")

            // Allow a single wrapping folder.
            var root = staging
            if (!File(root, "recipe.json").isFile) {
                val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
                val only = dirs.singleOrNull { File(it, "recipe.json").isFile }
                    ?: throw ImportError("no recipe.json found in the zip")
                root = only
            }

            // Prove it loads before we keep it.
            val name = runCatching {
                Recipe.load { rel -> File(root, rel).inputStream() }.name
            }.getOrElse { throw ImportError("recipe did not load: ${it.message}") }
                .ifBlank { fallbackName }

            val safe = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "recipe" }
            val dest = File(Recipes.userRoot(ctx), safe)
            if (dest.exists()) dest.deleteRecursively()
            if (!root.renameTo(dest)) {
                root.copyRecursively(dest, overwrite = true)
                root.deleteRecursively()
            }
            return RecipeRef(safe, dest)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Zip a recipe (bundled or saved) into the cache and return the file. */
    fun export(ctx: Context, ref: RecipeRef): File {
        val out = File(ctx.cacheDir, "shared").apply { mkdirs() }
            .resolve("${ref.name}.zip")
        val open = Recipes.opener(ctx, ref)
        val names = mutableListOf("recipe.json")
        val templates = if (ref.dir != null) {
            File(ref.dir, "templates").list()?.toList().orEmpty()
        } else {
            ctx.assets.list("recipes/${ref.name}/templates")?.toList().orEmpty()
        }
        names += templates.map { "templates/$it" }

        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            for (n in names) {
                zos.putNextEntry(ZipEntry(n))
                open(n).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }

    fun delete(ctx: Context, ref: RecipeRef): Boolean =
        ref.dir?.deleteRecursively() ?: false
}
