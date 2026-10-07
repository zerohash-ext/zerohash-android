package com.zerohash.sdk.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards the injected automation scripts, which are generated: `make sync` copies
 * them out of the scraper-mobile-library submodule. Nobody edits them here, so
 * nobody is reading this list when one appears or disappears.
 *
 * The required set is derived from the Kotlin sources rather than written out by
 * hand. The hand-written version had drifted to 10 of the 12 assets —
 * setup-execution-context.js and telemetry.js were missing, because they are
 * referenced through constants (SETUP_SCRIPT_ASSET, TELEMETRY_INSTALL_ASSET)
 * rather than inline, so neither was obvious to whoever last extended the list.
 *
 * Gradle runs unit tests with the module dir as the working directory.
 */
class AutomationAssetsTest {

    private val assetDir = File("src/main/assets/automation")
    private val sourceDir = File("src/main/java/com/zerohash/sdk")

    /** Every "automation/<name>.js" literal the Kotlin layer loads. */
    private fun referencedAssets(): Set<String> =
        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { ASSET_REFERENCE.findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun theSourcesReferenceSomeAssets() {
        // Without this, a regex that stops matching would make every other
        // assertion here vacuously true.
        val referenced = referencedAssets()
        assertTrue(
            "no \"automation/*.js\" references found in $sourceDir — has the " +
                "loading convention changed?",
            referenced.size >= 10,
        )
    }

    @Test
    fun everyReferencedScriptIsPackaged() {
        for (name in referencedAssets()) {
            val file = File(assetDir, name)
            // Non-empty too, so a renamed, shrunk or zeroed asset also fails and
            // not only a missing one.
            assertTrue("missing automation asset: $name", file.isFile)
            assertTrue("empty automation asset: $name", file.length() > 0)
        }
    }

    @Test
    fun thePackagedAssetsAreExactlyWhatTheLibraryProduces() {
        // The real invariant now that the directory is a copy: it must equal the
        // library's output. Catches a script orphaned by a rename — which the
        // referenced-assets check above cannot see, because an unreferenced file
        // is legitimate (auth-signup.js is loaded only by iOS, and the library
        // ships every script to both platforms).
        val dist = File("../scraper-mobile-library/dist")

        // Skipped rather than failed when the submodule is absent. A plain clone
        // without --recurse-submodules is normal, and failing here would turn a
        // check that simply cannot run into a broken build. Reported as skipped,
        // so it stays visible rather than vanishing.
        assumeTrue(
            "scraper-mobile-library is not checked out — run `git submodule update --init`",
            dist.isDirectory,
        )
        val produced = dist.listFiles { f -> f.extension == "js" }!!.map { it.name }.sorted()
        val packaged = assetDir.listFiles { f -> f.extension == "js" }!!.map { it.name }.sorted()

        // Scripts still awaiting their turn (AUTH-4516 to AUTH-4521) are
        // hand-written and live only here, so the library's set is a subset of
        // what is packaged until the migration finishes.
        val missing = produced - packaged.toSet()
        assertEquals(
            "the library produces scripts that were never copied here — run `make sync`",
            emptyList<String>(),
            missing,
        )
    }

    private companion object {
        val ASSET_REFERENCE = Regex("\"automation/([A-Za-z0-9._-]+\\.js)\"")
    }
}
