package com.genymobile.scrcpy;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ControlMessageReader {

    private static final int INJECT_KEYCODE_PAYLOAD_LENGTH        =  9;
    private static final int INJECT_MOUSE_EVENT_PAYLOAD_LENGTH    = 21;
    private static final int INJECT_TOUCH_PAYLOAD_LENGTH          = 21;
    private static final int INJECT_SCROLL_EVENT_PAYLOAD_LENGTH   = 24;
    private static final int SET_SCREEN_POWER_MODE_PAYLOAD_LENGTH =  1;
    private static final int COMMAND_PAYLOAD_LENGTH               =  5;

    public static final int TEXT_MAX_LENGTH = 300;
    public static final int CLIPBOARD_TEXT_MAX_LENGTH = 4093;
    private static final int RAW_BUFFER_SIZE = 1024;

    private final byte[] rawBuffer = new byte[RAW_BUFFER_SIZE];
    private final ByteBuffer buffer = ByteBuffer.wrap(rawBuffer);
    private final byte[] textBuffer = new byte[CLIPBOARD_TEXT_MAX_LENGTH];

    public ControlMessageReader() {
        // invariant: the buffer is always in "get" mode
        buffer.limit(0);
    }

    public boolean isFull() {
        return buffer.remaining() == rawBuffer.length;
    }

    public void readFrom(InputStream input) throws IOException {
        if (isFull()) {
            throw new IllegalStateException("Buffer full, call next() to consume");
        }
        buffer.compact();
        int head = buffer.position();
        int r = input.read(rawBuffer, head, rawBuffer.length - head);
        if (r == -1) {
            throw new EOFException("Controller socket closed");
        }
        buffer.position(head + r);
        buffer.flip();
    }

    public ControlMessage next() {
        if (!buffer.hasRemaining()) {
            return null;
        }
        int savedPosition = buffer.position();

        int type = buffer.get();
        ControlMessage msg;
        switch (type) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                msg = parseInjectKeycode();
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                msg = parseInjectText();
                break;
            case ControlMessage.TYPE_INJECT_MOUSE_EVENT:
                msg = parseInjectMouseEvent();
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                msg = parseInjectTouchEvent();
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                msg = parseInjectScrollEvent();
                break;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                msg = parseSetClipboard();
                break;
            case ControlMessage.TYPE_COMMAND:
                msg = parseCommandEvent();
                break;
            case ControlMessage.TYPE_SET_SCREEN_POWER_MODE:
                msg = parseSetScreenPowerMode();
                break;
            default:
                Ln.w("Unknown event type: " + type);
                msg = null;
                break;
        }

        if (msg == null) {
            // failure, reset savedPosition
            buffer.position(savedPosition);
        }
        return msg;
    }

    private ControlMessage parseInjectKeycode() {
        if (buffer.remaining() < INJECT_KEYCODE_PAYLOAD_LENGTH) {
            return null;
        }
        int action = toUnsigned(buffer.get());
        int keycode = buffer.getInt();
        int metaState = buffer.getInt();
        return ControlMessage.createInjectKeycode(action, keycode, metaState);
    }

    private String parseString() {
        if (buffer.remaining() < 2) {
            return null;
        }
        int len = toUnsigned(buffer.getShort());
        if (buffer.remaining() < len) {
            return null;
        }
        buffer.get(textBuffer, 0, len);
        return new String(textBuffer, 0, len, StandardCharsets.UTF_8);
    }

    private ControlMessage parseInjectText() {
        String text = parseString();
        if (text == null) {
            return null;
        }
        return ControlMessage.createInjectText(text);
    }

    private ControlMessage parseInjectMouseEvent() {
        if (buffer.remaining() < INJECT_MOUSE_EVENT_PAYLOAD_LENGTH) {
            return null;
        }
        int action        = toUnsigned(buffer.get());
        int buttons       = buffer.getInt();
        Position position = readPosition(buffer);
        long timestamp    = toUnsigned(buffer.getInt());
        return ControlMessage.createInjectMouseEvent(action, buttons, position, timestamp);
    }

    private ControlMessage parseInjectTouchEvent() {
        if (buffer.remaining() < INJECT_TOUCH_PAYLOAD_LENGTH) {
            return null;
        }
        int action        = toUnsigned(buffer.get());
        int touchId       = buffer.getInt();
        Position position = readPosition(buffer);
        long timestamp    = toUnsigned(buffer.getInt());
        return ControlMessage.createInjectTouchEvent(action, touchId, position, timestamp);
    }

    private ControlMessage parseInjectScrollEvent() {
        if (buffer.remaining() < INJECT_SCROLL_EVENT_PAYLOAD_LENGTH) {
            return null;
        }
        Position position = readPosition(buffer);
        int hScroll       = buffer.getInt();
        int vScroll       = buffer.getInt();
        long timestamp    = toUnsigned(buffer.getInt());
        return ControlMessage.createInjectScrollEvent(position, hScroll, vScroll, timestamp);
    }

    private ControlMessage parseSetClipboard() {
        String text = parseString();
        if (text == null) {
            return null;
        }
        return ControlMessage.createSetClipboard(text);
    }

    private ControlMessage parseSetScreenPowerMode() {
        if (buffer.remaining() < SET_SCREEN_POWER_MODE_PAYLOAD_LENGTH) {
            return null;
        }
        int mode = buffer.get();
        return ControlMessage.createSetScreenPowerMode(mode);
    }

    private ControlMessage parseCommandEvent() {
        if (buffer.remaining() < COMMAND_PAYLOAD_LENGTH) {
            return null;
        }
        int action     = toUnsigned(buffer.get());
        long timestamp = toUnsigned(buffer.getInt());
        return ControlMessage.createCommandEvent(action, timestamp);
    }

    private static Position readPosition(ByteBuffer buffer) {
        int x = buffer.getInt();
        int y = buffer.getInt();
        int screenWidth = toUnsigned(buffer.getShort());
        int screenHeight = toUnsigned(buffer.getShort());
        return new Position(x, y, screenWidth, screenHeight);
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static int toUnsigned(short value) {
        return value & 0xffff;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static int toUnsigned(byte value) {
        return value & 0xff;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static long toUnsigned(int value) {
        return ((long)value) & 0xffffffff;
    }
}
