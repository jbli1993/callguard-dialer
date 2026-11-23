package dev.alenajam.opendialer;

import android.app.Application;

import dagger.hilt.android.HiltAndroidApp;
import dev.alenajam.opendialer.core.common.SharedPreferenceHelper;

@HiltAndroidApp
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferenceHelper.init(this);
    }
}
