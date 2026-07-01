package com.winlator.star.xserver.extensions;

import android.util.Log;
import com.winlator.star.renderer.GPUImage;
import com.winlator.star.renderer.vulkan.VulkanRenderer;

import static com.winlator.star.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.star.core.Callback;
import com.winlator.star.sysvshm.SysVSharedMemory;
import com.winlator.star.xconnector.XConnectorEpoll;
import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.Drawable;
import com.winlator.star.xserver.Pixmap;
import com.winlator.star.xserver.Window;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.XLock;
import com.winlator.star.xserver.XServer;
import com.winlator.star.xserver.errors.BadAlloc;
import com.winlator.star.xserver.errors.BadDrawable;
import com.winlator.star.xserver.errors.BadIdChoice;
import com.winlator.star.xserver.errors.BadImplementation;
import com.winlator.star.xserver.errors.BadWindow;
import com.winlator.star.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DRI3Extension implements Extension {
    public static final byte MAJOR_OPCODE = -102;

    // SGSR2 Gate 0/1: depth AHBs arrive on the single-threaded X-server/DRI3 handler
    // thread, but receiving the AHB over the courier socket (blocking recvmsg) must NOT
    // run there — a stalled recv freezes X request/input dispatch and ANRs the app. So
    // the blocking recv + native query is handed to this dedicated worker. Daemon thread
    // so it never blocks JVM/container teardown. This async hand-off is the structure
    // Gate 1's real RGBA8 import + latest-depth holder will reuse.
    private static final java.util.concurrent.ExecutorService depthRecvExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dri3-depth-recv");
            t.setDaemon(true);
            return t;
        });
    // Cap the blocking AHB recv so a missing/late courier handoff can never hang the
    // worker indefinitely (fd is still closed on timeout).
    private static final int DEPTH_RECV_TIMEOUT_MS = 1000;
    private final Callback<Drawable> onDestroyDrawableListener = (drawable) -> {
        ByteBuffer data = drawable.getData();
        SysVSharedMemory.unmapSHMSegment(data, data.capacity());
    };

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }

    @Override
    public String getName() {
        return "DRI3";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = client.xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        pixmapFromFd(client, pixmapId, width, height, stride, 0, depth, fd, size);
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        Log.d("Dri3", "Received pixmap from buffers");
        int pixmapId = inputStream.readInt();
        Log.d("Dri3", "Read pixmap id " + pixmapId);
        int windowId = inputStream.readInt();
        Log.d("Dri3", "Read window id " + windowId);
        inputStream.skip(4);
        short width = inputStream.readShort();
        Log.d("Dri3", "Read width " + width);
        short height = inputStream.readShort();
        Log.d("Dri3", "Read height " + height);
        int stride = inputStream.readInt();
        Log.d("Dri3", "Read stride " + stride);
        int offset = inputStream.readInt();
        Log.d("Dri3", "Read offset " + offset);
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        Log.d("Dri3", "Read depth " + depth);
        inputStream.skip(3);
        long modifiers = inputStream.readLong();
        Log.d("Dri3", "Read modifiers " + modifiers);
        
        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);
        
        int fd = inputStream.getAncillaryFd();
        long size = (long)stride * height;

        if (modifiers == 1255) {
            Log.d("Dri3", "Creating pixmap from AHardwareBuffer");
            pixmapFromHardwareBuffer(client, pixmapId, width, height, depth, fd);
        }
        else if (modifiers == 1274) {
            Log.d("Dri3", "Creating pixmap from dmabuf filedescriptor");
            pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
        }
        else if ((modifiers & 0xFFFFFFFFL) == 1256L) {
            // SGSR2 Gate 0 depth-export receiver STUB.
            //
            // Wire format (must match the guest Wine depth courier):
            //   modifiers (64-bit) = ((long)frameId << 32) | 1256L
            //     - low  32 bits = tag 1256 (depth AHB)
            //     - high 32 bits = synthetic frame-id (guest present counter)
            //   exactly ONE AHB FD passed as the ancillary FD (same as the 1255 path)
            //   width/height/stride/offset = standard pixmapFromBuffers fields.
            //
            // Using the low 32 bits for the tag keeps the existing exact-match
            // 1255/1274 branches above unaffected (their high bits are always 0).
            //
            // Gate 0 = receive the AHB, query + LOG its format/size natively, then drop.
            // NOT registered as a Drawable/Pixmap (it is not visible window content);
            // nothing is rendered (grayscale quad is Gate 1).
            // Only the cheap header parse runs on the X-server thread. The blocking AHB
            // recv + native query is dispatched to the depth worker; the worker now OWNS
            // the ancillary fd (getAncillaryFd() already transferred ownership) and is
            // responsible for closing it. The X thread returns immediately -> no ANR.
            int frameId = (int)(modifiers >>> 32);
            Log.d("Dri3", "modifier 1256 (depth AHB) frameId=" + frameId + " w=" + width + " h=" + height);
            final int depthFd = fd;
            final short dw = width, dh = height;
            final int fId = frameId;
            try {
                depthRecvExecutor.execute(() -> receiveDepthAHB(dw, dh, fId, depthFd));
            }
            catch (Throwable t) {
                // Executor rejected (shutdown/OOM): don't leak the fd.
                Log.w("Dri3", "modifier 1256: could not dispatch depth recv (frameId=" + frameId + "), dropping", t);
                XConnectorEpoll.closeFd(depthFd);
            }
        }
    }

    // Gate 0 STUB: runs on the depth worker thread. Receive a depth AHB from the guest,
    // hand it to the Vulkan renderer to query + log format/size, then release it. Safe if
    // the AHB is null / recv times out (log + drop, no crash). Always closes the fd.
    // Never touches the window/pixmap managers or the X-server thread.
    private void receiveDepthAHB(short width, short height, int frameId, int fd) {
        // Lock-free, time-bounded recv: the depth AHB may be GPU-only, so we must NOT use
        // the auto-CPU-locking GPUImage(fd) constructor (it would release such a buffer);
        // the timeout guarantees the worker can never hang on a missing courier handoff.
        long ahbPtr = 0;
        try {
            ahbPtr = GPUImage.recvHardwareBufferUnlocked(fd, DEPTH_RECV_TIMEOUT_MS);
            if (ahbPtr == 0) {
                Log.w("Dri3", "modifier 1256: depth AHB recv failed/timed out (frameId=" + frameId + "), dropping");
                return;
            }
            VulkanRenderer.acceptDepthAHB(ahbPtr, frameId, width, height);
        }
        catch (Throwable t) {
            Log.w("Dri3", "modifier 1256: error handling depth AHB (frameId=" + frameId + ")", t);
        }
        finally {
            // Gate 0 STUB queries synchronously, so releasing the AHB here is safe.
            // Not registered as a Drawable/Pixmap -> nothing else references it.
            GPUImage.releaseHardwareBufferPtr(ahbPtr);
            XConnectorEpoll.closeFd(fd);
        }
    }
    
    private void pixmapFromHardwareBuffer(XClient client, int pixmapId, short width, short height, byte depth, int fd) throws IOException, XRequestError {
        try {
            GPUImage gpuImage = new GPUImage(fd);
            // GPUImage(fd) now locks the buffer, so getStride() is valid (matches the SHM path's
            // stride-based width). Mark the drawable directScanout so the Vulkan renderer can
            // present the AHB directly instead of compositing a blank CPU buffer (-> black).
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, gpuImage.getStride(), height, depth);
            drawable.setTexture(gpuImage);
            drawable.setDirectScanout(true);
            client.xServer.pixmapManager.createPixmap(drawable);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }   
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset, byte depth, int fd, long size)  throws IOException, XRequestError {
        try {
            ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
            if (buffer == null) throw new BadAlloc();
            
            short totalWidth = (short)(stride / 4);
            Drawable drawable = client.xServer.drawableManager.createDrawable(pixmapId, totalWidth, height, depth);
            drawable.setData(buffer);
            drawable.setTexture(null);
            drawable.setOnDestroyListener(onDestroyDrawableListener);
            client.xServer.pixmapManager.createPixmap(drawable);
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
