package com.example.security

enum class PermissionLevel(val level: Int, val title: String, val description: String) {
    SAFE(1, "Level 1 — Safe", "Automatic execution inside the authorized local workspace or read-only public web access."),
    CONFIRMATION_REQUIRED(2, "Level 2 — Confirm", "Requires explicit user confirmation before executing."),
    RESTRICTED(3, "Level 3 — Restricted", "Strictly blocked. Never executed automatically or manually by the agent.")
}

sealed class CommandValidationResult {
    data object Allowed : CommandValidationResult()
    data class RequiresConfirmation(val reason: String) : CommandValidationResult()
    data class Blocked(val reason: String) : CommandValidationResult()
}

object CommandSecurityGuard {

    private val hardBlockedPatterns = listOf(
        Regex("""rm\s+(-[a-zA-Z]*r[a-zA-Z]*f|-[a-zA-Z]*f[a-zA-Z]*r)?\s*/($|\s|\*)"""),
        Regex("""rm\s+-rf\s+/(system|data|vendor|boot|proc|dev|etc)"""),
        Regex("""mkfs|dd\s+if=.*of=/dev/|:\(\)\s*\{\s*:\|\s*:\s*&\s*\}\s*;"""),
        Regex("""/data/data/|/data/user/|su\s+-c|magisk|setenforce\s+0"""),
        Regex("""\.\./\.\./\.\."""),
        Regex("""(bypass|steal|dump).*(otp|password|keychain|keystore|biometric|banking)""", RegexOption.IGNORE_CASE)
    )

    private val confirmationRequiredPatterns = listOf(
        Regex("""\brm\s+"""),
        Regex("""\bchmod\b|\bchown\b"""),
        Regex("""\bcurl\b.*(-X\s*POST|-d\s|--data|\bhttp)""", RegexOption.IGNORE_CASE),
        Regex("""\bwget\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpkill\b|\bkillall\b|\bkill\s+-9"""),
        Regex(""">\s*/""")
    )

    private val sensitiveMemoryKeywords = listOf(
        "password", "passcode", "otp", "pin code", "cvv", "credit card",
        "debit card", "bank account", "private key", "secret key", "api_key", "aizasy", "sk-or-"
    )

    fun validateShellCommand(command: String): CommandValidationResult {
        val normalized = command.trim()
        if (normalized.isEmpty()) return CommandValidationResult.Blocked("Empty command")

        for (pattern in hardBlockedPatterns) {
            if (pattern.containsMatchIn(normalized)) {
                return CommandValidationResult.Blocked(
                    "Level 3 Restricted: Command violates device security boundaries ($normalized)."
                )
            }
        }

        for (pattern in confirmationRequiredPatterns) {
            if (pattern.containsMatchIn(normalized)) {
                return CommandValidationResult.RequiresConfirmation(
                    "Level 2 Confirmation: Command modifies files or triggers network transfer."
                )
            }
        }

        return CommandValidationResult.Allowed
    }

    fun containsSensitiveCredential(text: String): Boolean {
        val lower = text.lowercase()
        return sensitiveMemoryKeywords.any { lower.contains(it) }
    }

    fun isProtectedPackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        val protectedHints = listOf(
            "bank", "bkash", "nagad", "rocket", "paypal", "wallet",
            "authenticator", "keepass", "1password", "bitwarden", "keyguard",
            "packageinstaller", "settings"
        )
        return protectedHints.any { lower.contains(it) }
    }

    fun detectHumanChallenge(text: String, urlOrPkg: String = ""): Pair<String, String>? {
        val lower = "$text $urlOrPkg".lowercase()
        val captchaHints = listOf(
            "captcha", "recaptcha", "hcaptcha", "i'm not a robot", "i am not a robot",
            "verify you are human", "checking your browser", "cf-challenge", "turnstile",
            "ক্যাপচা", "মানুষ কিনা যাচাই"
        )
        if (captchaHints.any { lower.contains(it) }) {
            return "CAPTCHA" to "CAPTCHA বা মানব-যাচাইকরণ (Human Verification) ধরা পড়েছে।"
        }
        val otpHints = listOf(
            "enter otp", "one-time password", "verification code", "6-digit code",
            "2-step verification", "two-factor", "resend code", "sms code",
            "ওটিপি", "ভেরিফিকেশন কোড"
        )
        if (otpHints.any { lower.contains(it) }) {
            return "OTP / 2FA" to "OTP বা ২-ধাপ ভেরিফিকেশন কোড চাওয়া হয়েছে।"
        }
        return null
    }
}
