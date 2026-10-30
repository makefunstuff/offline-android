package com.lfmpixel.lfm.ipc;

import android.os.ParcelFileDescriptor;

/**
 * Everything LFM can ask of LFM Net. Three network calls and one that only reads LFM Net's own state;
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
}
