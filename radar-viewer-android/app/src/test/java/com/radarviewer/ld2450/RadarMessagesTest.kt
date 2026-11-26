package com.radarviewer.ld2450

import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RadarMessagesTest {
    // Exactly what the ESP32's encodeFrameJson produces (see esp32-radar-display tests).
    @Test fun parsesFrameFromEsp32() {
        val msg = RadarMessages.parse("""{"v":1,"seq":7,"targets":[{"id":1,"x":-782,"y":1713,"speed":-16}]}""")
        val frame = (msg as RadarMessage.Frame).frame
        assertEquals(7L, frame.seq)
        val t = frame.targets.single()
        assertEquals(RadarTarget(1, -782, 1713, -16), t)
        assertEquals(1.883, t.distanceM, 0.001)
        assertEquals(-24.54, t.angleDeg, 0.05)
        assertEquals(-0.16, t.speedMs, 1e-9)
    }

    @Test fun parsesEmptyFrameAndDropsBadIds() {
        val empty = RadarMessages.parse("""{"v":1,"seq":2,"targets":[]}""") as RadarMessage.Frame
        assertTrue(empty.frame.targets.isEmpty())
        val bad = RadarMessages.parse("""{"v":1,"seq":3,"targets":[{"id":9,"x":1,"y":2,"speed":0},{"id":3,"x":5,"y":6,"speed":7}]}""")
        assertEquals(listOf(3), (bad as RadarMessage.Frame).frame.targets.map { it.id })
    }

    @Test fun parsesStatus() {
        val msg = RadarMessages.parse(
            """{"v":1,"type":"status","device":"ld2450-35f4","state":"online","fw":"V2.04.23101915","ip":"192.168.1.243","mirror_x":false}"""
        ) as RadarMessage.Status
        assertEquals(DeviceStatus("ld2450-35f4", true, "V2.04.23101915", "192.168.1.243"), msg.status)
        val offline = RadarMessages.parse("""{"v":1,"type":"status","device":"ld2450-35f4","state":"offline","fw":"","ip":""}""") as RadarMessage.Status
        assertFalse(offline.status.online)
    }

    @Test fun ignoresGarbage() {
        assertNull(RadarMessages.parse("not json"))
        assertNull(RadarMessages.parse("""{"v":1}"""))
        assertNull(RadarMessages.parse("1"))
    }

    @Test fun topics() {
        assertEquals("ld2450/ld2450-35f4/targets", Topics.targets("ld2450-35f4"))
        assertEquals("ld2450/ld2450-35f4/watch", Topics.watch("ld2450-35f4"))
        assertEquals("ld2450-35f4", Topics.deviceIdOf("ld2450/ld2450-35f4/status"))
        assertNull(Topics.deviceIdOf("other/x/status"))
        assertNull(Topics.deviceIdOf("ld2450//status"))
    }

    @Test fun bundledRootCertificatesLoad() {
        val pem = File("src/main/res/raw/radar_root_cas.pem")
        assertEquals(4, Tls.certificateCount(pem.inputStream()))
        Tls.socketFactory(pem.inputStream())  // builds a trust store without throwing
    }

    @Test fun describesMqttErrors() {
        assertEquals("wrong MQTT username or password",
            CloudClient.describe(MqttException(MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt())))
        assertTrue(CloudClient.describe(MqttException(0, javax.net.ssl.SSLHandshakeException("bad cert")))
            .startsWith("secure connection failed"))
    }
}
