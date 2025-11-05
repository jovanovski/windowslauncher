package rocks.gorjan.gokixp.apps.notepad

data class Note(
    val id: String,
    var title: String,
    var content: String,
    var imageUris: MutableList<String> = mutableListOf()  // List of permanent URI strings
)
