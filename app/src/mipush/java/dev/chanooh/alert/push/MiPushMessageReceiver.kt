package dev.chanooh.alert.push

import android.content.Context
import com.xiaomi.mipush.sdk.MiPushCommandMessage
import com.xiaomi.mipush.sdk.MiPushMessage
import com.xiaomi.mipush.sdk.MiPushClient
import com.xiaomi.mipush.sdk.PushMessageReceiver

/** Compiled only with the official Mi Push AAR supplied in app/libs. */
class MiPushMessageReceiver : PushMessageReceiver() {
    override fun onReceiveRegisterResult(context: Context, message: MiPushCommandMessage) {
        if (message.command == MiPushClient.COMMAND_REGISTER && message.resultCode == 0L) {
            message.commandArguments.firstOrNull()?.let { MiPushBridge.onRegistrationId(context, it) }
        }
    }

    override fun onNotificationMessageArrived(context: Context, message: MiPushMessage) {
        MiPushBridge.handlePayload(context, message.content)
    }

    override fun onNotificationMessageClicked(context: Context, message: MiPushMessage) {
        MiPushBridge.handlePayload(context, message.content)
    }

    override fun onReceivePassThroughMessage(context: Context, message: MiPushMessage) {
        // Alert uses notification-bar messages for reliable lock-screen delivery;
        // accepting pass-through here remains useful for vendor diagnostics.
        MiPushBridge.handlePayload(context, message.content)
    }
}
