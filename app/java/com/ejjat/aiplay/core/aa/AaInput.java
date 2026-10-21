package com.ejjat.aiplay.core.aa;

/**
 * The Android Auto input channel: how the head unit's touch screen and keys reach the phone.
 *
 * <p><strong>Direction matters.</strong> The head unit <em>captures</em> input on its own screen and
 * <em>sends</em> it to the phone. The phone only sends one message on this channel, a binding request
 * naming the keys it wants; the head unit answers it and from then on sends
 * {@code INPUT_EVENT_INDICATION} messages. (An earlier plan in this repository had it backwards.)</p>
 *
 * <p>Every id and field number was read from aasdk (f1xpl/aasdk, GPL-3.0):
 * {@code InputChannelMessageIdsEnum.proto}, {@code BindingRequestMessage.proto},
 * {@code BindingResponseMessage.proto}, {@code InputEventIndicationMessage.proto},
 * {@code TouchEventData.proto}, {@code TouchLocationData.proto}, {@code TouchActionEnum.proto},
 * {@code ButtonEventsData.proto}, {@code ButtonEventData.proto}, {@code ButtonCodeEnum.proto}, with
 * behaviour checked against OpenAuto's {@code InputService.cpp}. OpenAuto only ever sends one touch
 * pointer; multi-touch here follows the message shape (a list of locations plus an action index) and
 * is not verified against a phone.</p>
 */
public final class AaInput {

    // InputChannelMessage.Enum
    public static final int INPUT_EVENT_INDICATION = 0x8001;
    public static final int BINDING_REQUEST = 0x8002;
    public static final int BINDING_RESPONSE = 0x8003;

    /** {@code TouchAction.Enum}. */
    public static final int PRESS = 0;
    public static final int RELEASE = 1;
    public static final int DRAG = 2;

    /** {@code ButtonCode.Enum} values the receiver can deliver (they equal Android key codes). */
    public static final int KEY_DPAD_UP = 0x13;
    public static final int KEY_DPAD_DOWN = 0x14;
    public static final int KEY_DPAD_LEFT = 0x15;
    public static final int KEY_DPAD_RIGHT = 0x16;
    public static final int KEY_ENTER = 0x17;
    public static final int KEY_TOGGLE_PLAY = 0x55;
    public static final int KEY_NEXT = 0x57;
    public static final int KEY_PREV = 0x58;
    public static final int KEY_PLAY = 0x7E;
    public static final int KEY_PAUSE = 0x7F;

    /**
     * The keys AiPlay advertises in service discovery. HOME (3) and BACK (4) are deliberately absent:
     * on the head unit they leave the projection screen, so they stay Android's. A key is only
     * advertised if the receiver forwards it.
     */
    public static final int[] SUPPORTED_KEYS = {
            KEY_DPAD_UP, KEY_DPAD_DOWN, KEY_DPAD_LEFT, KEY_DPAD_RIGHT, KEY_ENTER,
            KEY_TOGGLE_PLAY, KEY_NEXT, KEY_PREV, KEY_PLAY, KEY_PAUSE};

    // android.view.MotionEvent masked actions (stable API values; core/ must not import android.*).
    public static final int ANDROID_ACTION_DOWN = 0;
    public static final int ANDROID_ACTION_UP = 1;
    public static final int ANDROID_ACTION_MOVE = 2;
    public static final int ANDROID_ACTION_CANCEL = 3;
    public static final int ANDROID_ACTION_POINTER_DOWN = 5;
    public static final int ANDROID_ACTION_POINTER_UP = 6;

    private AaInput() {
    }

    public static String name(int id) {
        switch (id) {
            case INPUT_EVENT_INDICATION: return "INPUT_EVENT_INDICATION";
            case BINDING_REQUEST: return "BINDING_REQUEST";
            case BINDING_RESPONSE: return "BINDING_RESPONSE";
            default: return String.format("0x%04X", id);
        }
    }

    public static boolean isSupportedKey(int code) {
        for (int key : SUPPORTED_KEYS) {
            if (key == code) {
                return true;
            }
        }
        return false;
    }

