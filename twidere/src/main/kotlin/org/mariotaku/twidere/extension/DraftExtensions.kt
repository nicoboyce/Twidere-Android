package org.mariotaku.twidere.extension

import android.content.Context
import android.net.Uri
import org.apache.james.mime4j.dom.Header
import org.apache.james.mime4j.dom.MessageServiceFactory
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.dom.field.*
import org.apache.james.mime4j.message.AbstractMessage
import org.apache.james.mime4j.message.BodyPart
import org.apache.james.mime4j.message.MultipartImpl
import org.apache.james.mime4j.message.SimpleContentHandler
import org.apache.james.mime4j.parser.MimeStreamParser
import org.apache.james.mime4j.storage.StorageBodyFactory
import org.apache.james.mime4j.stream.BodyDescriptor
import org.apache.james.mime4j.stream.MimeConfig
import org.mariotaku.ktextension.convert
import org.mariotaku.ktextension.toInt
import org.mariotaku.ktextension.toString
import org.mariotaku.twidere.extension.model.getMimeType
import org.mariotaku.twidere.model.Draft
import org.mariotaku.twidere.model.ParcelableMedia
import org.mariotaku.twidere.model.ParcelableMediaUpdate
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.util.collection.NonEmptyHashMap
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.*


/**
 * Created by mariotaku on 2016/12/7.
 */
fun Draft.writeMimeMessageTo(context: Context, st: OutputStream) {
    val bodyFactory = StorageBodyFactory()
    val storageProvider = bodyFactory.storageProvider
    val contentResolver = context.contentResolver

    val factory = MessageServiceFactory.newInstance()
    val builder = factory.newMessageBuilder()
    val writer = factory.newMessageWriter()

    val message = builder.newMessage() as AbstractMessage

    message.date = Date(this.timestamp)
    message.setFrom(this.account_keys?.map { Mailbox(it.id, it.host) })

    val multipart = MultipartImpl("mixed")
    multipart.addBodyPart(BodyPart().apply {
        setText(bodyFactory.textBody(this@writeMimeMessageTo.text))
    })
    this.media?.forEach { mediaItem ->
        multipart.addBodyPart(BodyPart().apply {
            val uri = Uri.parse(mediaItem.uri)
            val storage = storageProvider.store(contentResolver.openInputStream(uri))
            val mimeType = mediaItem.getMimeType(contentResolver) ?: "application/octet-stream"
            val parameters = NonEmptyHashMap<String, String?>()
            parameters["alt_text"] = mediaItem.alt_text
            parameters["media_type"] = mediaItem.type.toString()
            this.setBody(bodyFactory.binaryBody(storage), mimeType, parameters)
            this.filename = uri.lastPathSegment
        })
    }

    message.setMultipart(multipart)
    writer.writeMessage(message, st)
}

fun Draft.readMimeMessageFrom(context: Context, st: InputStream) {
    val config = MimeConfig()
    val parser = MimeStreamParser(config)
    parser.setContentHandler(DraftContentHandler(this))
    parser.parse(st)
}

private class DraftContentHandler(private val draft: Draft) : SimpleContentHandler() {
    private val processingStack = Stack<SimpleContentHandler>()
    private val mediaList: MutableList<ParcelableMediaUpdate> = ArrayList()
    override fun headers(header: Header) {
        if (processingStack.isEmpty()) {
            draft.timestamp = header.getField("Date").convert {
                (it as DateTimeField).date.time
            }
            draft.account_keys = header.getField("From").convert { field ->
                when (field) {
                    is MailboxField -> {
                        return@convert arrayOf(field.mailbox.convert { UserKey(it.localPart, it.domain) })
                    }
                    is MailboxListField -> {
                        return@convert field.mailboxList.map { UserKey(it.localPart, it.domain) }.toTypedArray()
                    }
                    else -> {
                        return@convert null
                    }
                }
            }
        } else {
            processingStack.peek().headers(header)
        }
    }

    override fun startMultipart(bd: BodyDescriptor) {
    }

    override fun preamble(`is`: InputStream?) {
        processingStack.peek().preamble(`is`)
    }

    override fun startBodyPart() {
        processingStack.push(BodyPartHandler(draft))
    }

    override fun body(bd: BodyDescriptor?, `is`: InputStream?) {
        processingStack.peek().body(bd, `is`)
    }

    override fun endBodyPart() {
        val handler = processingStack.pop() as BodyPartHandler
        handler.media?.let {
            mediaList.add(it)
        }
    }

    override fun epilogue(`is`: InputStream?) {
        processingStack.peek().epilogue(`is`)
    }

    override fun endMultipart() {
        draft.media = mediaList.toTypedArray()
    }
}

private class BodyPartHandler(private val draft: Draft) : SimpleContentHandler() {
    internal lateinit var header: Header
    internal var media: ParcelableMediaUpdate? = null

    override fun headers(header: Header) {
        this.header = header
    }

    override fun body(bd: BodyDescriptor, st: InputStream) {
        body(header, bd, st)
    }

    fun body(header: Header, bd: BodyDescriptor, st: InputStream) {
        val contentDisposition = header.getField("Content-Disposition") as? ContentDispositionField
        if (contentDisposition != null && contentDisposition.isAttachment) {
            when (contentDisposition.filename) {
                else -> {
                    val contentType = header.getField("Content-Type") as? ContentTypeField
                    media = ParcelableMediaUpdate().apply {
                        this.type = contentType?.getParameter("media_type").toInt(ParcelableMedia.Type.UNKNOWN)
                        this.alt_text = contentType?.getParameter("alt_text")
                    }
                }
            }
        } else if (bd.mimeType == "text/plain" && draft.text == null) {
            draft.text = st.toString(Charset.forName(bd.charset))
        }
    }
}
