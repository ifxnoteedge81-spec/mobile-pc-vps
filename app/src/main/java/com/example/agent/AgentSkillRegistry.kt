package com.example.agent

enum class SkillCategory(val title: String) {
    CAD_DESIGN("AutoCAD & 3D Design"),
    VIRTUAL_HANDS("Virtual Hands"),
    BROWSER_WEB("Browser & Web"),
    FILES_DOCS("Files & Uploads"),
    CODE_DEV("Python / JS / API"),
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
    val textExcerpt: String? = null,
    val imageAttachments: List<com.example.ai.AiImageAttachment> = emptyList()
)

object AgentSkillRegistry {
    val skills: List<AgentSkill> = listOf(
        AgentSkill(
            id = "cad_floor_plan",
            title = "AutoCAD 2D ফ্লোর প্ল্যান ও ব্লুপ্রিন্ট",
            subtitle = "আসল DXF + Dimensioned SVG + Interactive Browser প্রিভিউ তৈরি",
            category = SkillCategory.CAD_DESIGN,
            promptTemplate = "cad_generate_drawing ব্যবহার করে নিচের মাপ ও রুম অনুযায়ী একটি সম্পূর্ণ AutoCAD (.dxf) ফ্লোর প্ল্যান, ডাইমেনশনসহ SVG ব্লুপ্রিন্ট ও লাইভ ব্রাউজার ভিউয়ার তৈরি করে প্রিভিউ দেখাও: "
        ),
        AgentSkill(
            id = "cad_mech_3d",
            title = "মেকানিক্যাল পার্টস (2D DXF + 3D STL)",
            subtitle = "ইঞ্জিনিয়ারিং পার্টস, গিয়ার বা ব্র্যাকেটের 2D CAD ও 3D STL মডেল",
            category = SkillCategory.CAD_DESIGN,
            promptTemplate = "cad_generate_drawing ব্যবহার করে সঠিক মাপ, হোল/সার্কেল, ডাইমেনশন এবং extrude_height_3d সহ একটি ইঞ্জিনিয়ারিং 2D DXF + 3D STL মডেল তৈরি করো: "
        ),
        AgentSkill(
            id = "super_autonomous_solver",
            title = "সুপার অটোনমাস টাস্ক সলভার (No Limits)",
            subtitle = "জটিল রিসার্চ, কোডিং, অটোমেশন ও সেলফ-ভেরিফিকেশন মোড",
            category = SkillCategory.AUTOMATION,
            promptTemplate = "তোমার সব টুল (Python, Shell, Browser, CAD, Virtual Hands, Universal Skills) ব্যবহার করে ধাপে ধাপে পরিকল্পনা করে, ফলাফল যাচাই করে নিচের জটিল কাজটি সম্পূর্ণ শেষ করো: "
        ),
        AgentSkill(
            id = "vh_app_control",
            title = "যেকোনো অ্যাপ চালান (Virtual Hands)",
            subtitle = "অ্যাপ লঞ্চ করে বাটন ক্লিক, স্ক্রল, টাইপ ও সম্পূর্ণ কাজ শেষ করা",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "Virtual Hands ব্যবহার করে ফোনের ইনস্টল করা অ্যাপের তালিকা দেখো, প্রয়োজনীয় অ্যাপ ওপেন করে স্ক্রিন দেখে ধাপে ধাপে কাজটি সম্পূর্ণ অটোনমাসভাবে শেষ করো: "
        ),
        AgentSkill(
            id = "vh_app_install",
            title = "প্রয়োজনীয় অ্যাপ ইনস্টল ও ব্যবহার",
            subtitle = "অ্যাপ না থাকলে Play Store থেকে ইনস্টল করে কাজ করা",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "প্রথমে app_list দিয়ে দেখো অ্যাপটি ফোনে আছে কি না। না থাকলে app_install_store দিয়ে Play Store ওপেন করে ইনস্টল শুরু করো এবং Virtual Hands দিয়ে পরবর্তী কাজ সম্পন্ন করো: "
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
            id = "auto_account_creator",
            title = "অটোনমাস অ্যাকাউন্ট তৈরি (Gmail / Web / App)",
            subtitle = "নিজে নিজে ফর্ম পূরণ, এবং ফোন নম্বর/OTP লাগলে লাইভ জিজ্ঞেস করে অ্যাকাউন্ট সম্পন্ন করা",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "লাইভ ব্রাউজার (https://accounts.google.com/signup) অথবা Virtual Hands ব্যবহার করে নিজে থেকে অ্যাকাউন্ট তৈরি শুরু করো। নাম, জন্মতারিখ, ইউজারনেম ও পাসওয়ার্ড পূরণ করো — এবং যেকোনো ধাপে ফোন নম্বর, OTP বা CAPTCHA চাইলে request_human_intervention কল করে আমার কাছে চাও এবং তা বসিয়ে অ্যাকাউন্ট সম্পন্ন করো: "
        ),
        AgentSkill(
            id = "vh_fast_upload",
            title = "সুপার ফাস্ট মিডিয়া আপলোড (TikTok / Social)",
            subtitle = "আপলোডকৃত ছবি/ভিডিও ১-ট্যাপে অ্যাপে পাঠিয়ে batch Virtual Hands দিয়ে পোস্ট",
            category = SkillCategory.VIRTUAL_HANDS,
            promptTemplate = "আমার আপলোড করা ছবি/ভিডিওটি device_intent_action(action_type='share_file') বা app_launch ও batch Virtual Hands দিয়ে দ্রুত পোস্ট/আপলোড করো (কোনো স্ক্রিনশট নেবে না, সরাসরি স্ক্রিন রিড করবে): "
        ),
        AgentSkill(
            id = "web_research",
            title = "ওয়েব রিসার্চ ও অটোমেশন",
            subtitle = "লাইভ ব্রাউজারে ফর্ম ফিল, ক্লিক, স্ক্রল ও রিপোর্ট তৈরি",
            category = SkillCategory.BROWSER_WEB,
            promptTemplate = "Open the live browser, perform the requested online task or research, interact with pages as needed, verify results, and write a clean report in workspace/research_report.md: "
        ),
        AgentSkill(
            id = "github_api_skill",
            title = "GitHub / API স্কিল ইমপোর্ট ও রান",
            subtitle = "HTTP দিয়ে GitHub ফাইল বা API ফেচ করে সেভ ও চালানো",
            category = SkillCategory.CODE_DEV,
            promptTemplate = "http_request বা browser_download ব্যবহার করে নিচের GitHub/API রিসোর্সটি নিয়ে এসে scripts/ বা workspace/ এ সেভ করো, বিশ্লেষণ করো এবং memory_save দিয়ে সংরক্ষণ করো: "
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
            id = "cross_app_share",
            title = "ক্রস-অ্যাপ শেয়ার, ইমেইল ও ক্লিপবোর্ড",
            subtitle = "অন্যান্য অ্যাপে ডেটা শেয়ার, ক্লিপবোর্ড কপি বা ইমেইল ড্রাফট",
            category = SkillCategory.AUTOMATION,
            promptTemplate = "তথ্যটি প্রস্তুত করে device_clipboard এবং device_intent_action (share/email/url) ব্যবহার করে কাজটি সম্পন্ন করো: "
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
