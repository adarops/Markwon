package ru.noties.markwon.il;

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.OkHttpClient;
import ru.noties.markwon.spans.AsyncDrawable;

public class AsyncDrawableLoader implements AsyncDrawable.Loader {

    @NonNull
    public static AsyncDrawableLoader create() {
        return builder().build();
    }

    @NonNull
    public static AsyncDrawableLoader.Builder builder() {
        return new Builder();
    }

    private final ExecutorService executorService;
    private final Handler mainThread;
    private final Drawable errorDrawable;
    private final Map<String, SchemeHandler> schemeHandlers;
    private final List<MediaDecoder> mediaDecoders;

    private final Map<String, Future<?>> requests;

    AsyncDrawableLoader(Builder builder) {
        this.executorService = builder.executorService;
        this.mainThread = new Handler(Looper.getMainLooper());
        this.errorDrawable = builder.errorDrawable;
        this.schemeHandlers = builder.schemeHandlers;
        this.mediaDecoders = builder.mediaDecoders;
        this.requests = new HashMap<>(3);
    }


    @Override
    public void load(@NonNull String destination, @NonNull AsyncDrawable drawable) {
        // if drawable is not a link -> show loading placeholder...
        requests.put(destination, execute(destination, drawable));
    }

    @Override
    public void cancel(@NonNull String destination) {

        final Future<?> request = requests.remove(destination);
        if (request != null) {
            request.cancel(true);
        }

        for (SchemeHandler schemeHandler : schemeHandlers.values()) {
            schemeHandler.cancel(destination);
        }
    }

    private Future<?> execute(@NonNull final String destination, @NonNull AsyncDrawable drawable) {

        final WeakReference<AsyncDrawable> reference = new WeakReference<AsyncDrawable>(drawable);

        // todo: should we cancel pending request for the same destination?
        //      we _could_ but there is possibility that one resource is request in multiple places

        // todo, if not a link -> show placeholder

        return executorService.submit(new Runnable() {
            @Override
            public void run() {

                final ImageItem item;

                final Uri uri = Uri.parse(destination);

                final SchemeHandler schemeHandler = schemeHandlers.get(uri.getScheme());
                if (schemeHandler != null) {
                    item = schemeHandler.handle(destination, uri);
                } else {
                    item = null;
                }

                final InputStream inputStream = item != null
                        ? item.inputStream()
                        : null;

                Drawable result = null;

                if (inputStream != null) {
                    try {

                        final String fileName = item.fileName();
                        final MediaDecoder mediaDecoder = fileName != null
                                ? mediaDecoderFromFile(fileName)
                                : mediaDecoderFromContentType(item.contentType());

                        if (mediaDecoder != null) {
                            result = mediaDecoder.decode(inputStream);
                        }

                    } finally {
                        try {
                            inputStream.close();
                        } catch (IOException e) {
                            // ignored
                        }
                    }
                }

                // if result is null, we assume it's an error
                if (result == null) {
                    result = errorDrawable;
                }

                if (result != null) {
                    final Drawable out = result;
                    mainThread.post(new Runnable() {
                        @Override
                        public void run() {
                            final AsyncDrawable asyncDrawable = reference.get();
                            if (asyncDrawable != null && asyncDrawable.isAttached()) {
                                asyncDrawable.setResult(out);
                            }
                        }
                    });
                }

                requests.remove(destination);
            }
        });
    }

    @Nullable
    private MediaDecoder mediaDecoderFromFile(@NonNull String fileName) {

        MediaDecoder out = null;

        for (MediaDecoder mediaDecoder : mediaDecoders) {
            if (mediaDecoder.canDecodeByFileName(fileName)) {
                out = mediaDecoder;
                break;
            }
        }

        return out;
    }

    @Nullable
    private MediaDecoder mediaDecoderFromContentType(@Nullable String contentType) {

        MediaDecoder out = null;

        for (MediaDecoder mediaDecoder : mediaDecoders) {
            if (mediaDecoder.canDecodeByContentType(contentType)) {
                out = mediaDecoder;
                break;
            }
        }

        return out;
    }

    public static class Builder {

        private OkHttpClient client;
        private Resources resources;
        private ExecutorService executorService;
        private Drawable errorDrawable;

        // @since 2.0.0
        private final Map<String, SchemeHandler> schemeHandlers = new HashMap<>(3);

        // @since 1.1.0
        private final List<MediaDecoder> mediaDecoders = new ArrayList<>(3);


        @NonNull
        @Deprecated
        public Builder client(@NonNull OkHttpClient client) {
            this.client = client;
            return this;
        }

        /**
         * Supplied resources argument will be used to open files from assets directory
         * and to create default {@link MediaDecoder}\'s which require resources instance
         *
         * @return self
         */
        @NonNull
        public Builder resources(@NonNull Resources resources) {
            this.resources = resources;
            return this;
        }

        @NonNull
        public Builder executorService(@NonNull ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        @NonNull
        public Builder errorDrawable(@NonNull Drawable errorDrawable) {
            this.errorDrawable = errorDrawable;
            return this;
        }

        /**
         * @since 2.0.0
         */
        @NonNull
        public Builder schemeHandler(@NonNull String scheme, @Nullable SchemeHandler schemeHandler) {
            schemeHandlers.put(scheme, schemeHandler);
            return this;
        }

        @NonNull
        public Builder mediaDecoders(@NonNull List<MediaDecoder> mediaDecoders) {
            this.mediaDecoders.clear();
            this.mediaDecoders.addAll(mediaDecoders);
            return this;
        }

        @NonNull
        public Builder mediaDecoders(MediaDecoder... mediaDecoders) {
            this.mediaDecoders.clear();
            if (mediaDecoders != null
                    && mediaDecoders.length > 0) {
                Collections.addAll(this.mediaDecoders, mediaDecoders);
            }
            return this;
        }

        @NonNull
        public AsyncDrawableLoader build() {

            // I think we should deprecate this...
            if (resources == null) {
                resources = Resources.getSystem();
            }

            if (executorService == null) {
                executorService = Executors.newCachedThreadPool();
            }

            // @since 2.0.0
            // put default scheme handlers (to mimic previous behavior)
            {

                final boolean hasHttp = schemeHandlers.containsKey("http");
                final boolean hasHttps = schemeHandlers.containsKey("https");

                if (!hasHttp || !hasHttps) {

                    if (client == null) {
                        client = new OkHttpClient();
                    }

                    final NetworkSchemeHandler handler = NetworkSchemeHandler.create(client);
                    if (!hasHttp) {
                        schemeHandlers.put("http", handler);
                    }
                    if (!hasHttps) {
                        schemeHandlers.put("https", handler);
                    }
                }

                if (!schemeHandlers.containsKey("file")) {
                    schemeHandlers.put("file", FileSchemeHandler.createWithAssets(resources.getAssets()));
                }

                if (!schemeHandlers.containsKey("data")) {
                    schemeHandlers.put("data", DataUriSchemeHandler.create());
                }
            }

            // add default media decoders if not specified
            if (mediaDecoders.size() == 0) {
                mediaDecoders.add(SvgMediaDecoder.create(resources));
                mediaDecoders.add(GifMediaDecoder.create(true));
                mediaDecoders.add(ImageMediaDecoder.create(resources));
            }

            return new AsyncDrawableLoader(this);
        }
    }
}
