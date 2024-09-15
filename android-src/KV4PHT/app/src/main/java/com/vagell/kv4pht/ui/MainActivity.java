package com.vagell.kv4pht.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.hoho.android.usbserial.driver.SerialTimeoutException;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.vagell.kv4pht.BR;
import com.vagell.kv4pht.R;
import com.vagell.kv4pht.aprs.parser.APRSPacket;
import com.vagell.kv4pht.aprs.parser.Digipeater;
import com.vagell.kv4pht.aprs.parser.InformationField;
import com.vagell.kv4pht.aprs.parser.MessagePacket;
import com.vagell.kv4pht.aprs.parser.Parser;
import com.vagell.kv4pht.data.AppSetting;
import com.vagell.kv4pht.data.ChannelMemory;
import com.vagell.kv4pht.databinding.ActivityMainBinding;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200Modulator;
import com.vagell.kv4pht.javAX25.ax25.Afsk1200MultiDemodulator;
import com.vagell.kv4pht.javAX25.ax25.Packet;
import com.vagell.kv4pht.javAX25.ax25.PacketDemodulator;
import com.vagell.kv4pht.javAX25.ax25.PacketHandler;

import org.apache.commons.lang3.ArrayUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    // Must match the ESP32 device we support.
    // Idx 0 matches https://www.amazon.com/gp/product/B08D5ZD528
    public static final int[] ESP32_VENDOR_IDS = {4292};
    public static final int[] ESP32_PRODUCT_IDS = {60000};

    private static final byte SILENT_BYTE = -128;

    // For transmitting audio to ESP32 / radio
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private int channelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int audioFormat = AudioFormat.ENCODING_PCM_8BIT;
    private int minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelConfig, audioFormat) * 2;
    private Thread recordingThread;
    private UsbManager usbManager;
    private UsbDevice esp32Device;
    private UsbSerialPort serialPort;
    private SerialInputOutputManager usbIoManager;
    private static final int TX_AUDIO_CHUNK_SIZE = 512; // Tx audio bytes to send to ESP32 in a single USB write
    private Snackbar usbSnackbar = null;

    // For receiving audio from ESP32 / radio
    private AudioTrack audioTrack;
    private static final int PRE_BUFFER_SIZE = 1000;
    private byte[] rxBytesPrebuffer = new byte[PRE_BUFFER_SIZE];
    private int rxPrebufferIdx = 0;
    private boolean prebufferComplete = false;
    private static final float SEC_BETWEEN_SCANS = 0.5f; // how long to wait during silence to scan to next frequency in scan mode

    // Delimiter must match ESP32 code
    private static final byte[] COMMAND_DELIMITER = new byte[] {(byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00, (byte)0xFF, (byte)0x00};

    private static final int REQUEST_AUDIO_PERMISSION_CODE = 1;
    private static final int REQUEST_NOTIFICATIONS_PERMISSION_CODE = 2;

    private static final String ACTION_USB_PERMISSION = "com.vagell.kv4pht.USB_PERMISSION";

    private static final int MODE_STARTUP = -1;
    private static final int MODE_RX = 0;
    private static final int MODE_TX = 1;
    private static final int MODE_SCAN = 2;
    private int mode = MODE_STARTUP;

    // AFSK modem
    private Afsk1200Modulator afskModulator = null;
    private PacketDemodulator afskDemodulator = null;
    private static final int MS_DELAY_BEFORE_DATA_XMIT = 1000;
    private static final int MS_SILENCE_BEFORE_DATA = 300;
    private static final int MS_SILENCE_AFTER_DATA = 700;

    // Radio params and related settings
    private String activeFrequencyStr = "144.000";
    private int squelch = 0;
    private String callsign = null;
    private boolean stickyPTT = false;
    private boolean disableAnimations = false;

    // Activity callback values
    public static final int REQUEST_ADD_MEMORY = 0;
    public static final int REQUEST_EDIT_MEMORY = 1;
    public static final int REQUEST_SETTINGS = 2;

    private Map<String, Integer> mTones = new HashMap<>();

    private MainViewModel viewModel;
    private RecyclerView recyclerView;
    private MemoriesAdapter adapter;

    private ThreadPoolExecutor threadPoolExecutor = null;

    private String selectedMemoryGroup = null; // null means unfiltered, no group selected
    private int activeMemoryId = -1; // -1 means we're in simplex mode
    private int consecutiveSilenceBytes = 0; // To determine when to move scan after silence

    // Audio visualizers
    private Visualizer rxAudioVisualizer = null;
    private static int AUDIO_VISUALIZER_RATE = Visualizer.getMaxCaptureRate();
    private static int MAX_AUDIO_VIZ_SIZE = 500;
    private static int MIN_TX_AUDIO_VIZ_SIZE = 200;
    private static int RECORD_ANIM_FPS = 30;

    // Safety constants
    private static int RUNAWAY_TX_TIMEOUT_SEC = 180; // Stop runaway tx after 3 minutes
    private long startTxTimeSec = -1;
    private Snackbar callsignSnackbar = null;

    // Notification stuff
    private static String MESSAGE_NOTIFICATION_CHANNEL_ID = "aprs_message_notifications";
    private static int MESSAGE_NOTIFICATION_TO_YOU_ID = 0;
    private static String INTENT_OPEN_CHAT = "com.vagell.kv4pht.OPEN_CHAT_ACTION";

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on
        // TODO This is a hack to keep app running (i.e. audio playing) and not be paused,
        // need to replace this with a Service so screen can be turned off.
        // Here is a starter for how to build the Service: https://developer.android.com/media/platform/mediaplayer#mpandservices
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        createNotificationChannels();

        // Bind data to the UI via the MainViewModel class
        viewModel = new ViewModelProvider(this).get(MainViewModel.class);
        viewModel.setActivity(this);
        ActivityMainBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_main);
        binding.setLifecycleOwner(this);
        binding.setVariable(BR.viewModel, viewModel);

        // Prepare a RecyclerView for the list of channel memories
        recyclerView = findViewById(R.id.memoriesList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemoriesAdapter(new MemoriesAdapter.MemoryListener() {
            @Override
            public void onMemoryClick(ChannelMemory memory) {
                // Actually tune to it.
                if (mode == MODE_SCAN) {
                    setScanning(false);
                }
                tuneToMemory(memory, squelch);

                // Highlight the tapped memory, unhighlight all the others.
                viewModel.highlightMemory(memory);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onMemoryDelete(ChannelMemory memory) {
                String freq = memory.frequency;
                viewModel.deleteMemory(memory);
                viewModel.loadData();
                adapter.notifyDataSetChanged();
                tuneToFreq(freq, squelch); // Stay on the same freq as the now-deleted memory
            }

            @Override
            public void onMemoryEdit(ChannelMemory memory) {
                Intent intent = new Intent("com.vagell.kv4pht.EDIT_MEMORY_ACTION");
                intent.putExtra("requestCode", REQUEST_EDIT_MEMORY);
                intent.putExtra("memoryId", memory.memoryId);
                startActivityForResult(intent, REQUEST_EDIT_MEMORY);
            }
        });
        recyclerView.setAdapter(adapter);

        // Observe the LiveData in MainViewModel (so the RecyclerView can populate with the memories)
        viewModel.getChannelMemories().observe(this, new Observer<List<ChannelMemory>>() {
            @Override
            public void onChanged(List<ChannelMemory> channelMemories) {
                // Update the adapter's data
                if (selectedMemoryGroup != null) {
                    for (int i = 0; i < channelMemories.size(); i++) {
                        if (!channelMemories.get(i).group.equals(selectedMemoryGroup)) {
                            channelMemories.remove(i--);
                        }
                    }
                }
                adapter.setMemoriesList(channelMemories);
                adapter.notifyDataSetChanged();
            }
        });

        // Set up behavior on the bottom nav
        BottomNavigationView bottomNav = findViewById(R.id.bottomNavigationView);
        bottomNav.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.voice_mode) {
                    setVisibleScreen(false);
                } else if (itemId == R.id.text_chat_mode) {
                    setVisibleScreen(true);
                }
                return true;
            }
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        requestAudioPermissions();
        requestNotificationPermissions(); // TODO store a boolean in our DB so we only ask for this once (in case they say no)
        findESP32Device();

        attachListeners();
        initAudioTrack();

        setupTones();

        initAFSKModem();

        viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
            @Override
            public void onLoadDataDone() {
                applySettings();
                viewModel.setCallback(null);
            }
        });
        viewModel.loadData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
            threadPoolExecutor = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        Log.d("DEBUG", "onPause");

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (threadPoolExecutor != null) {
            threadPoolExecutor.shutdownNow();
            threadPoolExecutor = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.d("DEBUG", "onResume");

        // TODO unclear why threadPoolExecutor sometimes breaks when we start another activity, but
        // we recreate it here as a workaround. I think it would be MUCH better fixed by moving all
        // audio and data handling to a Service and extract ALL those objects and code out of
        // MainActivity into that. Right now, we're really overloadnig MainActivity for way too much,
        // and it's Activity lifecycle changes are probably breaking things unexpectedly.
        threadPoolExecutor = new ThreadPoolExecutor(2,
                10, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

        initAudioTrack();
        initAudioRecorder();

        viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
            @Override
            public void onLoadDataDone() {
                applySettings();
                viewModel.setCallback(null);
            }
        });
        viewModel.loadData();
    }

    private void createNotificationChannels() {
        // Notification channel for APRS text chat messages
        NotificationChannel channel = new NotificationChannel(MESSAGE_NOTIFICATION_CHANNEL_ID,
                "Chat messages", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("APRS text chat messages addressed to you");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If we arrived here from an APRS text chat notification, open text chat.
        if (intent != null && intent.getAction().equals(INTENT_OPEN_CHAT)) {
            setVisibleScreen(true);
        }
    }

    private void initAFSKModem() {
        final Context activity = this;

        PacketHandler packetHandler = new PacketHandler() {
            @Override
            public void handlePacket(byte[] data) {
                APRSPacket aprsPacket;
                try {
                    aprsPacket = Parser.parseAX25(data);
                } catch (Exception e) {
                    debugLog("Unable to parse an APRSPacket, skipping.");
                    return;
                }

                final String finalString;

                // Reformat the packet to be more human readable.
                InformationField infoField = aprsPacket.getAprsInformation();
                if (infoField.getDataTypeIdentifier() == ':') { // APRS "message" type. What we expect for our text chat.
                    MessagePacket messagePacket = new MessagePacket(infoField.getRawBytes(), aprsPacket.getDestinationCall());
                    finalString = aprsPacket.getSourceCall() + " to " + messagePacket.getTargetCallsign() + ": " + messagePacket.getMessageBody();

                    // If the message was addressed to us, notify the user.
                    if (messagePacket.getTargetCallsign().toUpperCase().equals(callsign.toUpperCase())) {
                        showNotification(MESSAGE_NOTIFICATION_CHANNEL_ID, MESSAGE_NOTIFICATION_TO_YOU_ID,
                                aprsPacket.getSourceCall() + " messaged you", messagePacket.getMessageBody(), INTENT_OPEN_CHAT);
                    }
                } else { // Raw APRS packet. Useful for things like monitoring 144.39 for misc APRS traffic.
                    // TODO add better implementation of other message types (especially Location and Object, which are common on 144.390MHz).
                    finalString = aprsPacket.toString();
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        TextView chatLog = findViewById(R.id.textChatLog);
                        chatLog.append(finalString + "\n");

                        ScrollView textChatScrollView = findViewById(R.id.textChatScrollView);
                        textChatScrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        };

        try {
            afskDemodulator = new Afsk1200MultiDemodulator(AUDIO_SAMPLE_RATE, packetHandler);
            afskModulator = new Afsk1200Modulator(AUDIO_SAMPLE_RATE);
        } catch (Exception e) {
            Log.d("DEBUG", "Unable to create AFSK modem objects.");
        }
    }

    private void showNotification(String notificationChannelId, int notificationTypeId, String title, String message, String tapIntentName) {
        if (notificationChannelId == null || title == null || message == null) {
            Log.d("DEBUG", "Unexpected null in showNotification.");
            return;
        }

        // Has the user disallowed notifications?
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // If they tap the notification when doing something else, come back to this app
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(tapIntentName);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Notify the user they got a message.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, notificationChannelId)
                .setSmallIcon(R.drawable.ic_chat_notification)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true) // Dismiss on tap
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(notificationTypeId, builder.build());
    }

    /**
     * Specifies whether the voice or text chat screen should be visible.
     * @param isTextChat true to show the text chat screen, false to show the voice screen.
     */
    private void setVisibleScreen(boolean isTextChat) {
        // TODO The right way to implement the bottom nav toggling the UI would be with Fragments.
        // Controls for voice mode
        findViewById(R.id.voiceModeLineHolder).setVisibility(isTextChat ? View.GONE : View.VISIBLE);
        findViewById(R.id.pttButton).setVisibility(isTextChat ? View.GONE : View.VISIBLE);
        findViewById(R.id.memoriesList).setVisibility(isTextChat ? View.GONE : View.VISIBLE);
        findViewById(R.id.voiceModeBottomControls).setVisibility(isTextChat ? View.GONE : View.VISIBLE);

        // Controls for text mode
        findViewById(R.id.textModeContainer).setVisibility(isTextChat ? View.VISIBLE : View.GONE);

        if (isTextChat) {
            // Stop scanning when we enter chat mode, we don't want to tx data on an unexpected
            // frequency. User must set it manually (or select it before coming to chat mode, but
            // can't be scanning).
            setScanning(false, true);

            // If their callsign is not set, display a snackbar asking them to set it before they
            // can transmit.
            if (callsign.length() == 0) {
                showCallsignSnackbar();
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(false);
                findViewById(R.id.sendButtonOverlay).setVisibility(View.VISIBLE);
            } else {
                ImageButton sendButton = findViewById(R.id.sendButton);
                sendButton.setEnabled(true);
                if (callsignSnackbar != null) {
                    callsignSnackbar.dismiss();
                }
                findViewById(R.id.sendButtonOverlay).setVisibility(View.GONE);
            }
        } else {
            if (callsignSnackbar != null) {
                callsignSnackbar.dismiss();
            }
        }
    }

    private void showCallsignSnackbar() {
        CharSequence snackbarMsg = "Set your callsign to send text chat";
        callsignSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_LONG)
                .setAction("Set now", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        settingsClicked(null);
                    }
                })
                .setBackgroundTint(getResources().getColor(R.color.primary))
                .setTextColor(getResources().getColor(R.color.medium_gray))
                .setActionTextColor(getResources().getColor(R.color.black))
                .setAnchorView(findViewById(R.id.textChatInput));

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) callsignSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        callsignSnackbar.show();
    }

    public void sendButtonOverlayClicked(View view) {
        if (callsign.length() == 0) {
            showCallsignSnackbar();
            ImageButton sendButton = findViewById(R.id.sendButton);
            sendButton.setEnabled(false);
        }
    }

    public void sendTextClicked(View view) {
        String targetCallsign = ((EditText) findViewById(R.id.textChatTo)).getText().toString().trim();
        if (targetCallsign.length() == 0) {
            targetCallsign = "CQ";
        } else {
            targetCallsign = targetCallsign.toUpperCase();
        }
        ((EditText) findViewById(R.id.textChatTo)).setText(targetCallsign);

        String outText = ((EditText) findViewById(R.id.textChatInput)).getText().toString();
        if (outText.length() == 0) {
            return; // Nothing to send.
        }
        ((EditText) findViewById(R.id.textChatInput)).setText("");

        // Prepare APRS packet, and use its bytes to populate an AX.25 packet.
        MessagePacket msgPacket = new MessagePacket(targetCallsign, outText, "1"); // TODO increment messageNumber each time, store in Android app DB.
        ArrayList<Digipeater> digipeaters = new ArrayList<>();
        digipeaters.add(new Digipeater("WIDE1*"));
        digipeaters.add(new Digipeater("WIDE2-1"));
        APRSPacket aprsPacket = new APRSPacket(callsign, "CQ", digipeaters, msgPacket.getRawBytes());
        Packet ax25Packet = new Packet(aprsPacket.toAX25Frame());

        // This strange approach to getting bytes seems to be a state machine in the AFSK library.
        afskModulator.prepareToTransmit(ax25Packet);
        float[] txSamples = afskModulator.getTxSamplesBuffer();
        int n;
        ArrayList<Byte> audioBytes = new ArrayList<Byte>();
        while ((n = afskModulator.getSamples()) > 0) {
            for (int i = 0; i < n; i++) {
                byte audioByte = convertFloatToPCM8(txSamples[i]);
                audioBytes.add(audioByte);
            }
        }
        byte[] simpleAudioBytes = ArrayUtils.toPrimitive(audioBytes.toArray(new Byte[0]));

        startPtt(true);
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // Add some silence before and after the data.
                int bytesOfLeadInDelay = (AUDIO_SAMPLE_RATE / 1000 * MS_SILENCE_BEFORE_DATA);
                int bytesOfTailDelay = (AUDIO_SAMPLE_RATE / 1000 * MS_SILENCE_AFTER_DATA);
                byte[] combinedAudio = new byte[bytesOfLeadInDelay + simpleAudioBytes.length + bytesOfTailDelay];
                for (int i = 0; i < bytesOfLeadInDelay; i++) {
                    combinedAudio[i] = SILENT_BYTE;
                }
                for (int i = 0; i < simpleAudioBytes.length; i++) {
                    combinedAudio[i + bytesOfLeadInDelay] = simpleAudioBytes[i];
                }
                for (int i = (bytesOfLeadInDelay + simpleAudioBytes.length); i < combinedAudio.length; i++) {
                    combinedAudio[i] = SILENT_BYTE;
                }

                sendAudioToESP32(combinedAudio, true);
            }
        }, MS_DELAY_BEFORE_DATA_XMIT);

        TextView chatLog = findViewById(R.id.textChatLog);
        chatLog.append(callsign + " to " + targetCallsign + ": " + outText + "\n");

        ScrollView scrollView = findViewById(R.id.textChatScrollView);
        scrollView.fullScroll(View.FOCUS_DOWN);

        findViewById(R.id.textChatInput).requestFocus();
    }

    private void createRxAudioVisualizer() {
        rxAudioVisualizer = new Visualizer(audioTrack.getAudioSessionId());
        rxAudioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
            @Override
            public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                if (disableAnimations) { return; }

                float rxVolume = Math.max(0f, (((float) waveform[0] + 128f) / 256) - 0.4f); // 0 to 1
                ImageView rxAudioView = findViewById(R.id.rxAudioCircle);
                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) rxAudioView.getLayoutParams();
                layoutParams.width = (int) (MAX_AUDIO_VIZ_SIZE * rxVolume);
                layoutParams.height = (int) (MAX_AUDIO_VIZ_SIZE * rxVolume);
                rxAudioView.setLayoutParams(layoutParams);
            }

            @Override
            public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {

            }
        }, AUDIO_VISUALIZER_RATE, true, false);
        rxAudioVisualizer.setEnabled(true);
    }

    private void updateRecordingVisualization(int waitMs, byte audioByte) {
        if (disableAnimations) { return; }

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        float txVolume = ((float) audioByte + 128f) / 256; // 0 to 1
                        ImageView txAudioView = findViewById(R.id.txAudioCircle);
                        ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) txAudioView.getLayoutParams();
                        layoutParams.width = audioByte == SILENT_BYTE || mode == MODE_RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
                        layoutParams.height = audioByte == SILENT_BYTE || mode == MODE_RX ? 0 : (int) (MAX_AUDIO_VIZ_SIZE * txVolume) + MIN_TX_AUDIO_VIZ_SIZE;
                        txAudioView.setLayoutParams(layoutParams);
                    }
                });
            }
        }, waitMs); // waitMs gives us the fps we desire, see RECORD_ANIM_FPS constant.
    }

    private void applySettings() {
        if (viewModel.appDb == null || threadPoolExecutor == null) {
            return; // DB not yet loaded (e.g. radio attached before DB init completed)
        }

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting callsignSetting = viewModel.appDb.appSettingDao().getByName("callsign");
                AppSetting squelchSetting = viewModel.appDb.appSettingDao().getByName("squelch");
                AppSetting emphasisSetting = viewModel.appDb.appSettingDao().getByName("emphasis");
                AppSetting highpassSetting = viewModel.appDb.appSettingDao().getByName("highpass");
                AppSetting lowpassSetting = viewModel.appDb.appSettingDao().getByName("lowpass");
                AppSetting stickyPTTSetting = viewModel.appDb.appSettingDao().getByName("stickyPTT");
                AppSetting disableAnimationsSetting = viewModel.appDb.appSettingDao().getByName("disableAnimations");
                AppSetting lastMemoryId = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                AppSetting lastFreq = viewModel.appDb.appSettingDao().getByName("lastFreq");
                AppSetting lastGroupSetting = viewModel.appDb.appSettingDao().getByName("lastGroup");

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callsignSetting != null) {
                            callsign = callsignSetting.value;

                            // Enable or prevent APRS texting depending on if callsign was set.
                            if (callsign.length() == 0) {
                                findViewById(R.id.sendButton).setEnabled(false);
                                findViewById(R.id.sendButtonOverlay).setVisibility(View.VISIBLE);
                            } else {
                                findViewById(R.id.sendButton).setEnabled(true);
                                findViewById(R.id.sendButtonOverlay).setVisibility(View.GONE);
                            }
                        }

                        if (lastGroupSetting != null && !lastGroupSetting.value.equals("")) {
                            selectMemoryGroup(lastGroupSetting.value);
                        }

                        if (lastMemoryId != null && !lastMemoryId.value.equals("-1")) {
                            activeMemoryId = Integer.parseInt(lastMemoryId.value);
                        } else {
                            activeMemoryId = -1;
                            if (lastFreq != null) {
                                activeFrequencyStr = lastFreq.value;
                            } else {
                                activeFrequencyStr = "146.520"; // VHF calling freq
                            }
                        }

                        if (squelchSetting != null) {
                            squelch = Integer.parseInt(squelchSetting.value);
                        }
                        if (activeMemoryId > -1) {
                            tuneToMemory(activeMemoryId, squelch);
                        } else {
                            tuneToFreq(activeFrequencyStr, squelch);
                        }

                        boolean emphasis = false;
                        boolean highpass = false;
                        boolean lowpass = false;
                        if (emphasisSetting != null) {
                            emphasis = Boolean.parseBoolean(emphasisSetting.value);
                        }

                        if (highpassSetting != null) {
                            highpass = Boolean.parseBoolean(highpassSetting.value);
                        }

                        if (lowpassSetting != null) {
                            lowpass = Boolean.parseBoolean(lowpassSetting.value);
                        }

                        final boolean finalEmphasis = emphasis;
                        final boolean finalHighpass = highpass;
                        final boolean finalLowpass = lowpass;

                        threadPoolExecutor.execute(new Runnable() {
                            @Override
                            public void run() {
                                setRadioFilters(finalEmphasis, finalHighpass, finalLowpass);
                                if (mode != MODE_SCAN) {
                                    mode = MODE_RX;
                                }
                            }
                        });

                        if (stickyPTTSetting != null) {
                            stickyPTT = Boolean.parseBoolean(stickyPTTSetting.value);
                        }

                        if (disableAnimationsSetting != null) {
                            disableAnimations = Boolean.parseBoolean(disableAnimationsSetting.value);
                            if (disableAnimations) {
                                // Hide the rx audio visualization
                                ImageView rxAudioView = findViewById(R.id.rxAudioCircle);
                                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams) rxAudioView.getLayoutParams();
                                layoutParams.width = 0;
                                layoutParams.height = 0;
                                rxAudioView.setLayoutParams(layoutParams);

                                // Hide the tx audio visualization
                                updateRecordingVisualization(100, SILENT_BYTE);
                            }
                        }
                    }
                });
            }
        });
    }

    private void restartAudioPrebuffer() {
        prebufferComplete = false;
        rxPrebufferIdx = 0;
    }

    private void setRadioFilters(boolean emphasis, boolean highpass, boolean lowpass) {
        sendCommandToESP32(ESP32Command.FILTERS, (emphasis ? "1" : "0") + (highpass ? "1" : "0") + (lowpass ? "1" : "0"));

        // Discard any buffered audio which isn't filtered
        restartAudioPrebuffer();

        // After the radio filters have been set, the PTT button can be used. If it's used before that,
        // the async setting of the radio filters could conflict with the tx audio stream and cause an
        // app crash (because of the 3 sec async wait to apply the filters).
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setClickable(true);
    }

    private void setupTones() {
        mTones.put("None", 0);
        mTones.put("67", 1);
        mTones.put("71.9", 2);
        mTones.put("74.4", 3);
        mTones.put("77", 4);
        mTones.put("79.7", 5);
        mTones.put("82.5", 6);
        mTones.put("85.4", 7);
        mTones.put("88.5", 8);
        mTones.put("91.5", 9);
        mTones.put("94.8", 10);
        mTones.put("97.4", 11);
        mTones.put("100", 12);
        mTones.put("103.5", 13);
        mTones.put("107.2", 14);
        mTones.put("110.9", 15);
        mTones.put("114.8", 16);
        mTones.put("118.8", 17);
        mTones.put("123", 18);
        mTones.put("127.3", 19);
        mTones.put("131.8", 20);
        mTones.put("136.5", 21);
        mTones.put("141.3", 22);
        mTones.put("146.2", 23);
        mTones.put("151.4", 24);
        mTones.put("156.7", 25);
        mTones.put("162.2", 26);
        mTones.put("167.9", 27);
        mTones.put("173.8", 28);
        mTones.put("179.9", 29);
        mTones.put("186.2", 30);
        mTones.put("192.8", 31);
        mTones.put("203.5", 32);
        mTones.put("210.7", 33);
        mTones.put("218.1", 34);
        mTones.put("225.7", 35);
        mTones.put("233.6", 36);
        mTones.put("241.8", 37);
        mTones.put("250.3", 38);
    }

    private void attachListeners() {
        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // If the user tries to transmit, stop scanning so we don't
                // move to a different frequency during or after the tx.
                setScanning(false, false);

                boolean touchHandled = false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!((ImageButton) v).isClickable()) {
                            touchHandled = true;
                            break;
                        }
                        if (stickyPTT) {
                            if (mode == MODE_RX) {
                                ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                                startPtt(false);
                            } else if (mode == MODE_TX) {
                                ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                                endPtt();
                            }
                        } else {
                            ((Vibrator) getSystemService(Context.VIBRATOR_SERVICE)).vibrate(100);
                            startPtt(false);
                        }
                        touchHandled = true;
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!((ImageButton) v).isClickable()) {
                            touchHandled = true;
                            break;
                        }
                        if (!stickyPTT) {
                            endPtt();
                        }
                        touchHandled = true;
                        break;
                }

                return touchHandled;
            }
        });

        EditText activeFrequencyField = findViewById(R.id.activeFrequency);
        activeFrequencyField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                tuneToFreq(activeFrequencyField.getText().toString(), squelch);
                hideKeyboard();
                activeFrequencyField.clearFocus();
                return true;
            }
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(this.getCurrentFocus().getWindowToken(), 0);
    }

    // Reformats the given frequency as "xxx.xxx" and ensures it's the 2m US amateur band.
    // If the given frequency is unsalvageable, returns activeFrequencyStr.
    private String validateFrequency(String tempFrequency) {
        String newFrequency = formatFrequency(tempFrequency);

        // Resort to the old frequency, the one the user inputted is unsalvageable.
        return newFrequency == null ? activeFrequencyStr : newFrequency;
    }

    // TODO extract this to a utility class, shouldn't be in an Activity
    public static String formatFrequency(String tempFrequency) {
        tempFrequency = tempFrequency.trim();

        // Pad any missing zeroes to match format expected by radio module.
        if (tempFrequency.matches("14[4-8]\\.[0-9][0-9][0-9]")) {
            return tempFrequency;
        } else if (tempFrequency.matches("14[4-8]\\.[0-9][0-9]")) {
            return tempFrequency + "0";
        } else if (tempFrequency.matches("14[4-8]\\.[0-9]")) {
            return tempFrequency + "00";
        } else if (tempFrequency.matches("14[4-8]\\.")) {
            return tempFrequency + "000";
        } else if (tempFrequency.matches("14[4-8]")) {
            return tempFrequency + ".000";
        } else if (tempFrequency.matches("14[4-8][0-9][0-9][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 6);
        } else if (tempFrequency.matches("14[4-8][0-9][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 5) + "0";
        } else if (tempFrequency.matches("14[4-8][0-9]")) {
            return tempFrequency.substring(0, 3) + "." + tempFrequency.substring(3, 4) + "00";
        }

        return null;
    }

    // Tell microcontroller to tune to the given frequency string, which must already be formatted
    // in the style the radio module expects.
    private void tuneToFreq(String frequencyStr, int squelchLevel) {
        mode = MODE_RX;
        activeFrequencyStr = validateFrequency(frequencyStr);
        activeMemoryId = -1;

        if (serialPort != null) {
            sendCommandToESP32(ESP32Command.TUNE_TO, makeSafe2MFreq(activeFrequencyStr) + makeSafe2MFreq(activeFrequencyStr) + "00" + squelchLevel);
        }

        // Save most recent freq so we can restore it on app restart
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting lastFreqSetting = viewModel.appDb.appSettingDao().getByName("lastFreq");
                if (lastFreqSetting != null) {
                    lastFreqSetting.value = frequencyStr;
                    viewModel.appDb.appSettingDao().update(lastFreqSetting);
                } else {
                    lastFreqSetting = new AppSetting("lastFreq", frequencyStr);
                    viewModel.appDb.appSettingDao().insertAll(lastFreqSetting);
                }

                // And clear out any saved memory ID, so we restore to a simplex freq on restart.
                AppSetting lastMemoryIdSetting = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                if (lastMemoryIdSetting != null) {
                    lastMemoryIdSetting.value = "-1";
                    viewModel.appDb.appSettingDao().update(lastMemoryIdSetting);
                } else {
                    lastMemoryIdSetting = new AppSetting("lastMemoryId", "-1");
                    viewModel.appDb.appSettingDao().insertAll(lastMemoryIdSetting);
                }
            }
        });

        showMemoryName("Simplex");
        showFrequency(activeFrequencyStr);

        // Unhighlight all memory rows, since this is a simplex frequency.
        viewModel.highlightMemory(null);
        adapter.notifyDataSetChanged();

        // Reset audio prebuffer
        restartAudioPrebuffer();
    }

    private void tuneToMemory(int memoryId, int squelchLevel) {
        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        if (channelMemories == null) {
            return;
        }
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                if (serialPort != null) {
                    tuneToMemory(channelMemories.get(i), squelchLevel);
                }

                // Save most recent memory so we can restore it on app restart
                threadPoolExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        AppSetting lastMemoryIdSetting = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                        if (lastMemoryIdSetting != null) {
                            lastMemoryIdSetting.value = "" + memoryId;
                            viewModel.appDb.appSettingDao().update(lastMemoryIdSetting);
                        } else {
                            lastMemoryIdSetting = new AppSetting("lastMemoryId", "" + memoryId);
                            viewModel.appDb.appSettingDao().insertAll(lastMemoryIdSetting);
                        }
                    }
                });
                return;
            }
        }
    }

    private String getMemoryGroupStr(int memoryId) {
        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        if (channelMemories == null) {
            return null;
        }
        for (int i = 0; i < channelMemories.size(); i++) {
            if (channelMemories.get(i).memoryId == memoryId) {
                return channelMemories.get(i).group;
            }
        }
        return null;
    }

    private void tuneToMemory(ChannelMemory memory, int squelchLevel) {
        activeFrequencyStr = validateFrequency(memory.frequency);
        activeMemoryId = memory.memoryId;

        if (serialPort != null) {
            sendCommandToESP32(ESP32Command.TUNE_TO,
                    getTxFreq(memory.frequency, memory.offset) + makeSafe2MFreq(memory.frequency) + getToneIdxStr(memory.tone) + squelchLevel);
        }

        // Save most recent memory so we can restore it on app restart
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting lastMemoryIdSetting = viewModel.appDb.appSettingDao().getByName("lastMemoryId");
                if (lastMemoryIdSetting != null) {
                    lastMemoryIdSetting.value = "" + memory.memoryId;
                    viewModel.appDb.appSettingDao().update(lastMemoryIdSetting);
                } else {
                    lastMemoryIdSetting = new AppSetting("lastMemoryId", "" + memory.memoryId);
                    viewModel.appDb.appSettingDao().insertAll(lastMemoryIdSetting);
                }
            }
        });

        showMemoryName(memory.name);
        showFrequency(activeFrequencyStr);

        // Reset audio prebuffer
        restartAudioPrebuffer();
    }

    private void checkScanDueToSilence() {
        // Note that we handle scanning explicitly like this rather than using dra->scan() because
        // as best I can tell the DRA818v chip has a defect where it always returns "S=1" (which
        // means there is no signal detected on the given frequency) even when there is. I did
        // extensive debugging and even rewrote large portions of the DRA818v library to determine
        // that this was the case. So in lieu of that, we scan using a timing/silence-based system.
        if (consecutiveSilenceBytes >= (AUDIO_SAMPLE_RATE * SEC_BETWEEN_SCANS)) {
            consecutiveSilenceBytes = 0;
            nextScan();
        }
    }

    private void showMemoryName(String name) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView activeFrequencyField = findViewById(R.id.activeMemoryName);
                activeFrequencyField.setText(name);
            }
        });
    }

    private void showFrequency(String frequency) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                EditText activeFrequencyField = findViewById(R.id.activeFrequency);
                activeFrequencyField.setText(frequency);
                activeFrequencyStr = frequency;
            }
        });
    }

    private String getTxFreq(String txFreq, int offset) {
        if (offset == ChannelMemory.OFFSET_NONE) {
            return txFreq;
        } else {
            Float freqFloat = Float.parseFloat(txFreq);
            if (offset == ChannelMemory.OFFSET_UP) {
                freqFloat += .600f;
            } else if (offset == ChannelMemory.OFFSET_DOWN){
                freqFloat -= .600f;
            }
            return makeSafe2MFreq(freqFloat.toString());
        }
    }

    private String makeSafe2MFreq(String strFreq) {
        Float freq = Float.parseFloat(strFreq);
        freq = Math.min(freq, 148.0f);
        freq = Math.max(freq, 144.0f);

        strFreq = String.format(java.util.Locale.US,"%.3f", freq);
        strFreq = formatFrequency(strFreq);

        return strFreq;
    }

    private String getToneIdxStr(String toneStr) {
        if (toneStr == null) {
            toneStr = "None";
        }

        Integer toneIdx = mTones.get(toneStr);

        return toneIdx < 10 ? "0" + toneIdx : toneIdx.toString();
    }

    private void initAudioTrack() {
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBufferSize)
                .build();

        createRxAudioVisualizer();
        restartAudioPrebuffer();
    }

    protected void startPtt(boolean dataMode) {
        if (mode == MODE_TX) {
            return;
        }

        // Setup runaway tx safety measures.
        startTxTimeSec = System.currentTimeMillis() / 1000;
        threadPoolExecutor.execute(new Runnable() {
               @Override
               public void run() {
                   try {
                       Thread.sleep(RUNAWAY_TX_TIMEOUT_SEC * 1000);

                       if (mode != MODE_TX) {
                           return;
                       }

                       long elapsedSec = (System.currentTimeMillis() / 1000) - startTxTimeSec;
                       if (elapsedSec > RUNAWAY_TX_TIMEOUT_SEC) { // Check this because multiple tx may have happened with RUNAWAY_TX_TIMEOUT_SEC.
                           debugLog("Warning: runaway tx timeout reached, PTT stopped.");
                           endPtt();
                       }
                   } catch (InterruptedException e) {
                   }
               }
           });

        mode = MODE_TX;
        sendCommandToESP32(ESP32Command.PTT_DOWN);
        audioTrack.stop();

        if (!dataMode) {
            startRecording();
        }
    }

    protected void endPtt() {
        if (mode == MODE_RX) {
            return;
        }
        mode = MODE_RX;
        sendCommandToESP32(ESP32Command.PTT_UP);
        stopRecording();
        audioTrack.flush();
        restartAudioPrebuffer();
    }

    protected void requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.RECORD_AUDIO)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs the audio recording permission")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.RECORD_AUDIO},
                                        REQUEST_AUDIO_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_AUDIO_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    protected void requestNotificationPermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                    Manifest.permission.POST_NOTIFICATIONS)) {

                new AlertDialog.Builder(this)
                        .setTitle("Permission needed")
                        .setMessage("This app needs to be able to send notifications")
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ActivityCompat.requestPermissions(MainActivity.this,
                                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                        REQUEST_NOTIFICATIONS_PERMISSION_CODE);
                            }
                        })
                        .create()
                        .show();

            } else {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATIONS_PERMISSION_CODE);
            }
        } else {
            // Permission has already been granted
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        switch (requestCode) {
            case REQUEST_AUDIO_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                    initAudioRecorder();
                } else {
                    // Permission denied, things will just be broken.
                    debugLog("Error: Need audio permission");
                }
                return;
            }
            case REQUEST_NOTIFICATIONS_PERMISSION_CODE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted.
                } else {
                    // Permission denied
                    debugLog("Warning: Need notifications permission to be able to send APRS chat message notifications");
                }
                return;
            }
        }
    }

    private void debugLog(String text) {
        Log.d("DEBUG", text);
    }

    private void initAudioRecorder() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermissions();
            return;
        }

        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AUDIO_SAMPLE_RATE, channelConfig,
                audioFormat, minBufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            debugLog("Audio init error");
        }
    }

    private void startRecording() {
        if (audioRecord == null) {
            initAudioRecorder();
        }

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button_on));

        audioRecord.startRecording();
        isRecording = true;

        recordingThread = new Thread(new Runnable() {
            public void run() {
                processAudioStream();
            }
        }, "AudioRecorder Thread");

        recordingThread.start();
    }

    private void processAudioStream() {
        while (isRecording) {
            byte[] audioBuffer = new byte[minBufferSize];
            int bytesRead;
            bytesRead = audioRecord.read(audioBuffer, 0, minBufferSize);

            if (bytesRead > 0) {
                sendAudioToESP32(Arrays.copyOfRange(audioBuffer, 0, bytesRead), false);

                int bytesPerAnimFrame = AUDIO_SAMPLE_RATE / RECORD_ANIM_FPS;
                long audioChunkByteTotal = 0;
                int waitMs = 0;
                for (int i = 0; i < bytesRead; i++) {
                    if (i > 0 && i % bytesPerAnimFrame == 0) {
                        audioChunkByteTotal += audioBuffer[i];
                        updateRecordingVisualization(waitMs, (byte) (audioChunkByteTotal / bytesPerAnimFrame));
                        waitMs += (1.0f / RECORD_ANIM_FPS * 1000);
                        audioChunkByteTotal = 0;
                    } else {
                        audioChunkByteTotal += audioBuffer[i];
                    }
                }
            }
        }
    }

    private void stopRecording() {
        if (audioRecord != null) {
            isRecording = false;
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
            recordingThread = null;
            updateRecordingVisualization(100, SILENT_BYTE);
        }

        ImageButton pttButton = findViewById(R.id.pttButton);
        pttButton.setBackground(getDrawable(R.drawable.ptt_button));
    }

    private void findESP32Device() {
        debugLog("findESP32Device()");

        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();

        for (UsbDevice device : usbDevices.values()) {
            // Check for device's vendor ID and product ID
            if (isESP32Device(device)) {
                esp32Device = device;
                break;
            }
        }

        if (esp32Device == null) {
            debugLog("No ESP32 detected");
            showUSBSnackbar();
        } else {
            debugLog("Found ESP32.");
            setupSerialConnection();
        }
    }

    private void showUSBSnackbar() {
        CharSequence snackbarMsg = "KV4P-HT radio not found, plugged in?";
        usbSnackbar = Snackbar.make(this, findViewById(R.id.mainTopLevelLayout), snackbarMsg, Snackbar.LENGTH_INDEFINITE)
            .setBackgroundTint(Color.rgb(140, 20, 0)).setActionTextColor(Color.WHITE).setTextColor(Color.WHITE);

        // Make the text of the snackbar larger.
        TextView snackbarActionTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        snackbarActionTextView.setTextSize(20);
        TextView snackbarTextView = (TextView) usbSnackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        snackbarTextView.setTextSize(20);

        usbSnackbar.show();
    }

    private boolean isESP32Device(UsbDevice device) {
        debugLog("isESP32Device()");

        int vendorId = device.getVendorId();
        int productId = device.getProductId();
        debugLog("vendorId: " + vendorId + " productId: " + productId + " name: " + device.getDeviceName());
        for (int i = 0; i < ESP32_VENDOR_IDS.length; i++) {
            if ((vendorId == ESP32_VENDOR_IDS[i]) && (productId == ESP32_PRODUCT_IDS[i])) {
                return true;
            }
        }
        return false;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            debugLog("usbReceiver.onReceive()");

            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    setupSerialConnection();
                }
            }
        }
    };

    private void setupSerialConnection() {
        debugLog("setupSerialConnection()");

        // Find all available drivers from attached devices.
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
        if (availableDrivers.isEmpty()) {
            debugLog("Error: no available USB drivers.");
            showUSBSnackbar();
            return;
        }

        // Open a connection to the first available driver.
        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            debugLog("Error: couldn't open USB device.");
            showUSBSnackbar();
            return;
        }

        serialPort = driver.getPorts().get(0); // Most devices have just one port (port 0)
        debugLog("serialPort: " + serialPort);
        try {
            serialPort.open(connection);
            serialPort.setParameters(921600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        } catch (Exception e) {
            debugLog("Error: couldn't open USB serial port.");
            showUSBSnackbar();
            return;
        }

        try { // These settings needed for better data transfer on Adafruit QT Py ESP32-S2
            serialPort.setRTS(true);
            serialPort.setDTR(true);
        } catch (Exception e) {
            // Ignore, may not be supported on all devices.
        }

        usbIoManager = new SerialInputOutputManager(serialPort, new SerialInputOutputManager.Listener() {
            @Override
            public void onNewData(byte[] data) {
                handleESP32Data(data);
            }

            @Override
            public void onRunError(Exception e) {
                debugLog("Error reading from ESP32.");
                connection.close();
                try {
                    serialPort.close();
                } catch (Exception ex) {
                    // Ignore.
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                findESP32Device(); // Attempt to reconnect after the brief pause above.
                return;
            }
        });
        usbIoManager.setWriteBufferSize(90000); // Must be large enough that ESP32 can take its time accepting our bytes without overrun.
        usbIoManager.setReadBufferSize(4096); // Must be much larger than ESP32's send buffer (so we never block it)
        usbIoManager.setReadTimeout(1000); // Must not be 0 (infinite) or it may block on read() until a write() occurs.
        usbIoManager.start();

        debugLog("Connected to ESP32.");

        // After a brief pause (to let it boot), do things with the ESP32 that we were waiting to do.
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initAfterESP32Connected();
                    }
                }, 3000);
    }

    private void initAfterESP32Connected() {
        if (usbSnackbar != null) {
            usbSnackbar.dismiss();
            usbSnackbar = null;
        }

        // Start by prebuffering some audio
        restartAudioPrebuffer();

        // Tell the radio about any settings the user set.
        viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
            @Override
            public void onLoadDataDone() {
                applySettings();
                viewModel.setCallback(null);
            }
        });
        viewModel.loadData();

        // Turn off scanning if it was on (e.g. if radio was unplugged briefly and reconnected)
        setScanning(false);
    }

    public void scanClicked(View view) {
        setScanning(mode != MODE_SCAN); // Toggle scanning on/off
    }

    private void setScanning(boolean scanning, boolean goToRxMode) {
        AppCompatButton scanButton = findViewById(R.id.scanButton);
        if (!scanning) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText("SCAN");
                }
            });

            // If squelch was off before we started scanning, turn it off again
            if (squelch == 0) {
                tuneToMemory(activeMemoryId, squelch);
            }

            if (goToRxMode) {
                mode = MODE_RX;
            }
        } else { // Start scanning
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    scanButton.setText("STOP SCAN");
                }
            });
            mode = MODE_SCAN;
            nextScan();
        }
    }

    private void setScanning(boolean scanning) {
        setScanning(scanning, true);
    }

    private void nextScan() {
        if (mode != MODE_SCAN) {
            return;
        }

        List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
        ChannelMemory memoryToScanNext = null;

        // If we're in simplex, start by scanning to the first memory
        if (activeMemoryId == -1) {
            try {
                memoryToScanNext = channelMemories.get(0);
            } catch (IndexOutOfBoundsException e) {
                return; // No memories to scan.
            }
        }

        if (memoryToScanNext == null) {
            // Find the next memory after the one we last scanned
            for (int i = 0; i < channelMemories.size() - 1; i++) {
                if (channelMemories.get(i).memoryId == activeMemoryId) {
                    memoryToScanNext = channelMemories.get(i + 1);
                    break;
                }
            }
        }

        if (memoryToScanNext == null) {
            // If we hit the end of memories, go back to scanning from the start
            memoryToScanNext = channelMemories.get(0);
        }

        consecutiveSilenceBytes = 0;

        // debugLog("Scanning to: " + memoryToScanNext.name);
        tuneToMemory(memoryToScanNext, squelch > 0 ? squelch : 1); // If user turned off squelch, set it to 1 during scan.
    }

    public void addMemoryClicked(View view) {
        Intent intent = new Intent("com.vagell.kv4pht.ADD_MEMORY_ACTION");
        intent.putExtra("requestCode", REQUEST_ADD_MEMORY);
        intent.putExtra("activeFrequencyStr", activeFrequencyStr);
        intent.putExtra("selectedMemoryGroup", selectedMemoryGroup);

        startActivityForResult(intent, REQUEST_ADD_MEMORY);
    }

    public void groupSelectorClicked(View view) {
        Context themedContext = new ContextThemeWrapper(this, R.style.Custom_PopupMenu);
        PopupMenu groupsMenu = new PopupMenu(themedContext, view);
        groupsMenu.inflate(R.menu.groups_menu);

        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<String> memoryGroups = MainViewModel.appDb.channelMemoryDao().getGroups();
                for (int i = 0; i < memoryGroups.size(); i++) {
                    String groupName = memoryGroups.get(i);
                    if (groupName != null && groupName.trim().length() > 0) {
                        groupsMenu.getMenu().add(memoryGroups.get(i));
                    }
                }

                groupsMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        selectMemoryGroup(item.getTitle().toString());
                        return true;
                    }
                });

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        groupsMenu.show();
                    }
                });
            }
        });
    }

    private void selectMemoryGroup(String groupName) {
        this.selectedMemoryGroup = groupName.equals("All memories") ? null : groupName;
        viewModel.loadData();

        // Add drop-down arrow to end of selected group to suggest it's tappable
        TextView groupSelector = findViewById(R.id.groupSelector);
        groupSelector.setText(groupName + " ▼");

        // Save most recent group selection so we can restore it on app restart
        threadPoolExecutor.execute(new Runnable() {
            @Override
            public void run() {
                AppSetting lastGroupSetting = viewModel.appDb.appSettingDao().getByName("lastGroup");
                if (lastGroupSetting != null) {
                    lastGroupSetting.value = groupName == null ? "" : groupName;
                    viewModel.appDb.appSettingDao().update(lastGroupSetting);
                } else {
                    lastGroupSetting = new AppSetting("lastGroup", "" + groupName == null ? "" : groupName);
                    viewModel.appDb.appSettingDao().insertAll(lastGroupSetting);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQUEST_ADD_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    viewModel.loadData();
                    adapter.notifyDataSetChanged();
                }
                break;
            case REQUEST_EDIT_MEMORY:
                if (resultCode == Activity.RESULT_OK) {
                    // Add an observer to the model so we know when it's done reloading
                    // the edited memory, so we can tune to it.
                    final int editedMemoryId = data.getExtras().getInt("memoryId");
                    viewModel.setCallback(new MainViewModel.MainViewModelCallback() {
                        @Override
                        public void onLoadDataDone() {
                            super.onLoadDataDone();

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    adapter.notifyDataSetChanged();
                                    // Tune to the edited memory to force any changes to be applied (e.g. new tone
                                    // or frequency).
                                    List<ChannelMemory> channelMemories = viewModel.getChannelMemories().getValue();
                                    for (int i = 0; i < channelMemories.size(); i++) {
                                        if (channelMemories.get(i).memoryId == editedMemoryId) {
                                            viewModel.highlightMemory(channelMemories.get(i));
                                            tuneToMemory(channelMemories.get(i), squelch);
                                        }
                                    }
                                    viewModel.setCallback(null);
                                }
                            });
                        }
                    });
                    viewModel.loadData();
                }
                break;
            case REQUEST_SETTINGS:
                // Actually don't need to do anything here, since settings are always re-checked
                // in onResume.
                break;
            default:
                debugLog("Warning: Returned to MainActivity from unexpected request code: " + requestCode);
        }
    }

    public void settingsClicked(View view) {
        endPtt(); // Be safe, just in case we are somehow transmitting when settings is tapped.
        setScanning(false); // Stop scanning when settings brought up, so we don't get in a bad state after.
        Intent intent = new Intent("com.vagell.kv4pht.SETTINGS_ACTION");
        intent.putExtra("requestCode", REQUEST_SETTINGS);
        startActivityForResult(intent, REQUEST_SETTINGS);
    }

    private enum ESP32Command {
        PTT_DOWN((byte) 1),
        PTT_UP((byte) 2),
        TUNE_TO((byte) 3), // paramsStr contains freq, offset, tone details
        FILTERS((byte) 4); // paramStr contains emphasis, highpass, lowpass (each 0/1)

        private byte commandByte;
        ESP32Command(byte commandByte) {
            this.commandByte = commandByte;
        }

        public byte getByte() {
            return commandByte;
        }
    }

    private void sendAudioToESP32(byte[] audioBuffer, boolean dataMode) {
        if (audioBuffer.length <= TX_AUDIO_CHUNK_SIZE) {
            sendBytesToESP32(audioBuffer);
        } else {
            // If the audio is fairly long, we need to send it to ESP32 at the same rate
            // as audio sampling. Otherwise, we'll overwhelm its DAC buffer and some audio will
            // be lost.
            final Handler handler = new Handler(Looper.getMainLooper());
            final float msToSendOneChunk = (float) TX_AUDIO_CHUNK_SIZE / (float) AUDIO_SAMPLE_RATE * 1000f;
            float nextSendDelay = 0f;
            for (int i = 0; i < audioBuffer.length; i += TX_AUDIO_CHUNK_SIZE) {
                final int chunkStart = i;
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND +
                                android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
                        sendBytesToESP32(Arrays.copyOfRange(audioBuffer, chunkStart,
                                Math.min(audioBuffer.length, chunkStart + TX_AUDIO_CHUNK_SIZE)));
                    }
                }, (int) nextSendDelay);

                nextSendDelay += msToSendOneChunk;
            }

            // In data mode, also schedule PTT up after last audio chunk goes out.
            if (dataMode) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(
                                android.os.Process.THREAD_PRIORITY_BACKGROUND +
                                        android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
                        endPtt();
                    }
                }, (int) nextSendDelay);
            }
        }
    }

    private void sendCommandToESP32(ESP32Command command) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        sendBytesToESP32(commandArray);
        debugLog("Sent command: " + command);
    }

    private void sendCommandToESP32(ESP32Command command, String paramsStr) {
        byte[] commandArray = { COMMAND_DELIMITER[0], COMMAND_DELIMITER[1],
                COMMAND_DELIMITER[2], COMMAND_DELIMITER[3], COMMAND_DELIMITER[4], COMMAND_DELIMITER[5],
                COMMAND_DELIMITER[6], COMMAND_DELIMITER[7], command.getByte() };
        byte[] combined = new byte[commandArray.length + paramsStr.length()];
        ByteBuffer buffer = ByteBuffer.wrap(combined);
        buffer.put(commandArray);
        buffer.put(paramsStr.getBytes(StandardCharsets.US_ASCII));
        combined = buffer.array();

        // Write it in a single call so the params are guaranteed (?) to be in receive buffer on mcu.
        // A little concerned there could be a bug here in rare chance that these bytes span receive
        // buffer size on mcu.
        // TODO implement a more robust way (in mcu code) of ensuring params are received by mcu
        sendBytesToESP32(combined);
        debugLog("Sent command: " + command + " params: " + paramsStr);
    }

    private synchronized void sendBytesToESP32(byte[] newBytes) {
        try {
            // usbIoManager.writeAsync(newBytes); // On MCUs like the ESP32 S2 this causes USB failures with concurrent USB rx/tx.
            int bytesWritten = 0;
            int totalBytes = newBytes.length;
            final int MAX_BYTES_PER_USB_WRITE = 128;
            int usbRetries = 0;
            do {
                try {
                    byte[] arrayPart = Arrays.copyOfRange(newBytes, bytesWritten, Math.min(bytesWritten + MAX_BYTES_PER_USB_WRITE, totalBytes));
                    serialPort.write(arrayPart, 200);
                    bytesWritten += MAX_BYTES_PER_USB_WRITE;
                    usbRetries = 0;
                } catch (SerialTimeoutException ste) {
                    // Do nothing, we'll try again momentarily. ESP32's serial buffer may be full.
                    usbRetries++;
                    // debugLog("usbRetries: " + usbRetries);
                }
            } while (bytesWritten < totalBytes && usbRetries < 10);
            // debugLog("Wrote data: " + Arrays.toString(newBytes));
        } catch (Exception e) {
            e.printStackTrace();
            try {
                serialPort.close();
            } catch (Exception ex) {
                // Ignore.
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
            findESP32Device(); // Attempt to reconnect after the brief pause above.
        }
    }

    private void handleESP32Data(byte[] data) {
        try {
            // debugLog("Got bytes from ESP32: " + Arrays.toString(data));
        /* try {
            String dataStr = new String(data, "UTF-8");
            //if (dataStr.length() < 100 && dataStr.length() > 0)
                debugLog("Str data from ESP32: " + dataStr);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        } */
            // debugLog("Num bytes from ESP32: " + data.length);

            if (mode == MODE_RX || mode == MODE_SCAN) {
                if (prebufferComplete && audioTrack != null) {
                    synchronized (audioTrack) {
                        if (afskDemodulator != null) { // Avoid race condition at app start.
                            // Play the audio.
                            audioTrack.write(data, 0, data.length);

                            // Add the audio samples to the AFSK demodulator.
                            float[] audioAsFloats = convertPCM8ToFloatArray(data);
                            afskDemodulator.addSamples(audioAsFloats, audioAsFloats.length);
                        }

                        if (audioTrack != null && audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                            audioTrack.play();
                        }
                    }
                } else {
                    for (int i = 0; i < data.length; i++) {
                        // Prebuffer the incoming audio data so AudioTrack doesn't run out of audio to play
                        // while we're waiting for more bytes.
                        rxBytesPrebuffer[rxPrebufferIdx++] = data[i];
                        if (rxPrebufferIdx == PRE_BUFFER_SIZE) {
                            prebufferComplete = true;
                            // debugLog("Rx prebuffer full, writing to audioTrack.");
                            if (audioTrack != null) {
                                if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack.play();
                                }
                                synchronized (audioTrack) {
                                    audioTrack.write(rxBytesPrebuffer, 0, PRE_BUFFER_SIZE);
                                }
                            }

                            rxPrebufferIdx = 0;
                            break; // Might drop a few audio bytes from data[], should be very minimal
                        }
                    }
                }
            }

            if (mode == MODE_SCAN) {
                // Track consecutive silent bytes, so if we're scanning we can move to next after a while.
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == SILENT_BYTE) {
                        consecutiveSilenceBytes++;
                        // debugLog("consecutiveSilenceBytes: " + consecutiveSilenceBytes);
                        checkScanDueToSilence();
                    } else {
                        consecutiveSilenceBytes = 0;
                    }
                }
            } else if (mode == MODE_TX) {
                // Print any data we get in MODE_TX (we're not expecting any, this is either leftover rx bytes or debug info).
            /* try {
                String dataStr = new String(data, "UTF-8");
                debugLog("Unexpected data from ESP32 during MODE_TX: " + dataStr);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            } */
            }
        } catch (Exception e) {
            // Eat the exception. Whenever MainActivity is paused, this method may be called async
            // one or more times, and break in unexpected ways.
            // TODO fix this properly by extracting all the data and audio logic to a Service.
        }
    }

    private float[] convertPCM8ToFloatArray(byte[] pcm8Data) {
        // Create a float array of the same length as the input byte array
        float[] floatData = new float[pcm8Data.length];

        // Iterate through the byte array and convert each sample
        for (int i = 0; i < pcm8Data.length; i++) {
            // Convert unsigned 8-bit PCM to signed 8-bit value
            int signedValue = (pcm8Data[i] & 0xFF) - 128;

            // Normalize the signed 8-bit value to the range [-1.0, 1.0]
            floatData[i] = signedValue / 128.0f;
        }

        return floatData;
    }

    private byte convertFloatToPCM8(float floatValue) {
        // Clamp the float value to the range [-1.0, 1.0] to prevent overflow
        float clampedValue = Math.max(-1.0f, Math.min(1.0f, floatValue));

        // Convert float value in range [-1.0, 1.0] to signed 8-bit value
        int signedValue = Math.round(clampedValue * 128);

        // Convert signed 8-bit value to unsigned 8-bit PCM (range 0 to 255)
        return (byte) (signedValue + 128);
    }
}