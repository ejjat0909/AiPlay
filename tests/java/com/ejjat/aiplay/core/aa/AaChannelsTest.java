package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Input, sensor and audio: wire vectors computed by hand from the aasdk protobufs, and the session's
 * behaviour on each channel (plaintext mode, so the channel logic is tested on its own).
 */
public class AaChannelsTest {

    private static AaControl.ServiceDiscoveryConfig discovery() {
        return new AaControl.ServiceDiscoveryConfig();
    }

    private static final class Rig {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<String> events = new ArrayList<String>();
        final AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                new AaSession.Sink() {
                    @Override
                    public void onEvent(String message) {
                        events.add(message);
                    }
                }, discovery());
        private int consumed;

        AaFrame next() throws Exception {
            byte[] all = out.toByteArray();
            InputStream stream = new ByteArrayInputStream(all, consumed, all.length - consumed);
            AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
            List<AaFrame> frames = new ArrayList<AaFrame>();
            byte[] rest = new byte[all.length - consumed];
            System.arraycopy(all, consumed, rest, 0, rest.length);
            parser.feed(rest, 0, rest.length, frames);
            if (frames.isEmpty()) {
                throw new AssertionError("no frame written; events=" + events);
            }
            AaFrame first = frames.get(0);
            byte[] one = AaFrameCodec.encode(first.channel, first.control, first.messageId,
                    first.payload);
            consumed += one.length;
            return first;
        }

        int unread() {
            return out.size() - consumed;
        }

