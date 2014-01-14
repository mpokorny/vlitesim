package edu.nrao.vlite

import java.nio.ByteOrder.LITTLE_ENDIAN

final class VLITEHeader(
  val isInvalidData: Boolean,
  val secFromRefEpoch: Int,
  val refEpoch: Int,
  val numberWithinSec: Int,
  val threadID: Int,
  val stationID: Int,
  val lengthBy8: Int = 0
) extends Frame[VLITEHeader] {
  def isLegacyMode: Boolean = false
  def version: Int = 0 // TODO: check this value
  def isComplexData: Boolean = false
  def log2NumChannels: Int = 0
  def bitsPerSampleLess1: Int = 7
  def extendedDataVersion: Int = 0
  def extendedUserData0: Int = 0
  def extendedUserData1: Int = 0
  def extendedUserData2: Int = 0
  def extendedUserData3: Int = 0

  override def equals(other: Any) = other match {
    case that: VLITEHeader =>
      that.isInvalidData == isInvalidData &&
      that.secFromRefEpoch == secFromRefEpoch &&
      that.refEpoch == refEpoch &&
      that.numberWithinSec == numberWithinSec &&
      that.lengthBy8 == lengthBy8 &&
      that.threadID == threadID &&
      that.stationID == stationID
    case _ => false
  }

  override def hashCode: Int =
    41 * (
      41 * (
        41 * (
          41 * (
            41 * (
              41 * (
                41 + isInvalidData.hashCode
              ) + secFromRefEpoch
            ) + refEpoch
          ) + numberWithinSec
        ) + lengthBy8
      ) + threadID
    ) + stationID

  override def toString =
    s"VLITEHeader($isInvalidData,$secFromRefEpoch,$refEpoch,$numberWithinSec,$threadID,$stationID,$lengthBy8)"
}

object VLITEHeader {
  def apply(
    isInvalidData: Boolean,
    secFromRefEpoch: Int,
    refEpoch: Int,
    numberWithinSec: Int,
    threadID: Int,
    stationID: Int,
    lengthBy8: Int = 0): VLITEHeader =
    new VLITEHeader(
      isInvalidData = isInvalidData,
      secFromRefEpoch = secFromRefEpoch,
      refEpoch = refEpoch,
      numberWithinSec = numberWithinSec,
      lengthBy8 = lengthBy8,
      threadID = threadID,
      stationID = stationID)

  def unapply(vltHdr: VLITEHeader) = {
    Some((
      vltHdr.isInvalidData,
      vltHdr.secFromRefEpoch,
      vltHdr.refEpoch,
      vltHdr.numberWithinSec,
      vltHdr.threadID,
      vltHdr.stationID,
      vltHdr.lengthBy8))
  }

  implicit object Builder extends FrameBuilder[VLITEHeader] {
    val frameSize: Short = 32

    private def shiftMaskOr(acc: Int, v: Int, nbits: Int) =
      (acc << nbits) | (v & ((1 << nbits) - 1))

    def apply(hdr: VLITEHeader, buffer: TypedBuffer[VLITEHeader]) = {
      val word0 =
        shiftMaskOr(
          shiftMaskOr(
            if (hdr.isInvalidData) 1 else 0,
            if (hdr.isLegacyMode) 1 else 0,
            1),
          hdr.secFromRefEpoch,
          30)
      val word1 =
        shiftMaskOr(
          shiftMaskOr(
            0, // 2bits
            hdr.refEpoch,
            6),
          hdr.numberWithinSec,
          24)
      val word2 =
        shiftMaskOr(
          shiftMaskOr(
            hdr.version, // 3 bits
            hdr.log2NumChannels,
            5),
          hdr.lengthBy8,
          24)
      val word3 =
        shiftMaskOr(
          shiftMaskOr(
            shiftMaskOr(
              if (hdr.isComplexData) 1 else 0,
              hdr.bitsPerSampleLess1,
              5),
            hdr.threadID,
            10),
          hdr.stationID,
          16)
      val word4 =
        shiftMaskOr(
          hdr.extendedDataVersion,
          hdr.extendedUserData0,
          24)
      val b = buffer.byteBuffer
      b.order(LITTLE_ENDIAN)
      b.putInt(word0).
        putInt(word1).
        putInt(word2).
        putInt(word3).
        putInt(word4).
        putInt(hdr.extendedUserData1).
        putInt(hdr.extendedUserData2).
        putInt(hdr.extendedUserData3)
    }
  }

  implicit object Reader extends FrameReader[VLITEHeader] {
    def apply(buffer: TypedBuffer[VLITEHeader]) = {
      val b = buffer.byteBuffer
      b.order(LITTLE_ENDIAN)
      val word0 = b.getInt()
      val word1 = b.getInt()
      val word2 = b.getInt()
      val word3 = b.getInt()
      val word4 = b.getInt()
      val extendedUserData1 = b.getInt()
      val extendedUserData2 = b.getInt()
      val extendedUserData3 = b.getInt()
      VLITEHeader(
        isInvalidData = (word0 & (1 << 31)) != 0,
        secFromRefEpoch = word0 & ((1 << 30) - 1),
        refEpoch = (word1 >> 24) & ((1 << 6) - 1),
        numberWithinSec = word1 & ((1 << 24) - 1),
        lengthBy8 = word2 & ((1 << 24) - 1),
        threadID = (word3 >> 16) & ((1 << 10) - 1),
        stationID = word3 & ((1 << 16) - 1))
    }
  }
}

final class VLITEFrame(val header: VLITEHeader) extends Frame[VLITEFrame] {
  override def toString = s"VLITEFrame($header)"

  override def equals(other: Any): Boolean = other match {
    case that: VLITEFrame => that.header == header
    case _ => false
  }

  override def hashCode: Int = header.hashCode
}

object VLITEFrame {
  def apply(hdr: VLITEHeader): VLITEFrame =
    new VLITEFrame(
      VLITEHeader(
        isInvalidData = hdr.isInvalidData,
        secFromRefEpoch = hdr.secFromRefEpoch,
        refEpoch = hdr.refEpoch,
        numberWithinSec = hdr.numberWithinSec,
        threadID = hdr.threadID,
        stationID = hdr.stationID,
        lengthBy8 = Builder.frameSize / 8))

  def unapply(frame: VLITEFrame) = Some((frame.header))

  private val dataArraySize = 1000 // FIXME: should be 5000

  implicit object Builder extends FrameBuilder[VLITEFrame] {

    val frameSize = (dataArraySize + VLITEHeader.Builder.frameSize).toShort

    def apply(frame: VLITEFrame, buffer: TypedBuffer[VLITEFrame]) = {
      buffer.slice[VLITEHeader].write(frame.header)
      val b = buffer.byteBuffer
      b.position(b.position + dataArraySize)
    }
  }

  implicit object Reader extends FrameReader[VLITEFrame] {
    def apply(buffer: TypedBuffer[VLITEFrame]) = {
      val header = buffer.slice[VLITEHeader].read
      val b = buffer.byteBuffer
      b.position(b.position + dataArraySize)
      VLITEFrame(header)
    }
  }

  implicit object EthernetBuilder extends Ethernet.Builder[VLITEFrame]

  implicit object EthernetReader extends Ethernet.Reader[VLITEFrame]
}
