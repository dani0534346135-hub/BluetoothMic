package com.daniel.bluetoothmic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.view.Gravity;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private AudioRecord recorder;
    private AudioTrack player;
    private Thread audioThread;
    private volatile boolean running = false;
    private TextView status;
    private Button mic;
    private SeekBar volumeBar;
    private Spinner effectSpinner;
    private float volume = 0.85f;
    private int rate = 44100;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        ui();
        requestNeededPermissions();
    }

    private void requestNeededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.BLUETOOTH_SCAN);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 10);
    }

    private TextView tx(String s, int z) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(z);
        v.setGravity(Gravity.CENTER); v.setPadding(12,12,12,12);
        return v;
    }

    private void ui() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(28,30,28,20);
        r.setBackgroundColor(Color.rgb(7,0,20));

        TextView title = tx("BLUETOOTH MIC", 28);
        title.setTextColor(Color.CYAN);
        r.addView(title, new LinearLayout.LayoutParams(-1,80));

        status = tx("● READY — CONNECT A BLUETOOTH SPEAKER", 15);
        status.setTextColor(Color.CYAN);
        r.addView(status, new LinearLayout.LayoutParams(-1,75));

        mic = new Button(this);
        mic.setText("🎙 START MICROPHONE");
        mic.setTextSize(18);
        r.addView(mic, new LinearLayout.LayoutParams(-1,125));

        TextView vl = tx("OUTPUT VOLUME",14);
        r.addView(vl);
        volumeBar = new SeekBar(this);
        volumeBar.setMax(100); volumeBar.setProgress(85);
        r.addView(volumeBar, new LinearLayout.LayoutParams(-1,65));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s,int p,boolean f){ volume=p/100f; }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });

        effectSpinner = new Spinner(this);
        effectSpinner.setAdapter(new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Voice: Normal","Voice: Echo","Voice: Robot","Voice: Deep"}));
        r.addView(effectSpinner,new LinearLayout.LayoutParams(-1,65));

        Button bt = new Button(this);
        bt.setText("⚡ OPEN BLUETOOTH SETTINGS");
        r.addView(bt,new LinearLayout.LayoutParams(-1,85));

        Button ab = new Button(this);
        ab.setText("ⓘ ABOUT");
        r.addView(ab,new LinearLayout.LayoutParams(-1,75));

        mic.setOnClickListener(v -> { if(running) stopMic(); else startMic(); });
        bt.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
        ab.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle("Bluetooth Mic")
            .setMessage("2027 Neon Bluetooth Microphone\n\nDeveloper: דניאל כרייף\ndani0534346135@gmail.com")
            .setPositiveButton("OK",null).show());

        setContentView(r);
    }

    private void startMic() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions(); return;
        }

        try {
            int minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minIn <= 0 || minOut <= 0) throw new IllegalStateException("Audio buffer unavailable");

            int buffer = Math.max(minIn, minOut) * 2;

            recorder = new AudioRecord.Builder()
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(buffer).build();

            player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buffer).setTransferMode(AudioTrack.MODE_STREAM).build();

            if (Build.VERSION.SDK_INT >= 23) routeToBluetoothSpeaker(player);

            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Microphone did not start");

            player.play();
            running = true;
            mic.setText("⏹ STOP MICROPHONE");
            status.setText("● LIVE — SPEAK NOW");

            audioThread = new Thread(() -> {
                short[] pcm = new short[buffer / 2];
                long echoDelaySamples = rate / 5;
                short[] echo = new short[(int)echoDelaySamples];
                int echoPos = 0;

                while (running) {
                    int n = recorder.read(pcm,0,pcm.length);
                    if (n <= 0) continue;
                    String effect = String.valueOf(effectSpinner.getSelectedItem());
                    for (int i=0;i<n;i++) {
                        float x = pcm[i] * volume;
                        if (effect.contains("Echo")) {
                            x += echo[echoPos] * 0.45f;
                            echo[echoPos] = pcm[i];
                            echoPos = (echoPos + 1) % echo.length;
                        } else if (effect.contains("Robot")) {
                            x = (float)(Math.signum(x) * Math.min(Math.abs(x) * 1.15f, 32767));
                        } else if (effect.contains("Deep")) {
                            x *= 0.78f;
                        }
                        pcm[i] = (short)Math.max(-32768, Math.min(32767, (int)x));
                    }
                    if (player.write(pcm,0,n,AudioTrack.WRITE_BLOCKING) < 0) break;
                }
            }, "BluetoothMicAudio");
            audioThread.start();
        } catch (Exception e) {
            stopMic();
            status.setText("● AUDIO ERROR — CHECK PERMISSIONS / BLUETOOTH");
            new AlertDialog.Builder(this).setTitle("Audio error")
                .setMessage("לא ניתן להפעיל את המיקרופון. ודא שהרמקול מחובר ב-Bluetooth ושאישרת גישה למיקרופון.")
                .setPositiveButton("OK",null).show();
        }
    }

    private void routeToBluetoothSpeaker(AudioTrack track) {
        AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : devices) {
            if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                if (track.setPreferredDevice(d)) {
                    status.setText("● BLUETOOTH SPEAKER READY");
                    return;
                }
            }
        }
        status.setText("● NO BLUETOOTH SPEAKER FOUND");
    }

    private void stopMic() {
        running = false;
        try { if (recorder != null) { recorder.stop(); recorder.release(); } } catch(Exception ignored){}
        try { if (player != null) { player.stop(); player.release(); } } catch(Exception ignored){}
        recorder = null; player = null; audioThread = null;
        if (mic != null) mic.setText("🎙 START MICROPHONE");
        if (status != null) status.setText("● READY — CONNECT A BLUETOOTH SPEAKER");
    }

    @Override protected void onDestroy() {
        stopMic();
        super.onDestroy();
    }
}