        void open(int channel) throws Exception {
            session.handle(new AaFrame(channel, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                    new ProtoWriter().varint(2, channel).toByteArray()));
            AaFrame reply = next();
            assertEquals(channel, reply.channel);
            assertTrue(reply.control);
            assertEquals(AaControl.STATUS_OK, reply.payload[1]);
        }
    }

    // ---- input --------------------------------------------------------------------------------

    @Test
    public void inputIdsMatchAasdk() {
        assertEquals(0x8001, AaInput.INPUT_EVENT_INDICATION);
        assertEquals(0x8002, AaInput.BINDING_REQUEST);
        assertEquals(0x8003, AaInput.BINDING_RESPONSE);
        assertEquals(0, AaInput.PRESS);
        assertEquals(1, AaInput.RELEASE);
        assertEquals(2, AaInput.DRAG);
    }

    @Test
    public void touchEventWireBytes() {
        // timestamp 1 -> 08 01 ; touch_event (3): location {x=640 (80 05), y=360 (E8 02), id 0},
        // action_index 0, action PRESS 0
        byte[] expected = {
                0x08, 0x01,
                0x1A, 0x0E,
                0x0A, 0x08, 0x08, (byte) 0x80, 0x05, 0x10, (byte) 0xE8, 0x02, 0x18, 0x00,
                0x10, 0x00,
                0x18, 0x00};
        assertArrayEquals(expected, AaInput.touchEvent(1L, AaInput.PRESS, 0,
                new int[]{640}, new int[]{360}, new int[]{0}));
    }

    @Test
    public void multiTouchListsEveryPointerAndTheChangedIndex() throws Exception {
        byte[] payload = AaInput.touchEvent(77L, AaInput.PRESS, 1,
                new int[]{100, 900}, new int[]{200, 600}, new int[]{0, 1});
        AaInput.Touch touch = AaInput.parseTouch(payload);
        assertEquals(77L, touch.timestamp);
        assertEquals(AaInput.PRESS, touch.action);
        assertEquals(1, touch.actionIndex);
        assertArrayEquals(new int[]{100, 900}, touch.xs);
        assertArrayEquals(new int[]{200, 600}, touch.ys);
        assertArrayEquals(new int[]{0, 1}, touch.pointerIds);
    }

    @Test
    public void buttonEventWireBytes() {
        // timestamp 1; button_event (4) { button_events (1) { scan_code 22, pressed true, meta 0, long false } }
        byte[] expected = {
                0x08, 0x01,
                0x22, 0x0A,
                0x0A, 0x08, 0x08, 0x16, 0x10, 0x01, 0x18, 0x00, 0x20, 0x00};
        assertArrayEquals(expected, AaInput.buttonEvent(1L, AaInput.KEY_DPAD_RIGHT, true));
    }

    @Test
    public void androidActionsMapToTouchActions() {
        assertEquals(AaInput.PRESS, AaInput.touchActionFor(AaInput.ANDROID_ACTION_DOWN));
        assertEquals(AaInput.PRESS, AaInput.touchActionFor(AaInput.ANDROID_ACTION_POINTER_DOWN));
        assertEquals(AaInput.RELEASE, AaInput.touchActionFor(AaInput.ANDROID_ACTION_UP));
        assertEquals(AaInput.RELEASE, AaInput.touchActionFor(AaInput.ANDROID_ACTION_POINTER_UP));
        assertEquals("a cancelled gesture must not leave a finger down on the phone",
                AaInput.RELEASE, AaInput.touchActionFor(AaInput.ANDROID_ACTION_CANCEL));
        assertEquals(AaInput.DRAG, AaInput.touchActionFor(AaInput.ANDROID_ACTION_MOVE));
        assertEquals(-1, AaInput.touchActionFor(7));   // hover move: not sent
    }

    @Test
    public void bindingRequestAcceptedOnlyForAdvertisedKeys() {
        byte[] ok = new ProtoWriter().varint(1, 0x16).varint(1, 0x57).toByteArray();
        assertArrayEquals(new int[]{0x16, 0x57}, AaInput.parseBindingRequest(ok));
        assertTrue(AaInput.bindingAcceptable(AaInput.parseBindingRequest(ok)));
        // HOME (3) is not advertised: refused, as OpenAuto refuses an unsupported scan code
        byte[] home = new ProtoWriter().varint(1, 3).toByteArray();
        assertFalse(AaInput.bindingAcceptable(AaInput.parseBindingRequest(home)));
        // proto3 packed encoding of a repeated field is accepted too
        assertArrayEquals(new int[]{0x13, 0x14}, AaInput.parseBindingRequest(
                new byte[]{0x0A, 0x02, 0x13, 0x14}));
        assertEquals(0, AaInput.parseBindingRequest(new byte[0]).length);
    }

    @Test
    public void touchIsNotSentUntilTheInputChannelIsBound() throws Exception {
        Rig rig = new Rig();
        assertFalse(rig.session.sendTouch(1, AaInput.PRESS, 0, new int[]{1}, new int[]{1}, new int[]{0}));
        assertEquals(0, rig.unread());

        rig.open(AaChannel.INPUT);
        assertFalse("open is not binding", rig.session.isInputBound());
        assertFalse(rig.session.sendTouch(1, AaInput.PRESS, 0, new int[]{1}, new int[]{1}, new int[]{0}));

        rig.session.handle(AaFrame.channel(AaChannel.INPUT, AaInput.BINDING_REQUEST,
                new ProtoWriter().varint(1, 0x13).varint(1, 0x14).toByteArray()));
        AaFrame response = rig.next();
        assertEquals(AaChannel.INPUT, response.channel);
        assertEquals(AaInput.BINDING_RESPONSE, response.messageId);
        assertFalse(response.control);
        assertArrayEquals(new byte[]{0x08, 0x00}, response.payload);   // OK
        assertTrue(rig.session.isInputBound());

        assertTrue(rig.session.sendTouch(5, AaInput.RELEASE, 0, new int[]{10}, new int[]{20}, new int[]{0}));
        AaFrame touch = rig.next();
        assertEquals(AaChannel.INPUT, touch.channel);
        assertEquals(AaInput.INPUT_EVENT_INDICATION, touch.messageId);
        assertFalse(touch.control);
        AaInput.Touch decoded = AaInput.parseTouch(touch.payload);
        assertEquals(AaInput.RELEASE, decoded.action);
        assertEquals(10, decoded.xs[0]);
        assertEquals(20, decoded.ys[0]);
    }

    @Test
    public void aRefusedBindingLeavesInputUnbound() throws Exception {
        Rig rig = new Rig();
        rig.open(AaChannel.INPUT);
        rig.session.handle(AaFrame.channel(AaChannel.INPUT, AaInput.BINDING_REQUEST,
                new ProtoWriter().varint(1, 3).toByteArray()));
        assertArrayEquals(new byte[]{0x08, 0x01}, rig.next().payload);   // FAIL
        assertFalse(rig.session.isInputBound());
        assertFalse(rig.session.sendButton(1, AaInput.KEY_ENTER, true));
    }

    @Test
    public void buttonsOutsideTheAdvertisedSetAreNeverSent() throws Exception {
        Rig rig = new Rig();
        rig.open(AaChannel.INPUT);
        rig.session.handle(AaFrame.channel(AaChannel.INPUT, AaInput.BINDING_REQUEST,
                new ProtoWriter().varint(1, 0x17).toByteArray()));
        rig.next();
        assertFalse("HOME is not advertised", rig.session.sendButton(1, 3, true));
        assertTrue(rig.session.sendButton(1, AaInput.KEY_ENTER, true));
    }

    // ---- sensor -------------------------------------------------------------------------------

    @Test
    public void sensorWireBytes() {
        assertArrayEquals(new byte[]{0x6A, 0x02, 0x08, 0x00}, AaSensor.drivingStatus(0));
        assertArrayEquals(new byte[]{0x52, 0x02, 0x08, 0x00}, AaSensor.nightMode(false));
        assertArrayEquals(new byte[]{0x52, 0x02, 0x08, 0x01}, AaSensor.nightMode(true));
        assertEquals(Boolean.TRUE, AaSensor.parseNightMode(AaSensor.nightMode(true)));
        assertEquals(Boolean.FALSE, AaSensor.parseNightMode(AaSensor.nightMode(false)));
        assertEquals(0, AaSensor.parseDrivingStatus(AaSensor.drivingStatus(0)));
        assertEquals(-1, AaSensor.parseDrivingStatus(AaSensor.nightMode(true)));
        assertEquals(13, AaSensor.parseStartRequest(new byte[]{0x08, 0x0D, 0x10, 0x00}));
        assertEquals(0x8001, AaSensor.START_REQUEST);
        assertEquals(0x8002, AaSensor.START_RESPONSE);
        assertEquals(0x8003, AaSensor.EVENT_INDICATION);
    }

    @Test
    public void sensorStartAnswersThenPushesTheRequestedSensorOnly() throws Exception {
        Rig rig = new Rig();
        rig.session.setSensorSource(new AaSession.SensorSource() {
            @Override
            public boolean isNight() {
                return true;
            }

            @Override
            public int drivingStatus() {
                return 0;
            }
        });
        rig.open(AaChannel.SENSOR);

        rig.session.handle(AaFrame.channel(AaChannel.SENSOR, AaSensor.START_REQUEST,
                new ProtoWriter().varint(1, AaSensor.TYPE_NIGHT_DATA).varint(2, 1000).toByteArray()));
        assertEquals(AaSensor.START_RESPONSE, rig.next().messageId);
        AaFrame event = rig.next();
        assertEquals(AaSensor.EVENT_INDICATION, event.messageId);
        assertEquals(Boolean.TRUE, AaSensor.parseNightMode(event.payload));
        assertEquals("no driving status was asked for", 0, rig.unread());

        rig.session.handle(AaFrame.channel(AaChannel.SENSOR, AaSensor.START_REQUEST,
                new ProtoWriter().varint(1, AaSensor.TYPE_DRIVING_STATUS).toByteArray()));
        rig.next();
        assertEquals(0, AaSensor.parseDrivingStatus(rig.next().payload));
    }

    @Test
    public void anUnofferedSensorIsRefusedWithFail() throws Exception {
        Rig rig = new Rig();
        rig.open(AaChannel.SENSOR);
        rig.session.handle(AaFrame.channel(AaChannel.SENSOR, AaSensor.START_REQUEST,
                new ProtoWriter().varint(1, 1).toByteArray()));   // LOCATION: not offered
        AaFrame response = rig.next();
        assertArrayEquals(new byte[]{0x08, 0x01}, response.payload);
        assertEquals("nothing is pushed for a refused sensor", 0, rig.unread());
    }

    @Test
    public void nightModeChangesAreOnlyPushedToASubscribedPhone() throws Exception {
        Rig rig = new Rig();
        rig.open(AaChannel.SENSOR);
        assertFalse(rig.session.pushNightMode(true));
        assertEquals(0, rig.unread());

        rig.session.handle(AaFrame.channel(AaChannel.SENSOR, AaSensor.START_REQUEST,
                new ProtoWriter().varint(1, AaSensor.TYPE_NIGHT_DATA).toByteArray()));
        rig.next();
        rig.next();
        assertTrue(rig.session.pushNightMode(true));
        assertEquals(Boolean.TRUE, AaSensor.parseNightMode(rig.next().payload));
    }

    // ---- audio focus --------------------------------------------------------------------------

    @Test
    public void audioFocusIsGrantedExceptOnRelease() throws Exception {
        assertEquals(AaAudio.STATE_GAIN, AaAudio.stateFor(AaAudio.FOCUS_GAIN));
        assertEquals(AaAudio.STATE_GAIN, AaAudio.stateFor(AaAudio.FOCUS_GAIN_TRANSIENT));
        assertEquals(AaAudio.STATE_GAIN, AaAudio.stateFor(AaAudio.FOCUS_GAIN_NAVI));
        assertEquals(AaAudio.STATE_LOSS, AaAudio.stateFor(AaAudio.FOCUS_RELEASE));

        Rig rig = new Rig();
        final List<int[]> seen = new ArrayList<int[]>();
        rig.session.setAudioSink(new AaSession.AudioSink() {
            @Override public void onAudioStart(int channel, int session) { }
            @Override public void onAudioData(int c, long t, byte[] d, int o, int l) { }
            @Override public void onAudioStop(int channel) { }
            @Override public void onAudioFocus(int requestedType, int grantedState) {
                seen.add(new int[]{requestedType, grantedState});
            }
        });
        rig.session.handle(AaFrame.control(AaMessageType.AUDIO_FOCUS_REQUEST,
                new ProtoWriter().varint(1, AaAudio.FOCUS_GAIN_NAVI).toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaChannel.CONTROL, reply.channel);
        assertFalse("channel-0 messages are not CONTROL-flagged", reply.control);
        assertEquals(AaMessageType.AUDIO_FOCUS_RESPONSE, reply.messageId);
        assertArrayEquals(new byte[]{0x08, 0x01}, reply.payload);
        assertEquals(1, seen.size());
        assertEquals(AaAudio.FOCUS_GAIN_NAVI, seen.get(0)[0]);

        rig.session.handle(AaFrame.control(AaMessageType.AUDIO_FOCUS_REQUEST,
                new ProtoWriter().varint(1, AaAudio.FOCUS_RELEASE).toByteArray()));
        assertArrayEquals(new byte[]{0x08, 0x03}, rig.next().payload);
    }

    @Test
    public void navigationFocusIsAnsweredWithTypeTwo() throws Exception {
        Rig rig = new Rig();
        rig.session.handle(AaFrame.control(AaMessageType.NAVIGATION_FOCUS_REQUEST,
                new ProtoWriter().varint(1, 1).toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaMessageType.NAVIGATION_FOCUS_RESPONSE, reply.messageId);
        assertArrayEquals(new byte[]{0x08, 0x02}, reply.payload);
    }

    // ---- audio streams ------------------------------------------------------------------------

    @Test
    public void audioFormatsMatchOpenAuto() {
        assertEquals(48000, AaAudio.MEDIA.sampleRate);
        assertEquals(2, AaAudio.MEDIA.channels);
        assertEquals(16000, AaAudio.SPEECH.sampleRate);
        assertEquals(1, AaAudio.SPEECH.channels);
        assertEquals(16000, AaAudio.SYSTEM.sampleRate);
        assertEquals(192000, AaAudio.MEDIA.bytesPerSecond());
        assertEquals(32000, AaAudio.SPEECH.bytesPerSecond());
        assertEquals(null, AaAudio.formatFor(AaChannel.VIDEO));
    }

    @Test
    public void everyAudioChannelSetsUpStartsDeliversAndAcks() throws Exception {
        Rig rig = new Rig();
        final List<String> log = new ArrayList<String>();
        rig.session.setAudioSink(new AaSession.AudioSink() {
            @Override public void onAudioStart(int channel, int session) {
                log.add("start " + channel + " s" + session);
            }
            @Override public void onAudioData(int channel, long ts, byte[] d, int o, int l) {
                log.add("data " + channel + " " + l + "B ts" + ts);
            }
            @Override public void onAudioStop(int channel) {
                log.add("stop " + channel);
            }
            @Override public void onAudioFocus(int requestedType, int grantedState) { }
        });
        int[] channels = {AaChannel.MEDIA_AUDIO, AaChannel.SPEECH_AUDIO, AaChannel.SYSTEM_AUDIO};
        for (int channel : channels) {
            rig.open(channel);
            rig.session.handle(AaFrame.channel(channel, AaAv.SETUP_REQUEST,
                    new ProtoWriter().varint(1, 0).toByteArray()));
            AaFrame setup = rig.next();
            assertEquals(channel, setup.channel);
            assertEquals(AaAv.SETUP_RESPONSE, setup.messageId);
            assertArrayEquals(new byte[]{0x08, 0x02, 0x10, 0x01, 0x18, 0x00}, setup.payload);

            rig.session.handle(AaFrame.channel(channel, AaAv.START_INDICATION,
                    new ProtoWriter().varint(1, 9).varint(2, 0).toByteArray()));
            assertEquals(0, rig.unread());
            assertTrue(rig.session.isAudioStarted(channel));

            byte[] media = new byte[8 + 4];
            media[7] = 0x20;        // ts 32
            rig.session.handle(AaFrame.channel(channel, AaAv.MEDIA_WITH_TIMESTAMP, media));
            AaFrame ack = rig.next();
            assertEquals(channel, ack.channel);
            assertEquals(AaAv.MEDIA_ACK, ack.messageId);
            assertArrayEquals(new byte[]{0x08, 0x09, 0x10, 0x01}, ack.payload);   // session 9, value 1

            rig.session.handle(AaFrame.channel(channel, AaAv.STOP_INDICATION, new byte[0]));
            assertFalse(rig.session.isAudioStarted(channel));
        }
        assertEquals("start 4 s9", log.get(0));
        assertEquals("data 4 4B ts32", log.get(1));
        assertEquals("stop 4", log.get(2));
        assertEquals(9, log.size());
    }

    @Test
    public void audioArrivingBeforeItsChannelIsOpenIsIgnoredNotAcked() throws Exception {
        Rig rig = new Rig();
        rig.session.handle(AaFrame.channel(AaChannel.MEDIA_AUDIO, AaAv.MEDIA, new byte[]{1, 2}));
        assertEquals(0, rig.unread());
        assertTrue(rig.events.toString().contains("before the channel was opened"));
    }

    @Test
    public void shutdownStopsEveryStartedAudioStreamExactlyOnce() throws Exception {
        Rig rig = new Rig();
        final int[] stops = {0};
        rig.session.setAudioSink(new AaSession.AudioSink() {
            @Override public void onAudioStart(int channel, int session) { }
            @Override public void onAudioData(int c, long t, byte[] d, int o, int l) { }
            @Override public void onAudioStop(int channel) { stops[0]++; }
            @Override public void onAudioFocus(int requestedType, int grantedState) { }
        });
        rig.open(AaChannel.MEDIA_AUDIO);
        rig.open(AaChannel.SPEECH_AUDIO);
        for (int channel : new int[]{AaChannel.MEDIA_AUDIO, AaChannel.SPEECH_AUDIO}) {
            rig.session.handle(AaFrame.channel(channel, AaAv.START_INDICATION,
                    new ProtoWriter().varint(1, 1).toByteArray()));
        }
        rig.session.handle(AaFrame.control(AaMessageType.SHUTDOWN_REQUEST, new byte[0]));
        rig.session.handle(AaFrame.control(AaMessageType.SHUTDOWN_REQUEST, new byte[0]));
        assertEquals(2, stops[0]);
    }
}
