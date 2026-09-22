package dev.spaghetti

import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ThrowableRunnable

/**
 * Base class for all plugin tests.
 *
 * IntelliJ IDEA's licensing module (`com.intellij.modules.ultimate`) logs an error when it
 * starts inside the test sandbox, which would fail every test. We swallow only that error;
 * errors from any other source, including our own plugin, still fail the test.
 */
abstract class SpaghettiTestCase : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/testData"

    override fun runBare(testRunnable: ThrowableRunnable<Throwable>) {
        LoggedErrorProcessor.executeWith<Throwable>(IgnoreLicensingErrors) {
            super.runBare(testRunnable)
        }
    }

    private object IgnoreLicensingErrors : LoggedErrorProcessor() {
        override fun processError(
            category: String,
            message: String,
            details: Array<String>,
            t: Throwable?,
        ): Set<Action> =
            if (message.contains("com.intellij.modules.ultimate")) Action.NONE
            else super.processError(category, message, details, t)
    }
}
