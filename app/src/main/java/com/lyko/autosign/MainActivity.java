package com.lyko.autosign;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;

import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private TimePicker timePicker;
    private Switch enabled;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int margin = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(margin, margin, margin, margin);

        TextView title = new TextView(this);
        title.setText("领克自动签到");
        title.setTextSize(24);
        layout.addView(title);

        TextView guide = new TextView(this);
        guide.setText("每天首次打开领克时，自动进入签到页并点击“立即签到”。可以使用下方定时功能打开领克，也可以用手机自带定时任务打开领克。请在 LSPosed 中启用本模块，作用域选择领克汽车。");
        guide.setTextSize(15);
        layout.addView(guide);

        enabled = new Switch(this);
        enabled.setText("每天自动签到");
        enabled.setChecked(ScheduleManager.isEnabled(this));
        layout.addView(enabled);

        timePicker = new TimePicker(this);
        timePicker.setIs24HourView(true);
        timePicker.setHour(ScheduleManager.hour(this));
        timePicker.setMinute(ScheduleManager.minute(this));
        layout.addView(timePicker);

        Button save = new Button(this);
        save.setText("保存时间");
        save.setOnClickListener(v -> {
            ScheduleManager.save(this, enabled.isChecked(),
                    timePicker.getHour(), timePicker.getMinute());
            refreshStatus();
        });
        layout.addView(save);

        Button test = new Button(this);
        test.setText("立即测试打开签到页");
        test.setOnClickListener(v -> startActivity(ScheduleManager.signIntent()));
        layout.addView(test);

        Button scheduledTest = new Button(this);
        scheduledTest.setText("1 分钟后测试定时启动");
        scheduledTest.setOnClickListener(v -> {
            ScheduleManager.scheduleTest(this);
            status.setText("已安排 1 分钟后打开领克签到页。可以返回桌面等待测试。");
        });
        layout.addView(scheduledTest);

        Button permission = new Button(this);
        permission.setText("授予定时权限");
        permission.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        });
        layout.addView(permission);

        status = new TextView(this);
        status.setTextSize(15);
        layout.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ScheduleManager.isEnabled(this)) {
            ScheduleManager.scheduleNext(this, true);
        }
        refreshStatus();
    }

    private void refreshStatus() {
        if (!ScheduleManager.isEnabled(this)) {
            status.setText("当前未启用定时签到。");
            return;
        }
        String next = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(ScheduleManager.nextTrigger(this)));
        status.setText("下次启动：" + next + (ScheduleManager.hasExactAlarmAccess(this)
                ? "（已授予定时权限，省电策略仍可能延迟）" : "（未授予定时权限，系统可能延迟）"));
    }
}
