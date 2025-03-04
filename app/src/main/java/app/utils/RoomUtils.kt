package app.utils

import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import app.activities.WatchActivity
import app.protocol.JsonSender
import app.protocol.SyncplayProtocol
import app.wrappers.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Methods exclusive to Room functionality (messages, sending data to server, etc) */
object RoomUtils {

    /** Sends a play/pause playback to the server **/
    fun WatchActivity.sendPlayback(play: Boolean) {
        if (isSoloMode()) return

        p.sendPacket(
            JsonSender.sendState(
                servertime = null, clienttime = System.currentTimeMillis() / 1000.0,
                doSeek = null, seekPosition = 0, iChangeState = 1, play = play, protocol = p
            )
        )
    }

    fun WatchActivity.sendSeek(newpos: Long) {
        if (isSoloMode()) return

        p.sendPacket(
            JsonSender.sendState(
                null, (System.currentTimeMillis() / 1000.0), true,
                newpos, 1,
                play = player?.isInPlayState() == true,
                p
            )
        )
    }

    /** Sends a chat message to the server **/
    fun WatchActivity.sendMessage(message: String) {
        if (isSoloMode()) return

        Log.e("zrf", message)
        p.sendPacket(JsonSender.sendChat(message))
    }

    /** Periodic ping calculator (for UI and Protocol uses) */
    fun WatchActivity.pingUpdate() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (!isSoloMode()) {
                if (p.channel?.isActive == true) {
                    p.ping.value = MiscUtils.pingIcmp("151.80.32.178", 32) * 1000.0
                } else {
                    p.ping.value = -1.0
                }
                delay(1000)
            }
        }
    }

    /** This broadcasts a message to show it in the chat section **/
    fun WatchActivity.broadcastMessage(message: String, isChat: Boolean, chatter: String = "") {
        if (isSoloMode()) return

        /** Messages are just a wrapper class for everything we need about a message
        So first, we initialize it, customize it, then add it to our long list of messages */
        val msg = Message(
            sender = if (isChat) chatter else null,
            isMainUser = chatter == p.session.currentUsername,
            content = message
        )

        /** Adding the message instance to our message sequence **/
        p.session.messageSequence.add(msg)
    }

    /** TODO: Method to verify mismatches of files with different users in the room.
     * Mismatches are: Name, Size, Duration. If 3 mismatches are detected, no error is thrown
     * since that would mean that the two files are completely and obviously different.*/
    fun WatchActivity.checkFileMismatches(p: SyncplayProtocol) {
        if (isSoloMode()) return

        /** First, we check if user wanna be notified about file mismatchings */
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("warn_file_mismatch", true)
        ) return

        for (user in p.session.userList) {
//            val theirFile = user.file ?: continue /* If they have no file, iterate unto next */
//            val nameMismatch =
//                (media?.fileName != theirFile.fileName) && (media?.fileNameHashed != theirFile.fileName)
//            val durationMismatch = media?.fileDuration != theirFile.fileDuration
//            val sizeMismatch =
//                media?.fileSize != theirFile.fileSize && media?.fileSizeHashed != theirFile.fileSize
//
//            if (nameMismatch && durationMismatch && sizeMismatch) continue /* 2 mismatches or less */
//            var warning = string(R.string.room_file_mismatch_warning_core, user.name)
//            if (nameMismatch) warning =
//                warning.plus(string(R.string.room_file_mismatch_warning_name))
//            if (durationMismatch) warning =
//                warning.plus(string(R.string.room_file_mismatch_warning_duration))
//            if (sizeMismatch) warning =
//                warning.plus(string(R.string.room_file_mismatch_warning_size))

            //broadcastMessage(warning, false)
        }
    }

}