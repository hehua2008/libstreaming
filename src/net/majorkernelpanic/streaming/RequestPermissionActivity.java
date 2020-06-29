package net.majorkernelpanic.streaming;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import net.majorkernelpanic.streaming.video.ScreenStream;

public class RequestPermissionActivity extends Activity {
    private static final String TAG = RequestPermissionActivity.class.getSimpleName();

    private static final int REQUEST_MEDIA_PROJECTION = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_request_permission);

        MediaProjectionManager projectionManager = getSystemService(MediaProjectionManager.class);
        Log.d(TAG, "Requesting projection confirmation");
        // This initiates a prompt dialog for the user to confirm screen projection.
        startActivityForResult(projectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != Activity.RESULT_OK) {
                Log.w(TAG, "User cancelled");
                Toast.makeText(this, R.string.user_cancelled, Toast.LENGTH_SHORT).show();
                sendResult(null);
            } else {
                Log.d(TAG, "User confirmed");
                sendResult(data);
            }
        }
    }

    private void sendResult(Intent resultData) {
        Intent intent = new Intent(ScreenStream.ACTION_GET_MEDIA_PROJECTION);
        boolean success = (resultData != null);
        intent.putExtra(ScreenStream.MEDIA_PROJECTION_RESUTLT, success);
        if (success) {
            intent.putExtra(ScreenStream.MEDIA_PROJECTION_DATA, resultData);
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
