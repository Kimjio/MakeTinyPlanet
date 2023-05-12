package com.kimjio.tinyplanet.app;

import static androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.kimjio.tinyplanet.TinyPlanetFragment;
import com.kimjio.tinyplanet.databinding.MainActivityBinding;

public class MainActivity extends BaseActivity<MainActivityBinding> {
    private final ActivityResultLauncher<PickVisualMediaRequest> pickMedia =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    result -> {
                        if (result != null) {
                            launchTinyPlanetEditor(result);
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding.pickPictureBtn.setOnClickListener(
                v -> pickMedia.launch(
                        new PickVisualMediaRequest.Builder()
                                .setMediaType(PickVisualMedia.ImageOnly.INSTANCE)
                                .build()));
    }

    /**
     * Launch the tiny planet editor.
     *
     * @param data The data must be a 360 degree stereographically mapped panoramic image. It will
     *     not be modified, instead a new item with the result will be added to the filmstrip.
     */
    public void launchTinyPlanetEditor(Uri data) {
        Cursor cursor = getContentResolver().query(data, null, null, null, null);
        cursor.moveToFirst();

        String fileName =
                cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
        Log.d("TinyPlanet", data.toString());
        Log.d("TinyPlanet", fileName);
        cursor.close();
        TinyPlanetFragment fragment = new TinyPlanetFragment();
        Bundle bundle = new Bundle();
        bundle.putString(TinyPlanetFragment.ARGUMENT_URI, data.toString());
        bundle.putString(TinyPlanetFragment.ARGUMENT_TITLE, fileName);
        fragment.setArguments(bundle);
        fragment.show(getSupportFragmentManager(), "tiny_planet");
    }
}
