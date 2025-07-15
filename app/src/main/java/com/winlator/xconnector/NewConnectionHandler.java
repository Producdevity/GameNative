package com.winlator.xconnector;

/* loaded from: classes.dex */
public interface NewConnectionHandler {
    void handleConnectionShutdown(ConnectedClient connectedClient);

    void handleNewConnection(ConnectedClient connectedClient);
}
