package com.landan.client;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final String DEFAULT_WS_URL = "ws://172.16.45.117:8000/ws_detect";

    private PreviewView previewView;
    private ImageView resultImageView;
    private EditText serverUrlEdit;
    private TextView statusText;
    private TextView metricsText;
    private Button connectButton;
    private Button detectOnceButton;
    private Button continuousButton;

    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient okHttpClient;
    private WebSocket webSocket;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean waitingResult = new AtomicBoolean(false);
    private final AtomicBoolean continuousMode = new AtomicBoolean(false);

    private volatile String latestMetaText = "";
    private long lastSendTimeMs = 0L;
    private static final long CONTINUOUS_INTERVAL_MS = 700L;

    private final Runnable continuousRunnable = new Runnable() {
        @Override
        public void run() {
            if (continuousMode.get()) {
                long now = System.currentTimeMillis();
                if (connected.get() && !waitingResult.get() && now - lastSendTimeMs >= CONTINUOUS_INTERVAL_MS) {
                    captureAndSendFrame();
                }
                mainHandler.postDelayed(this, 120);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        cameraExecutor = Executors.newSingleThreadExecutor();
        okHttpClient = new OkHttpClient.Builder().build();

        buildUi();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("蓝丹检测手持端 WebSocket");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        serverUrlEdit = new EditText(this);
        serverUrlEdit.setSingleLine(true);
        serverUrlEdit.setText(DEFAULT_WS_URL);
        serverUrlEdit.setTextColor(0xFFFFFFFF);
        serverUrlEdit.setHintTextColor(0xFFAAAAAA);
        serverUrlEdit.setHint("ws://电脑IP:8000/ws_detect");
        serverUrlEdit.setTextSize(14);
        serverUrlEdit.setPadding(dp(10), dp(8), dp(10), dp(8));
        serverUrlEdit.setBackgroundColor(0xFF222222);
        root.addView(serverUrlEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        connectButton = makeButton("连接电脑端", 0xFF1677FF);
        root.addView(connectButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        connectButton.setOnClickListener(v -> toggleConnection());

        LinearLayout imageArea = new LinearLayout(this);
        imageArea.setOrientation(LinearLayout.VERTICAL);
        imageArea.setPadding(0, dp(8), 0, dp(8));
        root.addView(imageArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackgroundColor(0xFF222222);
        imageArea.addView(previewBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewBox.addView(previewView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        TextView previewLabel = new TextView(this);
        previewLabel.setText("实时预览");
        previewLabel.setTextColor(0xFFFFFFFF);
        previewLabel.setTextSize(13);
        previewLabel.setBackgroundColor(0x99000000);
        previewLabel.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams previewLabelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT
        );
        previewBox.addView(previewLabel, previewLabelParams);

        FrameLayout resultBox = new FrameLayout(this);
        resultBox.setBackgroundColor(0xFF222222);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        resultParams.topMargin = dp(8);
        imageArea.addView(resultBox, resultParams);

        resultImageView = new ImageView(this);
        resultImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImageView.setBackgroundColor(0xFF222222);
        resultBox.addView(resultImageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        TextView resultLabel = new TextView(this);
        resultLabel.setText("检测标注结果");
        resultLabel.setTextColor(0xFFFFFFFF);
        resultLabel.setTextSize(13);
        resultLabel.setBackgroundColor(0x99000000);
        resultLabel.setPadding(dp(6), dp(3), dp(6), dp(3));
        FrameLayout.LayoutParams resultLabelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT
        );
        resultBox.addView(resultLabel, resultLabelParams);

        statusText = new TextView(this);
        statusText.setText("未连接");
        statusText.setTextColor(0xFFFFAA00);
        statusText.setTextSize(22);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, dp(4), 0, dp(4));
        root.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        ScrollView scrollView = new ScrollView(this);
        metricsText = new TextView(this);
        metricsText.setText("请先连接电脑端服务");
        metricsText.setTextColor(0xFFDDDDDD);
        metricsText.setTextSize(14);
        metricsText.setPadding(dp(8), dp(6), dp(8), dp(6));
        metricsText.setBackgroundColor(0xFF1F1F1F);
        scrollView.addView(metricsText);
        root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(116)
        ));

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, dp(8), 0, 0);
        root.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        detectOnceButton = makeButton("检测当前画面", 0xFF00A870);
        LinearLayout.LayoutParams detectParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        buttonRow.addView(detectOnceButton, detectParams);
        detectOnceButton.setOnClickListener(v -> captureAndSendFrame());

        continuousButton = makeButton("连续检测", 0xFF444444);
        LinearLayout.LayoutParams continuousParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        continuousParams.leftMargin = dp(8);
        buttonRow.addView(continuousButton, continuousParams);
        continuousButton.setOnClickListener(v -> toggleContinuousMode());
    }

    private Button makeButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setBackgroundColor(color);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressWarnings("deprecation")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setJpegQuality(85)
                        .setTargetResolution(new Size(1280, 720))
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);

                setStatus("摄像头已启动，等待连接", 0xFFFFAA00);
            } catch (Exception e) {
                setStatus("摄像头启动失败", 0xFFFF4D4F);
                setMetrics(e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void toggleConnection() {
        if (connected.get()) {
            disconnectWebSocket();
        } else {
            connectWebSocket();
        }
    }

    private void connectWebSocket() {
        String url = serverUrlEdit.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入 WebSocket 地址", Toast.LENGTH_SHORT).show();
            return;
        }

        setStatus("正在连接...", 0xFF40A9FF);
        setMetrics("连接地址：" + url);

        Request request = new Request.Builder().url(url).build();
        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                connected.set(true);
                waitingResult.set(false);
                mainHandler.post(() -> {
                    connectButton.setText("断开连接");
                    setStatus("已连接", 0xFF00D26A);
                    setMetrics("已连接电脑端服务\n" + url + "\n点击“检测当前画面”开始检测。手机和电脑必须在同一局域网，电脑防火墙需放行 8000 端口。");
                });
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                latestMetaText = text;
                mainHandler.post(() -> handleMetaText(text));
            }

            @Override
            public void onMessage(@NonNull WebSocket ws, @NonNull ByteString bytes) {
                waitingResult.set(false);
                byte[] imageBytes = bytes.toByteArray();
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                mainHandler.post(() -> {
                    if (bitmap != null) {
                        resultImageView.setImageBitmap(bitmap);
                    }
                    handleFinishedFrame();
                });
            }

            @Override
            public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                connected.set(false);
                waitingResult.set(false);
                mainHandler.post(() -> {
                    connectButton.setText("连接电脑端");
                    setStatus("连接已关闭", 0xFFFFAA00);
                });
            }

            @Override
            public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, Response response) {
                connected.set(false);
                waitingResult.set(false);
                mainHandler.post(() -> {
                    connectButton.setText("连接电脑端");
                    setStatus("连接失败", 0xFFFF4D4F);
                    setMetrics("连接失败：" + t.getMessage() + "\n请检查：\n1. 电脑端 server.py 是否正在运行\n2. 地址是否为 ws://172.16.45.117:8000/ws_detect\n3. 手机和电脑是否在同一局域网\n4. Windows 防火墙是否放行 8000 端口");
                });
            }
        });
    }

    private void disconnectWebSocket() {
        continuousMode.set(false);
        continuousButton.setText("连续检测");
        continuousButton.setBackgroundColor(0xFF444444);

        if (webSocket != null) {
            webSocket.close(1000, "manual close");
            webSocket = null;
        }
        connected.set(false);
        waitingResult.set(false);
        connectButton.setText("连接电脑端");
        setStatus("未连接", 0xFFFFAA00);
        setMetrics("已断开连接");
    }

    private void captureAndSendFrame() {
        if (!connected.get() || webSocket == null) {
            setStatus("未连接", 0xFFFFAA00);
            setMetrics("请先连接电脑端服务");
            return;
        }

        if (imageCapture == null) {
            setStatus("摄像头未就绪", 0xFFFFAA00);
            return;
        }

        if (!waitingResult.compareAndSet(false, true)) {
            return;
        }

        lastSendTimeMs = System.currentTimeMillis();
        File photoFile = new File(getCacheDir(), "landan_frame_" + lastSendTimeMs + ".jpg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        mainHandler.post(() -> {
            setStatus("发送检测中...", 0xFF40A9FF);
            setMetrics("正在抓取当前画面并发送到电脑端...");
        });

        imageCapture.takePicture(options, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                try {
                    byte[] data = Files.readAllBytes(photoFile.toPath());
                    boolean sent = webSocket != null && webSocket.send(ByteString.of(data));
                    if (!sent) {
                        waitingResult.set(false);
                        mainHandler.post(() -> {
                            setStatus("发送失败", 0xFFFF4D4F);
                            setMetrics("WebSocket 发送失败，请重新连接");
                        });
                    }
                } catch (IOException e) {
                    waitingResult.set(false);
                    mainHandler.post(() -> {
                        setStatus("读取图片失败", 0xFFFF4D4F);
                        setMetrics(e.getMessage());
                    });
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    photoFile.delete();
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                waitingResult.set(false);
                mainHandler.post(() -> {
                    setStatus("拍照失败", 0xFFFF4D4F);
                    setMetrics(exception.getMessage());
                });
            }
        });
    }

    private void handleMetaText(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            boolean ok = obj.optBoolean("ok", true);
            if (!ok) {
                waitingResult.set(false);
                setStatus("检测失败", 0xFFFF4D4F);
                setMetrics(obj.optString("error", text));
                return;
            }

            String status = obj.optString("status", "UNKNOWN");
            double inferTime = obj.optDouble("infer_time_ms", 0.0);
            JSONObject m = obj.optJSONObject("metrics");

            StringBuilder sb = new StringBuilder();
            sb.append("电脑推理耗时：").append(String.format(Locale.US, "%.2f", inferTime)).append(" ms\n");

            if (m != null) {
                sb.append("蓝点数量：").append(m.optInt("blue_count", 0)).append("\n");
                sb.append("异常数量：").append(m.optInt("abnormal_count", 0)).append("\n");
                sb.append("坏窗口：")
                        .append(m.optInt("bad_window_count", 0))
                        .append("/")
                        .append(m.optInt("total_window_count", 0))
                        .append("\n");
                sb.append("面积比例：").append(formatDouble(m.optDouble("area_ratio", 0.0), 4)).append("\n");
                sb.append("均匀性：").append(formatDouble(m.optDouble("uniformity", 0.0), 4)).append("\n");
                sb.append("坏窗口比例：").append(formatDouble(m.optDouble("bad_window_ratio", 0.0), 4)).append("\n");
                sb.append("平均长边：").append(formatDouble(m.optDouble("mean_length", 0.0), 2)).append("\n");
                sb.append("平均短边：").append(formatDouble(m.optDouble("mean_width", 0.0), 2));
            } else {
                sb.append(text);
            }

            int color = "OK".equalsIgnoreCase(status) ? 0xFF00D26A : 0xFFFF4D4F;
            setStatus("等待标注图：" + status, color);
            setMetrics(sb.toString());

        } catch (Exception e) {
            setMetrics(text);
        }
    }

    private void handleFinishedFrame() {
        try {
            JSONObject obj = new JSONObject(latestMetaText);
            String status = obj.optString("status", "UNKNOWN");
            int color = "OK".equalsIgnoreCase(status) ? 0xFF00D26A : 0xFFFF4D4F;
            setStatus(status, color);
        } catch (Exception e) {
            setStatus("收到标注图", 0xFF00D26A);
        }
    }

    private String formatDouble(double value, int digits) {
        return String.format(Locale.US, "%." + digits + "f", value);
    }

    private void toggleContinuousMode() {
        if (!connected.get()) {
            setStatus("未连接", 0xFFFFAA00);
            setMetrics("请先连接电脑端服务");
            return;
        }

        boolean newValue = !continuousMode.get();
        continuousMode.set(newValue);

        if (newValue) {
            continuousButton.setText("停止连续检测");
            continuousButton.setBackgroundColor(0xFFB42318);
            setMetrics("已开启低帧率连续检测。默认约每 700ms 尝试发送 1 帧；上一帧未返回时会自动跳过，避免电脑端排队。");
            mainHandler.post(continuousRunnable);
        } else {
            continuousButton.setText("连续检测");
            continuousButton.setBackgroundColor(0xFF444444);
            setMetrics("已停止连续检测");
        }
    }

    private void setStatus(String text, int color) {
        statusText.setText(text);
        statusText.setTextColor(color);
    }

    private void setMetrics(String text) {
        metricsText.setText(text == null ? "" : text);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                setStatus("无摄像头权限", 0xFFFF4D4F);
                setMetrics("请在系统设置里允许本应用使用摄像头");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        continuousMode.set(false);
        if (webSocket != null) {
            webSocket.close(1000, "activity destroy");
            webSocket = null;
        }
        if (okHttpClient != null) {
            okHttpClient.dispatcher().executorService().shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }
}
