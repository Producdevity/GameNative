package com.winlator.xconnector;

import java.io.IOException;

/* loaded from: classes.dex */
public interface NewRequestHandler {
    boolean handleRequest(ConnectedClient connectedClient) throws IOException;
}
