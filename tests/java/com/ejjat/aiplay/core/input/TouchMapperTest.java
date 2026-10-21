package com.ejjat.aiplay.core.input;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TouchMapperTest {

    private static final float ASPECT = 1280f / 720f;

    @Test
    public void fitCentresTheLargestSixteenByNineRectangle() {
        // 1920x1080: exactly 16:9, fills the screen
        assertArrayEquals(new float[]{0, 0, 1920, 1080}, TouchMapper.fit(1920, 1080, ASPECT), 0.01f);
        // 1024x600 (a common head unit panel): width-limited? 1024/1.778 = 576 high, fits in 600
        float[] panel = TouchMapper.fit(1024, 600, ASPECT);
        assertEquals(1024f, panel[2], 0.01f);
        assertEquals(576f, panel[3], 0.01f);
        assertEquals(12f, panel[1], 0.01f);      // 12 px bar above and below
        // 1280x480 bar display: height-limited, bars left and right
        float[] bar = TouchMapper.fit(1280, 480, ASPECT);
        assertEquals(480f, bar[3], 0.01f);
        assertEquals(853.33f, bar[2], 0.05f);
        assertEquals(213.33f, bar[0], 0.05f);
    }

    @Test
    public void theCentreOfTheVideoMapsToTheCentreOfThePhoneWhateverThePanel() {
        float[][] panels = {{1920, 1080}, {1024, 600}, {1280, 480}, {800, 480}, {1080, 1920}};
        for (float[] panel : panels) {
            TouchMapper mapper = new TouchMapper(1280, 720);
            float[] rect = TouchMapper.fit(panel[0], panel[1], ASPECT);
            mapper.setVideoRect(rect[0], rect[1], rect[2], rect[3]);
            assertTrue(mapper.inside(panel[0] / 2, panel[1] / 2));
            assertEquals("x on " + panel[0] + "x" + panel[1], 640, mapper.mapX(panel[0] / 2), 1);
            assertEquals("y on " + panel[0] + "x" + panel[1], 360, mapper.mapY(panel[1] / 2), 1);
        }
    }

    @Test
    public void mappingIsRelativeToTheVideoNotTheScreen() {
        // 1024x600 panel: video is 1024x576 starting 12 px down.
        TouchMapper mapper = new TouchMapper(1280, 720);
        mapper.setVideoRect(0, 12, 1024, 576);
        // top-left corner of the VIDEO is phone (0,0), not the screen's (0,0)
        assertEquals(0, mapper.mapX(0));
        assertEquals(0, mapper.mapY(12));
        // a tap at the screen's own y = 12 + 288 is the video's vertical middle
        assertEquals(360, mapper.mapY(12 + 288));
        // 3/4 across and down
        assertEquals(960, mapper.mapX(768));
        assertEquals(540, mapper.mapY(12 + 432));
    }

    @Test
    public void letterboxBarsAreOutsideAndEdgesClamp() {
        TouchMapper mapper = new TouchMapper(1280, 720);
        mapper.setVideoRect(213.33f, 0, 853.33f, 480);
        assertFalse("left bar", mapper.inside(100, 240));
        assertFalse("right bar", mapper.inside(1200, 240));
        assertTrue(mapper.inside(640, 240));
        // a drag that leaves the video keeps reporting the nearest edge, never out of range
        assertEquals(0, mapper.mapX(0));
        assertEquals(1279, mapper.mapX(5000));
        assertEquals(0, mapper.mapY(-50));
        assertEquals(719, mapper.mapY(9999));
    }

    @Test
    public void nothingIsInsideBeforeTheVideoRectIsKnown() {
        TouchMapper mapper = new TouchMapper(1280, 720);
        assertFalse(mapper.inside(10, 10));
    }
}