    /** {@code BindingRequest.scan_codes = 1} (repeated int32, accepted packed or unpacked). */
    public static int[] parseBindingRequest(byte[] payload) {
        int[] codes = new int[8];
        int count = 0;
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                if (ProtoReader.field(tag) == 1 && ProtoReader.wireType(tag) == 0) {
                    if (count == codes.length) {
                        codes = java.util.Arrays.copyOf(codes, count * 2);
                    }
                    codes[count++] = (int) reader.readVarint();
                } else if (ProtoReader.field(tag) == 1 && ProtoReader.wireType(tag) == 2) {
                    byte[] packed = reader.readBytes();
                    ProtoReader inner = new ProtoReader(packed);
                    while (inner.hasMore()) {
                        if (count == codes.length) {
                            codes = java.util.Arrays.copyOf(codes, count * 2);
                        }
                        codes[count++] = (int) inner.readVarint();
                    }
                } else {
                    reader.skip(ProtoReader.wireType(tag));
                }
            }
        } catch (AaProtocolException ignored) {
            // truncated: the codes read so far are what the phone asked for
        }
        return java.util.Arrays.copyOf(codes, count);
    }

    /** True when every requested code is one the receiver advertised, as OpenAuto checks it. */
    public static boolean bindingAcceptable(int[] requested) {
        for (int code : requested) {
            if (!isSupportedKey(code)) {
                return false;
            }
        }
        return true;
    }

    /** {@code BindingResponse}: {@code status = 1} (OK 0, FAIL 1). */
    public static byte[] bindingResponse(boolean ok) {
        return AaControl.channelOpenResponse(ok ? AaControl.STATUS_OK : AaControl.STATUS_FAIL);
    }

    /**
     * Maps an Android masked action to a {@code TouchAction}, or -1 when the action is not sent
     * (hover, scroll, outside...). CANCEL is sent as a release so the phone never keeps a finger down.
     */
    public static int touchActionFor(int androidMaskedAction) {
        switch (androidMaskedAction) {
            case ANDROID_ACTION_DOWN:
            case ANDROID_ACTION_POINTER_DOWN:
                return PRESS;
            case ANDROID_ACTION_UP:
            case ANDROID_ACTION_POINTER_UP:
            case ANDROID_ACTION_CANCEL:
                return RELEASE;
            case ANDROID_ACTION_MOVE:
                return DRAG;
            default:
                return -1;
        }
    }

    /**
     * {@code InputEventIndication} with a {@code TouchEvent}: {@code timestamp = 1} (microseconds),
     * {@code touch_event = 3} {@code { touch_location = 1 { x = 1, y = 2, pointer_id = 3 },
     * action_index = 2, touch_action = 3 }}. All pointers are listed; {@code actionIndex} is the one
     * whose state changed.
     */
    public static byte[] touchEvent(long timestampMicros, int action, int actionIndex,
                                    int[] xs, int[] ys, int[] pointerIds) {
        if (xs.length != ys.length || xs.length != pointerIds.length || xs.length == 0) {
            throw new IllegalArgumentException("need at least one pointer, equal-length arrays");
        }
        ProtoWriter touch = new ProtoWriter();
        for (int i = 0; i < xs.length; i++) {
            touch.message(1, new ProtoWriter().varint(1, xs[i]).varint(2, ys[i])
                    .varint(3, pointerIds[i]));
        }
        touch.varint(2, actionIndex).varint(3, action);
        return new ProtoWriter().varint(1, timestampMicros).message(3, touch).toByteArray();
    }

    /**
     * {@code InputEventIndication} with a {@code ButtonEvents}: {@code button_event = 4}
     * {@code { button_events = 1 { scan_code = 1, is_pressed = 2, meta = 3, long_press = 4 } }}.
     */
    public static byte[] buttonEvent(long timestampMicros, int scanCode, boolean pressed) {
        ProtoWriter button = new ProtoWriter().varint(1, scanCode).bool(2, pressed).varint(3, 0)
                .bool(4, false);
        return new ProtoWriter().varint(1, timestampMicros)
                .message(4, new ProtoWriter().message(1, button)).toByteArray();
    }

    /** One decoded touch indication, for tests and the probe's mirror in Python. */
    public static final class Touch {
        public long timestamp;
        public int action = -1;
        public int actionIndex;
        public int[] xs = new int[0];
        public int[] ys = new int[0];
        public int[] pointerIds = new int[0];
    }

    /** Decodes {@link #touchEvent}'s output (tests only; the phone does the real decoding). */
    public static Touch parseTouch(byte[] payload) throws AaProtocolException {
        Touch touch = new Touch();
        ProtoReader reader = new ProtoReader(payload);
        while (reader.hasMore()) {
            int tag = reader.readTag();
            if (ProtoReader.field(tag) == 1) {
                touch.timestamp = reader.readVarint();
            } else if (ProtoReader.field(tag) == 3) {
                ProtoReader event = new ProtoReader(reader.readBytes());
                java.util.List<int[]> points = new java.util.ArrayList<int[]>();
                while (event.hasMore()) {
                    int eventTag = event.readTag();
                    switch (ProtoReader.field(eventTag)) {
                        case 1: {
                            ProtoReader location = new ProtoReader(event.readBytes());
                            int[] point = new int[3];
                            while (location.hasMore()) {
                                int locationTag = location.readTag();
                                int field = ProtoReader.field(locationTag);
                                if (field >= 1 && field <= 3) {
                                    point[field - 1] = (int) location.readVarint();
                                } else {
                                    location.skip(ProtoReader.wireType(locationTag));
                                }
                            }
                            points.add(point);
                            break;
                        }
                        case 2:
                            touch.actionIndex = (int) event.readVarint();
                            break;
                        case 3:
                            touch.action = (int) event.readVarint();
                            break;
                        default:
                            event.skip(ProtoReader.wireType(eventTag));
                            break;
                    }
                }
                touch.xs = new int[points.size()];
                touch.ys = new int[points.size()];
                touch.pointerIds = new int[points.size()];
                for (int i = 0; i < points.size(); i++) {
                    touch.xs[i] = points.get(i)[0];
                    touch.ys[i] = points.get(i)[1];
                    touch.pointerIds[i] = points.get(i)[2];
                }
            } else {
                reader.skip(ProtoReader.wireType(tag));
            }
        }
        return touch;
    }
}
