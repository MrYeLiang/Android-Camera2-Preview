package com.yeliang;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;


/**
 * 整个流程梳理
 * 1 首先
 */
public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    // Used to load the 'native-lib' library on application startup.
    static {
        System.loadLibrary("native-lib");
    }

    private final String LOG_TAG = "===TAG===";


    private AutoFitTextureView mTextureView;
    private Button mButtonStatus;


    private boolean mIsRecording = false;

    private String mNextVideoAbsolutePath;

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;

    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        initPermission();
    }

    private void initView() {
        mTextureView = findViewById(R.id.texture_view);
        mButtonStatus = findViewById(R.id.btn_status);

        mButtonStatus.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_status:
                if (mIsRecording) {
                    stopRecording();
                } else {
                    startRecording();
                }
                break;
            default:
                break;
        }
    }

    private void startRecording() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }

        closePreviewSession();
        try {
            //1 设置MediaRecorder
            setUpMediaRecorder();

            //2 获取到TextureView的Texture
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            //3 设置texture默认缓冲大小
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            //4 初始化CaptureRequest
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice.createCaptureSession(surfaces, stateCallback, mBackGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void stopRecording() {
        mIsRecording = false;
        mButtonStatus.setText("开始");
        // Stop recording
        mMediaRecorder.stop();
        mMediaRecorder.reset();

        Log.d(LOG_TAG, "Video saved: " + mNextVideoAbsolutePath);

        mNextVideoAbsolutePath = null;
        startPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopBackgroundThread();
    }

    private MediaRecorder mMediaRecorder;

    private void setUpMediaRecorder() {
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath();
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        try {
            mMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getVideoFilePath() {
        final File dir = Environment.getExternalStorageDirectory();
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + "test_record.mp4";
    }

    /**
     * 引用HandlerThread的目的: 通过HandlerThread在子线程中创建的Looper,
     * 来构建Handler,传给CameraCaptureSession。
     */
    private HandlerThread mBackGroundThread;
    private Handler mBackGroundHandler;

    private void startBackgroundThread() {

        mBackGroundThread = new HandlerThread("CameraBackground");
        mBackGroundThread.start();
        mBackGroundHandler = new Handler(mBackGroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        try {
            mBackGroundThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        mBackGroundThread = null;
        mBackGroundHandler = null;
    }

    //预览控件的尺寸
    private Size mPreviewSize;

    //视频数据的尺寸
    private Size mVideoSize;

    private Integer mSensorOrientation;

    @SuppressLint("MissingPermission")
    private void openCamera(int width, int height) {

        //1 初始化CameraManager
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = cameraManager.getCameraIdList()[0];

            //2 初始化摄像头设备id的属性
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            //3 获取到摄像头设备属性支持的配置，比如可以获取到所支持的所有窗口大小组合
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

//            Size[] sizeArray = map.getOutputSizes(MediaRecorder.class);
//            for(Size size: sizeArray){
//                Log.i(LOG_TAG, "Size Array: " + "width = "+size.getWidth()+", height = "+size.getHeight());
////                打印结果
////                width = 2016, height = 1508
////                width = 19206 , height = 1080
////                width = 1440, height = 1080
////                width = 1280, height = 720
////                width = 960, height = 720
////                width = 960, height = 540
////                width = 800, height = 600
////                width = 864, height = 480
////                width = 800, height = 480
////                width = 720, height = 480
////                width = 640, height = 480
////                width = 480, height = 368
////                width = 480, height = 320
////                width = 352, height = 288
////                width = 320, height = 240
////                width = 176, height = 144
//            }

            //4 获取到摄像头的拍摄旋转角度
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            //5 根据摄像头所支持的窗口size组合，来选择一个合适的窗口size组合
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

            //mVideoSize.getWidth(): 1440,mVideoSize.getHeight(): 1080
            Log.i(LOG_TAG, "mVideoSize.getWidth(): " + mVideoSize.getWidth() + ",mVideoSize.getHeight(): " + mVideoSize.getHeight());

            //6 选择一组合适的预览的size组合
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, mVideoSize);
            //mPreviewSize.getWidth(): 2016,mPreviewSize.getHeight(): 1508
            Log.i(LOG_TAG, "mPreviewSize.getWidth(): " + mPreviewSize.getWidth() + ",mPreviewSize.getHeight(): " + mPreviewSize.getHeight());


            //7 设置TextureView宽高比
            int orientation = getResources().getConfiguration().orientation;

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) { //横屏
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                Log.i(LOG_TAG, "横屏显示");
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                Log.i(LOG_TAG, "竖屏显示");
            }

            //8 配置TextureView的Transform
            configureTransform(width, height);

            //9 初始化MediaRecorder
            mMediaRecorder = new MediaRecorder();

            //10 打开摄像头
            cameraManager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getHeight() <= 1080) {
                return size;
            }
        }

        return choices[choices.length - 1];
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }

    private CameraDevice mCameraDevice;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    //相机开启，关闭， 异常的状态回调
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            mCameraDevice.close();
            mCameraDevice = null;
            finish();
        }
    };

    private CaptureRequest.Builder mPreviewBuilder;

    private void startPreview() {

        Log.i(LOG_TAG, "startPreview");
        if (null == mCameraDevice || mTextureView.isAvailable() || mPreviewSize == null) {
            return;
        }

        closePreviewSession();

        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        assert texture != null;
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        try {
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface previewSurface = new Surface(texture);
            mPreviewBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface), stateCallback, mBackGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(LOG_TAG, "onConfigured");

            mPreviewSession = cameraCaptureSession;
            updatePreview();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // UI
                    mButtonStatus.setText("停止");
                    mIsRecording = true;

                    // Start recording
                    mMediaRecorder.start();
                }
            });
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(LOG_TAG, "onConfigureFailed");
        }
    };

    private CameraCaptureSession mPreviewSession;

    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }

        setUpCaptureRequestBuilder(mPreviewBuilder);
        try {
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }


    static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * (long) lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
        }
    }

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private void initPermission() {
        int perMissionCamera = PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA);
        int storagePermission = PermissionChecker.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int recordPermission = PermissionChecker.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);

        if (perMissionCamera == -1 || storagePermission == -1 || recordPermission == -1) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO}, 1000);
        }
    }

    public native String stringFromJNI();
}
