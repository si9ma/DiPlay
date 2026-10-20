package com.shilapi.xcertplay.hud

import android.view.KeyEvent

/**
 * The steering wheel's call keys during a CarPlay call, as BYD's own CarPlay app handles them. Outside a
 * CarPlay call every key is left to the car, so the Bluetooth phone keeps working as before.
 *
 * On DiLink 3 BYD's window manager (PhoneWindowManager.interceptKeyBeforeQueueing) acts on the call keys
 * before any app or accessibility service: the call key (313) still reaches the focused app and the
 * wheel key service, but the hang-up key (314) and the multifunction key (309) reach no app, only BYD's
 * hang-up broadcast (com.byd.btcall.action.CLOSE_BLUETOOTHSETTING with the key code), see [onHangUpBroadcast].
 */
object CarPlayCallKeyPolicy {
    const val KEYCODE_BYD_DIAL_ANSWER = 313
    const val KEYCODE_BYD_HANG_UP = 314
    const val KEYCODE_BYD_MULTIPLEXING = 309
    const val ACTION_BYD_HANG_UP = "com.byd.btcall.action.CLOSE_BLUETOOTHSETTING"
    const val EXTRA_KEYCODE = "keycode"

    enum class Action { PASS, CONSUME, ANSWER, END }

    fun isAnswerKey(keyCode: Int): Boolean = keyCode == KEYCODE_BYD_DIAL_ANSWER || keyCode == KeyEvent.KEYCODE_CALL

    fun isEndKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_ENDCALL ||
        keyCode == KEYCODE_BYD_HANG_UP || keyCode == KEYCODE_BYD_MULTIPLEXING

    /**
     * A key event while CarPlay runs ([session]) with [call] on the iPhone. The call key answers a ringing
     * call and is kept otherwise (BYD's phone app would open over CarPlay); the hang-up keys end the call
     * or decline it. Both halves of a press are kept, the action runs on release.
     *
     * Wheels without call keys, such as the 2022 Song Pro DM-i's and the Di150VCP's, send only next and
     * previous track. While a call rings next answers and previous declines (#272); during a connected or
     * outgoing call previous hangs up and next is kept, so neither skips a song on the iPhone.
     *
     * [phoneCall] is CarPlay's own phone-call app state. Some calls arrive without an iAP2 call update
     * (no [call]); then previous ends the call and next is kept: answering needs the ringing state.
     */
    fun onKey(keyCode: Int, down: Boolean, call: CarPlayCallCard?, session: Boolean, phoneCall: Boolean = false): Action {
        if (!session || (call == null && !phoneCall)) return Action.PASS
        val ringing = call?.phase == CarPlayCallCard.Phase.RINGING
        return when {
            isAnswerKey(keyCode) -> when {
                down -> Action.CONSUME
                ringing -> Action.ANSWER
                else -> Action.CONSUME
            }
            isEndKey(keyCode) -> if (down) Action.CONSUME else Action.END
            keyCode == KeyEvent.KEYCODE_MEDIA_NEXT -> if (!down && ringing) Action.ANSWER else Action.CONSUME
            keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (down) Action.CONSUME else Action.END
            else -> Action.PASS
        }
    }

    /** BYD's hang-up broadcast, sent when the hang-up or multifunction key is released. */
    fun onHangUpBroadcast(keyCode: Int, call: CarPlayCallCard?, session: Boolean, phoneCall: Boolean = false): Action =
        if (session && (call != null || phoneCall) && (keyCode == KEYCODE_BYD_HANG_UP || keyCode == KEYCODE_BYD_MULTIPLEXING)) {
            Action.END
        } else {
            Action.PASS
        }
}
