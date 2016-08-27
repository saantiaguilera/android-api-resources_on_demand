package com.u.dynamic_resources.internal;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;

import com.u.dynamic_resources.internal.loading.FileCallback;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by saguilera on 8/25/16.
 */
final class Streamer {

    private @NonNull OkHttpClient client;

    /**
     * Not weak reference because probably the user will just execute and dont retain the instance
     * in a reference. Since we are working with bitmaps, theres a really nice chance the callback
     * gets gc'ed.
     *
     * It wont leak because we are the ones using it only :)
     */
    private @Nullable FileCallback callback;
    private @NonNull Uri uri;

    private @NonNull Cache cache;

    //For writing files
    private static final ExecutorService executor = Executors.newFixedThreadPool(1);

    public static Builder create() {
        return new Builder();
    }

    @SuppressWarnings("ConstantConditions")
    private Streamer(@Nullable OkHttpClient client,
                     @Nullable FileCallback callback,
                     @Nullable Cache cache,
                     @NonNull Uri uri) {
        if (client != null) {
            this.client = client;
        } else {
            this.client = new OkHttpClient.Builder().build();
        }

        this.cache = cache;
        this.callback = callback;
        this.uri = uri;
    }

    @UiThread
    private void fetch() {
        if (cache.contains(uri) && callback != null) {
            new Handler(Looper.getMainLooper()).postAtFrontOfQueue(new SuccessRunnable(cache.get(uri)));
            return;
        }

        CacheControl cacheControl = new CacheControl.Builder()
                .noCache()
                .noStore()
                .build();

        Request request = new Request.Builder()
                .url(uri.toString())
                .cacheControl(cacheControl)
                .get().build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!call.isCanceled() && callback != null) {
                    callback.onFailure(e);
                }
            }

            @Override
            public void onResponse(Call call, final Response response) throws IOException {
                if (!call.isCanceled()) {
                    executor.submit(new Runnable() {
                        @Override
                        public void run() {
                            if (response != null) {
                                Streamer.this.onResponse(response);
                            }
                        }
                    });
                }
            }
        });
    }

    private void onResponse(@NonNull Response response) {
        try {
            File file = cache.put(uri, response.body().byteStream());
            new Handler(Looper.getMainLooper()).postAtFrontOfQueue(new SuccessRunnable(file));
        } catch (Exception e) {
            new Handler(Looper.getMainLooper()).postAtFrontOfQueue(new FailureRunnable(e));
        }
    }

    static class Builder {

        private OkHttpClient client = null;
        private FileCallback callback = null;

        private Cache cache = null;

        Builder() {}

        public Builder client(@NonNull OkHttpClient client) {
            this.client = client;
            return this;
        }

        public Builder callback(@NonNull FileCallback callback) {
            this.callback = callback;
            return this;
        }

        public Builder cache(@NonNull Cache cache) {
            this.cache = cache;
            return this;
        }

        public Streamer fetch(@NonNull Uri uri) {
            Validator.checkNullAndThrow(this, uri);

            Streamer streamer = new Streamer(client,
                    callback == null ? null : callback,
                    cache,
                    uri);
            streamer.fetch();
            return streamer;
        }

    }

    class FailureRunnable implements Runnable {
        private @NonNull Exception exception;

        FailureRunnable(@NonNull Exception e) {
            exception = e;
        }

        @Override
        public void run() {
            if (callback != null) {
                callback.onFailure(exception);
            }
        }
    }

    class SuccessRunnable implements Runnable {
        private @Nullable File file;

        SuccessRunnable(@Nullable File file) {
            this.file = file;
        }

        @Override
        public void run() {
            if (callback != null && file != null) {
                callback.onSuccess(file);
            }
        }
    }

}
