package com.lfmpixel.lfm.ipc;

import android.os.ParcelFileDescriptor;
import com.lfmpixel.lfm.ipc.IEdgeReply;

/**
 * Everything LFM can ask of LFM Net. Four network calls and one that only reads LFM Net's own state;
 * bundle keys are in NetContract. Each network call returns the requests it made, for LFM's network log.
 */
interface ILfmNet {
    /** Is a provider set up, which one, Wikipedia on or off. Makes no request. */
    Bundle info();

    /** Searches the set-up providers for these words. */
    Bundle search(String query);

    /** Fetches one page as text; only an address from the latest search results. */
    Bundle fetch(String url);

    /**
     * Streams a pinned model file from Hugging Face into [sink], starting at [offset]; returns when done.
     * With [wifiOnly], only over an unmetered network (Wi-Fi); otherwise it fails with code "no-wifi".
     */
    Bundle download(String fileName, long offset, boolean wifiOnly, in ParcelFileDescriptor sink);

    /**
     * Edge mode: one request to the owner's own server, [method] [baseUrl] + [path], with [key] as its bearer token
     * and [body] (read to its end, may be null) sent as [contentType]. The reply body streams to [reply] as it comes;
     * the Bundle has the HTTP status (NetContract.STATUS) and the log. Only a private or tailnet address is opened
     * (EdgeRules), only the paths in EdgeRules.PATHS, never a redirect, never through the SOCKS proxy.
     */
    Bundle edge(String id, String baseUrl, String key, String method, String path, String contentType,
                in ParcelFileDescriptor body, IEdgeReply reply);

    /** Stops the Edge request [id] at once, even while the server has not answered yet. */
    void edgeCancel(String id);

    /**
     * The owner's server, as saved in Settings. Edge requests go only there; any other private address is opened
     * only for Settings' Test (/health and /v1/models).
     */
    void edgePin(String baseUrl);
}
