package com.jerry.ftvlaunch;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** 记录当前在前台的 Activity，遥控按键可以直接投递给它（不用 root）。 */
public class App extends Application {

    static Activity current;

    @Override
    public void onCreate() {
        super.onCreate();
        DlnaServer.get(this).start();
        AirPlayServer.get(this).start();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity a) {
                current = a;
            }

            @Override
            public void onActivityPaused(Activity a) {
                if (current == a) current = null;
            }

            public void onActivityCreated(Activity a, Bundle b) {
            }

            public void onActivityStarted(Activity a) {
            }

            public void onActivityStopped(Activity a) {
            }

            public void onActivitySaveInstanceState(Activity a, Bundle b) {
            }

            public void onActivityDestroyed(Activity a) {
            }
        });
    }
}
