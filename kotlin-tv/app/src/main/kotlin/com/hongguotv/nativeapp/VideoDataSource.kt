// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo
import java.io.EOFException
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDataSource(private val video: RemoteVideo): BaseDataSource(true) {
    private var position=0L
    private var remaining=0L
    private var buffer=byteArrayOf()
    private var offset=0
    private var opened=false
    private var uri: Uri?=null

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        if(spec.position<0L) throw IOException("视频读取位置无效")
        if(spec.length<0L && spec.length!=C.LENGTH_UNSET.toLong()) throw IOException("视频读取长度无效")
        if(spec.position>video.total) throw IOException("读取位置超出视频长度")
        uri=spec.uri
        position=spec.position
        remaining=if(spec.length==C.LENGTH_UNSET.toLong()) {
            video.total-position
        } else {
            minOf(spec.length,video.total-position)
        }
        buffer=byteArrayOf()
        offset=0
        opened=true
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray,targetOffset: Int,length: Int): Int {
        if(length==0) return 0
        if(length<0 || targetOffset<0 || targetOffset>target.size-length) {
            throw IndexOutOfBoundsException("视频读取缓冲区范围无效")
        }
        if(remaining<=0L) return C.RESULT_END_OF_INPUT
        if(offset>=buffer.size) {
            val request=minOf(512L*1024L,remaining).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            buffer=video.read(position,request)
            offset=0
        }
        if(buffer.isEmpty()) throw EOFException("视频分段意外结束")
        val available=buffer.size-offset
        if(available<=0) throw EOFException("视频分段数据不完整")
        // Keep the remaining byte count as Long until the final bounded copy size is chosen.
        val count=minOf(length,available,remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if(count<=0) throw EOFException("视频分段数据不完整")
        buffer.copyInto(target,targetOffset,offset,offset+count)
        offset+=count
        position+=count.toLong()
        remaining-=count.toLong()
        bytesTransferred(count)
        return count
    }

    override fun getUri()=uri

    override fun close() {
        buffer=byteArrayOf()
        offset=0
        uri=null
        if(opened) {
            opened=false
            transferEnded()
        }
    }
}
