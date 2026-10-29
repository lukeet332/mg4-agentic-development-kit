package com.lukeet.mg4apps;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** App drawer opened from the home screen's Apple CarPlay tile: every launchable app, plus Android Settings. */
public final class AppsActivity extends Activity {

    private static final String TAG = "MG4Apps";
    private static final ComponentName ANDROID_SETTINGS =
            new ComponentName("com.android.settings", "com.android.settings.Settings");

    private static final class App {
        final String label;
        final Drawable icon;
        final ComponentName component;

        App(String label, Drawable icon, ComponentName component) {
            this.label = label;
            this.icon = icon;
            this.component = component;
        }
    }

    private final List<App> apps = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A fresh install gets no boot/update broadcast; opening the drawer once switches the trigger on.
        AccessibilityEnabler.ensure(this, false);
        GridView grid = new GridView(this);
        grid.setBackgroundColor(Color.rgb(18, 18, 20));
        grid.setColumnWidth(dp(150));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(24), dp(24), dp(24), dp(24));
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(apps.get(position));
            }
        });
        setContentView(grid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        PackageManager pm = getPackageManager();
        apps.clear();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        boolean haveSettings = false;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            ComponentName c = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            if (c.getPackageName().equals(getPackageName()) && c.getClassName().equals(AppsActivity.class.getName())) continue;
            haveSettings |= c.getPackageName().equals(ANDROID_SETTINGS.getPackageName());
            apps.add(new App(String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm), c));
        }
        if (!haveSettings) {
            try {
                apps.add(new App("Android Settings", pm.getActivityIcon(ANDROID_SETTINGS), ANDROID_SETTINGS));
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "no Android Settings activity to offer");
            }
        }
        Collections.sort(apps, new Comparator<App>() {
            @Override public int compare(App a, App b) { return a.label.compareToIgnoreCase(b.label); }
        });
        adapter.notifyDataSetChanged();
    }

    private void open(App app) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Throwable t) {
            Log.w(TAG, "cannot open " + app.component + ": " + t);
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convertView;
            if (cell == null) {
                cell = new LinearLayout(AppsActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(8), dp(12), dp(8), dp(12));
                ImageView icon = new ImageView(AppsActivity.this);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
                TextView label = new TextView(AppsActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setPadding(0, dp(8), 0, 0);
                cell.addView(label);
            }
            App app = apps.get(position);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
            ((TextView) cell.getChildAt(1)).setText(app.label);
            return cell;
        }
    }
}
