package rocks.gorjan.gokixp.agent

import rocks.gorjan.gokixp.MainActivity
import rocks.gorjan.gokixp.R

data class Agent(
    val id: String,
    val name: String,
    val waitingDrawableRes: Int,
    val talkingDrawableRes: Int,
    val greeting: String = "Hi {USER}, I'm {AGENT_NAME}, welcome to Windows!",
    val voiceName: String = "Adult Male #2, American English (TruVoice)",
    val pitch: Int = 140,
    val speed: Int = 157
) {
    companion object {
        val ROVER = Agent(
            id = "rover",
            name = "Rover",
            waitingDrawableRes = R.drawable.rover_waiting,
            talkingDrawableRes = R.drawable.rover_talking
        )
        
        val BONZI = Agent(
            id = "bonzi", 
            name = "Bonzi",
            waitingDrawableRes = R.drawable.bonzi_waiting,
            talkingDrawableRes = R.drawable.bozi_talking
        )
        
        val ALL_AGENTS = listOf(ROVER, BONZI)
        
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