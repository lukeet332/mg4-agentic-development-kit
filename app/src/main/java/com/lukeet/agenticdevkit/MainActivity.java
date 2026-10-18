package com.lukeet.agenticdevkit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The in-car face of the kit: channel status, the master token, and the pairing
 * window an agent needs to be let in. Everything here is also reachable from
 * /state and /clients once a client is paired.
 */
public final class MainActivity extends Activity {

    private TextView status;
    private TextView url;
    private TextView token;
    private TextView pairCode;
    private TextView pairStatus;
    private TextView clients;
    private EditText port;
    private Button toggle;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        url = findViewById(R.id.url);
        token = findViewById(R.id.token);
        pairCode = findViewById(R.id.pair_code);
        pairStatus = findViewById(R.id.pair_status);
        clients = findViewById(R.id.clients);
        port = findViewById(R.id.port);
        toggle = findViewById(R.id.toggle);
        Button apply = findViewById(R.id.apply);
        Button copy = findViewById(R.id.copy);
        Button pair = findViewById(R.id.pair);
        Button refresh = findViewById(R.id.refresh);

        token.setText(TokenStore.master(this));
        token.setTextIsSelectable(true);
        port.setText(String.valueOf(Config.port(this)));

        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("ADK token", TokenStore.master(MainActivity.this)));
                    toast("Token copied");
                }
            }
        });
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    int p = Integer.parseInt(port.getText().toString().trim());
                    if (p < 1 || p > 65535) throw new NumberFormatException();
                    Config.setPort(MainActivity.this, p);
                    AdkService.restartChannel(MainActivity.this);
                    AdkService.start(MainActivity.this);
                    toast("Restarting on port " + p);
                } catch (Throwable t) {
                    toast("Port must be 1-65535");
                }
                refresh();
            }
        });
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AdkService.isRunning()) {
                    AdkService.stopChannel(MainActivity.this);
                } else {
                    AdkService.start(MainActivity.this);
                }
                handler.postDelayed(new Runnable() {
                    @Override public void run() { refresh(); }
                }, 700);
            }
        });
        pair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Pairing.open(MainActivity.this);
                refresh();
                toast("Pairing open for " + Pairing.WINDOW_SECONDS + "s");
            }
        });
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });

        // The channel is what makes this app useful; start it with the UI.
        AdkService.start(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void refresh() {
        boolean running = AdkService.isRunning();
        int p = running ? AdkService.runningPort() : Config.port(this);
        String ip = Net.primaryAddress();
        status.setText(running
                ? "Channel RUNNING on port " + p
                : "Channel stopped");
        url.setText(running
                ? "http://" + ip + ":" + p + "   (this unit)   ·   uid " + android.os.Process.myUid()
                : "Start the channel to serve http://" + ip + ":" + p);
        toggle.setText(running ? "Stop channel" : "Start channel");

        Pairing.Status st = Pairing.status(this, true);
        if (st.open && st.code != null) {
            pairCode.setVisibility(View.VISIBLE);
            pairCode.setText(st.code.substring(0, 3) + " " + st.code.substring(3));
            pairStatus.setText("Pairing open · " + st.secondsLeft + "s left · code is single-use");
        } else {
            pairCode.setVisibility(View.GONE);
            pairStatus.setText(st.locked
                    ? "Pairing locked after wrong codes - try again in a minute"
                    : "No pairing window open. Tap 'Pair a device' and give the code to your agent.");
        }

        List<Pairing.Client> list = Pairing.clients(this);
        if (list.isEmpty()) {
            clients.setText("No paired devices yet.");
        } else {
            StringBuilder b = new StringBuilder();
            for (Pairing.Client c : list) {
                b.append("• ").append(c.name).append("  [").append(c.id).append("]")
                        .append("\n   last seen ").append(c.lastSeen == 0 ? "never" : Api.iso(c.lastSeen))
                        .append(c.lastIp.isEmpty() ? "" : " from " + c.lastIp)
                        .append("\n");
            }
            clients.setText(b.toString().trim());
        }
        if (Rollback.pending(this)) {
            clients.append("\n\nUpdate pending confirmation until " + Api.iso(Rollback.deadline(this))
                    + " - POST /selfupdate/confirm to keep it.");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
