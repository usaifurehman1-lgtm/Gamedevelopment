import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private P2PMeshNode meshNode;
    private TextView tvChatLog;
    private EditText etMessage, etTargetId, etIpAddress;
    private Button btnRecordVoice, btnSendImage, btnSendVideo, btnStartVideoCall, btnStopVideoCall;
    private ImageView ivReceivedImage, ivVideoStream;

    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private boolean isVideoCalling = false;
    private Handler videoHandler = new Handler(Looper.getMainLooper());
    private Runnable videoStreamRunnable;

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int PICK_VIDEO_REQUEST = 2;
    private static final int REQUEST_CODE_PERMISSIONS = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvChatLog = findViewById(R.id.tvChatLog);
        etMessage = findViewById(R.id.etMessage);
        etTargetId = findViewById(R.id.etTargetId);
        etIpAddress = findViewById(R.id.etIpAddress);
        btnRecordVoice = findViewById(R.id.btnRecordVoice);
        btnSendImage = findViewById(R.id.btnSendImage);
        btnSendVideo = findViewById(R.id.btnSendVideo);
        btnStartVideoCall = findViewById(R.id.btnStartVideoCall); // ویڈیو کال شروع کرنے کا بٹن
        btnStopVideoCall = findViewById(R.id.btnStopVideoCall);   // ویڈیو کال بند کرنے کا بٹن
        ivReceivedImage = findViewById(R.id.ivReceivedImage);
        ivVideoStream = findViewById(R.id.ivVideoStream);         // لائیو ویڈیو دیکھنے کے لیے ImageView

        checkPermissions();

        audioFilePath = getExternalCacheDir().getAbsolutePath() + "/audio_record.3gp";
        String myUniqueId = "Phone_A"; // اپنے حساب سے تبدیل کریں

        // 1. MeshNode لسنر جو ٹیکسٹ، وائس، تصویر، ویڈیو اور لائیو ویڈیو فریمز کو ہینڈل کرے گا
        meshNode = new P2PMeshNode(myUniqueId, (senderId, messageText) -> {
            runOnUiThread(() -> {
                if (messageText.startsWith("AUDIO_BASE64:")) {
                    String base64Audio = messageText.replace("AUDIO_BASE64:", "");
                    tvChatLog.append("\n" + senderId + ": [Voice Message Received]");
                    playReceivedAudio(base64Audio);
                } else if (messageText.startsWith("IMAGE_BASE64:")) {
                    String base64Image = messageText.replace("IMAGE_BASE64:", "");
                    tvChatLog.append("\n" + senderId + ": [Image Received]");
                    displayReceivedImage(base64Image);
                } else if (messageText.startsWith("VIDEO_BASE64:")) {
                    String base64Video = messageText.replace("VIDEO_BASE64:", "");
                    tvChatLog.append("\n" + senderId + ": [Video Received]");
                    saveAndPlayReceivedVideo(base64Video);
                } else if (messageText.startsWith("VIDEO_FRAME:")) {
                    // لائیو ویڈیو کال کا فریم موصول ہونا
                    String base64Frame = messageText.replace("VIDEO_FRAME:", "");
                    displayLiveVideoFrame(base64Frame);
                } else {
                    tvChatLog.append("\n" + senderId + ": " + messageText);
                }
            });
        });

        // 2. سرور شروع کریں
        Button btnStartServer = findViewById(R.id.btnStartServer);
        btnStartServer.setOnClickListener(v -> {
            meshNode.startServer();
            tvChatLog.append("\nServer started successfully...");
        });

        // 3. کلائنٹ کنکشن
        Button btnConnect = findViewById(R.id.btnConnect);
        btnConnect.setOnClickListener(v -> {
            String ip = etIpAddress.getText().toString();
            meshNode.connectToPeer(ip);
            tvChatLog.append("\nConnecting to " + ip + "...");
        });

        // 4. ٹیکسٹ میسج بھیجیں
        Button btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener(v -> {
            String targetId = etTargetId.getText().toString();
            String msg = etMessage.getText().toString();
            meshNode.sendMessage(targetId, msg);
            tvChatLog.append("\nMe (to " + targetId + "): " + msg);
            etMessage.setText("");
        });

        // 5. وائس ریکارڈنگ اور سینڈنگ
        btnRecordVoice.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
                btnRecordVoice.setText("Stop & Send Voice");
            } else {
                stopRecordingAndSend();
                btnRecordVoice.setText("Record Voice");
            }
        });

        // 6. گیلری سے تصویر بھیجنا
        btnSendImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        });

        // 7. گیلری سے ویڈیو بھیجنا
        btnSendVideo.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
            startActivityForResult(intent, PICK_VIDEO_REQUEST);
        });

        // 8. لائیو ویڈیو کال شروع کرنا
        btnStartVideoCall.setOnClickListener(v -> {
            if (!isVideoCalling) {
                isVideoCalling = true;
                Toast.makeText(this, "Video Call Started", Toast.LENGTH_SHORT).show();
                startVideoStreaming();
            }
        });

        // 9. لائیو ویڈیو کال بند کرنا
        btnStopVideoCall.setOnClickListener(v -> {
            if (isVideoCalling) {
                isVideoCalling = false;
                videoHandler.removeCallbacks(videoStreamRunnable);
                Toast.makeText(this, "Video Call Stopped", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_CODE_PERMISSIONS);
        }
    }

    // --- لائیو ویڈیو کال / اسٹریمنگ کے فنکشنز ---
    private void startVideoStreaming() {
        // نوٹس: یہ سیمپل فرنٹ/ব্যাক کیمرے کے سکرین شاٹس یا فریمز کو لوپ میں بھیجتا ہے
        videoStreamRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isVideoCalling) return;

                // کیمرے یا ویو سے بٹ میپ حاصل کرنے کا عمل (یہاں آپ کیمرہ ویو یا سکرین کا بٹ میپ لے سکتے ہیں)
                // بطورِ مثال ہم ایک ویو کا کیش کیپچر یا لوکل فریم استعمال کر رہے ہیں
                if (ivReceivedImage.getDrawable() != null) {
                    ivReceivedImage.setDrawingCacheEnabled(true);
                    Bitmap bitmap = Bitmap.createBitmap(ivReceivedImage.getDrawingCache());
                    ivReceivedImage.setDrawingCacheEnabled(false);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 30, baos); // فریم سائز کم رکھنے کے لیے کوالٹی 30 رکھی ہے
                    byte[] frameBytes = baos.toByteArray();

                    String base64Frame = "VIDEO_FRAME:" + Base64.encodeToString(frameBytes, Base64.DEFAULT);
                    String targetId = etTargetId.getText().toString();
                    
                    if (!targetId.isEmpty()) {
                        meshNode.sendMessage(targetId, base64Frame);
                    }
                }

                // ہر 200 ملی سیکنڈ بعد اگلا فریم بھیجو (تقریباً 5 فریمز فی سیکنڈ)
                videoHandler.postDelayed(this, 200);
            }
        };
        videoHandler.post(videoStreamRunnable);
    }

    private void displayLiveVideoFrame(String base64Frame) {
        try {
            byte[] frameBytes = Base64.decode(base64Frame, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.length);
            if (ivVideoStream != null) {
                ivVideoStream.setImageBitmap(bitmap); // موصول ہونے والی لائیو ویڈیو اسکرین پر شو ہوگی
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- آڈیو کے فنکشنز ---
    private void startRecording() {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mediaRecorder.setOutputFile(audioFilePath);
        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopRecordingAndSend() {
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;

            File audioFile = new File(audioFilePath);
            byte[] audioBytes = new byte[(int) audioFile.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(audioFile);
            fis.read(audioBytes);
            fis.close();

            String base64Audio = "AUDIO_BASE64:" + Base64.encodeToString(audioBytes, Base64.DEFAULT);
            String targetId = etTargetId.getText().toString();
            meshNode.sendMessage(targetId, base64Audio);
            tvChatLog.append("\nMe (to " + targetId + "): [Voice Message Sent]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void playReceivedAudio(String base64Audio) {
        try {
            byte[] audioBytes = Base64.decode(base64Audio, Base64.DEFAULT);
            File tempFile = new File(getExternalCacheDir(), "received_audio.3gp");
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(audioBytes);
            fos.close();

            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // --- فائل سلیکشن (تصویر اور ویڈیو) کے نتائج ہینڈل کرنا ---
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            String targetId = etTargetId.getText().toString();

            if (requestCode == PICK_IMAGE_REQUEST) {
                sendImageBytes(uri, targetId);
            } else if (requestCode == PICK_VIDEO_REQUEST) {
                sendVideoBytes(uri, targetId);
            }
        }
    }

    // --- تصویر بھیجنے کا طریقہ ---
    private void sendImageBytes(Uri uri, String targetId) {
        try {
            Bitmap bitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), uri);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
            byte[] imageBytes = baos.toByteArray();

            String base64Image = "IMAGE_BASE64:" + Base64.encodeToString(imageBytes, Base64.DEFAULT);
            meshNode.sendMessage(targetId, base64Image);
            tvChatLog.append("\nMe (to " + targetId + "): [Image Sent]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void displayReceivedImage(String base64Image) {
        byte[] imageBytes = Base64.decode(base64Image, Base64.DEFAULT);
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (ivReceivedImage != null) {
            ivReceivedImage.setImageBitmap(bitmap);
        }
    }

    // --- ویڈیو بھیجنے کا طریقہ ---
    private void sendVideoBytes(Uri uri, String targetId) {
        try {
            java.io.InputStream iStream = getContentResolver().openInputStream(uri);
            byte[] videoBytes = getBytes(iStream);
            iStream.close();

            String base64Video = "VIDEO_BASE64:" + Base64.encodeToString(videoBytes, Base64.DEFAULT);
            meshNode.sendMessage(targetId, base64Video);
            tvChatLog.append("\nMe (to " + targetId + "): [Video Sent]");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private byte[] getBytes(java.io.InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    private void saveAndPlayReceivedVideo(String base64Video) {
        try {
            byte[] videoBytes = Base64.decode(base64Video, Base64.DEFAULT);
            File tempFile = new File(getExternalCacheDir(), "received_video.mp4");
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(videoBytes);
            fos.close();

            Toast.makeText(this, "Video saved in cache! Opening...", Toast.LENGTH_SHORT).show();
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(tempFile), "video/*");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);

        } catch/ (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isVideoCalling) {
            isVideoCalling = false;
            videoHandler.removeCallbacks(videoStreamRunnable);
        }
        if (meshNode != null) {
            meshNode.stopNode();
        }
    }
}
