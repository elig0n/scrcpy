package com.genymobile.scrcpy;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME = "scrcpy";

    private final LocalSocket   localVideoSocket;
    private final SocketChannel tcpVideoSocket;

    private final LocalSocket   localControlSocket;
    private final SocketChannel tcpControlSocket;
    private final InputStream   controlInputStream;
    private final OutputStream  controlOutputStream;

    private final ControlMessageReader reader = new ControlMessageReader();
    private final DeviceMessageWriter  writer = new DeviceMessageWriter();


    private DesktopConnection(LocalSocket videoSocket, LocalSocket controlSocket) throws IOException {
        this.localVideoSocket = videoSocket;
        this.tcpVideoSocket   = null;

        this.localControlSocket = controlSocket;
        this.tcpControlSocket   = null;

        this.controlInputStream  = controlSocket.getInputStream();
        this.controlOutputStream = controlSocket.getOutputStream();
    }

    private DesktopConnection(SocketChannel videoSocket, SocketChannel controlSocket) throws IOException {
        this.localVideoSocket = null;
        this.tcpVideoSocket   = videoSocket;

        this.localControlSocket = null;
        this.tcpControlSocket   = controlSocket;

        this.controlInputStream  = controlSocket.socket().getInputStream();
        this.controlOutputStream = controlSocket.socket().getOutputStream();
    }

    private static LocalSocket connect(final String abstractName) throws IOException {
        final LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    private static LocalSocket listenAndAccept(final String abstractName) throws IOException {
        final LocalServerSocket serverSocket = new LocalServerSocket(abstractName);
        final Timer timer = new Timer();
        try {
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        Ln.e("Timeout accepting forward connection");
                        serverSocket.close();
                    } catch (Exception e) {
                    }
                }
            }, 10000);
            final LocalSocket sock = serverSocket.accept();
            timer.cancel();
            serverSocket.close();
            return sock;
        } finally {
            serverSocket.close();
        }
    }

    private static SocketChannel listenAndAccept(int port) throws IOException {
        final ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.socket().setReuseAddress(true);
        final Timer timer = new Timer();
        try {
            serverSocket.socket().bind(new InetSocketAddress(port));
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        Ln.e("Timeout accepting direct connection");
                        serverSocket.close();
                    } catch (Exception e) {
                    }
                }
            }, 10000);
            final SocketChannel sock = serverSocket.accept();
            timer.cancel();
            serverSocket.close();
            return sock;
        } finally {
            serverSocket.close();
        }
    }

    public static DesktopConnection open(Device device, boolean tunnelForward, int port) throws IOException {
        DesktopConnection connection;
        if (tunnelForward) {
            // Accept connection and send one byte so the client may read() to detect a connection error
            if (port == 0) {
                Ln.i("Accepting forward connection");
                LocalSocket videoSocket = listenAndAccept(SOCKET_NAME);
                videoSocket.getOutputStream().write(0);
                LocalSocket controlSocket = listenAndAccept(SOCKET_NAME);
                connection = new DesktopConnection(videoSocket, controlSocket);
                Ln.i("Forward connection accepted");
            } else {
                Ln.i("Accepting direct connection at "+port);
                SocketChannel videoSocket = listenAndAccept(port);
                videoSocket.socket().setSendBufferSize(32*1024);
                videoSocket.socket().setTcpNoDelay(true);
                videoSocket.socket().setTrafficClass(0B00111100);
                videoSocket.socket().getOutputStream().write(0);
                SocketChannel controlSocket = listenAndAccept(port);
                controlSocket.socket().setTrafficClass(0B00111100);
                connection = new DesktopConnection(videoSocket, controlSocket);
                Ln.i("Direct connection accepted");
            }
        } else {
            Ln.i("Connecting to desktop");
            LocalSocket videoSocket   = connect(SOCKET_NAME);
            LocalSocket controlSocket = connect(SOCKET_NAME);
            connection = new DesktopConnection(videoSocket, controlSocket);
            Ln.i("Connected to desktop");
        }

        Size videoSize = device.getScreenInfo().getVideoSize();
        connection.send(Device.getDeviceName(), videoSize.getWidth(), videoSize.getHeight());
        return connection;
    }

    public void close() {
        if (localVideoSocket != null) {
            try {
                localVideoSocket.shutdownInput();
                localVideoSocket.shutdownOutput();
                localVideoSocket.close();
            } catch (IOException e) {
            }
        }
        if (tcpVideoSocket != null) {
            try {
                tcpVideoSocket.close();
            } catch (IOException e) {
            }
        }
        if (localControlSocket != null) {
            try {
                localControlSocket.shutdownInput();
                localControlSocket.shutdownOutput();
                localControlSocket.close();
            } catch (IOException e) {
            }
        }
        if (tcpControlSocket != null) {
            try {
                tcpControlSocket.close();
            } catch (IOException e) {
            }
        }
        Ln.i("DesktopConnection closed");
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private void send(String deviceName, int width, int height) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH + 4];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        buffer[DEVICE_NAME_FIELD_LENGTH] = (byte) (width >> 8);
        buffer[DEVICE_NAME_FIELD_LENGTH + 1] = (byte) width;
        buffer[DEVICE_NAME_FIELD_LENGTH + 2] = (byte) (height >> 8);
        buffer[DEVICE_NAME_FIELD_LENGTH + 3] = (byte) height;

        if (tcpVideoSocket != null)
            tcpVideoSocket.write(ByteBuffer.wrap(buffer));
        else
            localVideoSocket.getOutputStream().write(buffer);
    }

    public WritableByteChannel getOut() throws IOException {
        if (tcpVideoSocket != null) {
//            tcpVideoSocket.configureBlocking(false);
            return tcpVideoSocket;
        } else {
            return Channels.newChannel(localVideoSocket.getOutputStream());
        }
    }

    public ControlMessage receiveControlMessage() throws IOException {
        ControlMessage msg = reader.next();
        while (msg == null) {
            reader.readFrom(controlInputStream);
            msg = reader.next();
        }
        return msg;
    }

    public void sendDeviceMessage(DeviceMessage msg) throws IOException {
        writer.writeTo(msg, controlOutputStream);
    }
}
