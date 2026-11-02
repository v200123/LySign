package com.lyko.autosign;

import android.app.AlarmManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import java.util.Calendar;

final class ScheduleManager {
    private static final String PREFS = "schedule";
    private static final String ENABLED = "enabled";
    private static final String HOUR = "hour";
    private static final String MINUTE = "minute";
    private static final String SCHEDULED_KEY = "scheduled_key";
    private static final String SIGN_URI = "lynkco://h5/?routeUrl=https://app.lynkco.com/"
            + "lynkco3-cordova/index.html#/pages/sign/index";

    private ScheduleManager() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED, false);
    }

    static int hour(Context context) {
        return preferences(context).getInt(HOUR, 8);
    }

    static int minute(Context context) {
        return preferences(context).getInt(MINUTE, 0);
    }

    static void save(Context context, boolean enabled, int hour, int minute) {
        preferences(context).edit()
                .putBoolean(ENABLED, enabled)
                .putInt(HOUR, hour)
                .putInt(MINUTE, minute)
                .apply();
        scheduleNext(context, true);
    }

    static boolean hasExactAlarmAccess(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return manager != null && manager.canScheduleExactAlarms();
    }

    static long nextTrigger(Context context) {
        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour(context));
        next.set(Calendar.MINUTE, minute(context));
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next.getTimeInMillis();
    }

    static Intent signIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(SIGN_URI));
        intent.setPackage("com.lynkco.customer");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private static int dayKey(long time) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(time);
        return day.get(Calendar.YEAR) * 1000 + day.get(Calendar.DAY_OF_YEAR);
    }

    private static PendingIntent activityIntent(Context context, int key) {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 36) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        } else if (Build.VERSION.SDK_INT >= 34) {
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return PendingIntent.getActivity(context, key, signIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                options.toBundle());
    }

    private static PendingIntent rescheduleIntent(Context context, int key) {
        Intent intent = new Intent(context, AlarmReceiver.class);
        return PendingIntent.getBroadcast(context, key, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleNext(Context context, boolean cancelPrevious) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        SharedPreferences prefs = preferences(context);
        int oldKey = prefs.getInt(SCHEDULED_KEY, -1);
        if (cancelPrevious && oldKey != -1) {
            manager.cancel(activityIntent(context, oldKey));
            manager.cancel(rescheduleIntent(context, oldKey));
        }
        if (!isEnabled(context)) {
            prefs.edit().remove(SCHEDULED_KEY).apply();
            return;
        }
        long trigger = nextTrigger(context);
        int key = dayKey(trigger);
        PendingIntent openPage = activityIntent(context, key);
        PendingIntent reschedule = rescheduleIntent(context, key);
        if (hasExactAlarmAccess(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, openPage);
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reschedule);
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, openPage);
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reschedule);
        }
        prefs.edit().putInt(SCHEDULED_KEY, key).apply();
    }

    static void scheduleTest(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        PendingIntent openPage = activityIntent(context, 99_999);
        manager.cancel(openPage);
        long trigger = System.currentTimeMillis() + 60_000;
        if (hasExactAlarmAccess(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, openPage);
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, openPage);
        }
    }
}
