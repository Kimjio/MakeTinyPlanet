package com.kimjio.tinyplanet;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@SuppressWarnings("unchecked")
public interface IAsyncTask<Params, Result> {
    Result doInBackground(Params... params);

    void onPostExecute(Result result);

    default void execute(Params... params) {
        Executor executor = Executors.newCachedThreadPool();
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            final Result result = doInBackground(params);
            handler.post(() -> onPostExecute(result));
        });
    }
}
