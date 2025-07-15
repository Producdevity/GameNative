package com.winlator.xenvironment.components;

import androidx.annotation.Keep;
import com.winlator.contentdialog.VortekConfigDialog;
import com.winlator.core.GPUHelper;
import com.winlator.core.KeyValueSet;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.widget.XServerView;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;
import java.io.IOException;
import java.util.Objects;

import timber.log.Timber;

public class VortekRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    public static final int VK_MAX_VERSION = GPUHelper.vkMakeVersion(1, 3, 128);
    private XConnectorEpoll connector;
    private final Options options;
    private final UnixSocketConfig socketConfig;
    private final XServer xServer;

    private native long createVkContext(int i, Options options);

    private native void destroyVkContext(long j);

    static {
        System.loadLibrary("vortekrenderer");
    }

    public static class Options {
        public int vkMaxVersion = VortekRendererComponent.VK_MAX_VERSION;
        public short maxDeviceMemory = 4096;
        public short imageCacheSize = 256;
        public String[] exposedDeviceExtensions = null;

        public static Options fromKeyValueSet(KeyValueSet config) {
            if (config == null || config.isEmpty()) {
                return new Options();
            }
//            if (config == null || config.isEmpty()) {
//                return new Options();
//            }
            Options options = new Options();
            String exposedDeviceExtensions = config.get("exposedDeviceExtensions", "all");
            if (!exposedDeviceExtensions.isEmpty() && !exposedDeviceExtensions.equals("all")) {
                options.exposedDeviceExtensions = exposedDeviceExtensions.split("\\|");
            }
            String str = VortekConfigDialog.DEFAULT_VK_MAX_VERSION;
            String vkMaxVersion = config.get("vkMaxVersion", str);
            if (!vkMaxVersion.equals(str)) {
                String[] parts = vkMaxVersion.split("\\.");
                options.vkMaxVersion = GPUHelper.vkMakeVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), 128);
            }
            options.maxDeviceMemory = (short) config.getInt("maxDeviceMemory", 4096);
            options.imageCacheSize = (short) config.getInt("imageCacheSize", 256);
            return options;
        }
    }

    public VortekRendererComponent(XServer xServer, UnixSocketConfig socketConfig, Options options) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
        this.options = options;
    }

    @Override // com.winlator.xenvironment.EnvironmentComponent
    public void start() {
        Timber.i("[System] Starting");
        if (this.connector != null) {
            Timber.i("[System] Connector already started, skipping start");
            return;
        }
        XConnectorEpoll xConnectorEpoll = new XConnectorEpoll(this.socketConfig, this, this);
        this.connector = xConnectorEpoll;
        xConnectorEpoll.setInitialInputBufferCapacity(1);
        this.connector.setInitialOutputBufferCapacity(0);
        this.connector.start();
        Timber.i("[System] Connector started successfully");
    }

    @Override // com.winlator.xenvironment.EnvironmentComponent
    public void stop() {
        Timber.i("[System] Stopping");
        XConnectorEpoll xConnectorEpoll = this.connector;
        if (xConnectorEpoll != null) {
            Timber.i("[System] Stopping connector");
            xConnectorEpoll.stop();
            this.connector = null;
            Timber.i("[System] Connector stopped");
        } else {
            Timber.i("[System] No connector to stop");
        }
    }

    @Keep
    private int getWindowWidth(int windowId) {
        Timber.i("[System] Attempting to getWindowWidth for windowId: %d", windowId);
        Window window = this.xServer.windowManager.getWindow(windowId);
        if (window != null) {
            int width = window.getWidth();
            Timber.i("[System] Found window width: %d for windowId: %d", width, windowId);
            return width;
        }
        Timber.i("[System] Window not found for windowId: %d, returning 0", windowId);
        return 0;
    }

    @Keep
    private int getWindowHeight(int windowId) {
        Timber.i("[System] Attempting to getWindowHeight for windowId: %d", windowId);
        Window window = this.xServer.windowManager.getWindow(windowId);
        if (window != null) {
            int height = window.getHeight();
            Timber.i("[System] Found window height: %d for windowId: %d", height, windowId);
            return height;
        }
        Timber.i("[System] Window not found for windowId: %d, returning 0", windowId);
        return 0;
    }

    @Keep
    private long getWindowHardwareBuffer(int windowId) {
        Timber.i("[System] Attempting to getWindowHardwareBuffer for windowId: %d", windowId);
        Window window = this.xServer.windowManager.getWindow(windowId);
        if (window != null) {
            Timber.i("[System] Window found for windowId: %d", windowId);
            Drawable drawable = window.getContent();
            Texture texture = drawable.getTexture();
            if (!(texture instanceof GPUImage)) {
                Timber.i("[System] Texture is not GPUImage for windowId: %d, destroying and recreating", windowId);
                XServerView xServerView = this.xServer.getRenderer().xServerView;
                Objects.requireNonNull(texture);
                xServerView.queueEvent(() -> VortekRendererComponent.destroyTexture(texture));
                drawable.setTexture(new GPUImage(drawable.width, drawable.height, false, false));
            } else {
                Timber.i("[System] Texture is already GPUImage for windowId: %d", windowId);
            }
            long ptr = ((GPUImage) drawable.getTexture()).getHardwareBufferPtr();
            Timber.i("[System] Returning hardwareBufferPtr: %d for windowId: %d", ptr, windowId);
            return ptr;
        }
        Timber.i("[System] Window not found for windowId: %d, returning 0", windowId);
        return 0L;
    }

    @Keep
    private void updateWindowContent(int windowId) {
        Timber.i("[System] Attempting to updateWindowContent for windowId: %d", windowId);
        Window window = this.xServer.windowManager.getWindow(windowId);
        if (window != null) {
            Timber.i("[System] Found window for windowId: %d, updating content", windowId);
            Drawable drawable = window.getContent();
            synchronized (drawable.renderLock) {
                drawable.forceUpdate();
            }
            Timber.i("[System] Window content updated for windowId: %d", windowId);
        } else {
            Timber.i("[System] Window not found for windowId: %d, nothing to update", windowId);
        }
    }

    @Override // com.winlator.xconnector.ConnectionHandler
    public void handleConnectionShutdown(Client client) {
        Timber.i("[System] Attempting to handleConnectionShutdown for client: %s", client);
        if (client.getTag() != null) {
            long contextPtr = ((Long) client.getTag()).longValue();
            Timber.i("[System] Destroying VkContext: %d for client: %s", contextPtr, client);
            destroyVkContext(contextPtr);
            Timber.i("[System] VkContext destroyed: %d for client: %s", contextPtr, client);
        } else {
            Timber.i("[System] No VkContext to destroy for client: %s", client);
        }
    }

    @Override // com.winlator.xconnector.ConnectionHandler
    public void handleNewConnection(Client client) {
        Timber.i("[System] Attempting to handleNewConnection for client: %s", client);
        Timber.i("[System] Creating IO streams for client: %s", client);
        client.createIOStreams();
        Timber.i("[System] IO streams created for client: %s", client);
    }

    @Override // com.winlator.xconnector.RequestHandler
    public boolean handleRequest(Client client) throws IOException {
        Timber.i("[System] Handling request for client: %s", client);
        XInputStream inputStream = client.getInputStream();
        if (inputStream.available() < 1) {
            Timber.i("[System] No data available in inputStream for client: %s, returning false", client);
            return false;
        }
        byte requestCode = inputStream.readByte();
        Timber.i("[System] Received requestCode: %d for client: %s", requestCode, client);
        if (requestCode == 1) {
            Timber.i("[System] Attempting to create VkContext for client: %s", client);
            long contextPtr = createVkContext(client.clientSocket.fd, this.options);
            if (contextPtr > 0) {
                Timber.i("[System] VkContext created with ptr: %d for client: %s", contextPtr, client);
                client.setTag(Long.valueOf(contextPtr));
            } else {
                Timber.i("[System] Killing connection for client: %s due to context creation failure", client);
                this.connector.killConnection(client);
            }
        } else {
            Timber.i("[System] Unsupported requestCode: %d for client: %s", requestCode, client);
        }
        Timber.i("[System] Request handling completed for client: %s", client);
        return true;
    }

    public static void destroyTexture(Texture texture) {
        Timber.i("[System] Attempting to destroyTexture");
        if (texture != null) {
            Timber.i("[System] Destroying texture: %s", texture);
            texture.destroy();
            Timber.i("[System] Texture destroyed: %s", texture);
        } else {
            Timber.i("[System] Cannot destroy null texture");
        }
    }
}

