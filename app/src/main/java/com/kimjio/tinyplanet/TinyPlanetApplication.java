package com.kimjio.tinyplanet;

import android.app.Application;
import android.content.Context;

import com.kimjio.tinyplanet.util.AndroidContext;

public class TinyPlanetApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Android context must be the first item initialized.
        Context context = getApplicationContext();
        AndroidContext.initialize(context);
    }
}
