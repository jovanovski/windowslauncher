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

        val DEFAULT = CLIPPY

        val ALL_AGENTS = listOf(CLIPPY, ROVER, BONZI)

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
