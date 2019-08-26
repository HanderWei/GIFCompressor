package com.otaliastudios.gif.demo;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.otaliastudios.gif.GIFCompressor;
import com.otaliastudios.gif.GIFListener;
import com.otaliastudios.gif.GIFOptions;
import com.otaliastudios.gif.engine.TrackType;
import com.otaliastudios.gif.internal.Logger;
import com.otaliastudios.gif.sink.DataSink;
import com.otaliastudios.gif.sink.DefaultDataSink;
import com.otaliastudios.gif.strategy.DefaultAudioStrategy;
import com.otaliastudios.gif.strategy.DefaultVideoStrategy;
import com.otaliastudios.gif.strategy.RemoveTrackStrategy;
import com.otaliastudios.gif.strategy.TrackStrategy;
import com.otaliastudios.gif.strategy.size.AspectRatioResizer;
import com.otaliastudios.gif.strategy.size.FractionResizer;
import com.otaliastudios.gif.strategy.size.PassThroughResizer;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Future;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;


public class GIFActivity extends AppCompatActivity implements
        GIFListener,
        RadioGroup.OnCheckedChangeListener {

    private static final String TAG = "DemoApp";
    private static final Logger LOG = new Logger(TAG);

    private static final String FILE_PROVIDER_AUTHORITY = "com.otaliastudios.gif.demo.fileprovider";
    private static final int REQUEST_CODE_PICK = 1;
    private static final int REQUEST_CODE_PICK_AUDIO = 5;
    private static final int PROGRESS_BAR_MAX = 1000;

    private RadioGroup mAudioChannelsGroup;
    private RadioGroup mAudioSampleRateGroup;
    private RadioGroup mVideoFramesGroup;
    private RadioGroup mVideoResolutionGroup;
    private RadioGroup mVideoAspectGroup;
    private RadioGroup mVideoRotationGroup;
    private RadioGroup mSpeedGroup;
    private RadioGroup mAudioReplaceGroup;

    private ProgressBar mProgressView;
    private TextView mButtonView;
    private TextView mAudioReplaceView;

    private boolean mIsTranscoding;
    private boolean mIsAudioOnly;
    private Future<Void> mTranscodeFuture;
    private Uri mTranscodeInputUri1;
    private Uri mTranscodeInputUri2;
    private Uri mTranscodeInputUri3;
    private Uri mAudioReplacementUri;
    private File mTranscodeOutputFile;
    private long mTranscodeStartTime;
    private TrackStrategy mTranscodeVideoStrategy;
    private TrackStrategy mTranscodeAudioStrategy;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.setLogLevel(Logger.LEVEL_VERBOSE);
        setContentView(R.layout.activity_transcoder);

        mButtonView = findViewById(R.id.button);
        mButtonView.setOnClickListener(v -> {
            if (!mIsTranscoding) {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                        .setType("video/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQUEST_CODE_PICK);
            } else {
                mTranscodeFuture.cancel(true);
            }
        });
        setIsTranscoding(false);

        mProgressView = findViewById(R.id.progress);
        mProgressView.setMax(PROGRESS_BAR_MAX);

        mAudioReplaceView = findViewById(R.id.replace_info);

        mAudioChannelsGroup = findViewById(R.id.channels);
        mVideoFramesGroup = findViewById(R.id.frames);
        mVideoResolutionGroup = findViewById(R.id.resolution);
        mVideoAspectGroup = findViewById(R.id.aspect);
        mVideoRotationGroup = findViewById(R.id.rotation);
        mSpeedGroup = findViewById(R.id.speed);
        mAudioSampleRateGroup = findViewById(R.id.sampleRate);
        mAudioReplaceGroup = findViewById(R.id.replace);

        mAudioChannelsGroup.setOnCheckedChangeListener(this);
        mVideoFramesGroup.setOnCheckedChangeListener(this);
        mVideoResolutionGroup.setOnCheckedChangeListener(this);
        mVideoAspectGroup.setOnCheckedChangeListener(this);
        mAudioSampleRateGroup.setOnCheckedChangeListener(this);
        syncParameters();

        mAudioReplaceGroup.setOnCheckedChangeListener((group, checkedId) -> {
            mAudioReplacementUri = null;
            mAudioReplaceView.setText("No replacement selected.");
            if (checkedId == R.id.replace_yes) {
                if (!mIsTranscoding) {
                    startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT)
                            .setType("audio/*"), REQUEST_CODE_PICK_AUDIO);
                }
            }
            onCheckedChanged(group, checkedId);
        });
    }

    @Override
    public void onCheckedChanged(RadioGroup group, int checkedId) {
        syncParameters();
    }

    private void syncParameters() {
        int channels;
        switch (mAudioChannelsGroup.getCheckedRadioButtonId()) {
            case R.id.channels_mono: channels = 1; break;
            case R.id.channels_stereo: channels = 2; break;
            default: channels = DefaultAudioStrategy.CHANNELS_AS_INPUT;
        }
        int sampleRate;
        switch (mAudioSampleRateGroup.getCheckedRadioButtonId()) {
            case R.id.sampleRate_32: sampleRate = 32000; break;
            case R.id.sampleRate_48: sampleRate = 48000; break;
            default: sampleRate = DefaultAudioStrategy.SAMPLE_RATE_AS_INPUT;
        }
        boolean removeAudio;
        switch (mAudioReplaceGroup.getCheckedRadioButtonId()) {
            case R.id.replace_remove: removeAudio = true; break;
            case R.id.replace_yes: removeAudio = false; break;
            default: removeAudio = false;
        }
        if (removeAudio) {
            mTranscodeAudioStrategy = new RemoveTrackStrategy();
        } else {
            mTranscodeAudioStrategy = DefaultAudioStrategy.builder()
                    .channels(channels)
                    .sampleRate(sampleRate)
                    .build();
        }

        int frames;
        switch (mVideoFramesGroup.getCheckedRadioButtonId()) {
            case R.id.frames_24: frames = 24; break;
            case R.id.frames_30: frames = 30; break;
            case R.id.frames_60: frames = 60; break;
            default: frames = DefaultVideoStrategy.DEFAULT_FRAME_RATE;
        }
        float fraction;
        switch (mVideoResolutionGroup.getCheckedRadioButtonId()) {
            case R.id.resolution_half: fraction = 0.5F; break;
            case R.id.resolution_third: fraction = 1F / 3F; break;
            default: fraction = 1F;
        }
        float aspectRatio;
        switch (mVideoAspectGroup.getCheckedRadioButtonId()) {
            case R.id.aspect_169: aspectRatio = 16F / 9F; break;
            case R.id.aspect_43: aspectRatio = 4F / 3F; break;
            case R.id.aspect_square: aspectRatio = 1F; break;
            default: aspectRatio = 0F;
        }
        mTranscodeVideoStrategy = new DefaultVideoStrategy.Builder()
                .addResizer(aspectRatio > 0 ? new AspectRatioResizer(aspectRatio) : new PassThroughResizer())
                .addResizer(new FractionResizer(fraction))
                .frameRate(frames)
                .build();
    }

    private void setIsTranscoding(boolean isTranscoding) {
        mIsTranscoding = isTranscoding;
        mButtonView.setText(mIsTranscoding ? "Cancel Transcoding" : "Select Video & Transcode");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK
                && resultCode == RESULT_OK
                && data != null) {
            if (data.getData() != null) {
                mTranscodeInputUri1 = data.getData();
                mTranscodeInputUri2 = null;
                mTranscodeInputUri3 = null;
                transcode();
            } else if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                mTranscodeInputUri1 = clipData.getItemAt(0).getUri();
                mTranscodeInputUri2 = clipData.getItemCount() >= 2 ? clipData.getItemAt(1).getUri() : null;
                mTranscodeInputUri3 = clipData.getItemCount() >= 3 ? clipData.getItemAt(2).getUri() : null;
                transcode();
            }
        }
        if (requestCode == REQUEST_CODE_PICK_AUDIO
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            mAudioReplacementUri = data.getData();
            mAudioReplaceView.setText(mAudioReplacementUri.toString());

        }
    }

    private void transcode() {
        // Create a temporary file for output.
        try {
            File outputDir = new File(getExternalFilesDir(null), "outputs");
            //noinspection ResultOfMethodCallIgnored
            outputDir.mkdir();
            mTranscodeOutputFile = File.createTempFile("transcode_test", ".mp4", outputDir);
            LOG.i("Transcoding into " + mTranscodeOutputFile);
        } catch (IOException e) {
            LOG.e("Failed to create temporary file.", e);
            Toast.makeText(this, "Failed to create temporary file.", Toast.LENGTH_LONG).show();
            return;
        }

        int rotation;
        switch (mVideoRotationGroup.getCheckedRadioButtonId()) {
            case R.id.rotation_90: rotation = 90; break;
            case R.id.rotation_180: rotation = 180; break;
            case R.id.rotation_270: rotation = 270; break;
            default: rotation = 0;
        }

        float speed;
        switch (mSpeedGroup.getCheckedRadioButtonId()) {
            case R.id.speed_05x: speed = 0.5F; break;
            case R.id.speed_2x: speed = 2F; break;
            default: speed = 1F;
        }

        // Launch the transcoding operation.
        mTranscodeStartTime = SystemClock.uptimeMillis();
        setIsTranscoding(true);
        DataSink sink = new DefaultDataSink(mTranscodeOutputFile.getAbsolutePath());
        GIFOptions.Builder builder = GIFCompressor.into(sink);
        if (mAudioReplacementUri == null) {
            if (mTranscodeInputUri1 != null) builder.addDataSource(this, mTranscodeInputUri1);
            if (mTranscodeInputUri2 != null) builder.addDataSource(this, mTranscodeInputUri2);
            if (mTranscodeInputUri3 != null) builder.addDataSource(this, mTranscodeInputUri3);
        } else {
            if (mTranscodeInputUri1 != null) builder.addDataSource(TrackType.VIDEO, this, mTranscodeInputUri1);
            if (mTranscodeInputUri2 != null) builder.addDataSource(TrackType.VIDEO, this, mTranscodeInputUri2);
            if (mTranscodeInputUri3 != null) builder.addDataSource(TrackType.VIDEO, this, mTranscodeInputUri3);
            builder.addDataSource(TrackType.AUDIO, this, mAudioReplacementUri);
        }
        mTranscodeFuture = builder.setListener(this)
                .setAudioTrackStrategy(mTranscodeAudioStrategy)
                .setVideoTrackStrategy(mTranscodeVideoStrategy)
                .setVideoRotation(rotation)
                .setSpeed(speed)
                .compress();
    }

    @Override
    public void onGIFCompressionProgress(double progress) {
        if (progress < 0) {
            mProgressView.setIndeterminate(true);
        } else {
            mProgressView.setIndeterminate(false);
            mProgressView.setProgress((int) Math.round(progress * PROGRESS_BAR_MAX));
        }
    }

    @Override
    public void onGIFCompressionCompleted() {
        LOG.w("Transcoding took " + (SystemClock.uptimeMillis() - mTranscodeStartTime) + "ms");
        onTranscodeFinished(true, "Transcoded file placed on " + mTranscodeOutputFile);
        File file = mTranscodeOutputFile;
        String type = mIsAudioOnly ? "audio/mp4" : "video/mp4";
        Uri uri = FileProvider.getUriForFile(GIFActivity.this,
                FILE_PROVIDER_AUTHORITY, file);
        startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, type)
                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    }

    @Override
    public void onGIFCompressionCanceled() {
        onTranscodeFinished(false, "GIFCompressor canceled.");
    }

    @Override
    public void onGIFCompressionFailed(@NonNull Throwable exception) {
        onTranscodeFinished(false, "GIFCompressor error occurred. " + exception.getMessage());
    }

    private void onTranscodeFinished(boolean isSuccess, String toastMessage) {
        mProgressView.setIndeterminate(false);
        mProgressView.setProgress(isSuccess ? PROGRESS_BAR_MAX : 0);
        setIsTranscoding(false);
        Toast.makeText(GIFActivity.this, toastMessage, Toast.LENGTH_LONG).show();
    }

}