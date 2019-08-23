package com.example.detectnoise;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.TarsosDSPAudioFormat;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;
import be.tarsos.dsp.pitch.PitchDetectionHandler;
import be.tarsos.dsp.pitch.PitchDetectionResult;
import be.tarsos.dsp.pitch.PitchProcessor;
import be.tarsos.dsp.writer.WriterProcessor;

public class MainActivity extends AppCompatActivity {

    /*1번 기능 동작 관련 변수 정의부*/
    private static final int POLL_INTERVAL = 1000;
    public static int noise_sum = 0;
    public static int cnt = -1;

    private int mThreshold;
    private int RECORD_AUDIO = 0;
    private boolean mRunning = false;

    private DetectNoise mSensor;
    private Handler mHandler = new Handler();
    private PowerManager.WakeLock mWakeLock;


    /*RecordPlay 관련 변수 정의부*/
    private AudioDispatcher dispatcher;
    private TarsosDSPAudioFormat tarsosDSPAudioFormat;

    private File file;
    private boolean isRecording = false;
    private String filename = "recorded_sound.wav";


    /*UI 요소 정의부*/
    private ProgressBar bar;
    private TextView mStatusView, tv_noice, noise_avg, noise_pitch, pitchTextView;
    private Button recordButton;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bar = (ProgressBar)findViewById(R.id.progressBar1);

        tv_noice = (TextView)findViewById(R.id.tv_noice);
        mStatusView = (TextView)findViewById(R.id.status);
        noise_avg = (TextView)findViewById(R.id.noise_avg);
        noise_pitch = (TextView)findViewById(R.id.noise_pitch);
        pitchTextView = (TextView)findViewById(R.id.pitchTextView);

        recordButton = (Button)findViewById(R.id.recordButton);

        File sdCard = Environment.getExternalStorageDirectory();
        file = new File(sdCard, filename);

        tarsosDSPAudioFormat=new TarsosDSPAudioFormat(TarsosDSPAudioFormat.Encoding.PCM_SIGNED,
                22050,
                2 * 8,
                1,
                2 * 1,
                22050,
                ByteOrder.BIG_ENDIAN.equals(ByteOrder.nativeOrder()));

        mSensor = new DetectNoise();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "NoiseAlert:tag");

        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(!isRecording)
                {
                    start();
                    recordAudio();
                    isRecording = true;
                    recordButton.setText("STOP");
                }
                else
                {
                    stop();
                    stopRecording();
                    isRecording = false;
                    recordButton.setText("START");
                }
            }
        });
    }


    @Override
    public void onStop() {
        super.onStop();
        releaseDispatcher();
    }

    @SuppressLint("WakelockTimeout")
    private void start() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                    RECORD_AUDIO);
        }

        mSensor.start();
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
        mHandler.postDelayed(mPollTask, POLL_INTERVAL);
    }

    private void stop() {
        Log.d("Noise", "==== Stop Noise Monitoring===");
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        mHandler.removeCallbacks(mSleepTask);
        mHandler.removeCallbacks(mPollTask);
        mSensor.stop();
        bar.setProgress(0);
        updateDisplay("stopped...", 0.0);
        mRunning = false;
    }

    public void updateDisplay(String status, double signalEMA) {
        mStatusView.setText(status);
        bar.setProgress((int) signalEMA);
        tv_noice.setText(((int) signalEMA + "dB"));

        cnt++;
        if (signalEMA <= -2147483648) noise_sum -= (-2147483648); // why is that value detected?

        noise_sum += (int) signalEMA;
        double avg = noise_sum / (double) cnt;
        noise_avg.setText((noise_sum + "dB, " + avg + "dB"));

        if (signalEMA > avg) {
            Toast.makeText(getApplicationContext(), "Over", Toast.LENGTH_SHORT).show();
        }
    }

    private void callForHelp(double signalEMA) {
        Toast.makeText(getApplicationContext(), "Noise Thersold Crossed, do here your stuff.",
                Toast.LENGTH_LONG).show();
        Log.d("SONUND", String.valueOf(signalEMA));
        tv_noice.setText((signalEMA + "dB"));
    }

    private Runnable mSleepTask = new Runnable() {
        public void run() {
            start();
        }
    };

    private Runnable mPollTask = new Runnable() {
        public void run() {
            double amp = mSensor.getAmplitude();
            updateDisplay("Monitoring Voice...", amp);
            if ((amp > mThreshold)) {
                callForHelp(amp);
            }
            mHandler.postDelayed(mPollTask, POLL_INTERVAL);
        }
    };

    public void recordAudio()
    {
        releaseDispatcher();
        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(22050,1024,0);

        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file,"rw");
            AudioProcessor recordProcessor = new WriterProcessor(tarsosDSPAudioFormat, randomAccessFile);
            dispatcher.addAudioProcessor(recordProcessor);

            PitchDetectionHandler pitchDetectionHandler = new PitchDetectionHandler() {
                @Override
                public void handlePitch(PitchDetectionResult res, AudioEvent e){
                    final float pitchInHz = res.getPitch();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            noise_pitch.setText(pitchInHz + "");
                        }
                    });
                }
            };

            AudioProcessor pitchProcessor = new PitchProcessor(PitchProcessor.PitchEstimationAlgorithm.FFT_YIN, 22050, 1024, pitchDetectionHandler);
            dispatcher.addAudioProcessor(pitchProcessor);

            Thread audioThread = new Thread(dispatcher, "Audio Thread");
            audioThread.start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopRecording()
    {
        releaseDispatcher();
    }

    public void releaseDispatcher()
    {
        if(dispatcher != null)
        {
            if(!dispatcher.isStopped())
                dispatcher.stop();
            dispatcher = null;
        }
    }
}
