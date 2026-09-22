package dev.spaghetti

import com.intellij.psi.xml.XmlFile
import dev.spaghetti.plan.LightPlanner
import dev.spaghetti.plan.PlanApplier
import java.io.File

/**
 * Runs the real planner and applier on the Nimbus pages and writes the result to `build/demo/`:
 *
 *     build/demo/before/   the messy originals
 *     build/demo/after/    the untangled pages, plus the extracted css/ and js/ folders
 *
 * `tools/verify` serves that folder so a browser can compare before and after.
 */
class DemoExportTest : SpaghettiTestCase() {

    fun testExportUntangledNimbusPagesForBrowserComparison() {
        val root = File("build/demo")
        root.deleteRecursively()

        for (page in listOf("index", "about", "changelog")) {
            File("src/test/testData/samples/nimbus/$page.html").copyTo(File(root, "before/$page.html"), overwrite = true)

            val file = myFixture.configureByFile("samples/nimbus/$page.html") as XmlFile
            val folder = file.virtualFile.parent
            val plan = LightPlanner.plan(file) { folder.findFileByRelativePath(it) != null }
            PlanApplier.apply(project, file.virtualFile, plan)

            File(root, "after/$page.html").also { it.parentFile.mkdirs() }.writeText(file.viewProvider.document!!.text)
            for (planned in plan.files) {
                File(root, "after/${planned.relativePath}").also { it.parentFile.mkdirs() }.writeText(planned.content)
            }
            println("untangled $page.html -> ${plan.files.joinToString { it.relativePath }}")
        }
    }
}
