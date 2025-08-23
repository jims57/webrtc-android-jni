package cn.watchfun.android_use_cpp_demo1;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import cn.watchfun.aec3.WqAecProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.os.Environment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import android.content.Context;

// ASR集成导入
import okhttp3.*;
import okio.ByteString;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // 音频参数 - AEC3要求48kHz采样率
    private static final int SAMPLE_RATE = 48000;  // WebRTC AEC3要求48kHz
    private static final int CHANNELS = 1;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, 
            AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT);
    
    // AEC3特定参数
    private static final int AEC3_FRAME_SIZE = 480;  // 48kHz下10ms
    // Auto-adaptive mechanism will handle delay automatically
    
    // UI控制变量 - removed manual delay control

    private AudioTrack audioTrack;
    private AudioRecord audioRecord;
    private HandlerThread audioThread;
    private Handler audioHandler;
    private boolean isRecording = false;
    private boolean isPlayingTTS = false;
    
    // 来自AAR的AEC3处理器实例
    private WqAecProcessor aec3Processor = null;
    
    // TTS播放的PCM块
    private List<byte[]> pcmChunks = new ArrayList<>();
    
    // WAV保存的清洁音频帧
    private List<float[]> cleanAudioFrames = new ArrayList<>();
    
    // AEC3的帧级同步
    private final ConcurrentLinkedQueue<short[]> ttsFrameBuffer = new ConcurrentLinkedQueue<>();
    private volatile boolean enableAecSync = false;

    // AEC3故障排除的音频诊断
    private volatile long totalTtsFrames = 0;
    private volatile long totalMicFrames = 0;
    private volatile long missedTtsFrames = 0;
    private volatile long avgTtsEnergy = 0;
    private volatile long avgMicEnergy = 0;
    private volatile int currentBufferSize = 0;
    private long lastDiagnosticTime = 0;
    
    // WAV播放的MediaPlayer
    private MediaPlayer mediaPlayer = null;
    
    // 保存的WAV文件路径
    private String lastCleanWavPath = null;

    private volatile long globalFrameCount = 0;

    // ASR集成字段
    private static final String ASR_APP_ID = "82LmEHEReN1u3I3c";
    private static final String ASR_APP_SECRET = "nMPnDGKgvm4gcfpVlRM4hzC48k85IzfX";
    private static final String ASR_WS_URL = "wss://vt.watchfun.cn/ws/v1/translation";
    private static final int ASR_SAMPLE_RATE = 44100; // ASR API的目标采样率
    private static final int ASR_TRANSLATION_MODE = 1; // 转录模式
    private static final String ASR_SOURCE_LANGUAGE = "zh-CN"; // 中文转录
    
    private OkHttpClient httpClient;
    private WebSocket asrWebSocket;
    private volatile boolean isAsrProcessing = false;
    
    // 🛡️ 线程安全ASR结果管理
    // 防止来自并发WebSocket响应更新的UI崩溃
    private final Object asrTextLock = new Object(); // 同步锁
    private StringBuilder accumulatedAsrText = new StringBuilder(); // 最终累积结果
    private StringBuilder tempAsrBuffer = new StringBuilder(); // 部分结果的临时缓冲区
    private String lastRecognizedSegment = ""; // 跟踪最后段落以避免重复
    private volatile String lastAsrStatus = ""; // 缓存最后状态以避免冗余UI更新
    private volatile boolean asrSessionComplete = false; // 跟踪会话完成
    private Handler uiUpdateHandler; // 批量UI更新的处理器

    // 🛡️ 新的崩溃修复变量
    private volatile boolean isAsrTerminating = false; // 在终止期间防止新操作的标志
    private final Object asrWebSocketLock = new Object(); // WebSocket操作锁
    private volatile boolean isActivityDestroying = false; // Activity生命周期标志

    // 🛡️ 内存监控和OOM预防
    private volatile long maxMemoryUsed = 0;
    private volatile long currentMemoryUsed = 0;
    private volatile int totalProcessedFrames = 0;
    private volatile boolean forceStopProcessing = false; // 紧急停止标志
    
    // 🛡️ 时序同步监控
    private volatile long lastTtsTimestamp = 0;
    private volatile long lastMicTimestamp = 0;
    private volatile long timingSyncDrift = 0; // 跟踪时序漂移
    private volatile int consecutiveBadErle = 0; // 跟踪差的ERLE性能
    private volatile boolean adaptiveDelayEnabled = true; // 启用/禁用自适应延迟
    private volatile int currentStreamDelay = 5; // 默认流延迟值(ms)
    
    // 为最优ERLE性能移除调试日志

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        
        checkPermissions();
        initializeAudio();
        loadPCMChunks();
        initializeAsrClient(); // 初始化ASR客户端
        
        // 初始化线程安全UI更新处理器
        uiUpdateHandler = new Handler(getMainLooper());
        
        setupUI();
    }
    
    private void checkPermissions() {
        List<String> permissions = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, 
                    permissions.toArray(new String[0]), 
                    PERMISSION_REQUEST_CODE);
        }
    }
    
    private void initializeAudio() {
        // 初始化音频线程
        audioThread = new HandlerThread("AudioProcessing");
        audioThread.start();
        audioHandler = new Handler(audioThread.getLooper());
        
        // 🎵 AudioTrack配置
        int trackBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, 
                AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
        
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AUDIO_FORMAT)
                        .build())
                .setBufferSizeInBytes(trackBufferSize * 2) // 与您工作项目相同的2倍缓冲区
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // 低延迟模式
                .build();
        
        Log.i(TAG, "音频初始化: SAMPLE_RATE=" + SAMPLE_RATE + ", BUFFER_SIZE=" + BUFFER_SIZE);
    }
    
    private void loadPCMChunks() {
        new Thread(() -> {
            try {
                AssetManager assetManager = getAssets();
                String[] chunkFiles = assetManager.list("aec3_test_chunks");
                
                if (chunkFiles != null) {
                    // 排序文件以确保正确顺序 (aec3_chunk_0000.pcm, aec3_chunk_0001.pcm, 等等)
                    java.util.Arrays.sort(chunkFiles, (a, b) -> {
                        try {
                            // 从aec3_chunk_XXXX.pcm格式提取数字
                            if (a.startsWith("aec3_chunk_") && b.startsWith("aec3_chunk_")) {
                                String numA = a.replace("aec3_chunk_", "").replace(".pcm", "");
                                String numB = b.replace("aec3_chunk_", "").replace(".pcm", "");
                                int intA = Integer.parseInt(numA);
                                int intB = Integer.parseInt(numB);
                                return Integer.compare(intA, intB);
                            }
                            return a.compareTo(b);
                        } catch (NumberFormatException e) {
                            return a.compareTo(b);
                        }
                    });
                    
                    for (String fileName : chunkFiles) {
                        if (fileName.endsWith(".pcm")) {
                            InputStream inputStream = assetManager.open("aec3_test_chunks/" + fileName);
                            byte[] chunkData = new byte[inputStream.available()];
                            inputStream.read(chunkData);
                            inputStream.close();
                            
                            pcmChunks.add(chunkData);
                            Log.d(TAG, "已加载PCM块: " + fileName + ", 大小: " + chunkData.length);
                        }
                    }
                }
                
                runOnUiThread(() -> {
                    Log.i(TAG, "总共加载的PCM块: " + pcmChunks.size());
                });
                
            } catch (IOException e) {
                Log.e(TAG, "加载PCM块错误", e);
            }
        }).start();
    }
    
    private void setupUI() {        
        // 初始化AEC3按钮 
        Button buttonInitializeAEC3 = findViewById(R.id.buttonInitializeAEC3);
        buttonInitializeAEC3.setOnClickListener(v -> {
            Log.i(TAG, "初始化AEC3按钮被点击");
            updateStatus("正在初始化AEC3处理器...");
            initializeAEC3Processor();
        });

        // 播放PCM按钮 
        Button buttonPlayPCM = findViewById(R.id.buttonPlayPCM);
        buttonPlayPCM.setOnClickListener(v -> {
            Log.i(TAG, "播放PCM按钮被点击");
            updateStatus("开始播放TTS PCM音频...");
            if (!isPlayingTTS) {
                startTTSPlayback();
            } else {
                stopTTSPlayback();
            }
        });
        
        // AEC录音按钮 - 处理麦克风录音并进行回声消除
        Button buttonAECRecording = findViewById(R.id.buttonAECRecording);
        buttonAECRecording.setOnClickListener(v -> {
            Log.i(TAG, "AEC录音按钮被点击");
            if (aec3Processor == null) {
                updateStatus("错误: 请先初始化AEC3处理器");
                return;
            }
            if (!isRecording) {
                updateStatus("开始AEC3录音处理 - 移除TTS回声，保留人声...");
                startAECRecordingOnly();
            } else {
                stopRecording();
            }
        });
        
        // Auto-adaptive delay - no manual controls needed
        TextView textViewDelayValue = findViewById(R.id.textViewDelayValue);
        if (textViewDelayValue != null) {
            textViewDelayValue.setText("Auto");
        }
        
        // WAV播放按钮 - only clean audio playback needed
        Button buttonPlayClean = findViewById(R.id.buttonPlayClean);
        buttonPlayClean.setOnClickListener(v -> {
            // Thread-safe button click handling
            try {
                Log.d(TAG, "播放清洁录音按钮点击");
                playWavFile(lastCleanWavPath, "清洁录音");
            } catch (Exception e) {
                Log.e(TAG, "播放清洁录音按钮处理失败", e);
                updateStatus("播放失败: " + e.getMessage());
            }
        });
        
        // Auto-adaptive mechanism handles all parameters automatically
        // No manual parameter controls needed
        
        updateStatus("就绪 - 请选择测试模式");
    }
    
    // Debug control method removed for optimal ERLE performance
    
    private void updateStatus(String status) {
        runOnUiThread(() -> {
            TextView textViewStatus = findViewById(R.id.textViewStatus);
            textViewStatus.setText("状态: " + status);
        });
    }
    

    
    /**
     * Setup enhanced ERLE optimization controls
     */
    private void setupEnhancedControls() {
        Button buttonAutoOptimize = findViewById(R.id.buttonAutoOptimize);
        android.widget.Switch switchTimingSync = findViewById(R.id.switchTimingSync);
        TextView textViewErleTarget = findViewById(R.id.textViewErleTarget);
        
        // Auto-optimize delay button
        buttonAutoOptimize.setOnClickListener(v -> {
                    if (aec3Processor != null) {
                Log.i(TAG, "手动触发Enhanced自动延迟优化");
                updateStatus("执行Enhanced自动延迟优化...");
                
                boolean result = aec3Processor.autoOptimizeDelay();
                if (result) {
                    Log.i(TAG, "Enhanced自动优化成功");
                    updateStatus("Enhanced自动优化完成");
                    
                    // Get updated metrics and display
                    WqAecProcessor.EnhancedAecMetrics metrics = aec3Processor.getEnhancedMetrics();
                    if (metrics != null) {
                        updateEnhancedMetrics(metrics);
                        
                        // Update target display
                        String targetText = String.format("目标: ERLE > 15dB (当前: %.1fdB %s)", 
                                metrics.echoReturnLossEnhancement, metrics.getErleQuality());
                        textViewErleTarget.setText(targetText);
                        
                        // Auto-adaptive delay display
                        TextView textViewDelayValue = findViewById(R.id.textViewDelayValue);
                        if (textViewDelayValue != null) {
                            textViewDelayValue.setText("Auto: " + metrics.optimalDelayMs + "ms");
                        }
                    }
                } else {
                    Log.w(TAG, "🔧 Enhanced自动优化失败");
                    updateStatus("Enhanced自动优化失败");
                }
            } else {
                updateStatus("错误: AEC3处理器未初始化");
            }
        });
        
        // Timing synchronization switch
        switchTimingSync.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (aec3Processor != null) {
                boolean result = aec3Processor.enableTimingSync(isChecked);
                if (result) {
                    Log.i(TAG, String.format("时序同步已%s", isChecked ? "启用" : "禁用"));
                    updateStatus(String.format("时序同步已%s", isChecked ? "启用" : "禁用"));
                } else {
                    Log.w(TAG, "🔧 时序同步设置失败");
                    updateStatus("时序同步设置失败");
                    // Revert switch state on failure
                    switchTimingSync.setChecked(!isChecked);
                }
            } else {
                updateStatus("错误: AEC3处理器未初始化");
                switchTimingSync.setChecked(false);
            }
        });
        
        // Initialize target display
        textViewErleTarget.setText("目标: ERLE > 15dB (当前: -- dB)");
    }
    
    /**
     * Setup official AEC3 parameter controls for production-grade tuning
     */
    private void setupOfficialAEC3Controls() {
        SeekBar seekBarInitialStateSeconds = findViewById(R.id.seekBarInitialStateSeconds);
        SeekBar seekBarMaxDecFactorLF = findViewById(R.id.seekBarMaxDecFactorLF);
        SeekBar seekBarMaxIncFactor = findViewById(R.id.seekBarMaxIncFactor);
        SeekBar seekBarEnrThreshold = findViewById(R.id.seekBarEnrThreshold);
        Switch switchConservativeInitialPhase = findViewById(R.id.switchConservativeInitialPhase);
        
        TextView textInitialStateValue = findViewById(R.id.textInitialStateValue);
        TextView textMaxDecFactorLFValue = findViewById(R.id.textMaxDecFactorLFValue);
        TextView textMaxIncFactorValue = findViewById(R.id.textMaxIncFactorValue);
        TextView textEnrThresholdValue = findViewById(R.id.textEnrThresholdValue);
        
        // Initial State Seconds (OFFICIAL range 0.0-100.0, EXPANDED 0.0-20.0)
        seekBarInitialStateSeconds.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.0f + (progress / 100.0f) * 20.0f; // 0.0-20.0 EXPANDED range (OFFICIAL: 0.0-100.0)
                textInitialStateValue.setText(String.format("%.1f", value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setInitialStateSeconds(value);
                    updateStatus(String.format("🎛️ 初始状态时长: %.1f秒 (影响收敛速度)", value));
                    Log.i(TAG, String.format("🎛️ Initial state seconds: %.1f", value));
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        // Max Dec Factor LF (OFFICIAL range 0.0-100.0, EXPANDED 0.0-50.0 - 回声抑制强度)
        seekBarMaxDecFactorLF.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.0f + (progress / 100.0f) * 50.0f; // 0.0-50.0 EXPANDED range (OFFICIAL: 0.0-100.0)
                textMaxDecFactorLFValue.setText(String.format("%.1f", value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setMaxDecFactorLF(value);
                    updateStatus(String.format("🎛️ 低频抑制系数: %.1f (值越小语音越清晰)", value));
                    Log.i(TAG, String.format("🎛️ Max dec factor LF: %.1f", value));
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        // Max Inc Factor (OFFICIAL range 0.0-100.0, EXPANDED 0.0-20.0 - 语音恢复速度)
        seekBarMaxIncFactor.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.0f + (progress / 100.0f) * 20.0f; // 0.0-20.0 EXPANDED range (OFFICIAL: 0.0-100.0)
                textMaxIncFactorValue.setText(String.format("%.1f", value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setMaxIncFactor(value);
                    updateStatus(String.format("增益恢复系数: %.1f (值越大恢复越快)", value));
                    Log.i(TAG, String.format("Max inc factor: %.1f", value));
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        // ENR Threshold (OFFICIAL range 0.0-1000000.0, EXPANDED 0.0-5.0 - 语音检测阈值)
        seekBarEnrThreshold.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                float value = 0.0f + (progress / 100.0f) * 5.0f; // 0.0-5.0 EXPANDED range (OFFICIAL: 0.0-1000000.0)
                textEnrThresholdValue.setText(String.format("%.2f", value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setEnrThreshold(value);
                    updateStatus(String.format("🎛️ 语音检测阈值: %.2f (值越小检测越敏感)", value));
                    Log.i(TAG, String.format("🎛️ ENR threshold: %.2f", value));
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        
        // Conservative Initial Phase Switch (default false)
        switchConservativeInitialPhase.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (aec3Processor != null) {
                aec3Processor.setConservativeInitialPhase(isChecked);
                updateStatus(String.format("🎛️ 保守初始阶段: %s", isChecked ? "启用" : "禁用"));
                Log.i(TAG, String.format("🎛️ Conservative initial phase: %s", isChecked));
            }
        });
        
        // Set initial default values (all use AEC3 defaults)
        updateOfficialAEC3SeekBars();
    }
    
    /**
     * Update official AEC3 parameter seek bars with default values
     */
    private void updateOfficialAEC3SeekBars() {
        // Set default values for all official AEC3 parameters
        SeekBar seekBarInitialStateSeconds = findViewById(R.id.seekBarInitialStateSeconds);
        SeekBar seekBarMaxDecFactorLF = findViewById(R.id.seekBarMaxDecFactorLF);
        SeekBar seekBarMaxIncFactor = findViewById(R.id.seekBarMaxIncFactor);
        SeekBar seekBarEnrThreshold = findViewById(R.id.seekBarEnrThreshold);
        Switch switchConservativeInitialPhase = findViewById(R.id.switchConservativeInitialPhase);
        
        TextView textInitialStateValue = findViewById(R.id.textInitialStateValue);
        TextView textMaxDecFactorLFValue = findViewById(R.id.textMaxDecFactorLFValue);
        TextView textMaxIncFactorValue = findViewById(R.id.textMaxIncFactorValue);
        TextView textEnrThresholdValue = findViewById(R.id.textEnrThresholdValue);
        
        // Set optimized default values based on OFFICIAL WebRTC AEC3 validation limits 
        // RANGES FROM OFFICIAL echo_canceller3_config.cc VALIDATION FUNCTION
        
        // BALANCED DEFAULTS FOR UNIVERSAL CONVERGENCE + GOOD ERLE 
        // Reverted to safer values to ensure all devices converge (Samsung fix)
        
        // Initial State Seconds: optimized 2.5 for stable convergence, FULL range 0.0-20.0 (OFFICIAL: 0.0-100.0)
        int initialStateProgress = (int) ((2.5f - 0.0f) / 20.0f * 100); // 12% (WebRTC default for stability)
        seekBarInitialStateSeconds.setProgress(initialStateProgress);
        textInitialStateValue.setText("2.5");
        
        // Max Dec Factor LF (回声抑制强度): optimized 4.0 for stable convergence, FULL range 0.0-50.0 (OFFICIAL: 0.0-100.0)
        // Moderate = good echo removal without divergence risk
        int maxDecProgress = (int) ((4.0f - 0.0f) / 50.0f * 100); // 8% (stable echo removal)
        seekBarMaxDecFactorLF.setProgress(maxDecProgress);
        textMaxDecFactorLFValue.setText("4.0");
        
        // Max Inc Factor (语音恢复速度): optimized 3.5 for balanced recovery, FULL range 0.0-20.0 (OFFICIAL: 0.0-100.0)  
        // Moderate = good voice recovery without instability
        int maxIncProgress = (int) ((3.5f - 0.0f) / 20.0f * 100); // 17% (balanced voice recovery)
        seekBarMaxIncFactor.setProgress(maxIncProgress);
        textMaxIncFactorValue.setText("3.5");
        
        // ENR Threshold (语音检测阈值): optimized 0.20 for sensitive voice detection, FULL range 0.0-5.0 (OFFICIAL: 0.0-1000000.0)
        // Lower = more sensitive voice detection for better voice preservation
        int enrProgress = (int) ((0.20f - 0.0f) / 5.0f * 100); // 4% (sensitive voice detection)
        seekBarEnrThreshold.setProgress(enrProgress);
        textEnrThresholdValue.setText("0.20");
        
        // Conservative Initial Phase: optimized false (more aggressive)
        switchConservativeInitialPhase.setChecked(false);
        
        // Production ERLE settings now controlled via UI parameters
    }
    
    /**
     * Setup ERLE adjustment parameter controls for mobile developers 
     * Based on adjust-ERLE-result.md - expose fine-tuning parameters
     */
    private void setupErleAdjustmentControls() {
        SeekBar seekBarFilterLength = findViewById(R.id.seekBarFilterLength);
        SeekBar seekBarDelayDownSampling = findViewById(R.id.seekBarDelayDownSampling);
        SeekBar seekBarDelayNumFilters = findViewById(R.id.seekBarDelayNumFilters);
        
        TextView textFilterLengthValue = findViewById(R.id.textFilterLengthValue);
        TextView textDelayDownSamplingValue = findViewById(R.id.textDelayDownSamplingValue);
        TextView textDelayNumFiltersValue = findViewById(R.id.textDelayNumFiltersValue);
        
        // Filter Length Blocks (1-100, default=25 from adjust-ERLE-result.md)
        seekBarFilterLength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 1 + progress; // 1-100 range
                textFilterLengthValue.setText(String.valueOf(value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setFilterLengthBlocks(value);
                    updateStatus(String.format("滤波器长度: %d块 (影响回声学习)", value));
                    Log.i(TAG, String.format("Filter length blocks: %d", value));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Delay Down Sampling Factor (1-8, default=2 from adjust-ERLE-result.md)
        seekBarDelayDownSampling.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 1 + progress; // 1-8 range
                textDelayDownSamplingValue.setText(String.valueOf(value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setDelayDownSamplingFactor(value);
                    updateStatus(String.format("延迟采样系数: %d (影响精度)", value));
                    Log.i(TAG, String.format("Delay down sampling factor: %d", value));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Delay Num Filters (1-32, default=16 from adjust-ERLE-result.md)
        seekBarDelayNumFilters.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = 1 + progress; // 1-32 range
                textDelayNumFiltersValue.setText(String.valueOf(value));
                if (fromUser && aec3Processor != null) {
                    aec3Processor.setDelayNumFilters(value);
                    updateStatus(String.format("延迟滤波器数: %d (影响检测)", value));
                    Log.i(TAG, String.format("Delay num filters: %d", value));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        
        // Set initial default values from adjust-ERLE-result.md
        seekBarFilterLength.setProgress(24);      // 25 blocks
        seekBarDelayDownSampling.setProgress(1);  // 2 factor
        seekBarDelayNumFilters.setProgress(15);   // 16 filters
        
        textFilterLengthValue.setText("25");
        textDelayDownSamplingValue.setText("2");
        textDelayNumFiltersValue.setText("16");
    }
    
    private void setSeekBarValue(android.widget.SeekBar seekBar, float value, float min, float range) {
        int progress = (int) ((value - min) / range * 100);
        seekBar.setProgress(Math.max(0, Math.min(100, progress)));
    }
    
    private void updateMetrics(float erle, int delay) {
        runOnUiThread(() -> {
            TextView textViewMetrics = findViewById(R.id.textViewMetrics);
            String metricsText = String.format("ERLE: %.1f dB | 延迟: %d ms", erle, delay);
            textViewMetrics.setText(metricsText);
            
            // 根据ERLE值设置颜色提示
            if (erle > 15.0f) {
                textViewMetrics.setTextColor(0xFF4CAF50);  // 绿色 - 良好
            } else if (erle > 10.0f) {
                textViewMetrics.setTextColor(0xFFFF9800);  // 橙色 - 一般
            } else {
                textViewMetrics.setTextColor(0xFFF44336);  // 红色 - 需要调整
            }
        });
    }
    
    // Enhanced metrics display with comprehensive information 
    private void updateEnhancedMetrics(WqAecProcessor.EnhancedAecMetrics metrics) {
        runOnUiThread(() -> {
            TextView textViewMetrics = findViewById(R.id.textViewMetrics);
            String metricsText = String.format("ERLE: %.1f dB (%s) | 延迟: %d ms | 同步: %s", 
                    metrics.echoReturnLossEnhancement, 
                    metrics.getErleQuality(),
                    metrics.delayMs,
                    metrics.isFrameSynchronized() ? "✓" : "✗");
            textViewMetrics.setText(metricsText);
            
            // Enhanced color coding based on ERLE quality
            if (metrics.echoReturnLossEnhancement > 15.0f) {
                textViewMetrics.setTextColor(0xFF4CAF50);  // 绿色 - 卓越 (目标达成)
            } else if (metrics.echoReturnLossEnhancement > 10.0f) {
                textViewMetrics.setTextColor(0xFF2196F3);  // 蓝色 - 良好 (接近目标)
            } else if (metrics.echoReturnLossEnhancement > 5.0f) {
                textViewMetrics.setTextColor(0xFFFF9800);  // 橙色 - 一般 (需要优化)
            } else {
                textViewMetrics.setTextColor(0xFFF44336);  // 红色 - 差 (急需优化)
            }
        });
    }
    
    // Original recording method removed - not needed with separate PCM control
    
    // Separate AEC3 initialization method 
    private void initializeAEC3Processor() {
        Log.i(TAG, "开始初始化AEC3处理器 - 独立初始化流程");
        
        // Check audio permission at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "录音权限未授予");
            updateStatus("错误: 需要录音权限");
            ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.RECORD_AUDIO}, 
                    PERMISSION_REQUEST_CODE);
            return;
        }
        
        try {
            // Initialize AEC3 processor with detailed logging
            Log.i(TAG, "开始初始化AEC3处理器...");
            long initStartTime = System.currentTimeMillis();
            
            // Destroy existing processor if any
            if (aec3Processor != null) {
                aec3Processor.destroy();
                aec3Processor = null;
                Log.i(TAG, "已销毁现有AEC3处理器");
            }
            
            aec3Processor = new WqAecProcessor();
            boolean initResult = aec3Processor.initialize();
            
            long initDuration = System.currentTimeMillis() - initStartTime;
            
            if (initResult) {
                Log.i(TAG, String.format("AEC3处理器初始化成功, 耗时: %dms", initDuration));
                
                // Enhanced AEC3 functionality testing 
                WqAecProcessor.EnhancedAecMetrics initialMetrics = aec3Processor.getEnhancedMetrics();
                if (initialMetrics != null) {
                    Log.i(TAG, String.format("Enhanced AEC3初始指标 - ERL: %.1fdB, ERLE: %.1fdB, 延迟: %dms, 质量: %s",
                            initialMetrics.echoReturnLoss, 
                            initialMetrics.echoReturnLossEnhancement,
                            initialMetrics.delayMs,
                            initialMetrics.getErleQuality()));
                    Log.i(TAG, String.format("帧同步状态: %s, 渲染帧: %d, 捕获帧: %d, 优化延迟: %dms",
                            initialMetrics.isFrameSynchronized() ? "已同步" : "未同步",
                            initialMetrics.renderFrames,
                            initialMetrics.captureFrames,
                            initialMetrics.optimalDelayMs));
                } else {
                    Log.w(TAG, "无法获取Enhanced AEC3初始指标");
                }
                
                // Enable timing synchronization for maximum ERLE performance
                boolean timingSyncEnabled = aec3Processor.enableTimingSync(true);
                Log.i(TAG, String.format("精确时序同步: %s", timingSyncEnabled ? "已启用" : "启用失败"));
                
                // Set initial stream delay
                aec3Processor.setStreamDelay(currentStreamDelay);
                Log.i(TAG, String.format("AEC3处理器创建成功，延迟设置: %dms", currentStreamDelay));
                
                // SET ERLE ADJUSTMENT DEFAULTS FROM adjust-ERLE-result.md 
                aec3Processor.setFilterLengthBlocks(25);
                aec3Processor.setFilterLeakageConverged(0.000005f);
                aec3Processor.setFilterLeakageDiverged(0.005f);
                aec3Processor.setDelayDownSamplingFactor(2);
                aec3Processor.setDelayNumFilters(16);
                aec3Processor.setDelayEstimateSmoothing(0.98f);
                Log.i(TAG, "ERLE调优参数已设置为优化默认值 (来自adjust-ERLE-result.md)");
                
                // Enable buttons after successful initialization
        runOnUiThread(() -> {
                    Button buttonPlayPCM = findViewById(R.id.buttonPlayPCM);
                    Button buttonAECRecording = findViewById(R.id.buttonAECRecording);
                    buttonPlayPCM.setEnabled(true);
                    buttonAECRecording.setEnabled(true);
                    updateStatus("AEC3初始化完成 - 可以开始播放PCM和录音");
                });
                
            } else {
                Log.e(TAG, String.format("AEC3处理器初始化失败, 耗时: %dms", initDuration));
                updateStatus("错误: AEC3初始化失败");
                return;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "创建AEC3处理器失败", e);
            updateStatus("错误: " + e.getMessage());
            return;
        }
    }
    
    private void startAECRecording() {
        Log.i(TAG, "开始AEC录音测试 - 使用改进的WebRTC AEC3");
        
        // 🛡️ CRASH FIX 1: Prevent concurrent ASR sessions
        synchronized (asrWebSocketLock) {
            if (isAsrProcessing) {
                Log.w(TAG, "ASR正在处理中，跳过新的录音请求");
                updateStatus("错误: ASR正在处理中，请等待完成");
                return;
            }
            isAsrTerminating = false; // Reset termination flag
        }
        
        // 🛡️ Thread-safe ASR state reset
        synchronized (asrTextLock) {
            accumulatedAsrText.setLength(0);
            tempAsrBuffer.setLength(0);
            lastRecognizedSegment = "";
            asrSessionComplete = false;
            lastAsrStatus = "";
        }
        updateAsrStatus("待机");
        updateAsrResult("等待录音完成后进行语音识别...");
        
        // Check audio permission at runtime
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "录音权限未授予");
            updateStatus("错误: 需要录音权限");
            ActivityCompat.requestPermissions(this, 
                    new String[]{Manifest.permission.RECORD_AUDIO}, 
                    PERMISSION_REQUEST_CODE);
            return;
        }
        
        try {
            // Re-initialize AudioRecord for AEC recording
            if (audioRecord != null) {
                audioRecord.release();
            }
            
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, BUFFER_SIZE * 2);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord重新初始化失败，状态: " + audioRecord.getState());
                updateStatus("错误: 麦克风初始化失败");
                return;
            }
            
            Log.i(TAG, "AudioRecord重新初始化成功");
            
            // Initialize AEC3 processor with detailed logging
            Log.i(TAG, "开始初始化AEC3处理器...");
            long initStartTime = System.currentTimeMillis();
            
            aec3Processor = new WqAecProcessor();
            boolean initResult = aec3Processor.initialize();
            
            long initDuration = System.currentTimeMillis() - initStartTime;
            
            if (initResult) {
                Log.i(TAG, String.format("AEC3处理器初始化成功, 耗时: %dms", initDuration));
                
                // Enhanced AEC3 functionality testing 
                WqAecProcessor.EnhancedAecMetrics initialMetrics = aec3Processor.getEnhancedMetrics();
                if (initialMetrics != null) {
                    Log.i(TAG, String.format("Enhanced AEC3初始指标 - ERL: %.1fdB, ERLE: %.1fdB, 延迟: %dms, 质量: %s",
                            initialMetrics.echoReturnLoss, 
                            initialMetrics.echoReturnLossEnhancement,
                            initialMetrics.delayMs,
                            initialMetrics.getErleQuality()));
                    Log.i(TAG, String.format("帧同步状态: %s, 渲染帧: %d, 捕获帧: %d, 优化延迟: %dms",
                            initialMetrics.isFrameSynchronized() ? "已同步" : "未同步",
                            initialMetrics.renderFrames,
                            initialMetrics.captureFrames,
                            initialMetrics.optimalDelayMs));
                } else {
                    Log.w(TAG, "无法获取Enhanced AEC3初始指标");
                }
                
                // Enable timing synchronization for maximum ERLE performance
                boolean timingSyncEnabled = aec3Processor.enableTimingSync(true);
                Log.i(TAG, String.format("精确时序同步: %s", timingSyncEnabled ? "已启用" : "启用失败"));
                
                // Set initial stream delay
                aec3Processor.setStreamDelay(currentStreamDelay);
                Log.i(TAG, String.format("AEC3处理器创建成功，延迟设置: %dms", currentStreamDelay));
                
                // Log PCM chunk information for analysis
                Log.i(TAG, String.format("PCM chunks信息: 总数=%d", pcmChunks.size()));
                for (int i = 0; i < Math.min(pcmChunks.size(), 5); i++) {
                    byte[] chunk = pcmChunks.get(i);
                    float durationMs = (chunk.length / (float)(SAMPLE_RATE * 2)) * 1000;
                    Log.d(TAG, String.format("Chunk %d: %d bytes, %.1fms, %d samples", 
                            i, chunk.length, durationMs, chunk.length / 2));
                }
                
            } else {
                Log.e(TAG, String.format("AEC3处理器初始化失败, 耗时: %dms", initDuration));
                updateStatus("错误: AEC3初始化失败");
                return;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "创建AEC3处理器失败", e);
            updateStatus("错误: " + e.getMessage());
            return;
        }
        
        isRecording = true;
        
        // Clear previous audio frames
        cleanAudioFrames.clear();
        
        // Enable synchronized AEC processing
        ttsFrameBuffer.clear();
        enableAecSync = true;

        // Reset global frame counter
        globalFrameCount = 0;
        Log.i(TAG, "重置全局帧计数器");

        // Reset diagnostics
        totalTtsFrames = 0;
        totalMicFrames = 0;
        missedTtsFrames = 0;
        avgTtsEnergy = 0;
        avgMicEnergy = 0;
        currentBufferSize = 0;
        lastDiagnosticTime = System.currentTimeMillis();

        Log.i(TAG, "启用帧级同步AEC处理模式 + 完整诊断监控");
        
        // Update button text
        runOnUiThread(() -> {
            Button button = findViewById(R.id.buttonAECRecording);
            button.setText("停止AEC录音");
        });
        
        audioHandler.post(() -> {
            // Start TTS playback and microphone recording with AEC
            startTTSPlayback();
            startMicrophoneRecording(true); // true = use AEC
        });
    }

    // AEC recording with optimized timing sync
    private void startAECRecordingOnly() {
        if (aec3Processor == null) {
            updateStatus("错误: AEC3处理器未初始化");
            return;
        }
        
        // Reset delay for consistent timing sync
        aec3Processor.setStreamDelay(currentStreamDelay);
        
        // Thread-safe ASR state reset
        synchronized (asrTextLock) {
            accumulatedAsrText.setLength(0);
            tempAsrBuffer.setLength(0);
            lastRecognizedSegment = "";
            asrSessionComplete = false;
            lastAsrStatus = "";
        }
        updateAsrStatus("待机");
        updateAsrResult("等待录音完成后进行语音识别...");
        
        try {
            // Re-initialize AudioRecord for AEC recording
            if (audioRecord != null) {
                audioRecord.release();
            }
            
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, BUFFER_SIZE * 2);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                updateStatus("错误: 麦克风初始化失败");
                return;
            }
            
        } catch (Exception e) {
            updateStatus("错误: " + e.getMessage());
            return;
        }
        
        isRecording = true;
        
        // Clear previous audio frames for clean audio collection
        cleanAudioFrames.clear();
        
        // Enable synchronized AEC processing - optimized for timing sync
        aec3Processor.clearCleanAudioBuffer();
        ttsFrameBuffer.clear();
        enableAecSync = true;

        // Reset counters for clean state
        globalFrameCount = 0;
        totalTtsFrames = 0;
        totalMicFrames = 0;
        missedTtsFrames = 0;
        avgTtsEnergy = 0;
        avgMicEnergy = 0;
        currentBufferSize = 0;
        lastDiagnosticTime = System.currentTimeMillis();
        
        // Update button text
        runOnUiThread(() -> {
            Button button = findViewById(R.id.buttonAECRecording);
            button.setText("停止AEC录音");
        });
        
        audioHandler.post(() -> {
            // Start microphone recording with full AEC processing
            // This will:
            // 1. Capture microphone signal (human voice + TTS echo)
            // 2. Use TTS reference signal from ttsFrameBuffer to cancel echo
            // 3. Produce clean audio (human voice only) and save to WAV
            startMicrophoneRecording(true); // true = use full AEC processing
        });
    }

    // 🔧 IMPROVED: Non-blocking TTS playback stop method 
    private void stopTTSPlayback() {
        Log.i(TAG, "停止TTS播放 - 非阻塞方式");
        
        // Set flag first to stop playback loop
        isPlayingTTS = false;
        
        // CONTINUOUS AEC3: Keep AEC3 running for consistent performance
        Log.i(TAG, "连续AEC3: TTS手动停止 - AEC3持续运行保证语音清晰");
        
        // Update UI immediately without waiting
        runOnUiThread(() -> {
            Button buttonPlayPCM = findViewById(R.id.buttonPlayPCM);
            buttonPlayPCM.setText("播放PCM");
            updateStatus("正在停止TTS播放...");
        });
        
        // Stop AudioTrack in background thread to avoid blocking UI
        new Thread(() -> {
            try {
                if (audioTrack != null) {
                    audioTrack.stop();
                    Log.i(TAG, "AudioTrack已停止");
                }
                
                // Update status when actually stopped
                runOnUiThread(() -> {
                    updateStatus("TTS播放已停止");
                });
                
            } catch (Exception e) {
                Log.e(TAG, "停止AudioTrack时出错", e);
                runOnUiThread(() -> {
                    updateStatus("停止播放时出错: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void startTTSPlayback() {
        if (pcmChunks.isEmpty()) {
            Log.w(TAG, "没有PCM chunks可播放");
            return;
        }
        
        isPlayingTTS = true;
        Log.i(TAG, "开始播放TTS PCM chunks，总数: " + pcmChunks.size());
        
        // Continuous AEC3 for consistent performance
        
        // Update button text
        runOnUiThread(() -> {
            Button buttonPlayPCM = findViewById(R.id.buttonPlayPCM);
            buttonPlayPCM.setText("停止播放");
        });
        
        new Thread(() -> {
            try {
                // PRODUCTION FIX: Pre-buffer TTS frames before AudioTrack starts
                // This prevents initial echo by ensuring AEC3 has reference frames ready
                if (enableAecSync && aec3Processor != null) {
                    Log.i(TAG, "🔥 生产级优化: 预缓冲TTS帧以消除启动回声");
                    
                    // Pre-buffer first 5 chunks (50ms) for perfect AEC3 startup
                    for (int preBuffer = 0; preBuffer < Math.min(5, pcmChunks.size()); preBuffer++) {
                        byte[] preChunkData = pcmChunks.get(preBuffer);
                        float[] preFloatData = byteArrayToFloatArray(preChunkData);
                        
                        int offset = 0;
                        while (offset + AEC3_FRAME_SIZE <= preFloatData.length) {
                            float[] frame = new float[AEC3_FRAME_SIZE];
                            System.arraycopy(preFloatData, offset, frame, 0, AEC3_FRAME_SIZE);
                            
                            short[] shortFrame = new short[AEC3_FRAME_SIZE];
                            for (int j = 0; j < AEC3_FRAME_SIZE; j++) {
                                shortFrame[j] = (short) (frame[j] * Short.MAX_VALUE);
                            }
                            
                            ttsFrameBuffer.offer(shortFrame);
                            offset += AEC3_FRAME_SIZE;
                        }
                    }
                    
                    Log.i(TAG, String.format("✅ 预缓冲完成: %d帧TTS参考信号准备就绪", ttsFrameBuffer.size()));
                }
                
                // 🎵 一次性AudioTrack启动 - 与您CosyVoice项目相同
                audioTrack.play();
                Log.i(TAG, "AudioTrack启动成功，准备连续播放");

                // CRITICAL: Ensure AudioTrack is at full volume
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                Log.i(TAG, String.format("音频音量检查: 当前=%d, 最大=%d", currentVolume, maxVolume));

                if (currentVolume < maxVolume * 0.5) {
                    Log.w(TAG, "⚠️ 音频音量过低 - 建议调高媒体音量");
                }

                // Set AudioTrack to maximum volume
                audioTrack.setVolume(1.0f);
                Log.i(TAG, "AudioTrack音量设置为最大");
                
                for (int i = 0; i < pcmChunks.size() && isPlayingTTS; i++) {
                    byte[] chunkData = pcmChunks.get(i);
                    long chunkStartTime = System.currentTimeMillis();
                    
                    // Check if PCM data contains actual audio
                    long rawEnergy = 0;
                    for (int j = 0; j < Math.min(chunkData.length, 100); j++) {
                        rawEnergy += Math.abs(chunkData[j]);
                    }

                    // Convert byte[] to float[] for AEC3 processing (48kHz data)
                    float[] floatData = byteArrayToFloatArray(chunkData);
                    
                    // Buffer TTS frames for synchronized AEC processing
                    if (enableAecSync && aec3Processor != null) {
                        int offset = 0;
                        int frameCount = 0;
                        
                        while (offset + AEC3_FRAME_SIZE <= floatData.length) {
                            float[] frame = new float[AEC3_FRAME_SIZE];
                            System.arraycopy(floatData, offset, frame, 0, AEC3_FRAME_SIZE);
                            
                            // Convert float to short array for buffering
                            short[] shortFrame = new short[AEC3_FRAME_SIZE];
                            for (int j = 0; j < AEC3_FRAME_SIZE; j++) {
                                shortFrame[j] = (short) (frame[j] * Short.MAX_VALUE);
                            }
                            
                            // Buffer frame for synchronous processing with microphone
                            ttsFrameBuffer.offer(shortFrame);

                            // Verify TTS frame energy before buffering
                            long ttsFrameEnergy = 0;
                            for (short sample : shortFrame) {
                                ttsFrameEnergy += Math.abs(sample);
                            }

                            // Maintain buffer for timing variations
                            int currentSize = ttsFrameBuffer.size();
                            if (currentSize > 30) {
                                // Remove excess frames but keep reasonable buffer
                                while (ttsFrameBuffer.size() > 20) {
                                    ttsFrameBuffer.poll();
                                }
                            } else if (currentSize < 5 && i > 10) {
                                // Add multiple frames if available for better buffering
                                for (int bufferBoost = 0; bufferBoost < 3 && (offset + AEC3_FRAME_SIZE <= floatData.length); bufferBoost++) {
                                    float[] extraFrame = new float[AEC3_FRAME_SIZE];
                                    System.arraycopy(floatData, offset, extraFrame, 0, AEC3_FRAME_SIZE);
                                    
                                    short[] extraShortFrame = new short[AEC3_FRAME_SIZE];
                                    for (int j = 0; j < AEC3_FRAME_SIZE; j++) {
                                        extraShortFrame[j] = (short) (extraFrame[j] * Short.MAX_VALUE);
                                    }
                                    
                                    ttsFrameBuffer.offer(extraShortFrame);
                                    offset += AEC3_FRAME_SIZE;
                                    frameCount++;
                                }
                            }
                            
                            offset += AEC3_FRAME_SIZE;
                            frameCount++;
                        }
                        
                        // TTS frames buffered for synchronized AEC processing
                    }
                    
                    // Direct audio playback for optimal timing sync
                    int totalBytesWritten = audioTrack.write(chunkData, 0, chunkData.length);
                }
                
                audioTrack.stop();
                isPlayingTTS = false;
                
                // Update button text
                runOnUiThread(() -> {
                    Button buttonPlayPCM = findViewById(R.id.buttonPlayPCM);
                    buttonPlayPCM.setText("播放PCM");
                });
                
            } catch (Exception e) {
                Log.e(TAG, "TTS播放错误", e);
                isPlayingTTS = false;
            }
        }).start();
    }
    
    private void startMicrophoneRecording(boolean useAEC) {
        // 🔧 CRITICAL FIX: TTS Content Validation 
        if (useAEC) {
            // Validate TTS has actual audio content before starting AEC
            int totalNonSilentChunks = 0;
            for (byte[] chunk : pcmChunks) {
                long energy = 0;
                for (int i = 0; i < Math.min(chunk.length, 1000); i++) {
                    energy += Math.abs(chunk[i]);
                }
                if (energy > 1000) { // Non-silent threshold
                    totalNonSilentChunks++;
                }
            }
            
            Log.w(TAG, String.format("🔧 TTS验证: 总chunks=%d, 非静音chunks=%d", 
                    pcmChunks.size(), totalNonSilentChunks));
            
            if (totalNonSilentChunks == 0) {
                Log.e(TAG, "❌ TTS音频全为静音，无法进行AEC处理");
                updateStatus("错误: TTS音频为静音，无法进行回声消除");
                return;
            } else if (totalNonSilentChunks < pcmChunks.size() * 0.1) {
                Log.w(TAG, "⚠️ TTS音频内容过少，AEC效果可能不佳");
                updateStatus("警告: TTS音频内容较少");
            }
        }
        
        try {
            // Release existing AudioRecord
            if (audioRecord != null) {
                audioRecord.release();
                audioRecord = null;
            }
            
            // CRITICAL: Try multiple audio sources and buffer sizes for compatibility
            int[] audioSources = {
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT
            };
            
            int[] bufferMultipliers = {4, 3, 2, 1}; // Try larger buffers first
            
            boolean audioRecordInitialized = false;
            
            for (int source : audioSources) {
                if (audioRecordInitialized) break;
                
                for (int multiplier : bufferMultipliers) {
                    try {
                        int testBufferSize = BUFFER_SIZE * multiplier;
                        
                        audioRecord = new AudioRecord(source, SAMPLE_RATE, 
                                AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, testBufferSize);
                        
                        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                            Log.i(TAG, String.format("✅ AudioRecord初始化成功: 源=%d, 缓冲区=%d", 
                                    source, testBufferSize));
                            audioRecordInitialized = true;
                            break;
                        } else {
                            Log.w(TAG, String.format("❌ AudioRecord失败: 源=%d, 缓冲区=%d, 状态=%d", 
                                    source, testBufferSize, audioRecord.getState()));
                            audioRecord.release();
                            audioRecord = null;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, String.format("AudioRecord异常: 源=%d, 错误=%s", source, e.getMessage()));
                        if (audioRecord != null) {
                            audioRecord.release();
                            audioRecord = null;
                        }
                    }
                }
            }
            
            if (!audioRecordInitialized) {
                Log.e(TAG, "所有AudioRecord配置都失败");
                updateStatus("错误: 无法初始化麦克风 - 请检查权限或重启应用");
                return;
            }
            
            // Additional validation
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED) {
                Log.i(TAG, "AudioRecord验证成功，可以开始录音");
            } else {
                Log.w(TAG, "AudioRecord状态异常: " + audioRecord.getRecordingState());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord初始化严重错误", e);
            updateStatus("错误: " + e.getMessage());
            return;
        }
        
        Log.i(TAG, "开始麦克风录音，使用AEC: " + useAEC + "，AudioRecord状态: " + audioRecord.getState());
        
        new Thread(() -> {
            try {
                audioRecord.startRecording();

                // CRITICAL: Check AudioRecord state and gain
                Log.i(TAG, String.format("AudioRecord状态检查: state=%d, recordingState=%d", 
                        audioRecord.getState(), audioRecord.getRecordingState()));

                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(TAG, "❌ AudioRecord未正常录音");
                    return;
                }

                // Check microphone gain
                AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (audioManager.isMicrophoneMute()) {
                    Log.e(TAG, "❌ 麦克风被静音");
                }

                byte[] buffer = new byte[BUFFER_SIZE];
                
                while (isRecording && !forceStopProcessing) { // 🛡️ Add forceStopProcessing check
                    int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                    
                    // 🛡️ FIX 4: Immediate termination check in processing loop
                    if (forceStopProcessing || isAsrTerminating || !isRecording) {
                        Log.i(TAG, "🛑 检测到强制停止信号，立即终止麦克风处理");
                        break;
                    }
                    
                    if (bytesRead > 0) {
                        // Convert to float array for processing
                        float[] micData = byteArrayToFloatArray(buffer, bytesRead);
                        
                        if (useAEC && aec3Processor != null) {
                            // Process with frame-synchronized AEC3 in 480-sample frames
                            int offset = 0;
                            long micProcessStartTime = System.currentTimeMillis();
                            
                            while (offset + AEC3_FRAME_SIZE <= micData.length && !forceStopProcessing) {
                                // 🛡️ FIX 5: Frame-level termination check
                                if (forceStopProcessing || isAsrTerminating || !isRecording) {
                                    Log.i(TAG, "帧处理中检测到停止信号，终止处理");
                                    break;
                                }
                                
                                float[] frame = new float[AEC3_FRAME_SIZE];
                                System.arraycopy(micData, offset, frame, 0, AEC3_FRAME_SIZE);
                                
                                // Convert float to short array for new AAR API
                                short[] shortFrame = new short[AEC3_FRAME_SIZE];
                                for (int j = 0; j < AEC3_FRAME_SIZE; j++) {
                                    shortFrame[j] = (short) (frame[j] * Short.MAX_VALUE);
                                }
                                
                                // CRITICAL: Use GLOBAL frame counter
                                globalFrameCount++;
                                totalProcessedFrames++; // Track total for memory monitoring
                                long currentFrame = globalFrameCount;
                                
                                // 🛡️ FIX 6: Memory monitoring during processing
                                if (totalProcessedFrames % 1000 == 0) { // Check every 1000 frames
                                    Runtime runtime = Runtime.getRuntime();
                                    currentMemoryUsed = runtime.totalMemory() - runtime.freeMemory();
                                    if (currentMemoryUsed > maxMemoryUsed) {
                                        maxMemoryUsed = currentMemoryUsed;
                                    }
                                    
                                    Log.d(TAG, String.format("🧠 内存监控: 当前=%dMB, 峰值=%dMB, 已处理帧=%d", 
                                            currentMemoryUsed/1024/1024, maxMemoryUsed/1024/1024, totalProcessedFrames));
                                    
                                    // 🚨 OOM prevention - Force stop if memory usage is too high
                                    long maxMemory = runtime.maxMemory();
                                    if (currentMemoryUsed > maxMemory * 0.85) {
                                        Log.e(TAG, "内存使用率超过85%，强制停止录音防止OOM");
                                        forceStopProcessing = true;
                                        break;
                                    }
                                }
                                
                                // 🛡️ FIX 7: Enhanced timing synchronization monitoring
                                lastMicTimestamp = System.currentTimeMillis();
                                
                                // CRITICAL: Process TTS reference frame IMMEDIATELY before microphone frame
                                short[] ttsFrame = ttsFrameBuffer.poll();
                                currentBufferSize = ttsFrameBuffer.size(); // Update buffer size

                                if (ttsFrame != null) {
                                    lastTtsTimestamp = System.currentTimeMillis();
                                    timingSyncDrift = Math.abs(lastTtsTimestamp - lastMicTimestamp);
                                    
                                    // Calculate TTS frame energy for diagnostics
                                    long ttsEnergy = 0;
                                    for (short sample : ttsFrame) {
                                        ttsEnergy += Math.abs(sample);
                                    }
                                    avgTtsEnergy = (avgTtsEnergy + ttsEnergy) / 2; // Running average
                                    totalTtsFrames++;
                                    
                                                                    // Skip AEC processing when TTS is silent 
                                if (ttsEnergy == 0 && !isPlayingTTS) {
                                    continue;
                                }
                                
                                // Process TTS reference signal FIRST (AEC3 requirement)
                                aec3Processor.processTtsAudio(ttsFrame);
                                } else {
                                    missedTtsFrames++;
                                }
                                
                                // Calculate microphone frame energy for diagnostics
                                long micEnergy = 0;
                                for (short sample : shortFrame) {
                                    micEnergy += Math.abs(sample);
                                }
                                avgMicEnergy = (avgMicEnergy + micEnergy) / 2; // Running average
                                totalMicFrames++;

                                // Process microphone audio and get clean output
                                short[] cleanOutput = aec3Processor.processMicrophoneAudio(shortFrame);
                                
                                if (cleanOutput != null) {
                                    // Get Enhanced AEC performance metrics 
                                    WqAecProcessor.EnhancedAecMetrics enhancedMetrics = aec3Processor.getEnhancedMetrics();
                                    if (enhancedMetrics != null) {
                                        float erle = (float) enhancedMetrics.echoReturnLossEnhancement;
                                        int delay = enhancedMetrics.delayMs;
                                        
                                        // Enhanced ERLE performance tracking (target >15dB)
                                        if (erle < 5.0f) {
                                            consecutiveBadErle++;
                                        } else {
                                            consecutiveBadErle = 0;
                                        }
                                        
                                        // Simplified monitoring for optimal timing sync
                                        if (currentFrame % 1000 == 0) { // Much less frequent monitoring
                                            // Minimal essential monitoring only
                                            
                                        // Simplified adaptive optimization for better timing sync
                                        if (consecutiveBadErle > 50 && adaptiveDelayEnabled) { // Higher threshold for stability
                                            boolean isProblematicDevice = (delay <= 0 || delay > 500 || Math.abs(delay - currentStreamDelay) > 100);
                                            
                                            if (isProblematicDevice) {
                                                // Simplified delay optimization for problematic devices
                                                int[] testDelays = {60, 100, 150}; // Reduced test delays
                                                for (int testDelay : testDelays) {
                                                    if (Math.abs(testDelay - currentStreamDelay) > 20) {
                                                        currentStreamDelay = testDelay;
                                                        aec3Processor.setStreamDelay(currentStreamDelay);
                                                        break;
                                                    }
                                                }
                                                aec3Processor.enableTimingSync(true);
                                            } else {
                                                // Simple auto-optimization for standard devices
                                                if (aec3Processor.autoOptimizeDelay()) {
                                                    WqAecProcessor.EnhancedAecMetrics optimizedMetrics = aec3Processor.getEnhancedMetrics();
                                                    if (optimizedMetrics != null) {
                                                        int newOptimalDelay = optimizedMetrics.optimalDelayMs;
                                                        if (newOptimalDelay != currentStreamDelay && newOptimalDelay > 0) {
                                                            currentStreamDelay = newOptimalDelay;
                                                            Log.i(TAG, String.format("⚡ Auto-adaptive delay updated: %dms", currentStreamDelay));
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            consecutiveBadErle = 0;
                                        }
                                        }
                                        
                                        // Auto-adaptive optimization - call regularly for continuous adaptation
                                        if (currentFrame % 100 == 0 && adaptiveDelayEnabled) { // Every 100 frames (~1 second)
                                            if (aec3Processor.autoOptimizeDelay()) {
                                                WqAecProcessor.EnhancedAecMetrics optimizedMetrics = aec3Processor.getEnhancedMetrics();
                                                if (optimizedMetrics != null) {
                                                    int newOptimalDelay = optimizedMetrics.optimalDelayMs;
                                                    if (newOptimalDelay != currentStreamDelay && newOptimalDelay > 0) {
                                                        currentStreamDelay = newOptimalDelay;
                                                        Log.i(TAG, String.format(" Regular auto-adaptive delay update: %dms", currentStreamDelay));
                                                    }
                                                }
                                            }
                                        }
                                        
                                        // Update UI metrics less frequently for better performance
                                        if (currentFrame % 500 == 0) { // Reduced from 200 to 500
                                            updateEnhancedMetrics(enhancedMetrics);
                                        }
                                    }
                                    
                                    // Convert short output back to float for WAV saving
                                    float[] cleanFrame = new float[AEC3_FRAME_SIZE];
                                    for (int j = 0; j < AEC3_FRAME_SIZE; j++) {
                                        cleanFrame[j] = cleanOutput[j] / (float) Short.MAX_VALUE;
                                    }
                                    cleanAudioFrames.add(cleanFrame);
                                }
                                
                                offset += AEC3_FRAME_SIZE;
                            }
                            
                            // Microphone buffer processing completed - minimal logging for optimal timing
                        }
                        // AEC processing mode only
                    }
                }
                
                audioRecord.stop();
                // Microphone recording stopped
                
            } catch (Exception e) {
                Log.e(TAG, "麦克风录音错误", e);
            }
        }).start();
    }
    
    private void stopRecording() {
        Log.i(TAG, "停止录音 - 非阻塞方式");
        
        // 🚨 IMMEDIATE UI UPDATE - Set flags and update UI first
        forceStopProcessing = true;
        // 🔧 FIX ASR: Don't terminate ASR when stopping recording - let it process clean audio 
        // isAsrTerminating = true;  // Commented out to allow ASR processing
        isRecording = false;
        isPlayingTTS = false;
        
        // Update UI immediately without waiting
        runOnUiThread(() -> {
            Button aecButton = findViewById(R.id.buttonAECRecording);
            Button playPCMButton = findViewById(R.id.buttonPlayPCM);
            aecButton.setText("AEC3录音（回声消除）");
            playPCMButton.setText("播放PCM");
            updateStatus("正在停止录音...");
        });
        
        // 🔧 NON-BLOCKING CLEANUP: Perform all cleanup in background thread 
        new Thread(() -> {
            try {
        // 🧠 MEMORY MONITORING - Log current memory usage
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        Log.w(TAG, String.format("🧠 内存状态: 已用=%dMB, 最大=%dMB, 使用率=%.1f%%", 
                usedMemory/1024/1024, maxMemory/1024/1024, (float)usedMemory/maxMemory*100));
        
        // 🕐 TIMING ANALYSIS - Log final timing statistics
        Log.w(TAG, String.format("🕐 时序分析: TTS最后时间戳=%dms, Mic最后时间戳=%dms, 漂移=%dms", 
                lastTtsTimestamp, lastMicTimestamp, timingSyncDrift));
        Log.w(TAG, String.format("📊 性能统计: 总处理帧=%d, 连续低ERLE次数=%d", 
                totalProcessedFrames, consecutiveBadErle));
        
                // Brief pause to allow current operations to complete
                Thread.sleep(50);
                
                // Continue with existing cleanup logic in background...
                performRecordingCleanup();
                
            } catch (Exception e) {
                Log.e(TAG, "停止录音后台处理失败", e);
                runOnUiThread(() -> updateStatus("停止录音时出错: " + e.getMessage()));
            }
        }).start();
    }
    
    /**
     * 🔧 NON-BLOCKING: Background cleanup after stopping recording 
     */
    private void performRecordingCleanup() {
        try {
            // Disable synchronized AEC processing
        enableAecSync = false;
        
        // 🛡️ FORCE CLEAR BUFFERS to prevent continued processing
        ttsFrameBuffer.clear();
        Log.i(TAG, "🧹 强制清空TTS缓冲区，禁用帧级同步AEC处理模式");
        
        // Enhanced WAV saving with detailed diagnostics
            Log.w(TAG, String.format("🎵 WAV保存状态: 清洁音频=%d帧", cleanAudioFrames.size()));
        
            // Get clean audio from C++ buffer and save both WAV and PCM
        if (aec3Processor != null) {
            try {
                // Get clean audio as WAV format (44.1kHz for ASR compatibility)
                byte[] cleanWavData = aec3Processor.getCleanAudioAsWAV(44100);
                if (cleanWavData != null && cleanWavData.length > 0) {
                    Log.i(TAG, String.format("从C++缓冲区获取清洁音频WAV: %d bytes", cleanWavData.length));
                    
                    // Save WAV file
                    saveCleanAudioFromBytes(cleanWavData, "wav");
                    
                    // Get clean audio as PCM for immediate ASR processing
                    byte[] cleanPcmData = aec3Processor.getCleanAudioAsPCM(16000);
                    if (cleanPcmData != null && cleanPcmData.length > 0) {
                        Log.i(TAG, String.format("从C++缓冲区获取清洁音频PCM: %d bytes", cleanPcmData.length));
                        
                        // Save PCM file for debugging
                        saveCleanAudioFromBytes(cleanPcmData, "pcm");
                        
                        // IMMEDIATE ASR PROCESSING with PCM data
                        // Process ASR immediately with the PCM data without additional conversion delay
                        if (!isActivityDestroying) {
                            try {
                                Log.i(TAG, String.format("🎤 启动即时ASR处理: 使用%d bytes PCM数据", cleanPcmData.length));
                                processCleanPcmWithASRRobust(cleanPcmData);
                            } catch (Exception e) {
                                Log.e(TAG, "即时ASR处理失败", e);
                                updateAsrStatus("ASR处理失败");
                                updateAsrResult("ASR处理失败: " + e.getMessage());
                            }
                        } else {
                            Log.i(TAG, "⏹️ 跳过ASR处理 - 应用正在关闭");
                            updateAsrStatus("已取消");
                            updateAsrResult("应用关闭，跳过语音识别");
                        }
                    }
                } else {
                    Log.e(TAG, "❌ C++缓冲区中无清洁音频数据");
                }
            } catch (Exception e) {
                Log.e(TAG, "获取C++清洁音频缓冲区失败", e);
                updateAsrStatus("音频获取失败");
                updateAsrResult("无法获取清洁音频: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "❌ AEC3处理器未初始化，无法获取清洁音频");
        }
        
        // 🔧 FALLBACK: Keep old logic for compatibility if C++ buffer fails
        if (!cleanAudioFrames.isEmpty()) {
            Log.w(TAG, String.format("🔄 使用Java缓冲区作为备用: %d帧清洁音频", cleanAudioFrames.size()));
            
            // 🛡️ ENHANCED: Robust ASR processing with crash prevention 
            // 🔧 FIX ASR: Allow ASR processing even when recording is stopped 
            if (!isActivityDestroying) {
                try {
                    // Create deep copy to prevent concurrent modification
                    List<float[]> cleanAudioCopy = new ArrayList<>();
                    synchronized (cleanAudioFrames) {
                        for (float[] frame : cleanAudioFrames) {
                            float[] frameCopy = new float[frame.length];
                            System.arraycopy(frame, 0, frameCopy, 0, frame.length);
                            cleanAudioCopy.add(frameCopy);
                        }
                    }
                    
                    Log.i(TAG, String.format("🎤 启动强化ASR处理: 安全复制了%d帧用于处理", cleanAudioCopy.size()));
                    
                    // Process in separate thread with comprehensive error handling
                    processCleanAudioWithASRRobust(cleanAudioCopy);
                        
                    } catch (Exception e) {
                        Log.e(TAG, "ASR处理准备阶段失败", e);
                        updateAsrStatus("ASR准备失败");
                        updateAsrResult("ASR处理准备失败: " + e.getMessage());
                    }
            } else {
                    Log.i(TAG, "⏹️ 跳过ASR处理 - 应用正在关闭");
                updateAsrStatus("已取消");
                    updateAsrResult("应用关闭，跳过语音识别");
            }
            
            cleanAudioFrames.clear();
        } else {
            Log.e(TAG, "❌ 清洁音频帧为空 - AEC3处理可能失败");
        }
        
        // Reset processing counters
        totalProcessedFrames = 0;
        consecutiveBadErle = 0;
        maxMemoryUsed = 0;
        forceStopProcessing = false; // Reset for next recording
        
            // Final status update
        runOnUiThread(() -> {
                updateStatus("录音已停止");
            
                // Reset performance metrics display
            TextView textViewMetrics = findViewById(R.id.textViewMetrics);
            textViewMetrics.setText("ERLE: -- dB | 延迟: -- ms");
            textViewMetrics.setTextColor(0xFF2196F3);  // 恢复默认蓝色
        });
        
        } catch (Exception e) {
            Log.e(TAG, "录音清理过程失败", e);
            runOnUiThread(() -> updateStatus("停止录音时出错: " + e.getMessage()));
        }
    }
    
    private float[] byteArrayToFloatArray(byte[] bytes) {
        return byteArrayToFloatArray(bytes, bytes.length);
    }
    
    private float[] byteArrayToFloatArray(byte[] bytes, int length) {
        // Convert 16-bit PCM byte array to float array
        float[] floats = new float[length / 2]; // 2 bytes per 16-bit sample
        
        // Convert shorts to floats and normalize
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < floats.length; i++) {
            short sample = byteBuffer.getShort(i * 2);
            floats[i] = sample / 32768.0f; // Normalize to [-1.0, 1.0]
        }
        
        return floats;
    }

    /**
     * Save clean audio from C++ byte array to file
     * @param audioData Audio data as byte array (WAV or PCM format)
     * @param format File format ("wav" or "pcm")
     */
    private void saveCleanAudioFromBytes(byte[] audioData, String format) {
        if (audioData == null || audioData.length == 0) {
            Log.w(TAG, "没有音频数据可保存: " + format);
            return;
        }
        
        try {
            // Create filename with timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String filename = "clean_audio_" + timestamp + "." + format;
            
            // Use public Documents directory (convenient for user access)
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (!documentsDir.exists()) {
                documentsDir.mkdirs();
            }
            File audioFile = new File(documentsDir, filename);
            
            // Write audio data directly to file
            try (FileOutputStream fos = new FileOutputStream(audioFile)) {
                fos.write(audioData);
                fos.flush();
            }
            
            // Update UI and save path for WAV files
            if ("wav".equals(format)) {
                lastCleanWavPath = audioFile.getAbsolutePath();
                runOnUiThread(() -> {
                    updateStatus("清洁音频已保存: " + filename);
                    updateWavFileUI();
                });
            }
            
            Log.i(TAG, String.format("清洁音频已保存到: %s (%d bytes)", audioFile.getAbsolutePath(), audioData.length));
            
        } catch (Exception e) {
            Log.e(TAG, "保存清洁音频失败: " + format, e);
            runOnUiThread(() -> updateStatus("保存" + format + "音频失败: " + e.getMessage()));
        }
    }

    private void saveCleanAudioToWAV() {
        if (cleanAudioFrames.isEmpty()) {
            Log.w(TAG, "没有清洁音频帧可保存");
            return;
        }
        
        try {
            // Create filename with timestamp
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String filename = "clean_audio_" + timestamp + ".wav";
            
            // Use public Documents directory (convenient for user access)
            File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (!documentsDir.exists()) {
                documentsDir.mkdirs();
            }
            File wavFile = new File(documentsDir, filename);
            
            // Calculate total samples
            int totalSamples = cleanAudioFrames.size() * AEC3_FRAME_SIZE;
            
            // Combine all frames into one array
            float[] allSamples = new float[totalSamples];
            int offset = 0;
            for (float[] frame : cleanAudioFrames) {
                System.arraycopy(frame, 0, allSamples, offset, frame.length);
                offset += frame.length;
            }
            
            // Write WAV file
            writeWAVFile(wavFile, allSamples, SAMPLE_RATE);
            
            // Update UI and save path
            lastCleanWavPath = wavFile.getAbsolutePath();
            
            Log.i(TAG, "清洁音频已保存到: " + wavFile.getAbsolutePath());
            runOnUiThread(() -> {
                updateStatus("清洁音频已保存: " + filename);
                updateWavFileUI();
            });
            
        } catch (Exception e) {
            Log.e(TAG, "保存清洁音频失败", e);
            runOnUiThread(() -> updateStatus("保存音频失败: " + e.getMessage()));
        }
    }

    // Original recording method removed - not needed with separate PCM control

    private void writeWAVFile(File file, float[] audioData, int sampleRate) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        
        // WAV file header
        int dataSize = audioData.length * 2; // 16-bit samples
        int fileSize = 36 + dataSize;
        
        // Write WAV header
        out.write("RIFF".getBytes());
        writeInt(out, fileSize);
        out.write("WAVE".getBytes());
        out.write("fmt ".getBytes());
        writeInt(out, 16); // PCM format size
        writeShort(out, (short) 1); // PCM format
        writeShort(out, (short) 1); // Mono
        writeInt(out, sampleRate);
        writeInt(out, sampleRate * 2); // Byte rate
        writeShort(out, (short) 2); // Block align
        writeShort(out, (short) 16); // Bits per sample
        out.write("data".getBytes());
        writeInt(out, dataSize);
        
        // Write audio data (convert float to 16-bit PCM)
        for (float sample : audioData) {
            short pcmSample = (short) (sample * Short.MAX_VALUE);
            writeShort(out, pcmSample);
        }
        
        out.close();
    }

    private void writeInt(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private void writeShort(FileOutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
    
    private void playWavFile(String filePath, String fileType) {
        // Thread-safe validation and conflict prevention
        if (filePath == null || filePath.trim().isEmpty()) {
            updateStatus("错误: " + fileType + "文件路径为空");
            return;
        }
        
        java.io.File file = new java.io.File(filePath);
        if (!file.exists() || !file.canRead()) {
            updateStatus("错误: " + fileType + "文件不存在或无法读取");
            return;
        }
        
        // Check for concurrent audio operations that could cause conflicts
        if (isRecording) {
            updateStatus("错误: 录音进行中，无法播放音频文件");
            return;
        }
        
        if (isPlayingTTS) {
            updateStatus("错误: TTS播放中，无法播放音频文件");
            return;
        }
        
        if (isAsrProcessing) {
            updateStatus("警告: ASR处理中，继续播放可能影响性能");
            Log.w(TAG, "MediaPlayer播放与ASR处理并发，可能导致音频冲突");
        }
        
        // Stop any currently playing audio safely
        stopMediaPlayer();
        
        try {
            // Additional safety check after stopMediaPlayer
            if (mediaPlayer != null) {
                Log.w(TAG, "MediaPlayer未正确清理，强制设置为null");
                mediaPlayer = null;
            }
            
            mediaPlayer = new MediaPlayer();
            
            // Set data source with additional error handling
            try {
                mediaPlayer.setDataSource(filePath);
            } catch (java.io.IOException e) {
                Log.e(TAG, "设置MediaPlayer数据源失败: " + filePath, e);
                updateStatus("错误: 无法读取音频文件");
                stopMediaPlayer();
                return;
            }
            
            mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            );
            
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    if (mp != null && !isRecording && !isPlayingTTS) {
                        mp.start();
                        updateStatus("正在播放: " + fileType);
                    } else {
                        Log.w(TAG, "MediaPlayer准备完成但状态不允许播放");
                        updateStatus("播放取消: 音频状态冲突");
                        stopMediaPlayer();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "MediaPlayer启动失败", e);
                    updateStatus("播放失败: " + e.getMessage());
                    stopMediaPlayer();
                }
            });
            
            mediaPlayer.setOnCompletionListener(mp -> {
                updateStatus("播放完成: " + fileType);
                stopMediaPlayer();
            });
            
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer播放错误 - what: " + what + ", extra: " + extra + ", file: " + filePath);
                updateStatus("播放错误: " + fileType + " (错误码: " + what + ")");
                stopMediaPlayer();
                return true;
            });
            
            // Add timeout protection for prepare
            new Thread(() -> {
                try {
                    Thread.sleep(10000); // 10 second timeout
                    if (mediaPlayer != null && (mediaPlayer.isPlaying() == false)) {
                        Log.w(TAG, "MediaPlayer准备超时，强制停止");
                        runOnUiThread(() -> {
                            updateStatus("播放超时: " + fileType);
                            stopMediaPlayer();
                        });
                    }
                } catch (InterruptedException e) {
                    // Timeout thread interrupted, normal behavior
                }
            }).start();
            
            mediaPlayer.prepareAsync();
            
        } catch (Exception e) {
            Log.e(TAG, "播放WAV文件失败: " + filePath, e);
            updateStatus("播放失败: " + e.getMessage());
            stopMediaPlayer();
        }
    }
    
    private void stopMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                // Additional state checking to prevent IllegalStateException
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                        Log.d(TAG, "MediaPlayer停止播放");
                    }
                } catch (IllegalStateException e) {
                    Log.w(TAG, "MediaPlayer状态异常，直接释放资源: " + e.getMessage());
                }
                
                // Reset to idle state before release
                try {
                    mediaPlayer.reset();
                } catch (Exception e) {
                    Log.w(TAG, "MediaPlayer重置失败: " + e.getMessage());
                }
                
                mediaPlayer.release();
                Log.d(TAG, "MediaPlayer资源已释放");
                
            } catch (Exception e) {
                Log.e(TAG, "停止MediaPlayer失败", e);
            } finally {
                // Ensure mediaPlayer is always set to null
                mediaPlayer = null;
            }
        }
    }
    
    private void updateWavFileUI() {
        // Ensure UI updates happen on main thread
        runOnUiThread(() -> {
            try {
                TextView textViewCleanWav = findViewById(R.id.textViewCleanWav);
                Button buttonPlayClean = findViewById(R.id.buttonPlayClean);
                
                // Null safety checks for UI components
                if (textViewCleanWav == null || buttonPlayClean == null) {
                    Log.e(TAG, "WAV文件UI组件为null，跳过更新");
                    return;
                }
                
                // Update clean WAV UI only (original recording removed)
                if (lastCleanWavPath != null && !lastCleanWavPath.trim().isEmpty()) {
                    try {
                        String filename = new java.io.File(lastCleanWavPath).getName();
                        textViewCleanWav.setText(filename);
                        buttonPlayClean.setEnabled(true);
                    } catch (Exception e) {
                        Log.w(TAG, "处理清洁音频文件路径失败: " + lastCleanWavPath, e);
                        textViewCleanWav.setText("文件路径错误");
                        buttonPlayClean.setEnabled(false);
                    }
                } else {
                    textViewCleanWav.setText("未保存录音文件");
                    buttonPlayClean.setEnabled(false);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "更新WAV文件UI失败", e);
            }
        });
    }
    
    // ======= ASR Integration Methods =======
    
    /**
     * Initialize ASR HTTP client with timeouts matching the HTML implementation
     */
    private void initializeAsrClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        Log.i(TAG, "ASR HTTP client initialized");
    }
    
    /**
     * Generate MD5 hash for authentication - exact same logic as HTML
     */
    private String generateMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "MD5 algorithm not found", e);
            return "";
        }
    }
    
    /**
     * Resample audio from 48kHz to 44.1kHz for ASR API compatibility
     */
    private float[] resampleAudio(float[] inputAudio, int inputSampleRate, int outputSampleRate) {
        if (inputSampleRate == outputSampleRate) {
            return inputAudio;
        }
        
        double ratio = (double) outputSampleRate / inputSampleRate;
        int outputLength = (int) (inputAudio.length * ratio);
        float[] outputAudio = new float[outputLength];
        
        for (int i = 0; i < outputLength; i++) {
            double srcIndex = i / ratio;
            int index = (int) srcIndex;
            double fraction = srcIndex - index;
            
            if (index < inputAudio.length - 1) {
                // Linear interpolation
                outputAudio[i] = (float) (inputAudio[index] * (1 - fraction) + 
                                         inputAudio[index + 1] * fraction);
            } else if (index < inputAudio.length) {
                outputAudio[i] = inputAudio[index];
            }
        }
        
        Log.i(TAG, String.format("音频重采样: %dHz->%dHz, %d->%d samples", 
                inputSampleRate, outputSampleRate, inputAudio.length, outputLength));
        return outputAudio;
    }
    
    /**
     * Convert float audio to Int16 PCM format for WebSocket transmission
     * Exact same logic as HTML convertFloat32ToInt16 function
     */
    private byte[] convertFloatToInt16PCM(float[] floatArray) {
        byte[] pcmData = new byte[floatArray.length * 2];
        ByteBuffer buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
        
        for (float sample : floatArray) {
            // Clamp to [-1.0, 1.0] range
            float clamped = Math.max(-1.0f, Math.min(1.0f, sample));
            // Convert to 16-bit signed integer
            short pcmSample = (short) (clamped < 0 ? clamped * 0x8000 : clamped * 0x7FFF);
            buffer.putShort(pcmSample);
        }
        
        return pcmData;
    }
    
    /**
     * 🛡️ Thread-safe ASR status update - prevents UI crashes
     * Uses synchronized access and Handler to ensure safe UI updates
     */
    private void updateAsrStatus(String status) {
        if (status == null || status.equals(lastAsrStatus)) {
            return; // Skip redundant updates
        }
        lastAsrStatus = status;
        
        // Use Handler to post to UI thread safely
        uiUpdateHandler.post(() -> {
            try {
                TextView textViewAsrStatus = findViewById(R.id.textViewAsrStatus);
                if (textViewAsrStatus != null) {
                    textViewAsrStatus.setText("状态: " + status);
                    Log.i(TAG, "ASR状态: " + status);
                }
            } catch (Exception e) {
                Log.e(TAG, "更新ASR状态失败", e);
            }
        });
    }
    
    /**
     * 🛡️ Thread-safe ASR result update - prevents UI crashes  
     * Uses synchronized StringBuilder operations and batched UI updates
     */
    private void updateAsrResult(String result) {
        if (result == null) {
            return;
        }
        
        // Use Handler to post to UI thread safely
        uiUpdateHandler.post(() -> {
            try {
                TextView textViewAsrResult = findViewById(R.id.textViewAsrResult);
                if (textViewAsrResult != null) {
                    textViewAsrResult.setText(result);
                    Log.i(TAG, "ASR结果更新: " + result.length() + " 字符");
                }
            } catch (Exception e) {
                Log.e(TAG, "更新ASR结果失败", e);
            }
        });
    }
    
    /**
     * 🛡️ Thread-safe ASR text accumulation
     * Handles partial results and final segments with proper synchronization
     */
    private void appendAsrSegment(String newSegment, boolean isFinal) {
        if (newSegment == null || newSegment.trim().isEmpty()) {
            return;
        }
        
        synchronized (asrTextLock) {
            if (isFinal) {
                // Check for duplicate final segments
                if (!newSegment.equals(lastRecognizedSegment)) {
                    if (accumulatedAsrText.length() > 0) {
                        accumulatedAsrText.append("\n");
                    }
                    accumulatedAsrText.append(newSegment);
                    lastRecognizedSegment = newSegment;
                    
                    // Update UI with final accumulated text
                    String finalResult = "最终结果:\n" + accumulatedAsrText.toString();
                    updateAsrResult(finalResult);
                    Log.i(TAG, "ASR新片段: " + newSegment + " (总长度: " + accumulatedAsrText.length() + ")");
                } else {
                    Log.d(TAG, "跳过重复ASR片段: " + newSegment);
                }
            } else {
                // Handle partial/intermediate results
                String displayText;
                if (accumulatedAsrText.length() > 0) {
                    displayText = "最终结果:\n" + accumulatedAsrText.toString() + "\n识别中: " + newSegment;
                } else {
                    displayText = "识别中: " + newSegment;
                }
                updateAsrResult(displayText);
                Log.d(TAG, "ASR中间结果: " + newSegment);
            }
        }
    }
    
    /**
     * 🛡️ Complete ASR session safely
     * Ensures final UI update when all processing is done
     */
    private void completeAsrSession() {
        synchronized (asrTextLock) {
            asrSessionComplete = true;
            
            // Final UI update with complete results
            uiUpdateHandler.post(() -> {
                try {
                    if (accumulatedAsrText.length() > 0) {
                        String finalResult = "识别完成:\n" + accumulatedAsrText.toString();
                        TextView textViewAsrResult = findViewById(R.id.textViewAsrResult);
                        if (textViewAsrResult != null) {
                            textViewAsrResult.setText(finalResult);
                        }
                        Log.i(TAG, "ASR会话完成 - 最终结果: " + accumulatedAsrText.length() + " 字符");
                    } else {
                        updateAsrResult("识别完成: 未检测到语音内容");
                        Log.w(TAG, "ASR会话完成 - 无识别结果");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "完成ASR会话失败", e);
                }
            });
        }
    }
    
    /**
     * IMMEDIATE ASR processing with pre-converted PCM data
     * This method processes PCM data directly without additional conversion delays
     */
    private void processCleanPcmWithASRRobust(byte[] cleanPcmData) {
        if (cleanPcmData == null || cleanPcmData.length == 0) {
            Log.w(TAG, "ASR处理输入PCM数据为空，跳过处理");
            updateAsrStatus("输入错误");
            updateAsrResult("ASR输入PCM数据为空");
            return;
        }
        
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR处理被终止 - 应用状态不允许");
            updateAsrStatus("已终止");
            updateAsrResult("ASR处理已终止");
            return;
        }
        
        Log.i(TAG, String.format("开始即时ASR处理: %d bytes PCM数据 (44.1kHz)", cleanPcmData.length));
        updateAsrStatus("准备发送音频...");
        
        // Thread-safe session initialization
        try {
            synchronized (asrTextLock) {
                accumulatedAsrText.setLength(0);
                tempAsrBuffer.setLength(0);
                lastRecognizedSegment = "";
                asrSessionComplete = false;
                lastAsrStatus = "";
            }
        } catch (Exception e) {
            Log.e(TAG, "ASR状态初始化失败", e);
            updateAsrStatus("初始化失败");
            updateAsrResult("ASR状态初始化失败");
            return;
        }
        
        // Process in background thread
        new Thread(() -> {
            try {
                // PCM data is already in the correct format (44.1kHz, 16-bit), send directly to ASR
                Log.i(TAG, "🚀 PCM数据已预处理完成，直接发送至ASR服务");
                updateAsrStatus("连接ASR服务...");
                sendPcmDataToASRRobust(cleanPcmData);
                
            } catch (Exception e) {
                Log.e(TAG, "即时ASR处理发生未捕获异常", e);
                if (!isAsrTerminating && !isActivityDestroying) {
                    updateAsrStatus("处理异常");
                    updateAsrResult("ASR处理发生异常: " + e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * Send pre-converted PCM data directly to ASR service
     */
    private void sendPcmDataToASRRobust(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            Log.e(TAG, "ASR WebSocket输入PCM数据无效");
            updateAsrStatus("数据无效");
            updateAsrResult("无效的PCM数据");
            return;
        }
        
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR WebSocket创建被终止");
            return;
        }
        
        try {
            // Generate authentication parameters
            long timestamp = System.currentTimeMillis() / 1000;
            String signStr = ASR_APP_ID + ASR_APP_SECRET + timestamp;
            String sign = generateMD5(signStr);
            
            if (sign == null || sign.isEmpty()) {
                Log.e(TAG, "MD5签名生成失败");
                updateAsrStatus("认证失败");
                updateAsrResult("ASR认证签名生成失败");
                return;
            }
            
            String wsUrl = ASR_WS_URL + "?appId=" + ASR_APP_ID + "&timestamp=" + timestamp + "&sign=" + sign;
            
            Log.i(TAG, "连接ASR WebSocket: " + wsUrl);
            updateAsrStatus("建立连接...");
            
            Request request = new Request.Builder()
                    .url(wsUrl)
                    .build();
            
            // Create robust WebSocket listener for PCM data
            WebSocketListener listener = createRobustWebSocketListener();
            
            // Synchronized WebSocket creation
            synchronized (asrWebSocketLock) {
                if (!isAsrTerminating && !isActivityDestroying) {
                    asrWebSocket = httpClient.newWebSocket(request, listener);
                } else {
                    Log.i(TAG, "跳过WebSocket创建 - 应用正在终止");
                    return;
                }
            }
            
            // Store PCM data for sending after connection
            this.pendingPcmData = pcmData;
            
        } catch (Exception e) {
            Log.e(TAG, "ASR WebSocket创建失败", e);
            updateAsrStatus("连接创建失败");
            updateAsrResult("无法创建ASR连接: " + e.getMessage());
        }
    }

    /**
     * 🛡️ ROBUST: Crash-free ASR processing with comprehensive error handling 
     * This method is designed to never crash or exit the app
     */
    private void processCleanAudioWithASRRobust(List<float[]> audioFramesToProcess) {
        // Early validation with null safety
        if (audioFramesToProcess == null) {
            Log.w(TAG, "ASR处理输入为null，跳过处理");
            updateAsrStatus("输入错误");
            updateAsrResult("ASR输入数据为空");
            return;
        }
        
        if (audioFramesToProcess.isEmpty()) {
            Log.w(TAG, "没有清洁音频帧可进行ASR处理");
            updateAsrStatus("无音频数据");
            updateAsrResult("没有音频数据可供识别");
            return;
        }
        
        // 🔧 FIX ASR TERMINATION: Only check activity destroying and ASR termination, not forceStopProcessing 
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR处理被终止 - 应用状态不允许");
            updateAsrStatus("已终止");
            updateAsrResult("ASR处理已终止");
            return;
        }
        
        Log.i(TAG, String.format("开始强化ASR处理: %d帧清洁音频", audioFramesToProcess.size()));
        updateAsrStatus("准备音频数据...");
        
        // Thread-safe session initialization
        try {
            synchronized (asrTextLock) {
                accumulatedAsrText.setLength(0);
                tempAsrBuffer.setLength(0);
                lastRecognizedSegment = "";
                asrSessionComplete = false;
                lastAsrStatus = "";
            }
        } catch (Exception e) {
            Log.e(TAG, "ASR状态初始化失败", e);
            updateAsrStatus("初始化失败");
            updateAsrResult("ASR状态初始化失败");
            return;
        }
        
        // Process in background thread with comprehensive error handling
        new Thread(() -> {
            try {
                processASRInBackground(audioFramesToProcess);
            } catch (Exception e) {
                Log.e(TAG, "ASR后台处理发生未捕获异常", e);
                if (!isAsrTerminating && !isActivityDestroying) {
                    updateAsrStatus("处理异常");
                    updateAsrResult("ASR处理发生异常: " + e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * Background ASR processing with robust error handling
     */
    private void processASRInBackground(List<float[]> audioFramesToProcess) {
        try {
            // Multiple termination checks during processing
            if (isAsrTerminating || isActivityDestroying) {
                Log.i(TAG, "ASR后台处理终止 - 应用状态检查失败");
                return;
            }
            
            // 1. Combine all clean audio frames with validation
            int totalSamples = audioFramesToProcess.size() * AEC3_FRAME_SIZE;
            if (totalSamples <= 0) {
                Log.e(TAG, "计算的总样本数无效: " + totalSamples);
                updateAsrStatus("数据无效");
                updateAsrResult("音频数据计算错误");
                return;
            }
            
            float[] allCleanAudio = new float[totalSamples];
            int offset = 0;
            
            for (float[] frame : audioFramesToProcess) {
                // Check termination during processing
                if (isAsrTerminating || isActivityDestroying) {
                    Log.i(TAG, "ASR音频合并被终止");
                    return;
                }
                
                if (frame == null || frame.length != AEC3_FRAME_SIZE) {
                    Log.w(TAG, String.format("跳过无效帧: 长度=%d (期望=%d)", 
                            frame != null ? frame.length : 0, AEC3_FRAME_SIZE));
                    continue;
                }
                
                if (offset + frame.length <= allCleanAudio.length) {
                    System.arraycopy(frame, 0, allCleanAudio, offset, frame.length);
                    offset += frame.length;
                } else {
                    Log.w(TAG, "帧复制越界，停止处理");
                    break;
                }
            }
            
            if (offset == 0) {
                Log.e(TAG, "没有有效的音频帧被处理");
                updateAsrStatus("无有效数据");
                updateAsrResult("没有有效的音频帧可供识别");
                return;
            }
            
            // Adjust array size if needed
            if (offset < allCleanAudio.length) {
                float[] adjustedAudio = new float[offset];
                System.arraycopy(allCleanAudio, 0, adjustedAudio, 0, offset);
                allCleanAudio = adjustedAudio;
            }
            
            Log.i(TAG, String.format("音频数据合并完成: %d samples (%.1f秒)", 
                    allCleanAudio.length, allCleanAudio.length / (float)SAMPLE_RATE));
            
            // Continue with existing ASR processing logic...
            processAudioForASR(allCleanAudio);
            
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "ASR处理内存不足", e);
            updateAsrStatus("内存不足");
            updateAsrResult("内存不足，无法处理音频数据");
        } catch (Exception e) {
            Log.e(TAG, "ASR后台处理失败", e);
            if (!isAsrTerminating && !isActivityDestroying) {
                updateAsrStatus("处理失败");
                updateAsrResult("ASR处理失败: " + e.getMessage());
            }
        }
    }
    
    /**
     * Continue ASR processing with the prepared audio data
     */
    private void processAudioForASR(float[] allCleanAudio) throws Exception {
        // 🚀 SIMPLIFIED ASR: Skip energy detection, directly process clean audio 
        Log.i(TAG, "🎤 直接处理清洁音频数据，跳过能量预检");
        updateAsrStatus("准备处理音频...");
        
        // 2. Resample from 48kHz to 44.1kHz for ASR API
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR重采样被终止");
            return;
        }
        updateAsrStatus("重采样音频...");
        float[] resampledAudio = resampleAudio(allCleanAudio, SAMPLE_RATE, ASR_SAMPLE_RATE);
        
        // 3. Convert to Int16 PCM for WebSocket
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR格式转换被终止");
            return;
        }
        updateAsrStatus("转换音频格式...");
        byte[] pcmData = convertFloatToInt16PCM(resampledAudio);
        
        // CRITICAL: Verify PCM data is not empty
        if (pcmData.length == 0) {
            Log.e(TAG, "❌ PCM转换后数据为空");
            updateAsrStatus("转换失败");
            updateAsrResult("音频格式转换失败");
            return;
        }
        
        Log.i(TAG, String.format("音频处理完成: %d bytes PCM数据", pcmData.length));
        
        // 4. Send to ASR API via WebSocket
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR WebSocket连接被终止");
            return;
        }
        updateAsrStatus("连接ASR服务...");
        sendAudioToASRRobust(pcmData);
    }
    
    /**
     * Process clean audio with ASR after recording stops
     * This is the main integration point called from stopRecording()
     */
    private void processCleanAudioWithASR(List<float[]> audioFramesToProcess) {
        // Early termination check
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR处理被终止 - 应用状态不允许");
            return;
        }
        
        if (audioFramesToProcess.isEmpty()) {
            Log.w(TAG, "没有清洁音频帧可进行ASR处理");
            updateAsrStatus("错误: 无音频数据");
            return;
        }
        
        Log.i(TAG, String.format("开始ASR处理: %d帧清洁音频", audioFramesToProcess.size()));
        updateAsrStatus("准备音频数据...");
        
        // 🛡️ Thread-safe session initialization
        synchronized (asrTextLock) {
            accumulatedAsrText.setLength(0);
            tempAsrBuffer.setLength(0);
            lastRecognizedSegment = "";
            asrSessionComplete = false;
            lastAsrStatus = "";
        }
        
        new Thread(() -> {
            try {
                // 🛡️ CRASH FIX 5: Multiple termination checks during processing
                if (isAsrTerminating || isActivityDestroying) {
                    Log.i(TAG, "ASR处理线程终止 - 应用状态检查失败");
                    return;
                }
                
                // 1. Combine all clean audio frames
                int totalSamples = audioFramesToProcess.size() * AEC3_FRAME_SIZE;
                float[] allCleanAudio = new float[totalSamples];
                int offset = 0;
                
                for (float[] frame : audioFramesToProcess) {
                    // Check termination during processing
                    if (isAsrTerminating || isActivityDestroying) {
                        Log.i(TAG, "ASR音频合并被终止");
                        return;
                    }
                    System.arraycopy(frame, 0, allCleanAudio, offset, frame.length);
                    offset += frame.length;
                }
                
                Log.i(TAG, String.format("音频数据合并完成: %d samples (%.1f秒)", 
                        totalSamples, totalSamples / (float)SAMPLE_RATE));
                
                // 🚀 SIMPLIFIED ASR: Skip energy detection, directly process clean WAV 
                Log.i(TAG, "🎤 直接处理清洁音频，跳过能量检测以提高效率");
                
                // 2. Resample from 48kHz to 44.1kHz for ASR API
                if (isAsrTerminating || isActivityDestroying) {
                    Log.i(TAG, "ASR重采样被终止");
                    return;
                }
                updateAsrStatus("重采样音频...");
                float[] resampledAudio = resampleAudio(allCleanAudio, SAMPLE_RATE, ASR_SAMPLE_RATE);
                
                // 3. Convert to Int16 PCM for WebSocket
                if (isAsrTerminating || isActivityDestroying) {
                    Log.i(TAG, "ASR格式转换被终止");
                    return;
                }
                updateAsrStatus("转换音频格式...");
                byte[] pcmData = convertFloatToInt16PCM(resampledAudio);
                
                // CRITICAL: Verify PCM data is not empty
                if (pcmData.length == 0) {
                    Log.e(TAG, "❌ PCM转换后数据为空");
                    updateAsrStatus("错误: PCM转换失败");
                    updateAsrResult("错误: 音频格式转换失败");
                    return;
                }
                
                // Check a few samples to ensure they're not all zero
                int nonZeroSamples = 0;
                for (int i = 0; i < Math.min(pcmData.length, 100); i += 2) {
                    if (pcmData[i] != 0 || pcmData[i+1] != 0) {
                        nonZeroSamples++;
                    }
                }
                
                Log.i(TAG, String.format("音频处理完成: %d bytes PCM数据, 前50样本中%d个非零", 
                        pcmData.length, nonZeroSamples));
                
                if (nonZeroSamples == 0) {
                    Log.w(TAG, "⚠️ PCM数据全为零 - 可能音频过于安静");
                }
                
                // 4. Send to ASR API via WebSocket
                if (isAsrTerminating || isActivityDestroying) {
                    Log.i(TAG, "ASR WebSocket连接被终止");
                    return;
                }
                updateAsrStatus("连接ASR服务...");
                sendAudioToASR(pcmData);
                
            } catch (Exception e) {
                Log.e(TAG, "ASR音频处理失败", e);
                if (!isAsrTerminating && !isActivityDestroying) {
                    updateAsrStatus("错误: " + e.getMessage());
                    updateAsrResult("音频处理失败: " + e.getMessage());
                }
            }
        }).start();
    }
    
    /**
     * 🛡️ ROBUST: Crash-free ASR WebSocket processing 
     */
    private void sendAudioToASRRobust(byte[] pcmData) {
        // Comprehensive input validation
        if (pcmData == null || pcmData.length == 0) {
            Log.e(TAG, "ASR WebSocket输入数据无效");
            updateAsrStatus("数据无效");
            updateAsrResult("无效的音频数据");
            return;
        }
        
        // Final termination check before WebSocket creation
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR WebSocket创建被终止");
            return;
        }
        
        try {
            // Generate authentication parameters with error handling
            long timestamp = System.currentTimeMillis() / 1000;
            String signStr = ASR_APP_ID + ASR_APP_SECRET + timestamp;
            String sign = generateMD5(signStr);
            
            if (sign == null || sign.isEmpty()) {
                Log.e(TAG, "MD5签名生成失败");
                updateAsrStatus("认证失败");
                updateAsrResult("ASR认证签名生成失败");
                return;
            }
            
            String wsUrl = ASR_WS_URL + "?appId=" + ASR_APP_ID + "&timestamp=" + timestamp + "&sign=" + sign;
            
            Log.i(TAG, "连接ASR WebSocket: " + wsUrl);
            updateAsrStatus("建立连接...");
            
            Request request = new Request.Builder()
                    .url(wsUrl)
                    .build();
            
            // Create robust WebSocket listener
            WebSocketListener listener = createRobustWebSocketListener();
            
            // Synchronized WebSocket creation
            synchronized (asrWebSocketLock) {
                if (!isAsrTerminating && !isActivityDestroying) {
                    asrWebSocket = httpClient.newWebSocket(request, listener);
                } else {
                    Log.i(TAG, "跳过WebSocket创建 - 应用正在终止");
                    return;
                }
            }
            
            // Store PCM data for sending after connection
            this.pendingPcmData = pcmData;
            
        } catch (Exception e) {
            Log.e(TAG, "ASR WebSocket创建失败", e);
            updateAsrStatus("连接创建失败");
            updateAsrResult("无法创建ASR连接: " + e.getMessage());
        }
    }

    /**
     * 🛡️ Create robust WebSocket listener with comprehensive error handling 
     */
    private WebSocketListener createRobustWebSocketListener() {
        return new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try {
                    // Check state before WebSocket operations
                    synchronized (asrWebSocketLock) {
                        if (isAsrTerminating || isActivityDestroying) {
                            Log.i(TAG, "ASR WebSocket打开时检测到终止状态，关闭连接");
                            webSocket.close(1000, "App terminating");
                            return;
                        }
                        isAsrProcessing = true;
                    }
                    
                    Log.i(TAG, "ASR WebSocket连接成功");
                    updateAsrStatus("发送音频数据...");
                    
                    // Send start command with error handling
                    try {
                        JsonObject startCommand = new JsonObject();
                        startCommand.addProperty("command", "startSpeech");
                        startCommand.addProperty("sourceLanguage", ASR_SOURCE_LANGUAGE);
                        startCommand.addProperty("targetLanguage", ASR_SOURCE_LANGUAGE);
                        startCommand.addProperty("translationMode", ASR_TRANSLATION_MODE);
                        startCommand.addProperty("generateAudio", false);
                        startCommand.addProperty("outputFormat", "pcm");
                        startCommand.addProperty("ttsProvider", "watchTtsCosy");
                        
                        String startCommandJson = new Gson().toJson(startCommand);
                        webSocket.send(startCommandJson);
                        Log.i(TAG, "发送启动命令: " + startCommandJson);
                        
                        // Send PCM audio data if available
                        if (pendingPcmData != null && pendingPcmData.length > 0) {
                            sendAudioInChunks(webSocket, pendingPcmData);
                        } else {
                            Log.e(TAG, "待发送的PCM数据为空");
                            updateAsrStatus("数据错误");
                            webSocket.close(1000, "No audio data");
                        }
                        
                    } catch (Exception e) {
                        Log.e(TAG, "发送ASR启动命令失败", e);
                        updateAsrStatus("命令发送失败");
                        webSocket.close(1000, "Command failed");
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "ASR WebSocket onOpen处理失败", e);
                    updateAsrStatus("连接处理失败");
                    if (!isAsrTerminating && !isActivityDestroying) {
                        updateAsrResult("连接处理失败: " + e.getMessage());
                    }
                }
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    // Check state before processing messages
                    if (isAsrTerminating || isActivityDestroying) {
                        Log.d(TAG, "收到ASR消息但应用正在终止，忽略处理");
                        return;
                    }
                    
                    Log.i(TAG, "ASR响应: " + text);
                    
                    JsonObject response = JsonParser.parseString(text).getAsJsonObject();
                    
                    if (response.has("type")) {
                        String type = response.get("type").getAsString();
                        
                        if ("error".equals(type)) {
                            String errorMsg = response.has("message") ? response.get("message").getAsString() : "未知错误";
                            Log.e(TAG, "ASR错误: " + errorMsg);
                            updateAsrStatus("错误: " + errorMsg);
                            updateAsrResult("识别失败: " + errorMsg);
                            webSocket.close(1000, "Error received");
                            return;
                        } else if ("speechStarted".equals(type)) {
                            Log.i(TAG, "ASR识别会话已开始");
                            updateAsrStatus("识别会话已开始");
                            return;
                        } else if ("speechStopped".equals(type)) {
                            Log.i(TAG, "ASR识别会话已结束 - 所有音频处理完成");
                            completeAsrSession();
                            webSocket.close(1000, "Speech processing completed");
                            return;
                        }
                    }
                    
                    // Handle recognition results
                    if (response.has("status")) {
                        String status = response.get("status").getAsString();
                        
                        if ("recognizing".equals(status)) {
                            updateAsrStatus("识别中...");
                            if (response.has("text")) {
                                String text_partial = response.get("text").getAsString();
                                appendAsrSegment(text_partial, false);
                            }
                        } else if ("recognized".equals(status)) {
                            updateAsrStatus("识别片段完成，等待更多内容...");
                            if (response.has("text")) {
                                String finalText = response.get("text").getAsString().trim();
                                if (!finalText.isEmpty()) {
                                    appendAsrSegment(finalText, true);
                                } else {
                                    Log.w(TAG, "ASR识别片段结果为空");
                                }
                            } else {
                                Log.w(TAG, "ASR响应中没有text字段");
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "解析ASR响应失败", e);
                    if (!isAsrTerminating && !isActivityDestroying) {
                        updateAsrStatus("解析失败");
                        updateAsrResult("响应解析错误: " + e.getMessage());
                    }
                }
            }
            
            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                if (!isAsrTerminating && !isActivityDestroying) {
                    Log.d(TAG, "收到音频响应: " + bytes.size() + " bytes (忽略)");
                }
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "ASR WebSocket关闭中: " + code + " " + reason);
                if (!isAsrTerminating && !isActivityDestroying) {
                    updateAsrStatus("连接关闭中...");
                }
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "ASR WebSocket已关闭: " + code + " " + reason);
                
                try {
                    synchronized (asrWebSocketLock) {
                        synchronized (asrTextLock) {
                            if (!asrSessionComplete && !isAsrTerminating && !isActivityDestroying) {
                                completeAsrSession();
                            }
                        }
                        
                        if (!isAsrTerminating && !isActivityDestroying) {
                            if (code == 1000) {
                                updateAsrStatus("识别完成");
                            } else {
                                updateAsrStatus("连接关闭");
                            }
                        }
                        
                        asrWebSocket = null;
                        isAsrProcessing = false;
                        pendingPcmData = null; // Clear pending data
                    }
                } catch (Exception e) {
                    Log.e(TAG, "ASR WebSocket关闭处理失败", e);
                }
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "ASR WebSocket连接失败", t);
                
                try {
                    synchronized (asrWebSocketLock) {
                        if (!isAsrTerminating && !isActivityDestroying) {
                            updateAsrStatus("连接失败: " + t.getMessage());
                            updateAsrResult("连接失败: " + t.getMessage());
                        }
                        asrWebSocket = null;
                        isAsrProcessing = false;
                        pendingPcmData = null; // Clear pending data
                    }
                } catch (Exception e) {
                    Log.e(TAG, "ASR WebSocket失败处理异常", e);
                }
            }
        };
    }

    // Store PCM data for robust WebSocket processing
    private volatile byte[] pendingPcmData = null;
    
    /**
     * Send audio to ASR via WebSocket - exact same protocol as HTML
     */
    private void sendAudioToASR(byte[] pcmData) {
        // Final termination check before WebSocket creation
        if (isAsrTerminating || isActivityDestroying) {
            Log.i(TAG, "ASR WebSocket创建被终止");
            return;
        }
        
        // Generate authentication parameters
        long timestamp = System.currentTimeMillis() / 1000;
        String signStr = ASR_APP_ID + ASR_APP_SECRET + timestamp;
        String sign = generateMD5(signStr);
        
        String wsUrl = ASR_WS_URL + "?appId=" + ASR_APP_ID + "&timestamp=" + timestamp + "&sign=" + sign;
        
        Log.i(TAG, "连接ASR WebSocket: " + wsUrl);
        updateAsrStatus("建立连接...");
        
        Request request = new Request.Builder()
                .url(wsUrl)
                .build();
        
        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                // 🛡️ CRASH FIX 7: Check state before WebSocket operations
                synchronized (asrWebSocketLock) {
                    if (isAsrTerminating || isActivityDestroying) {
                        Log.i(TAG, "ASR WebSocket打开时检测到终止状态，关闭连接");
                        webSocket.close(1000, "App terminating");
                        return;
                    }
                    isAsrProcessing = true;
                }
                
                Log.i(TAG, "ASR WebSocket连接成功");
                updateAsrStatus("发送音频数据...");
                
                // Send start command - exact same format as HTML
                JsonObject startCommand = new JsonObject();
                startCommand.addProperty("command", "startSpeech");
                startCommand.addProperty("sourceLanguage", ASR_SOURCE_LANGUAGE);
                startCommand.addProperty("targetLanguage", ASR_SOURCE_LANGUAGE); // Same as source for transcription
                startCommand.addProperty("translationMode", ASR_TRANSLATION_MODE);
                startCommand.addProperty("generateAudio", false); // No TTS needed
                startCommand.addProperty("outputFormat", "pcm");
                startCommand.addProperty("ttsProvider", "watchTtsCosy");
                
                String startCommandJson = new Gson().toJson(startCommand);
                webSocket.send(startCommandJson);
                Log.i(TAG, "发送启动命令: " + startCommandJson);
                
                // Send PCM audio data in chunks to allow better ASR processing
                sendAudioInChunks(webSocket, pcmData);
                
                updateAsrStatus("等待识别结果...");
            }
            
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                // 🛡️ CRASH FIX 8: Check state before processing messages
                if (isAsrTerminating || isActivityDestroying) {
                    Log.d(TAG, "收到ASR消息但应用正在终止，忽略处理");
                    return;
                }
                
                Log.i(TAG, "ASR响应: " + text);
                
                try {
                    JsonObject response = JsonParser.parseString(text).getAsJsonObject();
                    
                    if (response.has("type")) {
                        String type = response.get("type").getAsString();
                        
                        if ("error".equals(type)) {
                            String errorMsg = response.has("message") ? response.get("message").getAsString() : "未知错误";
                            Log.e(TAG, "ASR错误: " + errorMsg);
                            updateAsrStatus("错误: " + errorMsg);
                            updateAsrResult("识别失败: " + errorMsg);
                            webSocket.close(1000, "Error received");
                            return;
                        } else if ("speechStarted".equals(type)) {
                            Log.i(TAG, "ASR识别会话已开始");
                            updateAsrStatus("识别会话已开始");
                            return;
                        } else if ("speechStopped".equals(type)) {
                            Log.i(TAG, "ASR识别会话已结束 - 所有音频处理完成");
                            // 🛡️ Thread-safe session completion
                            completeAsrSession();
                            // Close connection now - all audio has been processed
                            webSocket.close(1000, "Speech processing completed");
                            return;
                        }
                    }
                    
                    // Handle recognition results
                    if (response.has("status")) {
                        String status = response.get("status").getAsString();
                        
                        if ("recognizing".equals(status)) {
                            // 🛡️ Thread-safe intermediate result handling
                            updateAsrStatus("识别中...");
                            if (response.has("text")) {
                                String text_partial = response.get("text").getAsString();
                                appendAsrSegment(text_partial, false); // false = partial result
                            }
                        } else if ("recognized".equals(status)) {
                            // 🛡️ Thread-safe final segment handling
                            updateAsrStatus("识别片段完成，等待更多内容...");
                            if (response.has("text")) {
                                String finalText = response.get("text").getAsString().trim();
                                if (!finalText.isEmpty()) {
                                    appendAsrSegment(finalText, true); // true = final result
                                } else {
                                    Log.w(TAG, "ASR识别片段结果为空");
                                }
                            } else {
                                Log.w(TAG, "ASR响应中没有text字段");
                            }
                            // Don't close connection - wait for speechStopped event
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "解析ASR响应失败", e);
                    if (!isAsrTerminating && !isActivityDestroying) {
                        updateAsrStatus("解析响应失败");
                        updateAsrResult("响应解析错误: " + e.getMessage());
                    }
                }
            }
            
            @Override
            public void onMessage(WebSocket webSocket, ByteString bytes) {
                // 🛡️ CRASH FIX 9: Safe handling of binary messages
                if (!isAsrTerminating && !isActivityDestroying) {
                    Log.d(TAG, "收到音频响应: " + bytes.size() + " bytes (忽略)");
                }
            }
            
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "ASR WebSocket关闭中: " + code + " " + reason);
                if (!isAsrTerminating && !isActivityDestroying) {
                    updateAsrStatus("连接关闭中...");
                }
            }
            
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.i(TAG, "ASR WebSocket已关闭: " + code + " " + reason);
                
                // 🛡️ CRASH FIX 10: Thread-safe cleanup on connection close
                synchronized (asrWebSocketLock) {
                    synchronized (asrTextLock) {
                        if (!asrSessionComplete && !isAsrTerminating && !isActivityDestroying) {
                            // Session ended unexpectedly, complete it safely
                            completeAsrSession();
                        }
                    }
                    
                    if (!isAsrTerminating && !isActivityDestroying) {
                        if (code == 1000) {
                            updateAsrStatus("识别完成");
                        } else {
                            updateAsrStatus("连接关闭");
                        }
                    }
                    
                    asrWebSocket = null;
                    isAsrProcessing = false;
                }
            }
            
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "ASR WebSocket连接失败", t);
                
                // 🛡️ CRASH FIX 11: Safe error handling
                synchronized (asrWebSocketLock) {
                    if (!isAsrTerminating && !isActivityDestroying) {
                        updateAsrStatus("连接失败: " + t.getMessage());
                        updateAsrResult("连接失败: " + t.getMessage());
                    }
                    asrWebSocket = null;
                    isAsrProcessing = false;
                }
            }
        };
        
        // 🛡️ CRASH FIX 12: Synchronized WebSocket creation
        synchronized (asrWebSocketLock) {
            if (!isAsrTerminating && !isActivityDestroying) {
                asrWebSocket = httpClient.newWebSocket(request, listener);
            } else {
                Log.i(TAG, "跳过WebSocket创建 - 应用正在终止");
                return;
            }
        }
        
        // Add timeout mechanism to prevent hanging
        new Thread(() -> {
            try {
                Thread.sleep(30000); // 30 second timeout
                synchronized (asrWebSocketLock) {
                    if (isAsrProcessing && asrWebSocket != null && !isAsrTerminating && !isActivityDestroying) {
                        Log.w(TAG, "ASR处理超时，强制关闭连接");
                        updateAsrStatus("超时: ASR处理时间过长");
                        updateAsrResult("处理超时: 服务器响应时间过长，请检查网络连接或重试");
                        asrWebSocket.close(1000, "Timeout");
                        asrWebSocket = null;
                        isAsrProcessing = false;
                    }
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "ASR超时线程被中断");
            }
        }).start();
    }
    
    /**
     * Send audio data in chunks to prevent early ASR termination
     * This allows the ASR service to process longer speech without stopping at sentence boundaries
     */
    private void sendAudioInChunks(WebSocket webSocket, byte[] pcmData) {
        new Thread(() -> {
            try {
                // Calculate chunk size for approximately 500ms of audio
                int sampleRate = ASR_SAMPLE_RATE; // 44100 Hz
                int bytesPerSample = 2; // 16-bit
                int chunkDurationMs = 500; // 500ms chunks
                int chunkSize = (sampleRate * bytesPerSample * chunkDurationMs) / 1000;
                
                // Make sure chunk size is even (for 16-bit samples)
                chunkSize = (chunkSize / 2) * 2;
                
                Log.i(TAG, String.format("开始分块发送音频: 总大小=%d bytes, 块大小=%d bytes, 块时长=%dms", 
                        pcmData.length, chunkSize, chunkDurationMs));
                
                int totalChunks = (pcmData.length + chunkSize - 1) / chunkSize;
                
                for (int i = 0; i < totalChunks; i++) {
                    // 🛡️ CRASH FIX 14: Check termination state in loop
                    synchronized (asrWebSocketLock) {
                        if (!isAsrProcessing || asrWebSocket == null || isAsrTerminating || isActivityDestroying) {
                            Log.w(TAG, "ASR处理已停止，终止音频发送");
                            break;
                        }
                    }
                    
                    int startOffset = i * chunkSize;
                    int endOffset = Math.min(startOffset + chunkSize, pcmData.length);
                    int currentChunkSize = endOffset - startOffset;
                    
                    byte[] chunk = new byte[currentChunkSize];
                    System.arraycopy(pcmData, startOffset, chunk, 0, currentChunkSize);
                    
                    // 🛡️ CRASH FIX 15: Safe WebSocket send with error handling
                    try {
                        webSocket.send(ByteString.of(chunk));
                        Log.d(TAG, String.format("发送音频块 %d/%d: %d bytes", i + 1, totalChunks, currentChunkSize));
                    } catch (Exception e) {
                        Log.e(TAG, "发送音频块失败: " + e.getMessage());
                        break;
                    }
                    
                    // Wait for chunk duration to simulate real-time audio streaming
                    if (i < totalChunks - 1) { // Don't sleep after the last chunk
                        try {
                            Thread.sleep(chunkDurationMs / 2); // Send faster than real-time but not too fast
                        } catch (InterruptedException e) {
                            Log.w(TAG, "音频发送睡眠被中断");
                            break;
                        }
                    }
                }
                
                // 🛡️ CRASH FIX 16: Final state check before stop command
                synchronized (asrWebSocketLock) {
                    if (isAsrProcessing && asrWebSocket != null && !isAsrTerminating && !isActivityDestroying) {
                        Log.i(TAG, "音频数据发送完成，等待2秒后发送停止命令");
                        
                        // Wait a bit more before sending stop command to ensure ASR processes all audio
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Log.w(TAG, "等待停止命令被中断");
                            return;
                        }
                        
                        // 🛡️ CRASH FIX 17: Safe stop command with final check
                        if (isAsrProcessing && asrWebSocket != null && !isAsrTerminating && !isActivityDestroying) {
                            try {
                                JsonObject stopCommand = new JsonObject();
                                stopCommand.addProperty("command", "stopSpeech");
                                String stopCommandJson = new Gson().toJson(stopCommand);
                                webSocket.send(stopCommandJson);
                                Log.i(TAG, "发送停止命令: " + stopCommandJson);
                            } catch (Exception e) {
                                Log.e(TAG, "发送停止命令失败: " + e.getMessage());
                            }
                        }
                    } else {
                        Log.i(TAG, "跳过停止命令 - ASR会话已终止");
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "音频分块发送失败", e);
            }
        }).start();
    }
    
    // ======= End of ASR Integration Methods =======
    
    @Override
    protected void onDestroy() {
        Log.i(TAG, "Activity正在销毁，开始清理资源");
        
        // Set destruction flag FIRST to prevent new operations
        isActivityDestroying = true;
        isAsrTerminating = true;
        
        super.onDestroy();
        
        stopRecording();
        stopMediaPlayer(); // Clean up MediaPlayer
        
        // 🛡️ Thread-safe ASR cleanup with timeout
        synchronized (asrWebSocketLock) {
            if (asrWebSocket != null) {
                try {
                    asrWebSocket.close(1000, "App destroyed");
                    Log.i(TAG, "ASR WebSocket已关闭");
                } catch (Exception e) {
                    Log.w(TAG, "关闭ASR WebSocket时出错: " + e.getMessage());
                }
                asrWebSocket = null;
            }
            isAsrProcessing = false;
        }
        
        // Wait briefly for WebSocket operations to complete
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Log.d(TAG, "资源清理等待被中断");
        }
        
        if (httpClient != null) {
            try {
                httpClient.dispatcher().executorService().shutdown();
                Log.i(TAG, "HTTP客户端已关闭");
            } catch (Exception e) {
                Log.w(TAG, "关闭HTTP客户端时出错: " + e.getMessage());
            }
        }
        
        // Safely clear ASR state
        synchronized (asrTextLock) {
            accumulatedAsrText.setLength(0);
            tempAsrBuffer.setLength(0);
            lastRecognizedSegment = "";
            asrSessionComplete = true;
        }
        
        // Clean up UI handler
        if (uiUpdateHandler != null) {
            uiUpdateHandler.removeCallbacksAndMessages(null);
        }
        
        if (audioTrack != null) {
            audioTrack.release();
        }
        
        if (audioRecord != null) {
            audioRecord.release();
        }
        
        if (audioThread != null) {
            audioThread.quitSafely();
        }
        
        Log.i(TAG, "所有资源已安全释放（包括ASR客户端）");
    }
}