package helium314.keyboard.latin.permissions;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionsActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1;
    public static final String ACTION_RECORD_AUDIO_GRANTED = "helium314.keyboard.latin.permissions.RECORD_AUDIO_GRANTED";

    public static void run(@NonNull Context context) {
        Intent intent = new Intent(context, PermissionsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish();
        } else if (savedInstanceState == null) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Intent intent = new Intent(ACTION_RECORD_AUDIO_GRANTED);
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, "Microphone permission is permanently denied. Please enable it in app settings.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Audio recording permission is required for voice input.", Toast.LENGTH_SHORT).show();
                }
            }
        }
        finish();
    }
}
