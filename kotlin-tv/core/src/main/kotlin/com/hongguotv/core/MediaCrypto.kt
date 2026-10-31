// Kotlin adaptation of drpys 22261ad; GPL-3.0-only.
package com.hongguotv.core

import java.io.IOException
import java.math.BigInteger

object MediaCrypto {
    data class Sample(val start: Long, val end: Long, val iv: ByteArray, val encryptedOffset: Long = 0)
    data class Prepared(val header: ByteArray, val samples: List<Sample>, val headerOffset: Long = 0)
    data class TopLevelBox(val offset: Long, val size: Long, val headerSize: Int, val type: String) { val end: Long get() = offset + size }
    fun deriveKey(value: String): ByteArray {
        require(value.isNotBlank() && value.length <= 8192) { "内容密钥格式异常" }
        val raw=decode64(value)
        require(raw.size>=34) { "内容密钥格式异常" }
        val length=minOf(raw.size-(raw[0].u() xor raw[1].u() xor raw[2].u())+47,raw.size-1)
        require(length>=33 && 1L+length<=raw.size) { "内容密钥长度异常" }
        val work=raw.copyOfRange(1,1+length); var a=85; var b=246
        for(i in work.indices) {
            val old=work[i].u()
            val previous=if(i and 1 != 0) a else b
            if(i and 1 != 0) a=old else b=old
            work[i]=(-21-Integer.bitCount(i)+(previous xor old)).toByte()
        }
        val encoded=String(work.copyOfRange(1,33),Charsets.US_ASCII)
        require(encoded.length==32 && encoded.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "内容密钥编码无效" }
        return hex(encoded).also { require(it.size==16) { "内容密钥长度无效" } }
    }
    fun decryptUrl(value: String, seed: ByteArray): String {
        require(value.isNotBlank() && value.length <= 16384 && seed.isNotEmpty() && seed.size <= 4096) { "播放地址密钥格式异常" }
        val raw=decode64(value)
        require(raw.size>=20 && raw[0].u()==0xa8 && raw[2].u()==1 && raw[3].u()==0) { "播放地址格式异常" }
        val cipherLength=raw.size-4
        require(cipherLength>0 && cipherLength%16==0) { "播放地址密文长度异常" }
        val constants=hex("4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58")
        val h=digest("SHA-512",digest("SHA-512",seed)+constants)
        val bytes=aes("CBC",h.copyOfRange(0,16),h.copyOfRange(16,32),raw.copyOfRange(4,raw.size))
        var end=bytes.size
        val padding=bytes.last().u()
        if(padding in 1..16 && padding<=end && (end-padding until end).all { bytes[it].u()==padding }) end-=padding
        while(end>0 && bytes[end-1].u()==0) end--
        val decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes,0,end)).toString()
        require(decoded.isNotBlank() && decoded.none { it.isISOControl() }) { "解码后的播放地址无效" }
        return decoded
    }
    private data class Box(val start: Int,val end: Int,val body: Int,val type: String)
    private fun box(data: ByteArray, at: Int, limit: Int = data.size): Box? {
        if(at<0 || at+8>limit) return null
        var size=data.u32(at); var header=8
        if(size==1L) { if(at+16>limit) return null; size=data.i64(at+8); header=16 }
        if(size==0L) size=(limit-at).toLong()
        if(size<header || size>limit-at) return null
        return Box(at,at+size.toInt(),at+header,String(data,at+4,4,Charsets.US_ASCII))
    }
    /** Read only real, sequential child boxes; never search arbitrary payload bytes for a box signature. */
    private fun child(data: ByteArray,type: String,start: Int,limit: Int): Box? {
        if(start<0 || limit>data.size || start>limit) throw IOException("MP4 盒子范围异常")
        var at=start
        var found: Box?=null
        while(at<limit) {
            val item=box(data,at,limit) ?: throw IOException("MP4 子盒结构异常")
            if(item.type==type) {
                if(found!=null) throw IOException("MP4 子盒重复：$type")
                found=item
            }
            at=item.end
        }
        if(at!=limit) throw IOException("MP4 子盒边界异常")
        return found
    }
    private fun fullBox(data: ByteArray,box: Box,versions: Set<Int>,allowedFlags: Long=0L): Pair<Int,Long> {
        if(box.body+4>box.end) throw IOException("MP4 FullBox 头不完整：${box.type}")
        val value=data.u32(box.body)
        val version=(value ushr 24).toInt()
        val flags=value and 0x00ffffffL
        if(version !in versions || flags!=allowedFlags) throw IOException("MP4 FullBox 版本或标志不支持：${box.type}")
        return version to flags
    }
    private fun trackSampleTable(data: ByteArray,track: Box): Box? {
        val mdia=child(data,"mdia",track.body,track.end) ?: return null
        val minf=child(data,"minf",mdia.body,mdia.end) ?: return null
        return child(data,"stbl",minf.body,minf.end)
    }
    private fun encryptedSampleEntry(data: ByteArray,stbl: Box): Pair<Box,Box> {
        val stsd=child(data,"stsd",stbl.body,stbl.end) ?: throw IOException("样本描述缺失")
        if(stsd.body+8>stsd.end) throw IOException("样本描述不完整")
        fullBox(data,stsd,setOf(0))
        val count=data.u32(stsd.body+4)
        if(count !in 1L..64L) throw IOException("样本描述数量异常")
        var at=stsd.body+8
        var first: Box?=null
        repeat(count.toInt()) {
            val entry=box(data,at,stsd.end) ?: throw IOException("样本描述盒异常")
            if(first==null) first=entry
            at=entry.end
        }
        if(at!=stsd.end) throw IOException("样本描述边界异常")
        val entry=first ?: throw IOException("样本描述为空")
        val childStart=when(entry.type) {
            "encv" -> entry.body+78
            "enca" -> {
                if(entry.body+10>entry.end) throw IOException("音频样本描述不完整")
                when(data.u16(entry.body+8)) {
                    0 -> entry.body+28
                    1 -> entry.body+44
                    2 -> entry.body+64
                    else -> throw IOException("不支持的音频样本描述版本")
                }
            }
            else -> throw IOException("样本描述未启用受支持的加密格式")
        }
        if(childStart>entry.end) throw IOException("加密样本描述不完整")
        val sinf=child(data,"sinf",childStart,entry.end) ?: throw IOException("加密方案信息缺失")
        return entry to sinf
    }
    data class EncryptionConfig(val scheme: String, val ivSize: Int, val constantIv: ByteArray? = null)
    private fun encryptionConfig(data: ByteArray, sinf: Box): EncryptionConfig {
        val schm=child(data,"schm",sinf.body,sinf.end) ?: throw IOException("加密方案标识缺失")
        fullBox(data,schm,setOf(0))
        if(schm.body+12!=schm.end) throw IOException("加密方案标识长度异常")
        val scheme=String(data,schm.body+4,4,Charsets.US_ASCII)
        if(scheme!="cenc") throw IOException("暂不支持加密方案：$scheme")
        val schi=child(data,"schi",sinf.body,sinf.end) ?: throw IOException("加密参数缺失")
        val tenc=child(data,"tenc",schi.body,schi.end) ?: throw IOException("加密参数缺失")
        if(tenc.body+23>tenc.end) throw IOException("加密参数不完整")
        val fullBox=data.u32(tenc.body)
        val version=(fullBox ushr 24).toInt()
        val flags=fullBox and 0x00ffffffL
        if(version>1 || flags!=0L) throw IOException("不支持的加密参数版本或标志")
        val patternOrReserved=data[tenc.body+4].u()
        if(patternOrReserved!=0) throw IOException("暂不支持 CENC 模式加密参数")
        val isProtected=data[tenc.body+5].u()
        val ivSize=data[tenc.body+6].u()
        if(isProtected!=1) throw IOException("轨道未启用样本加密")
        if(ivSize==8 || ivSize==16) {
            if(tenc.body+23!=tenc.end) throw IOException("样本 IV 参数长度异常")
            return EncryptionConfig(scheme,ivSize)
        }
        if(ivSize!=0) throw IOException("不支持的样本 IV 长度：$ivSize")
        val kidEnd=tenc.body+23
        if(kidEnd>=tenc.end) throw IOException("固定 IV 参数缺失")
        val constantSize=data[kidEnd].u()
        if(constantSize!=8 && constantSize!=16) throw IOException("固定 IV 长度无效")
        if(kidEnd+1+constantSize!=tenc.end) throw IOException("固定 IV 数据长度异常")
        return EncryptionConfig(scheme,0,data.copyOfRange(kidEnd+1,kidEnd+1+constantSize))
    }
    /** Parse a top-level ISO BMFF box header from a range whose first byte is [offset]. */
    fun topLevelBoxHeader(bytes: ByteArray, offset: Long, total: Long): TopLevelBox {
        if (offset < 0 || total <= offset || bytes.size < 8) throw IOException("视频区块头不完整")
        val size32 = bytes.u32(0)
        val type = String(bytes, 4, 4, Charsets.US_ASCII)
        val header = if (size32 == 1L) 16 else 8
        if (bytes.size < header) throw IOException("视频区块扩展头不完整")
        val size = when (size32) {
            0L -> total - offset
            1L -> bytes.i64(8).also { if (it < 0) throw IOException("视频区块大小异常") }
            else -> size32
        }
        if (size < header || size > total - offset) throw IOException("视频区块范围异常")
        return TopLevelBox(offset, size, header, type)
    }
    /** Locate a front-loaded moov box while safely skipping ordinary top-level boxes.
     *  A moov after mdat is deliberately rejected: loading it requires a separate range
     *  for the index and must not turn a large media payload into an in-memory header. */
    private fun locateMoov(data: ByteArray, total: Long, requireComplete: Boolean): Pair<Int, Long> {
        val ftyp = box(data, 0) ?: throw IOException("视频文件头不完整")
        if (ftyp.type != "ftyp" || ftyp.start != 0) throw IOException("不支持的视频容器")
        var at = ftyp.end
        while (at.toLong() + 8L <= minOf(data.size.toLong(), total)) {
            val size32 = data.u32(at)
            val type = String(data, at + 4, 4, Charsets.US_ASCII)
            val header = if (size32 == 1L) 16 else 8
            if (at + header > data.size) throw IOException("视频索引头不完整")
            val size = when (size32) {
                0L -> throw IOException("不支持未知长度的视频索引块")
                1L -> data.i64(at + 8).also { if (it < 0) throw IOException("视频索引大小异常") }
                else -> size32
            }
            if (size < header) throw IOException("视频索引大小异常")
            val end = at.toLong() + size
            if (end > total || end > 8L * 1024L * 1024L) {
                if (type == "moov") throw IOException("视频索引大小异常")
                throw IOException("视频索引不在文件开头")
            }
            if (type == "moov") {
                if (requireComplete && end > data.size) throw IOException("视频索引不完整")
                return at to end
            }
            if (type == "mdat") throw IOException("视频索引位于媒体数据之后，暂不支持")
            if (end > data.size) throw IOException("视频索引不在文件开头")
            at = end.toInt()
        }
        throw IOException("视频索引不在文件开头")
    }
    fun headerEnd(first: ByteArray,total: Long): Int {
        val (_, end) = locateMoov(first,total,false)
        return end.toInt()
    }
    fun prepare(data: ByteArray,key: ByteArray?, auxiliaryReader: ((Long, Int) -> ByteArray)? = null): Prepared {
        if(key==null) return Prepared(data.copyOf(),emptyList())
        val (moovOffset, _) = locateMoov(data,data.size.toLong(),true)
        val moov=box(data,moovOffset) ?: throw IOException("视频索引不完整")
        require(moov.type=="moov")
        val samples=mutableListOf<Sample>(); val header=data.copyOf()
        if(child(data,"mvex",moov.body,moov.end)!=null) throw IOException("暂不支持 fragmented MP4")
        var cursor=moov.body
        while(cursor<moov.end) {
            val track=box(data,cursor,moov.end) ?: throw IOException("视频轨道格式异常"); cursor=track.end
            if(track.type!="trak") continue
            val stbl=trackSampleTable(data,track) ?: continue
            val saiz=child(data,"saiz",stbl.body,stbl.end) ?: continue
            val saio=child(data,"saio",stbl.body,stbl.end) ?: throw IOException("视频加密索引缺失")
            val stsz=child(data,"stsz",stbl.body,stbl.end) ?: throw IOException("样本索引缺失")
            val stsc=child(data,"stsc",stbl.body,stbl.end) ?: throw IOException("块索引缺失")
            val stco=child(data,"stco",stbl.body,stbl.end)
            val co64=child(data,"co64",stbl.body,stbl.end)
            val chunks=stco ?: co64 ?: throw IOException("块偏移缺失")
            val (sampleEntry,sinf)=encryptedSampleEntry(data,stbl)
            fun count(at: Int,limit: Int): Int { if(at+4>limit) throw IOException("索引越界"); return data.u32(at).also { if(it>200000) throw IOException("索引数量过大") }.toInt() }
            fullBox(data,stsz,setOf(0))
            val n=count(stsz.body+8,stsz.end); val defaultSize=data.u32(stsz.body+4)
            val stszEnd=stsz.body+12+(if(defaultSize==0L) n.toLong()*4 else 0L)
            if(stszEnd!=stsz.end) throw IOException("样本索引长度异常")
            val sizes=LongArray(n) { if(defaultSize>0) defaultSize else data.u32(stsz.body+12+it*4) }
            val (chunkVersion,chunkFlags)=fullBox(data,chunks,setOf(0))
            if(chunkVersion!=0 || chunkFlags!=0L) throw IOException("块偏移标志不支持")
            val chunkCount=count(chunks.body+4,chunks.end); val width=if(chunks.type=="co64") 8 else 4
            if(chunks.body+8+chunkCount.toLong()*width!=chunks.end) throw IOException("块偏移长度异常")
            val offsets=LongArray(chunkCount) { if(width==8) data.i64(chunks.body+8+it*width) else data.u32(chunks.body+8+it*width) }
            if(offsets.any { it<0 } || offsets.zipWithNext().any { (a,b)->b<=a }) throw IOException("块偏移顺序异常")
            if(stco!=null && co64!=null) throw IOException("stco 与 co64 不应同时存在")
            fullBox(data,stsc,setOf(0))
            val entryCount=count(stsc.body+4,stsc.end)
            if(stsc.body+8+entryCount.toLong()*12!=stsc.end || entryCount==0) throw IOException("块索引长度异常")
            if(n==0 || chunkCount==0) throw IOException("视频样本索引为空")
            val entries=(0 until entryCount).map { index ->
                val at=stsc.body+8+index*12
                Triple(data.u32(at),data.u32(at+4),data.u32(at+8))
            }
            if(entries.first().first!=1L || entries.any { it.first==0L || it.second==0L || it.third!=1L } ||
                entries.zipWithNext().any { (a,b) -> b.first<=a.first } || entries.last().first>chunkCount ||
                entries.any { it.first>Int.MAX_VALUE || it.second>n.toLong() }) {
                throw IOException("块映射索引不受支持")
            }
            if(saiz.body+4>saiz.end) throw IOException("辅助索引头不完整")
            val (_,saizFlags)=fullBox(data,saiz,setOf(0),0L)
            val sizeBase=saiz.body+4
            if(sizeBase+5>saiz.end) throw IOException("辅助索引截断")
            val auxDefault=data[sizeBase].u(); val auxCount=count(sizeBase+1,saiz.end)
            if(auxCount!=n || sizeBase+5+(if(auxDefault==0) n else 0)!=saiz.end) throw IOException("辅助索引数量或长度不一致")
            val auxSizes=IntArray(n) { if(auxDefault>0) auxDefault else data[sizeBase+5+it].u() }
            // Sample-group encryption entries (seig) can override the track's default
            // KID/IV/protection settings on a per-sample basis. This parser currently
            // supports only the track-level tenc defaults, so fail closed rather than
            // silently decrypting those samples with the wrong parameters.
            for (groupType in listOf("sgpd", "sbgp")) {
                val group=child(data,groupType,stbl.body,stbl.end) ?: continue
                if (group.body+8<=group.end &&
                    String(data,group.body+4,4,Charsets.US_ASCII)=="seig") {
                    throw IOException("暂不支持 seig 样本组加密参数")
                }
            }
            if(saio.body+4>saio.end) throw IOException("辅助偏移头不完整")
            val (saioVersion,saioFlags)=fullBox(data,saio,setOf(0,1),0L)
            val encryption=encryptionConfig(data,sinf)
            val offsetBase=saio.body+4
            val offsetWidth=if(saioVersion==1) 8 else 4
            if(count(offsetBase,saio.end)!=1 || offsetBase+4+offsetWidth!=saio.end) throw IOException("不支持的辅助索引布局")
            var aux=if(offsetWidth==8) data.i64(offsetBase+4) else data.u32(offsetBase+4)
            val auxLengthLong=auxSizes.sumOf { it.toLong() }
            if(aux<0 || auxLengthLong>8L*1024L*1024L || aux>Long.MAX_VALUE-auxLengthLong) throw IOException("辅助数据范围异常")
            val auxEnd=aux+auxLengthLong
            val auxBytes=if(auxLengthLong==0L) byteArrayOf()
                else if(auxiliaryReader!=null) auxiliaryReader.invoke(aux,auxLengthLong.toInt())
                else if(auxEnd<=data.size) data.copyOfRange(aux.toInt(),auxEnd.toInt())
                else throw IOException("辅助数据超出索引范围")
            if(auxBytes.size!=auxLengthLong.toInt()) throw IOException("辅助数据读取不完整")
            var auxCursor=0
            var sampleIndex=0; var entry=0
            for(chunk in offsets.indices) {
                while(entry+1<entries.size && entries[entry+1].first<=chunk+1) entry++
                val count=entries[entry].second
                if(count>n-sampleIndex || entries[entry].first>chunk+1) throw IOException("样本与块数量不一致")
                var position=offsets[chunk]
                repeat(count.toInt()) {
                    val length=sizes[sampleIndex]; val auxSize=auxSizes[sampleIndex]
                    if(position<0 || length<=0 || position>Long.MAX_VALUE-length) throw IOException("样本索引异常")
                    val ivLength=if(encryption.ivSize==0) 0 else encryption.ivSize
                    if(auxSize<ivLength) throw IOException("样本 IV 数据不完整")
                    val iv=if(ivLength>0) auxBytes.copyOfRange(auxCursor,auxCursor+ivLength)
                        else encryption.constantIv ?: throw IOException("固定 IV 缺失")
                    val subsampleAt=auxCursor+ivLength
                    val remaining=auxSize-ivLength
                    if(remaining==0) samples+=Sample(position,position+length,iv)
                    else {
                        if(remaining<2) throw IOException("加密子样本索引异常")
                        val subs=(auxBytes[subsampleAt].u() shl 8) or auxBytes[subsampleAt+1].u()
                        if(remaining!=2+subs*6) throw IOException("加密子样本索引异常")
                        var offset=position; var encrypted=0L
                        for(sub in 0 until subs) {
                            val at=subsampleAt+2+sub*6; val clear=(auxBytes[at].u() shl 8) or auxBytes[at+1].u(); val size=auxBytes.u32(at+2)
                            offset+=clear
                            if(offset+size>position+length) throw IOException("加密子样本越界")
                            if(size>0) samples+=Sample(offset,offset+size,iv,encrypted)
                            encrypted+=size; offset+=size
                        }
                        if(offset!=position+length) throw IOException("加密子样本长度异常")
                    }
                    position+=length; auxCursor+=auxSize; sampleIndex++
                }
            }
            if(sampleIndex!=n) throw IOException("视频样本不完整")
            val frma=child(data,"frma",sinf.body,sinf.end) ?: throw IOException("原始编码格式缺失")
            if(frma.body+4!=frma.end) throw IOException("原始编码格式无效")
            val original=data.copyOfRange(frma.body,frma.body+4)
            original.copyInto(header,sampleEntry.start+4)
            "free".toByteArray().copyInto(header,sinf.start+4)
            header.fill(0,sinf.body,sinf.end)
        }
        if(samples.isEmpty()) throw IOException("视频加密索引不可用")
        val sorted=samples.sortedBy { it.start }
        if(sorted.zipWithNext().any { (a,b) -> a.end>b.start }) throw IOException("视频样本重叠")
        return Prepared(header,sorted)
    }
    /** Prepare a moov box fetched independently from its original file position. */
    fun prepareMoov(ftyp: ByteArray, moov: ByteArray, moovFileOffset: Long, key: ByteArray,
                    auxiliaryReader: (Long, Int) -> ByteArray): Prepared {
        if (ftyp.isEmpty() || moov.isEmpty() || moovFileOffset < ftyp.size) throw IOException("视频索引位置异常")
        val parsed=prepare(ftyp+moov,key,auxiliaryReader)
        val patchedMoov=parsed.header.copyOfRange(ftyp.size,ftyp.size+moov.size)
        return Prepared(patchedMoov,parsed.samples,moovFileOffset)
    }
    fun decrypt(data: ByteArray,start: Long,key: ByteArray?,prepared: Prepared): ByteArray {
        require(start>=0)
        val result=data.copyOf(); val end=start+data.size
        val patchStart=prepared.headerOffset
        val patchEnd=patchStart+prepared.header.size
        val overlapStart=maxOf(start,patchStart)
        val overlapEnd=minOf(end,patchEnd)
        if(overlapStart<overlapEnd) prepared.header.copyInto(
            result,(overlapStart-start).toInt(),(overlapStart-patchStart).toInt(),(overlapEnd-patchStart).toInt()
        )
        if(key==null) return result
        val samples=prepared.samples
        var low=0; var high=samples.size
        while(low<high) { val middle=(low+high) ushr 1; if(samples[middle].end<=start) low=middle+1 else high=middle }
        for(i in low until samples.size) {
            val sample=samples[i]; if(sample.start>=end) break
            val begin=maxOf(start,sample.start); val stop=minOf(end,sample.end); if(begin>=stop) continue
            val relative=begin-sample.start+sample.encryptedOffset; val skip=(relative%16).toInt()
            val initialCounter=if(sample.iv.size==8) sample.iv+ByteArray(8) else sample.iv
            val counter=(BigInteger(1,initialCounter)+BigInteger.valueOf(relative/16)).and(BigInteger.ONE.shiftLeft(128)-BigInteger.ONE).toByteArray()
            val iv=ByteArray(16); counter.copyInto(iv,maxOf(0,16-counter.size),maxOf(0,counter.size-16),counter.size)
            val decrypted=aes("CTR",key,iv,ByteArray(skip)+result.copyOfRange((begin-start).toInt(),(stop-start).toInt()))
            decrypted.copyInto(result,(begin-start).toInt(),skip)
        }
        return result
    }
}
