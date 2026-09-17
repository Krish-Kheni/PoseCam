package com.posecam

import com.google.ar.core.Frame
import com.google.ar.core.ImageMetadata
import com.google.ar.core.exceptions.MetadataNotFoundException
import com.google.ar.core.exceptions.NotYetAvailableException

/** GL thread: reads the capture result ARCore attached to a frame. */
object FrameMetadataReader {
    fun read(frame: Frame): FrameMetadata? {
        val m = try {
            frame.imageMetadata
        } catch (e: NotYetAvailableException) {
            return null
        }
        return FrameMetadata(
            exposureTimeNs = optional { m.getLong(ImageMetadata.SENSOR_EXPOSURE_TIME) },
            frameDurationNs = optional { m.getLong(ImageMetadata.SENSOR_FRAME_DURATION) },
            rollingShutterSkewNs = optional { m.getLong(ImageMetadata.SENSOR_ROLLING_SHUTTER_SKEW) },
            sensitivityIso = optional { m.getInt(ImageMetadata.SENSOR_SENSITIVITY) },
            focusDistanceDiopters = optional { m.getFloat(ImageMetadata.LENS_FOCUS_DISTANCE) },
            focalLengthMm = optional { m.getFloat(ImageMetadata.LENS_FOCAL_LENGTH) },
            opticalStabilizationMode = optional { m.getByte(ImageMetadata.LENS_OPTICAL_STABILIZATION_MODE).toInt() },
        )
    }

    private inline fun <T> optional(read: () -> T): T? = try {
        read()
    } catch (e: MetadataNotFoundException) {
        null
    }
}
