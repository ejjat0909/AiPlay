package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AaControlTest {

    @Test
    public void versionRequestMatchesWireVector() {
        // raw big-endian u16s, NOT protobuf: [major=1][minor=1]
        assertArrayEquals(new byte[]{0x00, 0x01, 0x00, 0x01}, AaControl.versionRequest());
    }

    @Test
    public void authCompleteCarriesStatus() {
        assertArrayEquals(new byte[]{0x08, 0x00}, AaControl.authComplete(AaControl.STATUS_OK));
        assertArrayEquals(new byte[]{0x08, 0x01}, AaControl.authComplete(AaControl.STATUS_FAIL));
    }

    @Test
    public void pingRoundTrips() throws Exception {
        byte[] payload = AaControl.pingRequest(1234567L);
        ProtoReader reader = new ProtoReader(payload);
        int tag = reader.readTag();
        assertEquals(1, ProtoReader.field(tag));
        assertEquals(1234567L, reader.readVarint());
        assertFalse(reader.hasMore());
    }

    @Test
    public void channelOpenResponseIsStatusOnly() {
        assertArrayEquals(new byte[]{0x08, 0x00}, AaControl.channelOpenResponse(AaControl.STATUS_OK));
    }

    @Test
    public void serviceDiscoveryAdvertisesAllServedChannels() throws Exception {
        AaControl.ServiceDiscoveryConfig config = new AaControl.ServiceDiscoveryConfig();
        config.headUnitName = "AiPlay-Test";
        config.carModel = "Proton X50";
        config.videoWidth = 1280;
        config.videoHeight = 720;
        config.videoFps = 30;

        byte[] payload = AaControl.serviceDiscoveryResponse(config);

        List<byte[]> channels = new ArrayList<byte[]>();
        ProtoReader reader = new ProtoReader(payload);
        String headUnitName = null;
        boolean leftHandDrive = false;
        while (reader.hasMore()) {
            int tag = reader.readTag();
            switch (ProtoReader.field(tag)) {
                case 1:
                    channels.add(reader.readBytes());
                    break;
                case 2:
                    headUnitName = reader.readString();
                    break;
                case 3:
                    assertEquals("Proton X50", reader.readString());
                    break;
                case 6:
                    leftHandDrive = reader.readVarint() == 1;
                    break;
                default:
                    reader.skip(ProtoReader.wireType(tag));
                    break;
            }
        }

        assertEquals("AiPlay-Test", headUnitName);
        assertTrue(leftHandDrive);
        assertEquals(6, channels.size());

        // Video: channel_id = AaChannel.VIDEO (3), av_channel.stream_type = VIDEO(3), video_configs[0].
        ProtoReader video = new ProtoReader(channels.get(0));
        int channelId = -1;
        int streamType = -1;
        int resolution = -1;
        int fps = -1;
        int dpi = -1;
        while (video.hasMore()) {
            int tag = video.readTag();
            switch (ProtoReader.field(tag)) {
                case 1:
                    channelId = (int) video.readVarint();
                    break;
                case 3:
                    ProtoReader av = new ProtoReader(video.readBytes());
                    while (av.hasMore()) {
                        int avTag = av.readTag();
                        if (ProtoReader.field(avTag) == 1) {
                            streamType = (int) av.readVarint();
                        } else if (ProtoReader.field(avTag) == 4) {
                            ProtoReader vc = new ProtoReader(av.readBytes());
                            while (vc.hasMore()) {
                                int vcTag = vc.readTag();
                                switch (ProtoReader.field(vcTag)) {
                                    case 1:
                                        resolution = (int) vc.readVarint();
                                        break;
                                    case 2:
                                        fps = (int) vc.readVarint();
                                        break;
                                    case 5:
                                        dpi = (int) vc.readVarint();
                                        break;
                                    default:
                                        vc.skip(ProtoReader.wireType(vcTag));
                                        break;
                                }
                            }
                        } else {
                            av.skip(ProtoReader.wireType(avTag));
                        }
                    }
                    break;
                default:
                    video.skip(ProtoReader.wireType(tag));
                    break;
            }
        }
        assertEquals(AaChannel.VIDEO, channelId);
        assertEquals(AaControl.STREAM_VIDEO, streamType);
        assertEquals(2, resolution); // 720p
        assertEquals(1, fps);        // 30
        assertEquals(160, dpi);

        // Channel 2: media audio. audio_type = MEDIA(3), audio_config 48000/16/2.
        ProtoReader media = new ProtoReader(channels.get(1));
        int mediaType = -1;
        int sampleRate = -1;
        int channelCount = -1;
        while (media.hasMore()) {
            int tag = media.readTag();
            if (ProtoReader.field(tag) == 3) {
                ProtoReader av = new ProtoReader(media.readBytes());
                while (av.hasMore()) {
                    int avTag = av.readTag();
                    switch (ProtoReader.field(avTag)) {
                        case 2:
                            mediaType = (int) av.readVarint();
                            break;
                        case 3:
                            ProtoReader ac = new ProtoReader(av.readBytes());
                            while (ac.hasMore()) {
                                int acTag = ac.readTag();
                                if (ProtoReader.field(acTag) == 1) {
                                    sampleRate = (int) ac.readVarint();
                                } else if (ProtoReader.field(acTag) == 3) {
                                    channelCount = (int) ac.readVarint();
                                } else {
                                    ac.skip(ProtoReader.wireType(acTag));
                                }
                            }
                            break;
                        default:
                            av.skip(ProtoReader.wireType(avTag));
                            break;
                    }
                }
            } else {
                media.skip(ProtoReader.wireType(tag));
            }
        }
        assertEquals(AaControl.AUDIO_MEDIA, mediaType);
        assertEquals(48000, sampleRate);
        assertEquals(2, channelCount);

        // Input is the fifth descriptor (video, media, speech, system, input, sensor).
        ProtoReader input = new ProtoReader(channels.get(4));
        int touchWidth = -1;
        while (input.hasMore()) {
            int tag = input.readTag();
            if (ProtoReader.field(tag) == 4) {
                ProtoReader ic = new ProtoReader(input.readBytes());
                while (ic.hasMore()) {
                    int icTag = ic.readTag();
                    if (ProtoReader.field(icTag) == 2) {
                        ProtoReader tc = new ProtoReader(ic.readBytes());
                        while (tc.hasMore()) {
                            int tcTag = tc.readTag();
                            if (ProtoReader.field(tcTag) == 1) {
                                touchWidth = (int) tc.readVarint();
                            } else {
                                tc.skip(ProtoReader.wireType(tcTag));
                            }
                        }
                    } else {
                        ic.skip(ProtoReader.wireType(icTag));
                    }
                }
            } else {
                input.skip(ProtoReader.wireType(tag));
            }
        }
        assertEquals(1280, touchWidth);
    }

    @Test
    public void defaultDiscoveryOffersEveryServedChannelWithRealChannelIds() throws Exception {
        byte[] payload = AaControl.serviceDiscoveryResponse(new AaControl.ServiceDiscoveryConfig());
        ProtoReader reader = new ProtoReader(payload);
        List<Integer> ids = new ArrayList<Integer>();
        while (reader.hasMore()) {
            int tag = reader.readTag();
            if (ProtoReader.field(tag) == 1) {
                ProtoReader channel = new ProtoReader(reader.readBytes());
                while (channel.hasMore()) {
                    int channelTag = channel.readTag();
                    if (ProtoReader.field(channelTag) == 1) {
                        ids.add((int) channel.readVarint());
                    } else {
                        channel.skip(ProtoReader.wireType(channelTag));
                    }
                }
            } else {
                reader.skip(ProtoReader.wireType(tag));
            }
        }
        assertEquals(java.util.Arrays.asList(AaChannel.VIDEO, AaChannel.MEDIA_AUDIO,
                AaChannel.SPEECH_AUDIO, AaChannel.SYSTEM_AUDIO, AaChannel.INPUT, AaChannel.SENSOR),
                ids);
    }

    @Test
    public void speechAndSystemAudioAreSixteenKilohertzMono() throws Exception {
        // ServiceFactory.cpp in OpenAuto: media 48000/16/2, speech and system 16000/16/1.
        AaControl.ServiceDiscoveryConfig config = new AaControl.ServiceDiscoveryConfig();
        config.advertiseVideo = false;
        config.advertiseInput = false;
        config.advertiseSensor = false;
        config.advertiseMediaAudio = false;
        config.advertiseSystemAudio = false;      // speech only
        ProtoReader reader = new ProtoReader(AaControl.serviceDiscoveryResponse(config));
        int rate = -1, depth = -1, channels = -1, audioType = -1;
        boolean inCall = false;
        while (reader.hasMore()) {
            int tag = reader.readTag();
            if (ProtoReader.field(tag) != 1) {
                reader.skip(ProtoReader.wireType(tag));
                continue;
            }
            ProtoReader descriptor = new ProtoReader(reader.readBytes());
            while (descriptor.hasMore()) {
                int dTag = descriptor.readTag();
                if (ProtoReader.field(dTag) != 3) {
                    descriptor.skip(ProtoReader.wireType(dTag));
                    continue;
                }
                ProtoReader av = new ProtoReader(descriptor.readBytes());
                while (av.hasMore()) {
                    int aTag = av.readTag();
                    switch (ProtoReader.field(aTag)) {
                        case 2: audioType = (int) av.readVarint(); break;
                        case 5: inCall = av.readVarint() != 0; break;
                        case 3: {
                            ProtoReader cfg = new ProtoReader(av.readBytes());
                            while (cfg.hasMore()) {
                                int cTag = cfg.readTag();
                                switch (ProtoReader.field(cTag)) {
                                    case 1: rate = (int) cfg.readVarint(); break;
                                    case 2: depth = (int) cfg.readVarint(); break;
                                    case 3: channels = (int) cfg.readVarint(); break;
                                    default: cfg.skip(ProtoReader.wireType(cTag)); break;
                                }
                            }
                            break;
                        }
                        default: av.skip(ProtoReader.wireType(aTag)); break;
                    }
                }
            }
        }
        assertEquals(AaControl.AUDIO_SPEECH, audioType);
        assertEquals(16000, rate);
        assertEquals(16, depth);
        assertEquals(1, channels);
        assertTrue("available_while_in_call", inCall);
    }

    @Test
    public void sensorDescriptorOffersDrivingStatusAndNightMode() throws Exception {
        AaControl.ServiceDiscoveryConfig config = new AaControl.ServiceDiscoveryConfig();
        config.advertiseVideo = false;
        config.advertiseMediaAudio = false;
        config.advertiseSpeechAudio = false;
        config.advertiseSystemAudio = false;
        config.advertiseInput = false;
        ProtoReader reader = new ProtoReader(AaControl.serviceDiscoveryResponse(config));
        List<Integer> types = new ArrayList<Integer>();
        int channelId = -1;
        while (reader.hasMore()) {
            int tag = reader.readTag();
            if (ProtoReader.field(tag) != 1) {
                reader.skip(ProtoReader.wireType(tag));
                continue;
            }
            ProtoReader descriptor = new ProtoReader(reader.readBytes());
            while (descriptor.hasMore()) {
                int dTag = descriptor.readTag();
                if (ProtoReader.field(dTag) == 1) {
                    channelId = (int) descriptor.readVarint();
                } else if (ProtoReader.field(dTag) == 2) {
                    ProtoReader sensors = new ProtoReader(descriptor.readBytes());
                    while (sensors.hasMore()) {
                        int sTag = sensors.readTag();
                        ProtoReader sensor = new ProtoReader(sensors.readBytes());
                        while (sensor.hasMore()) {
                            int tTag = sensor.readTag();
                            if (ProtoReader.field(tTag) == 1) {
                                types.add((int) sensor.readVarint());
                            } else {
                                sensor.skip(ProtoReader.wireType(tTag));
                            }
                        }
                    }
                } else {
                    descriptor.skip(ProtoReader.wireType(dTag));
                }
            }
        }
        assertEquals(AaChannel.SENSOR, channelId);
        assertEquals(java.util.Arrays.asList(13, 10), types);   // DRIVING_STATUS, NIGHT_DATA
    }

    @Test
    public void inputDescriptorAdvertisesOnlyKeysTheReceiverForwards() throws Exception {
        for (int key : AaInput.SUPPORTED_KEYS) {
            assertTrue(AaInput.isSupportedKey(key));
        }
        assertTrue("HOME stays Android's", !AaInput.isSupportedKey(3));
        assertTrue("BACK stays Android's", !AaInput.isSupportedKey(4));
    }

    @Test
    public void parsesChannelOpenRequest() {
        assertEquals(3, AaControl.parseChannelOpenRequest(
                new ProtoWriter().varint(1, 0).varint(2, 3).toByteArray()));
        assertEquals(-1, AaControl.parseChannelOpenRequest(new byte[0]));
    }

    @Test
    public void parsesVersionResponse() {
        AaControl.VersionResponse response =
                AaControl.parseVersionResponse(new byte[]{0x00, 0x01, 0x00, 0x07, 0x00, 0x00});
        assertTrue(response.complete);
        assertTrue(response.matches());
        assertEquals(1, response.major);
        assertEquals(7, response.minor);
        assertEquals(0, response.status);
    }

    @Test
    public void versionMismatchIsRecognised() {
        AaControl.VersionResponse response = AaControl.parseVersionResponse(
                AaControl.versionResponse(1, 1, AaControl.VERSION_MISMATCH));
        assertFalse(response.matches());
        assertTrue(response.toString().contains("MISMATCH"));
    }

    @Test
    public void aVersionResponseWithoutStatusCountsAsMismatch() {
        // aasdk: a missing status defaults to MISMATCH
        AaControl.VersionResponse response =
                AaControl.parseVersionResponse(new byte[]{0x00, 0x01, 0x00, 0x01});
        assertFalse(response.complete);
        assertFalse(response.matches());
        assertFalse(AaControl.parseVersionResponse(new byte[]{(byte) 0x80}).matches());
    }

    @Test
    public void versionIsAasdksOneDotOne() {
        // include/f1x/aasdk/Version.hpp: AASDK_MAJOR = 1, AASDK_MINOR = 1
        assertEquals(1, AaControl.PROTOCOL_MAJOR);
        assertEquals(1, AaControl.PROTOCOL_MINOR);
    }

    @Test
    public void parsesServiceDiscoveryRequest() {
        byte[] payload = new ProtoWriter()
                .string(4, "Pixel 8")
                .string(5, "Google")
                .toByteArray();
        AaControl.ServiceDiscoveryRequest request = AaControl.parseServiceDiscoveryRequest(payload);
        assertEquals("Pixel 8", request.deviceName);
        assertEquals("Google", request.deviceBrand);
    }
}
