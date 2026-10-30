package io.embodit.theremin.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.theremin.platform.ThLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.theremin.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s <serial> shell "am broadcast -a io.embodit.theremin.DEBUG -p io.embodit.theremin --es cmd 'mode=follow,song=molihua,step=5 freeze=1'"
 *
 * Every extra is read as text (string, float, int and boolean extras alike).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.extras ?: return
        val extras = HashMap<String, String>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION") val v = bundle.get(key) ?: continue
            extras[key] = v.toString()
        }
        if (extras.isEmpty()) return
        ThLog.i("CMD recv $extras")
        DebugCommands.run(context.applicationContext, extras)
    }
}
