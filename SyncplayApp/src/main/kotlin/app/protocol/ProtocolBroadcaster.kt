package app.protocol

interface ProtocolBroadcaster {

    /** Interface that is used as an intermediate (middle-man) bridge between the protocol and the
     * client itself. This interface broadcasts multiple events to the UI client (RoomActivity) **/

    fun onSomeonePaused(pauser: String)

    fun onSomeonePlayed(player: String)

    fun onChatReceived(chatter: String, chatmessage: String)

    fun onSomeoneJoined(joiner: String)

    fun onSomeoneLeft(leaver: String)

    fun onSomeoneSeeked(seeker: String, toPosition: Double)

    fun onSomeoneBehind(behinder: String, toPosition: Double)

    fun onReceivedList()

    fun onSomeoneLoadedFile(person: String, file: String?, fileduration: Double?)

    fun onDisconnected()

    fun onReconnected()

    fun onJoined()

    fun onConnectionAttempt()

    fun onConnectionFailed()

    fun onPlaylistUpdated(user: String)

    fun onPlaylistIndexChanged(user: String, index: Int)
}