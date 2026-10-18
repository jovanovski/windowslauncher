package rocks.gorjan.gokixp.agent

import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

/**
 * An MS Agent character extracted frame-by-frame: every animation is an animated WebP in
 * assets/agents/<agent id>/, named after the original MS Agent animation.
 */
data class SpriteAnimations(
    // Looped for as long as the agent is speaking
    val talking: String,
    // Still frame shown between idle animations
    val rest: String = "RestPose",
    // Animations that never play at random while idle (enter/exit and reaction animations)
    val notIdle: Set<String> = emptySet()
)

data class Agent(
    val id: String,
    val name: String,
    val sprites: SpriteAnimations? = null,
    // GIF agents (no sprites) loop one waiting and one talking animation
    val waitingDrawableRes: Int = 0,
    val talkingDrawableRes: Int = 0,
    val widthDp: Int = 100,
    val heightDp: Int = 100,
    val greeting: String = "Hi {USER}, I'm {AGENT_NAME}, welcome to Windows!",
    // How the AI is told to sound when the agent is answering real questions
    val persona: String = "",
    val voiceName: String = "Adult Male #2, American English (TruVoice)",
    val pitch: Int = 140,
    val speed: Int = 157
) {
    companion object {
        // Sprite sizes keep the original sheets' proportions (Clippy 124x93, Rover 80x80): Rover at 1.25dp per source pixel, Clippy 10% smaller
        val CLIPPY = Agent(
            id = "clippy",
            name = "Clippy",
            sprites = SpriteAnimations(
                talking = "Talking",
                notIdle = setOf("Show", "Hide", "Greeting", "GoodBye")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "the eager Microsoft Office paperclip. You are relentlessly helpful and a little nosy, " +
                "and you still think everything might be a letter."
        )

        val ROVER = Agent(
            id = "rover",
            name = "Rover",
            sprites = SpriteAnimations(
                talking = "Acknowledge",
                notIdle = setOf("Show", "Hide", "HideQuick", "ClickedOn")
            ),
            persona = "the search dog from Windows XP. You are cheerful and loyal, you sniff things out, " +
                "and you slip the odd dog-ish remark in."
        )

        val BONZI = Agent(
            id = "bonzi",
            name = "Bonzi",
            waitingDrawableRes = R.drawable.bonzi_waiting,
            talkingDrawableRes = R.drawable.bozi_talking,
            persona = "the purple gorilla desktop buddy from the late 90s. You are chatty and over-familiar, " +
                "and you love a joke or a useless fun fact."
        )

        // The Office XP assistants. Office Logo and Mother Nature are cut from sprite sheets, so their
        // animations are named after what they show rather than after the original MS Agent ones
        val OFFICE_LOGO = Agent(
            id = "officelogo",
            name = "Office Logo",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf("Show", "GoodBye")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "the Office logo, four puzzle pieces in red, green, blue and yellow. You are tidy and businesslike, " +
                "and you like it when everything fits together.",
            voiceName = "Adult Male #1, American English (TruVoice)"
        )

        val GENIUS = Agent(
            id = "genius",
            name = "The Genius",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf("Show", "Hide", "Greeting", "Goodbye")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "The Genius, the wild-haired scientist from Microsoft Office. You are brilliant and absent-minded, " +
                "and you can't resist a bit of physics.",
            voiceName = "Adult Male #8, American English (TruVoice)",
            pitch = 120
        )

        val F1 = Agent(
            id = "f1",
            name = "F1",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf("Show", "Hide", "Greeting", "Goodbye")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "F1, the little help robot from Microsoft Office. You are precise and literal, " +
                "and you talk a bit like a machine that is trying very hard to be friendly.",
            voiceName = "Adult Male #3, American English (TruVoice)",
            pitch = 170
        )

        val LINKS = Agent(
            id = "links",
            name = "Links",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf("Show", "Hide", "Greeting", "GoodBye")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "Links, the cat from Microsoft Office. You are curious and a little aloof, " +
                "and you help on your own terms.",
            voiceName = "Adult Female #2, American English (TruVoice)"
        )

        val ROCKY = Agent(
            id = "rocky",
            name = "Rocky",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf("Show", "Hide", "Greeting", "Goodbye")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "Rocky, the dog from Microsoft Office. You are bouncy and eager, " +
                "and you would fetch the answer if you could.",
            voiceName = "Adult Male #6, American English (TruVoice)",
            pitch = 160
        )

        val MOTHER_NATURE = Agent(
            id = "mothernature",
            name = "Mother Nature",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf("Show", "Hide")
            ),
            widthDp = 140,
            heightDp = 105,
            persona = "Mother Nature, the painted globe from Microsoft Office. You are calm, warm and patient, " +
                "and you like a gentle nature metaphor.",
            voiceName = "Adult Female #1, American English (TruVoice)"
        )

        // Merlin's sheet is 128x128, sized like the others per source pixel
        val MERLIN = Agent(
            id = "merlin",
            name = "Merlin",
            sprites = SpriteAnimations(
                talking = "Explain",
                notIdle = setOf(
                    "Show", "Hide", "Greet", "MoveDown", "MoveLeft", "MoveRight", "MoveUp",
                    "Hearing_1", "Hearing_2", "Hearing_3", "Hearing_4", "StartListening", "StopListening",
                    "GetAttentionContinued", "GetAttentionReturn", "ReadContinued", "ReadReturn",
                    "WriteContinued", "WriteReturn", "LookDownReturn", "LookLeftReturn", "LookRightReturn", "LookUpReturn"
                )
            ),
            widthDp = 145,
            heightDp = 145,
            persona = "Merlin, the wizard from Microsoft Office. You are wise and a little theatrical, " +
                "and you treat every answer as a small piece of magic.",
            voiceName = "Adult Male #2, American English (TruVoice)",
            pitch = 110,
            speed = 150
        )

        val DEFAULT = CLIPPY

        val ALL_AGENTS = listOf(CLIPPY, ROVER, BONZI, OFFICE_LOGO, GENIUS, F1, LINKS, ROCKY, MOTHER_NATURE, MERLIN)

        fun getAgentById(id: String): Agent? {
            return ALL_AGENTS.find { it.id == id }
        }
    }

    fun getGreetingMessage(context: android.content.Context): String {
        // Get user name from MainActivity
        val userName = MainActivity.getUserName(context)

        return if (userName.isNotEmpty() && userName != "User") {
            // Use greeting with user name
            greeting.replace("{USER}", userName).replace("{AGENT_NAME}", name)
        } else {
            // Use fallback greeting without user name
            "Hi, I'm $name, welcome to Windows!"
        }
    }
}
