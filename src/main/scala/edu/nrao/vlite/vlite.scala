package edu.nrao.vlite

import akka.util.ByteString
import akka.io.{ SymmetricPipelineStage, SymmetricPipePair, PipelineContext }
import java.nio.ByteOrder.LITTLE_ENDIAN

final class VLITEHeader(
  val isInvalidData: Boolean,
  val secFromRefEpoch: Int,
  val refEpoch: Int,
  val numberWithinSec: Int,
  val threadID: Int,
  val stationID: Int,
  val lengthBy8: Int = 0
) {
  def isLegacyMode = VLITEHeader.isLegacyMode
  def version = VLITEHeader.version
  def isComplexData = VLITEHeader.isComplexData
  def log2NumChannels = VLITEHeader.log2NumChannels
  def bitsPerSampleLess1 = VLITEHeader.bitsPerSampleLess1
  def extendedDataVersion = VLITEHeader.extendedDataVersion
  def extendedUserData0 = VLITEHeader.extendedUserData0
  def extendedUserData1 = VLITEHeader.extendedUserData1
  def extendedUserData2 = VLITEHeader.extendedUserData2
  def extendedUserData3 = VLITEHeader.extendedUserData3

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

  val isLegacyMode: Boolean = false
  val version: Int = 0 // TODO: check this value
  val isComplexData: Boolean = false
  val log2NumChannels: Int = 0
  val bitsPerSampleLess1: Int = 7
  val extendedDataVersion: Int = 0
  val extendedUserData0: Int = 0
  val extendedUserData1: Int = 0
  val extendedUserData2: Int = 0
  val extendedUserData3: Int = 0
}

trait VLITEConfig extends PipelineContext {
  def dataArraySize: Int
  def initDataArray: Seq[Byte]

  val samplesPerSec = 128 * 1000000

  val samplesPerFrame = dataArraySize / ((VLITEHeader.bitsPerSampleLess1 + 1) / 8)

  val framesPerSec = samplesPerSec / samplesPerFrame

  val framesPerMs = framesPerSec / 1000.0
}

object VLITEStage
    extends SymmetricPipelineStage[VLITEConfig, VLITEHeader, ByteString] {

  override def apply(ctx: VLITEConfig) =
    new SymmetricPipePair[VLITEHeader, ByteString] {

      implicit val byteOrder = LITTLE_ENDIAN

      var buffer: Option[ByteString] = None

      val frameSize = ctx.dataArraySize + 32

      val lengthBy8 = frameSize / 8

      private def shiftMaskOr(acc: Int, v: Int, nbits: Int) =
        (acc << nbits) | (v & ((1 << nbits) - 1))

      def commandPipeline = { hdr: VLITEHeader =>
        val bb = ByteString.newBuilder.
          putInt(
            shiftMaskOr(
              shiftMaskOr(
                if (hdr.isInvalidData) 1 else 0,
                if (hdr.isLegacyMode) 1 else 0,
                1),
              hdr.secFromRefEpoch,
              30)).
          putInt(
            shiftMaskOr(
              shiftMaskOr(
                0, // 2bits
                hdr.refEpoch,
                6),
              hdr.numberWithinSec,
              24)).
          putInt(
            shiftMaskOr(
              shiftMaskOr(
                hdr.version, // 3 bits
                hdr.log2NumChannels,
                5),
              lengthBy8,
              24)).
          putInt(
            shiftMaskOr(
              shiftMaskOr(
                shiftMaskOr(
                  if (hdr.isComplexData) 1 else 0,
                  hdr.bitsPerSampleLess1,
                  5),
                hdr.threadID,
                10),
              hdr.stationID,
              16)).
          putInt(
            shiftMaskOr(
              hdr.extendedDataVersion,
              hdr.extendedUserData0,
              24)).
          putInt(hdr.extendedUserData1).
          putInt(hdr.extendedUserData2).
          putInt(hdr.extendedUserData3)
        bb ++= ctx.initDataArray
        ctx.singleCommand(bb.result)
      }

      protected def extractOne(bs: ByteString): VLITEHeader = {
        val iter = bs.iterator
        val word0 = iter.getInt
        val word1 = iter.getInt
        val word2 = iter.getInt
        val word3 = iter.getInt
        val word4 = iter.getInt
        val extendedUserData1 = iter.getInt
        val extendedUserData2 = iter.getInt
        val extendedUserData3 = iter.getInt
        val hdrLengthBy8 = word2 & ((1 << 24) - 1)
        assert(hdrLengthBy8 == lengthBy8)
        VLITEHeader(
          isInvalidData = (word0 & (1 << 31)) != 0,
          secFromRefEpoch = word0 & ((1 << 30) - 1),
          refEpoch = (word1 >> 24) & ((1 << 6) - 1),
          numberWithinSec = word1 & ((1 << 24) - 1),
          lengthBy8 = hdrLengthBy8,
          threadID = (word3 >> 16) & ((1 << 10) - 1),
          stationID = word3 & ((1 << 16) - 1))
      }

      protected def extractFrameHeaders(bs: ByteString, acc: List[VLITEHeader]):
          (Option[ByteString], List[VLITEHeader]) = {
        if (bs.isEmpty)
          (None, acc)
        else if (bs.length < frameSize)
          (Some(bs.compact), acc)
        else
          bs.splitAt(frameSize) match {
            case (first, rest) =>
              extractFrameHeaders(rest, extractOne(first) :: acc)
          }
      }

      def eventPipeline = { bs: ByteString =>
        val data = if (buffer.isEmpty) bs else buffer.get ++ bs
        val (nb, headers) = extractFrameHeaders(data, Nil)
        buffer = nb
        headers match {
          case Nil        => Nil
          case one :: Nil => ctx.singleEvent(one)
          case many       => many reverseMap (Left(_))
        }
      }
    }
}
