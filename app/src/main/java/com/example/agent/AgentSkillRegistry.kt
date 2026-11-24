package com.example.agent

enum class SkillCategory(val title: String) {
    VIRTUAL_HANDS("Virtual Hands"),
    BROWSER_WEB("Browser & Web"),
    FILES_DOCS("Files & Uploads"),
    CODE_DEV("Python / JS / Server"),
    AUTOMATION("Automation")
}

data class AgentSkill(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: SkillCategory,
    val promptTemplate: String
)

data class AgentUploadedAttachment(
    val id: String = "att_${System.nanoTime()}",
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val textExcerpt: String? = null
)

object AgentSkillRegistry {
    val skills: List<AgentSkill> = listOf(
        AgentSkill(
            id = "vh_app_control",
            title = "যেকোনো অ্যাপ চালান (Virtual Hands)",
            subtitle = "অ্যাপ লঞ্চ করে বাটন ক্লিক, স্ক্রল ও টাইপ করা",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "Virtual Hands ব্যবহার করে ফোনের ইনস্টল করা অ্যাপের তালিকা দেখো, তারপর প্রয়োজনীয় অ্যাপ ওপেন করে স্ক্রিন দেখে ধাপে ধাপে কাজটি সম্পন্ন করো: "
        ),
        AgentSkill(
            id = "vh_screen_audit",
            title = "বর্তমান স্ক্রিন রিড ও অ্যাকশন",
            subtitle = "Accessibility দিয়ে স্ক্রিনের সব এলিমেন্ট পড়ে কাজ করা",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "Virtual Hands দিয়ে বর্তমান স্ক্রিন inspect করো, কী কী বাটন ও ইনপুট আছে তা বিশ্লেষণ করে পরবর্তী কাজ সম্পন্ন করো।"
        ),
        AgentSkill(
            id = "vh_scroll_collect",
            title = "অ্যাপ স্ক্রল ও তথ্য সংগ্রহ",
            subtitle = "বারবার স্ক্রল করে ডেটা সংগ্রহ ও ফাইলে সেভ করা",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "Virtual Hands দিয়ে স্ক্রিন নিচে স্ক্রল করে গুরুত্বপূর্ণ তথ্যগুলো সংগ্রহ করো এবং workspace/screen_notes.txt ফাইলে সংরক্ষণ করো।"
        ),
        AgentSkill(
            id = "vh_otp_handoff",
            title = "লগইন / OTP লাইভ হ্যান্ডঅফ",
            subtitle = "ক্যাপচা বা ওটিপিতে আটকে গেলে লাইভ স্ক্রিন সামনে এনে রিজিউম",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "ব্রাউজার বা Virtual Hands ব্যবহার করে সাইট/অ্যাপে যাও। কোথাও OTP বা CAPTCHA এলে request_human_intervention কল করে লাইভ স্ক্রিন দেখাও, আমি সল্ভ করলে পরের ধাপ থেকে কাজ শেষ করো: "
        ),
        AgentSkill(
            id = "web_research",
            title = "ওয়েব রিসার্চ ও রিপোর্ট",
            subtitle = "লাইভ ব্রাউজারে সার্চ করে ভেরিফাইড সারসংক্ষেপ তৈরি",
            category = SkillCategory.BROWSER_WEB,
            promptTemplate = "Open the browser, research the following topic thoroughly, verify links, and write a clean report in workspace/research_report.md: "
        ),
        AgentSkill(
            id = "job_cv_apply",
            title = "আপলোডকৃত CV দিয়ে জব খোঁজা",
            subtitle = "আপলোড করা CV পড়ে উপযুক্ত জব ও কভার লেটার তৈরি",
            category = SkillCategory.FILES_DOCS,
            promptTemplate = "আমার আপলোড করা CV/ফাইলটি পড়ে বিশ্লেষণ করো, তারপর ব্রাউজারে ২০টি উপযুক্ত চাকরি খুঁজে workspace/job_applications.md এ প্রস্তুতি ও কভার লেটার তৈরি করো।"
        ),
        AgentSkill(
            id = "file_analyzer",
            title = "আপলোড ফাইল বিশ্লেষণ (Python + AI)",
            subtitle = "ডকুমেন্ট, কোড, CSV বা JSON পড়ে সারসংক্ষেপ তৈরি",
            category = SkillCategory.FILES_DOCS,
            promptTemplate = "আমার আপলোড করা ফাইলগুলো ভালোভাবে পড়ে বিশ্লেষণ করো, প্রয়োজনে Python দিয়ে ডেটা প্রসেস করে মূল পয়েন্টগুলোর সম্পূর্ণ সারসংক্ষেপ দাও।"
        ),
        AgentSkill(
            id = "python_builder",
            title = "Python স্ক্রিপ্ট তৈরি ও রান",
            subtitle = "অন-ডিভাইস পাইথন স্ক্রিপ্ট লিখে সরাসরি আউটপুট দেখা",
            category = SkillCategory.CODE_DEV,
            promptTemplate = "Create a clean Python script in scripts/, execute it on-device, verify the stdout output, and fix any issues: "
        ),
        AgentSkill(
            id = "web_dev_server",
            title = "লোকাল ওয়েব অ্যাপ + সার্ভার",
            subtitle = "HTML/JS প্রজেক্ট বানিয়ে 127.0.0.1:8080 এ চালু করা",
            category = SkillCategory.CODE_DEV,
            promptTemplate = "Build an interactive HTML/CSS/JS web app inside projects/index.html, start local_server_control on 127.0.0.1:8080, and open it in the integrated browser."
        ),
        AgentSkill(
            id = "youtube_pkg",
            title = "YouTube ভিডিও প্যাকেজ",
            subtitle = "স্ক্রিপ্ট, টাইটেল, ডেসক্রিপশন ও ট্যাগ তৈরি",
            category = SkillCategory.AUTOMATION,
            promptTemplate = "Prepare a complete YouTube production package (script, scenes, title, description, tags) using youtube_prepare_package about: "
        ),
        AgentSkill(
            id = "daily_scheduler",
            title = "দৈনিক অটোমেশন শিডিউল",
            subtitle = "প্রতিদিন নির্দিষ্ট সময়ে WorkManager টাস্ক সেট করা",
            category = SkillCategory.AUTOMATION,
            promptTemplate = "Schedule a recurring DAILY automation task using schedule_automation at 09:00 AM for: "
        )
    )
}
