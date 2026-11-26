package viritualisres.phonevr;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Small MJPEG endpoint used by the existing MediaPipe hand-tracking script. */
final class BridgeMjpegServer {
    private final int port;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AtomicInteger clients = new AtomicInteger(0);

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private byte[] latestFrame;
    private long frameNumber;

    BridgeMjpegServer() {
        this(8080);
    }

    BridgeMjpegServer(int port) {
        this.port = port;
    }

    int getClientCount() {
        return clients.get();
    }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "vrpb-mjpeg-accept");
        thread.setDaemon(true);
        thread.start();
    }

    void push(byte[] jpeg) {
        lock.lock();
        try {
            latestFrame = jpeg;
            frameNumber++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    synchronized void stop() {
        running = false;
        ServerSocket current = serverSocket;
        serverSocket = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
        lock.lock();
        try {
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket server = null;
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(port), 4);
                serverSocket = server;
                while (running) {
                    final Socket socket = server.accept();
                    Thread client = new Thread(new Runnable() {
                        @Override public void run() {
                            serve(socket);
                        }
                    }, "vrpb-mjpeg-client");
                    client.setDaemon(true);
                    client.start();
                }
            } catch (IOException error) {
                if (running) {
                    try { Thread.sleep(700); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
            } finally {
                if (server != null) {
                    try { server.close(); } catch (IOException ignored) { }
                }
                if (serverSocket == server) serverSocket = null;
            }
        }
    }

    private FrameData nextFrame(long after) {
        lock.lock();
        try {
            if (frameNumber == after && running) {
                try { changed.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            return new FrameData(frameNumber, latestFrame);
        } finally {
            lock.unlock();
        }
    }

    private void serve(Socket socket) {
        boolean counted = false;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(4000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) return;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { }
            socket.setSoTimeout(0);

            String[] parts = request.split(" ");
            String path = parts.length >= 2 ? parts[1] : "/";
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());

            if (path.startsWith("/shot")) {
                byte[] frame;
                lock.lock();
                try { frame = latestFrame; }
                finally { lock.unlock(); }
                if (frame == null) {
                    writeAscii(out, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                } else {
                    writeAscii(out, "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: "
                            + frame.length + "\r\nConnection: close\r\n\r\n");
                    out.write(frame);
                }
                out.flush();
                return;
            }

            if (!path.startsWith("/video")) {
                byte[] body = ("VR Phone Bridge camera endpoint\n"
                        + "/video = MJPEG stream\n/shot.jpg = latest frame\n").getBytes(StandardCharsets.UTF_8);
                writeAscii(out, "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n");
                out.write(body);
                out.flush();
                return;
            }

            clients.incrementAndGet();
            counted = true;
            writeAscii(out, "HTTP/1.1 200 OK\r\n"
                    + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                    + "Pragma: no-cache\r\n"
                    + "Connection: keep-alive\r\n"
                    + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n");
            out.flush();

            long last = -1L;
            while (running && !socket.isClosed()) {
                FrameData data = nextFrame(last);
                if (data.frame == null || data.id == last) continue;
                last = data.id;
                writeAscii(out, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                        + data.frame.length + "\r\n\r\n");
                out.write(data.frame);
                writeAscii(out, "\r\n");
                out.flush();
            }
        } catch (IOException ignored) {
            // Client disconnected or the app is shutting down.
        } finally {
            if (counted) clients.decrementAndGet();
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static void writeAscii(BufferedOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static final class FrameData {
        final long id;
        final byte[] frame;
        FrameData(long id, byte[] frame) {
            this.id = id;
            this.frame = frame;
        }
    }
}
