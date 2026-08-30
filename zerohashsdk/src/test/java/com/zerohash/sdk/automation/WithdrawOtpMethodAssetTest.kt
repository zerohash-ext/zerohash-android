package com.zerohash.sdk.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 2FA method chooser must resolve before the code field is awaited. No JS engine
 *  in this suite, so guards assert on the asset's source text. */
class WithdrawOtpMethodAssetTest {

    private val withdraw: String =
        File("src/main/assets/automation/withdraw.js").readText()

    private fun code(src: String): String =
        src.lineSequence().map { it.substringBefore("//") }.joinToString("\n")

    private fun fn(name: String): String =
        code(withdraw).substringAfter("function $name(").substringBefore("\n  }")

    @Test
    fun smsIsPreferredOverTotpMatchingTheLoginChooser() {
        val body = fn("chooseOtpMethod")
        val sms = body.indexOf("TWO_FACTOR_SMS")
        val totp = body.indexOf("TWO_FACTOR_TOTP")
        assertTrue("chooseOtpMethod must consider both methods", sms >= 0 && totp >= 0)
        assertTrue("SMS must be preferred over TOTP, as in login", sms < totp)
    }

    @Test
    fun theMethodIsClickedTheSameWayEverySelectorInThisFileIs() {
        val body = fn("chooseOtpMethod")
        val human = Regex("""humanClick\(""").findAll(body).count()
        assertTrue("both branches must click via humanClick; found $human", human == 2)
        assertTrue(
            "no bare .click() may remain in chooseOtpMethod",
            !Regex("""\w\.click\(\)""").containsMatchIn(body),
        )
    }

    @Test
    fun enterOtpChoosesAMethodBeforeWaitingForTheField() {
        val body = fn("enterOtp")
        val choose = body.indexOf("chooseOtpMethod")
        val waitField = body.indexOf("waitForElement(SEL.OTP_INPUT")
        assertTrue("enterOtp must resolve the chooser", choose >= 0)
        assertTrue("enterOtp must still wait for the field", waitField >= 0)
        assertTrue(
            "the method must be chosen BEFORE waiting for the field",
            choose < waitField,
        )
    }

    @Test
    fun startReportsOtpWithoutWaitingForTheField() {
        val body = code(withdraw).substringAfter("async function detectAndHandle2fa(")
            .substringBefore("\n  }")
        assertTrue("detectAndHandle2fa must choose a method", body.contains("chooseOtpMethod"))
        assertTrue(
            "start must report otp directly, without waiting on the field",
            body.contains("""if (await chooseOtpMethod()) return { kind: "otp" };"""),
        )
    }

    @Test
    fun theRenamedErrorIsGatedOnHavingChosenAMethod() {
        val body = fn("enterOtp")
        assertTrue("the choice must be recorded", body.contains("choseMethod = true"))
        assertTrue("the rename must be gated", body.contains("if (choseMethod)"))
        assertTrue("the original error must still propagate", body.contains("throw e;"))
        assertTrue(
            "there must be a single field wait, not a pre-wait plus a wait",
            Regex("""waitForElement\(SEL\.OTP_INPUT""").findAll(body).count() == 1,
        )
    }

    @Test
    fun theMethodIsOnlyClickedWhenTheFieldIsGenuinelyAbsent() {
        val body = fn("enterOtp")
        assertTrue(
            "enterOtp must check the field is absent before choosing",
            body.contains("!queryVisible(SEL.OTP_INPUT) && await chooseOtpMethod()"),
        )
    }

    @Test
    fun failingAfterChoosingSaysSoRatherThanReportingABareSelector() {
        assertTrue(
            "the chooser path must have its own error",
            code(withdraw).contains("withdraw/otp-field-missing-after-method-choice"),
        )
    }
}
