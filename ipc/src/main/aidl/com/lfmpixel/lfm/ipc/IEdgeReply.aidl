package com.lfmpixel.lfm.ipc;

/** Where LFM Net streams a reply from the owner's server, piece by piece as it comes. */
interface IEdgeReply {
    /** The next bytes of the reply body. Return false to stop: LFM Net then drops the request. */
    boolean data(in byte[] bytes);
}
