/*  Copyright (C) 2015-2024 Andreas Shimokawa, Arjan Schrijver, Daniele
    Gobbetti, Frank Slezak, José Rebelo, mvn23, Petr Kadlec

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.model

import android.os.Parcelable
import android.graphics.drawable.Icon
import kotlinx.parcelize.Parcelize
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.RtlUtils
import nodomain.freeyourgadget.gadgetbridge.util.language.Transliterator

import java.util.concurrent.atomic.AtomicInteger

@Parcelize
data class NotificationSpec @JvmOverloads constructor(
    private val requestedId: Int = -1,
    val `when`: Long = System.currentTimeMillis(),
    var flags: Int = 0,
    var key: String? = null,
    var sender: String? = null,
    var phoneNumber: String? = null,
    var title: String? = null,
    var subject: String? = null,
    var body: String? = null,
    var type: NotificationType? = null,
    var sourceName: String? = null,
    var channelId: String? = null,
    var category: String? = null,
    var cannedReplies: Array<String>? = null,
    var attachedActions: ArrayList<Action?>? = null,
    var sourceAppId: String? = null,
    var iconId: Int = 0,
    var iconPackageId: String? = null,
    var picturePath: String? = null,
    /** NotificationService large/person icon, kept separate from picturePath. */
    var avatarIcon: Icon? = null,
    /** Samsung protocol-v15 MessagingStyle JSON; generated from Android's exposed MessagingStyle only. */
    var messagingStyleJson: String? = null,
    /** Local delta metadata only; never serialized into Samsung attr 44. */
    var samsungMessagingSnapshot: String? = null,
    /**
     * Gear S3 BigPicture compatibility candidate.  This is URI metadata only:
     * image I/O and rasterization are deferred to GearS3DeviceSupport's ordered worker.
     */
    var samsungBigPictureMediaUri: String? = null,
    var samsungBigPictureMediaMimeType: String? = null,
    var dndSuppressed: Int = 0,
    val id: Int = if (requestedId != -1) requestedId else c.incrementAndGet(),
) : DeviceTextAdaptable<NotificationSpec>, Parcelable {
    override fun withRtlFix(): NotificationSpec {
        if (!RtlUtils.rtlSupport()) return this
        return copy(
            sender = sender?.let(RtlUtils::fixRtl),
            subject = subject?.let(RtlUtils::fixRtl),
            title = title?.let(RtlUtils::fixRtl),
            body = body?.let(RtlUtils::fixRtl),
            sourceName = sourceName?.let(RtlUtils::fixRtl)
        )
    }

    override fun transliterated(
        deviceSupport: DeviceSupport,
        transliterator: Transliterator?
    ): NotificationSpec = copy(
            sender = transform(sender, deviceSupport, transliterator),
            subject = transform(subject, deviceSupport, transliterator),
            title = transform(title, deviceSupport, transliterator),
            body = transform(body, deviceSupport, transliterator),
            sourceName = transform(sourceName, deviceSupport, transliterator)
        )

    fun copyOf(): NotificationSpec = copy() // data class .copy() method is not available from Java code

    fun withSender(sender: String?): NotificationSpec = copy(sender = sender)

    fun withMessageDetailsCleared(): NotificationSpec = copy(
        phoneNumber = null,
        sender = null,
        subject = null,
        title = null,
        body = null
    )

    fun withMessageBodyCleared(): NotificationSpec = copy(
        body = null
    )

    companion object {
        private val c = AtomicInteger((System.currentTimeMillis() / 1000).toInt())
    }

    @Parcelize
    data class Action @JvmOverloads constructor(
        var type: Int = TYPE_UNDEFINED,
        var handle: Long = 0,
        var title: String? = null,
        var remoteInputKey: String? = null,
        var remoteInputLabel: String? = null,
        var remoteInputAllowFreeInput: Boolean = false,
        var remoteInputChoices: Array<String>? = null
    ) : Parcelable {
        val isReply: Boolean
            get() = type == TYPE_WEARABLE_REPLY || type == TYPE_SYNTHETIC_REPLY_PHONENR || type == TYPE_CUSTOM_REPLY

        companion object {
            const val TYPE_UNDEFINED: Int = -1
            const val TYPE_WEARABLE_SIMPLE: Int = 0
            const val TYPE_WEARABLE_REPLY: Int = 1
            const val TYPE_SYNTHETIC_REPLY_PHONENR: Int = 2
            const val TYPE_SYNTHETIC_DISMISS: Int = 3
            const val TYPE_SYNTHETIC_DISMISS_ALL: Int = 4
            const val TYPE_SYNTHETIC_MUTE: Int = 5
            const val TYPE_SYNTHETIC_OPEN: Int = 6
            const val TYPE_CUSTOM_SIMPLE: Int = 7
            const val TYPE_CUSTOM_REPLY: Int = 8
        }
    }
}
