package com.winlab.replay;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.winlab.replay.utils.CallBackUtil;
import com.winlab.replay.utils.OkhttpUtil;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.Call;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "MainActivity";

    private String HOST = "192.168.0.167:5000";
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private StringBuffer sensorLog = new StringBuffer();

    private MediaPlayer mMediaPlayer = null;
    private int samplingPeriodUs = 10000; // 100Hz

    private Switch replaySwitch = null;
    private TextView textViewNowPlaying = null;
    private EditText editTextHost = null;
    private EditText editTextSampleRate = null;

    private boolean isPlay = false;
    private Boolean interrupt = false;
    private String playingAudio = "None";


    private List<String> playList = null;
    private int currentIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        replaySwitch = findViewById(R.id.switch_replay);
        replaySwitch.setOnCheckedChangeListener(swicthListener);
        textViewNowPlaying = findViewById(R.id.textViewNowPlay);
        editTextHost = findViewById(R.id.editTextHost);
        editTextSampleRate = findViewById(R.id.editTextSampleRate);
    }

    private void updateParameters(){
        HOST = editTextHost.getText().toString();
        int sampleRate = Integer.parseInt(editTextSampleRate.getText().toString());
        samplingPeriodUs = 1000000/sampleRate;
    }

    private CompoundButton.OnCheckedChangeListener swicthListener= new CompoundButton.OnCheckedChangeListener(){

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if(isChecked){
                updateParameters();
                loadPlayList();
                interrupt = false;
            }else{
                interrupt = true;
            }
        }
    };

    Runnable runnable = new Runnable(){

        @Override
        public void run() {
//                while(!playQueue.isEmpty()){
                for(currentIndex = 0; currentIndex < playList.size(); ){
                    Log.d(LOG_TAG, "current"+isPlay+"," + interrupt);
                    String audio = playList.get(currentIndex);
                    playingAudio = audio;
                    if(!isPlay){
                        // update UI
                        MainActivity.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                textViewNowPlaying.setText(playingAudio);
                            }
                        });
                        isPlay = true;
//                        startPlaying("http://" + HOST + "/server_audio/"+ audio);
                        downloadAndPlay(audio);
                        currentIndex++;
                    }
                    Log.d(LOG_TAG,"block here");
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if(interrupt){
                        break;
                    }
                }
                Log.d(LOG_TAG, "end here");
        }

    };

    private void startPlaying(String path){
        mMediaPlayer = new MediaPlayer();
        try {
            Log.d(LOG_TAG, "play start" + path);
            // listen sensor
            sensorManager.registerListener(sensorEventListener, accelerometer, samplingPeriodUs);
            // play audio
            mMediaPlayer.setDataSource(path);
            mMediaPlayer.prepare();
//            mMediaPlayer.prepareAsync();
            mMediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {

                    mMediaPlayer.start();
                }
            });

        } catch (IOException e) {
            Log.e(LOG_TAG, "prepare() failed");
        }

        mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                stopPlaying();
            }
        });
    }

    private void stopPlaying() {
        textViewNowPlaying.setText("None");
        Log.d(LOG_TAG ,"end play");
        // stop listen
        sensorManager.unregisterListener(sensorEventListener);

        // upload accelerometer data

        HashMap<String, String> parameters = new HashMap<>();
        parameters.put("machine", "va");
        parameters.put("name", playingAudio);
        parameters.put("acclog", sensorLog.toString());
        sensorLog = new StringBuffer();
        OkhttpUtil.okHttpPost("http://"+HOST+"/acclog", parameters, new CallBackUtil.CallBackString() {
            @Override
            public void onFailure(Call call, Exception e) {
                Log.e(LOG_TAG, "fail to upload log");
            }

            @Override
            public void onResponse(String response) {
                Log.e(LOG_TAG, "success to upload log");
            }
        });
        mMediaPlayer.stop();
        mMediaPlayer.reset();
        mMediaPlayer.release();
        mMediaPlayer = null;
        isPlay = false;
        if (currentIndex >= playList.size()){
            replaySwitch.setChecked(false);
        }
    }

    // sensor listener
    private SensorEventListener sensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {
            try {
                switch(event.sensor.getType()) {
                    case Sensor.TYPE_ACCELEROMETER:
                        String str = String.format("%d,%f,%f,%f\n",
                                event.timestamp,
                                event.values[0],
                                event.values[1],
                                event.values[2]);
                        sensorLog.append(str);
//                        Log.d(LOG_TAG, str);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };

    private void loadPlayList(){
        playList = new ArrayList();

        String link = "http://"+HOST+"/playlist";
        Log.e(LOG_TAG, "link:" + link);
        OkhttpUtil.okHttpGet(link, new CallBackUtil.CallBackString() {
            @Override
            public void onFailure(Call call, Exception e) {
                Toast.makeText(getApplicationContext(), "Fail to connect Server, Pls set server host", Toast.LENGTH_LONG).show();
                Log.e(LOG_TAG, "fail to load audio from server");
            }

            @Override
            public void onResponse(String response) {
                Log.e(LOG_TAG, "success to get audio list");
                try {
                    JSONArray jsonArray = new JSONArray(response);
                    for(int i = 0; i < jsonArray.length(); i++){
                        String audio = jsonArray.getString(i);
                        playList.add(audio);
                        Log.d(LOG_TAG, audio);
                    }
                    Thread syncThread = new Thread(runnable);
                    syncThread.start();

                }catch (JSONException e){
                    e.printStackTrace();
                }
            }
        });
    }
    private String downloadAndPlay(String fileName){
        String url = "http://"+HOST+"/server_audio/" + fileName;
        String mFilePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        mFilePath += "/SoundRecorder/";
        final String mDestFileDir = mFilePath;
        final String mdestFileName = fileName;
        String path = mFilePath + fileName;

        OkhttpUtil.okHttpDownloadFile(url, new CallBackUtil.CallBackFile(mDestFileDir, mdestFileName) {
            @Override
            public void onFailure(Call call, Exception e) {

            }

            @Override
            public void onResponse(File response) {
                Log.d(LOG_TAG, "Download File success");
                startPlaying(mDestFileDir+mdestFileName);
            }
        });

        return path;
    }
}