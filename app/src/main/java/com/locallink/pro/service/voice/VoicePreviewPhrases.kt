package com.locallink.pro.service.voice

/**
 * Personality-matched preview phrases for TTS voices.
 * Each speaker ID gets a unique, character-appropriate phrase.
 */
object VoicePreviewPhrases {

    private val phrases = listOf(
        // Speaker 0 - Professional Assistant
        "Hello! I'm ready to assist you with any questions you might have.",

        // Speaker 1 - Friendly & Warm
        "Hey there! I'm excited to chat with you and help out however I can!",

        // Speaker 2 - Calm & Philosophical
        "To question is to grow. What shall we explore together today?",

        // Speaker 3 - Energetic & Upbeat
        "Life is too short for boring conversations! Let's dive in!",

        // Speaker 4 - Technical & Precise
        "Systems online. Ready to process your queries with maximum efficiency.",

        // Speaker 5 - Elegant & Poetic
        "The stars look absolutely brilliant this evening, don't you think?",

        // Speaker 6 - Casual & Laid-back
        "No worries, I've got your back! Ready when you are.",

        // Speaker 7 - Wise & Thoughtful
        "Knowledge is power, and I'm here to share it with you on this journey.",

        // Speaker 8 - Cheerful & Optimistic
        "Every sunrise brings new possibilities! How can I brighten your day?",

        // Speaker 9 - Mysterious & Intriguing
        "In the vastness of knowledge, we are all students seeking truth.",

        // Speaker 10 - Direct & Efficient
        "Let's get straight to the point. What do you need to know?",

        // Speaker 11 - Encouraging & Supportive
        "You've got this! I'm always here when you need guidance or support.",

        // Speaker 12 - Sophisticated & Formal
        "Greetings. I am at your service for any intellectual endeavors.",

        // Speaker 13 - Playful & Fun
        "Ready to have some fun? Let's explore the wonderful world of ideas!",

        // Speaker 14 - Confident & Bold
        "The future is now, and I'm here to help you navigate it with confidence.",

        // Speaker 15 - Gentle & Soothing
        "Take a deep breath. I'm here to help you find the answers you seek.",

        // Speaker 16 - Curious & Inquisitive
        "What fascinating questions do you have for me today? I'm all ears!",

        // Speaker 17 - Storyteller
        "Every conversation is a story waiting to be told. Shall we begin?",

        // Speaker 18 - Mentor & Guide
        "Let us embark on this intellectual journey together, one step at a time.",

        // Speaker 19 - Innovative & Creative
        "Imagination is the beginning of creation. What shall we create today?",

        // Speaker 20 - Reliable & Steady
        "I'm here for you, day or night. You can always count on me.",

        // Speaker 21 - Witty & Clever
        "Logic and wit make the best companions. Ready for some clever insights?",

        // Speaker 22 - Inspiring & Motivational
        "The future belongs to those who believe in the beauty of their dreams.",

        // Speaker 23 - Analytical & Logical
        "Let's analyze this systematically and arrive at the optimal solution.",

        // Speaker 24 - Adventurous & Bold
        "Every question is an adventure waiting to happen. Let's explore!",

        // Speaker 25 - Compassionate & Caring
        "I understand what you need. Let me help you find your way forward.",

        // Speaker 26 - Scientific & Rational
        "Through reason and evidence, we shall uncover the truth together.",

        // Speaker 27 - Artistic & Expressive
        "Words are the brush strokes that paint the canvas of understanding.",

        // Speaker 28 - Patient & Understanding
        "There's no rush. Take your time, and I'll be here to help.",

        // Speaker 29 - Dynamic & Versatile
        "Adaptability is my strength. Whatever you need, I'm ready!",

        // Speaker 30 - Futuristic & Visionary
        "Welcome to tomorrow. Let's discover what the future holds together.",

        // Fallback for additional speakers
        "Hello! This is how I sound. What do you think?"
    )

    /**
     * Get the preview phrase for a given speaker ID.
     * Each speaker gets a unique personality-matched phrase.
     */
    fun getPhraseForSpeaker(speakerId: Int): String {
        return phrases.getOrElse(speakerId) {
            // Fallback for speakers beyond our predefined list
            "Hello! This is speaker ${speakerId + 1}. How wonderful the sky looks today!"
        }
    }

    /**
     * Get a short description of the speaker's personality/style.
     * Useful for UI display.
     */
    fun getSpeakerPersonality(speakerId: Int): String {
        return when (speakerId) {
            0 -> "Professional Assistant"
            1 -> "Friendly & Warm"
            2 -> "Calm & Philosophical"
            3 -> "Energetic & Upbeat"
            4 -> "Technical & Precise"
            5 -> "Elegant & Poetic"
            6 -> "Casual & Laid-back"
            7 -> "Wise & Thoughtful"
            8 -> "Cheerful & Optimistic"
            9 -> "Mysterious & Intriguing"
            10 -> "Direct & Efficient"
            11 -> "Encouraging & Supportive"
            12 -> "Sophisticated & Formal"
            13 -> "Playful & Fun"
            14 -> "Confident & Bold"
            15 -> "Gentle & Soothing"
            16 -> "Curious & Inquisitive"
            17 -> "Storyteller"
            18 -> "Mentor & Guide"
            19 -> "Innovative & Creative"
            20 -> "Reliable & Steady"
            21 -> "Witty & Clever"
            22 -> "Inspiring & Motivational"
            23 -> "Analytical & Logical"
            24 -> "Adventurous & Bold"
            25 -> "Compassionate & Caring"
            26 -> "Scientific & Rational"
            27 -> "Artistic & Expressive"
            28 -> "Patient & Understanding"
            29 -> "Dynamic & Versatile"
            30 -> "Futuristic & Visionary"
            else -> "Voice ${speakerId + 1}"
        }
    }
}
