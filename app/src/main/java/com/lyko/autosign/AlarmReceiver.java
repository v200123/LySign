package com.lyko.autosign;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ScheduleManager.isEnabled(context)) {
            return;
        }
        ScheduleManager.scheduleNext(context, false);
    }
}
