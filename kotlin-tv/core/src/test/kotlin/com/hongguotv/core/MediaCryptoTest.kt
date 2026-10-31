package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class MediaCryptoTest {
    private val key=hex("2b7e151628aed2a6abf7158809cf4f3c")
    private val iv=hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    private val plain=hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710")
    private val encrypted=hex("874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee")
    @Test fun parsesAndDecryptsSamplesWithSixteenBytePerSampleIv() {
        val data=fixture(ivSize=16,auxSize=16)
        val prepared=MediaCrypto.prepare(data,key)
        assertEquals(1,prepared.samples.size)
        assertArrayEquals(iv+ByteArray(8),prepared.samples.single().iv)
    }

    @Test fun parsesConstantIvWhenPerSampleIvSizeIsZero() {
        val constant=hex("00112233445566778899aabbccddeeff")
        val data=fixture(ivSize=0,constantIv=constant)
        val prepared=MediaCrypto.prepare(data,key)
        assertEquals(1,prepared.samples.size)
        assertArrayEquals(constant,prepared.samples.single().iv)
    }

    @Test fun rejectsUnsupportedPerSampleIvLength() {
        val data=fixture(ivSize=12,auxSize=12)
        try { MediaCrypto.prepare(data,key); fail("expected unsupported IV length") }
        catch(e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("IV")) }
    }

    @Test fun nistCtrVectorsAcrossEveryUnalignedRange() {
        val prefix=ByteArray(23){0x66}; val source=prefix+encrypted+ByteArray(12){0x77}
        val expected=prefix+plain+ByteArray(12){0x77}
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(23,87,iv)))
        for(start in source.indices) for(length in 1..minOf(29,source.size-start)) assertArrayEquals("$start+$length",expected.copyOfRange(start,start+length),MediaCrypto.decrypt(source.copyOfRange(start,start+length),start.toLong(),key,prepared))
    }
    @Test fun subsamplesPreserveClearBytesAndContinueCtrOffset() {
        val source=byteArrayOf(1,2,3)+encrypted.copyOfRange(0,21)+byteArrayOf(4,5)+encrypted.copyOfRange(21,64)
        val expected=byteArrayOf(1,2,3)+plain.copyOfRange(0,21)+byteArrayOf(4,5)+plain.copyOfRange(21,64)
        val prepared=MediaCrypto.Prepared(byteArrayOf(),listOf(MediaCrypto.Sample(3,24,iv),MediaCrypto.Sample(26,69,iv,21)))
        assertArrayEquals(expected,MediaCrypto.decrypt(source,0,key,prepared))
        assertArrayEquals(expected.copyOfRange(22,47),MediaCrypto.decrypt(source.copyOfRange(22,47),22,key,prepared))
    }
    private fun box(type: String,body: ByteArray)=be32(body.size+8)+type.toByteArray()+body
    private fun extendedBox(type: String, body: ByteArray): ByteArray =
        be32(1) + type.toByteArray() + java.nio.ByteBuffer.allocate(8).putLong(body.size.toLong()+16L).array() + body
    private fun fixture(codec: String="avc1",auxSize: Int=8,spacer: Boolean=false,extendedSpacer: Boolean=false,auxOffset: Int?=null,ivSize: Int=8,constantIv: ByteArray?=null): ByteArray {
        val ftyp=box("ftyp","isom0000".toByteArray())
        val actualAuxSize=if(constantIv!=null) 0 else auxSize
        val sampleIv=constantIv ?: if(ivSize==16) iv+ByteArray(8) else iv.copyOf(8)
        val tencBody=ByteArray(4)+byteArrayOf(0,1.toByte(),(if(constantIv!=null) 0 else ivSize).toByte())+ByteArray(16)+
            (if(constantIv!=null) byteArrayOf(constantIv.size.toByte())+constantIv else byteArrayOf())
        fun moov(offset: Int): ByteArray {
            val sinf=box("sinf",box("frma",codec.toByteArray())+box("schi",box("tenc",tencBody)))
            val entry=box("encv",ByteArray(78)+sinf)
            val stsd=box("stsd",ByteArray(4)+be32(1)+entry)
            val stsz=box("stsz",ByteArray(4)+be32(16)+be32(1))
            val stsc=box("stsc",ByteArray(4)+be32(1)+be32(1)+be32(1)+be32(1))
            val stco=box("stco",ByteArray(4)+be32(1)+be32(4096))
            val saiz=box("saiz",ByteArray(4)+byteArrayOf(actualAuxSize.toByte())+be32(1))
            val saio=box("saio",ByteArray(4)+be32(1)+be32(offset))
            return box("moov",box("trak",box("mdia",box("minf",box("stbl",stsd+stsz+stsc+stco+saiz+saio))))+box("free",sampleIv))
        }
        val padding=when { extendedSpacer -> extendedBox("free",byteArrayOf(1,2,3,4)); spacer -> box("free",byteArrayOf(1,2,3,4)); else -> byteArrayOf() }
        val initial=ftyp+padding+moov(0)
        return ftyp+padding+moov(auxOffset ?: initial.size-sampleIv.size)
    }
    @Test fun headerRestoresOriginalAvcCodecAndSampleIndex() {
        val data=fixture(); val prepared=MediaCrypto.prepare(data,key)
        assertTrue(String(prepared.header).contains("avc1")); assertFalse(String(prepared.header).contains("encv")); assertFalse(String(prepared.header).contains("sinf"))
        assertEquals(1,prepared.samples.size); assertEquals(4096L,prepared.samples[0].start); assertEquals(4112L,prepared.samples[0].end)
        assertEquals(data.size,MediaCrypto.headerEnd(data,5000))
    }
    @Test fun headerSkipsOrdinaryTopLevelPaddingBeforeMoov() {
        val data=fixture(spacer=true)
        val end=MediaCrypto.headerEnd(data,5000)
        assertEquals(data.size,end)
        val prepared=MediaCrypto.prepare(data,key)
        assertEquals(1,prepared.samples.size)
        assertEquals(4096L,prepared.samples[0].start)
        assertTrue(String(prepared.header).contains("avc1"))
    }

    @Test fun headerSkipsExtendedSizeTopLevelPaddingBeforeMoov() {
        val data=fixture(extendedSpacer=true)
        val end=MediaCrypto.headerEnd(data,5000)
        assertEquals(data.size,end)
        val prepared=MediaCrypto.prepare(data,key)
        assertEquals(1,prepared.samples.size)
        assertEquals(4096L,prepared.samples[0].start)
    }

    @Test fun preparesTailMoovAndFetchesAuxiliaryDataByAbsoluteRange() {
        val full=fixture(auxOffset=5000)
        val ftypSize=box("ftyp","isom0000".toByteArray()).size
        val ftyp=full.copyOfRange(0,ftypSize)
        val moov=full.copyOfRange(ftypSize,full.size)
        val auxiliary=hex("0102030405060708")
        var requestedOffset=-1L
        var requestedLength=0
        val prepared=MediaCrypto.prepareMoov(ftyp,moov,9000,key) { offset,length ->
            requestedOffset=offset
            requestedLength=length
            auxiliary
        }
        assertEquals(5000L,requestedOffset)
        assertEquals(8,requestedLength)
        assertEquals(9000L,prepared.headerOffset)
        assertEquals(1,prepared.samples.size)
        assertArrayEquals(auxiliary+ByteArray(8),prepared.samples.single().iv)
        assertTrue(String(prepared.header).contains("avc1"))
        assertFalse(String(prepared.header).contains("encv"))
    }

    @Test fun decryptAppliesHeaderPatchAtItsOriginalFileOffset() {
        val prepared=MediaCrypto.Prepared(byteArrayOf(7,8,9),emptyList(),100)
        val result=MediaCrypto.decrypt(ByteArray(12),95,key,prepared)
        assertArrayEquals(byteArrayOf(7,8,9),result.copyOfRange(5,8))
        assertArrayEquals(ByteArray(5),result.copyOfRange(0,5))
    }

    @Test fun topLevelScannerCanJumpOverLargeMdatToTailMoov() {
        val ftyp=box("ftyp","isom0000".toByteArray())
        val mdat=box("mdat",ByteArray(4096))
        val moov=box("moov",byteArrayOf(1,2,3,4))
        val total=(ftyp+mdat+moov).size.toLong()
        val mdatHeader=MediaCrypto.topLevelBoxHeader(mdat.copyOfRange(0,16),ftyp.size.toLong(),total)
        assertEquals("mdat",mdatHeader.type)
        assertEquals(ftyp.size.toLong()+mdat.size,mdatHeader.end)
        val moovHeader=MediaCrypto.topLevelBoxHeader(moov,mdatHeader.end,total)
        assertEquals("moov",moovHeader.type)
        assertEquals(moov.size.toLong(),moovHeader.size)
    }
    @Test(expected=java.io.IOException::class) fun moovAfterMdatIsRejectedWithoutReadingMediaPayload() {
        val ftyp=box("ftyp","isom0000".toByteArray())
        val mdat=box("mdat",ByteArray(128){it.toByte()})
        val moov=box("moov",byteArrayOf())
        MediaCrypto.headerEnd(ftyp+mdat+moov,ftyp.size.toLong()+mdat.size+moov.size)
    }
    @Test(expected=java.io.IOException::class) fun truncatedAuxiliaryDataFailsClosed() { MediaCrypto.prepare(fixture(auxSize=16),key) }
    @Test(expected=java.io.IOException::class) fun oversizedMoovRejected() { MediaCrypto.headerEnd(box("ftyp","isom0000".toByteArray())+be32(9000000)+"moov".toByteArray(),10000000) }
    @Test fun routerParserHandlesEscapedQuotesAndNestedBraces() {
        val data=ContentRepository.extractRouter("<script>window._ROUTER_DATA = {\"loaderData\":{\"x\":\"a}\\\"b\",\"list\":[1,2]}};</script>")
        assertEquals("a}\"b",data.getJSONObject("loaderData").getString("x"))
    }
}
