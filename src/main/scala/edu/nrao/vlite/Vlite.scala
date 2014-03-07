package edu.nrao.vlite

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import akka.actor._
import akka.pattern.ask
import akka.util.{ ByteString, Timeout }
import akka.io.{ PipelineStage, PipePair, PipelineContext }
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
  val dataArraySize: Int

  def dataArray: ByteString

  val samplesPerSec = 128 * 1000000

  lazy val samplesPerFrame =
    dataArraySize / ((VLITEHeader.bitsPerSampleLess1 + 1) / 8)

  lazy val framesPerSec = samplesPerSec / samplesPerFrame

  lazy val framesPerMs = framesPerSec / 1000.0
}

object VLITEStage
    extends PipelineStage[VLITEConfig, VLITEHeader, ByteString, (VLITEHeader, Seq[Byte]), ByteString] {

  override def apply(ctx: VLITEConfig) =
    new PipePair[VLITEHeader, ByteString, (VLITEHeader, Seq[Byte]), ByteString] {

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
        bb ++= ctx.dataArray
        ctx.singleCommand(bb.result)
      }

      protected def extractOne(bs: ByteString): (VLITEHeader, Seq[Byte]) = {
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
        val array = new Array[Byte](ctx.dataArraySize)
        iter.getBytes(array)
        (VLITEHeader(
          isInvalidData = (word0 & (1 << 31)) != 0,
          secFromRefEpoch = word0 & ((1 << 30) - 1),
          refEpoch = (word1 >> 24) & ((1 << 6) - 1),
          numberWithinSec = word1 & ((1 << 24) - 1),
          lengthBy8 = hdrLengthBy8,
          threadID = (word3 >> 16) & ((1 << 10) - 1),
          stationID = word3 & ((1 << 16) - 1)),
          array.toSeq)
      }

      protected def extractFrames(
        bs: ByteString,
        acc: List[(VLITEHeader, Seq[Byte])]):
          (Option[ByteString], List[(VLITEHeader, Seq[Byte])]) = {
        if (bs.isEmpty)
          (None, acc)
        else if (bs.length < frameSize)
          (Some(bs.compact), acc)
        else
          bs.splitAt(frameSize) match {
            case (first, rest) =>
              extractFrames(rest, extractOne(first) :: acc)
          }
      }

      def eventPipeline = { bs: ByteString =>
        val data = if (buffer.isEmpty) bs else buffer.get ++ bs
        val (nb, frames) = extractFrames(data, Nil)
        buffer = nb
        frames match {
          case Nil        => Nil
          case one :: Nil => ctx.singleEvent(one)
          case many       => many reverseMap (Left(_))
        }
      }
    }
}

trait VLITEConfigZeroData extends VLITEConfig {
  lazy val zeroDataArray = {
    val bldr = ByteString.newBuilder
    bldr.sizeHint(dataArraySize)
    (1 to dataArraySize) foreach (_ => bldr.putByte(0.toByte))
    bldr.result
  }
  def dataArray = zeroDataArray
}

trait VLITEConfigSimData extends VLITEConfig {
  val seed: Long

  val filter: Vector[Double]

  val scale: Double

  val offset: Long

  val system: ActorSystem

  def bufferSize: Int

  lazy val bsActor = system.actorOf(
    ByteStringSource.props(
      SimulatedValueSource.props(
        seed,
        offset,
        scale,
        filter),
      dataArraySize,
      bufferSize))

  implicit val timeout: Timeout

  implicit lazy val executionContext = system.dispatcher

  def nextRequest: Future[ByteString] =
    (bsActor ? ValueSource.Get(1)) map {
      case ValueSource.Values(bss) => bss(0).asInstanceOf[ByteString]
    }

  var nextValue: Option[Future[ByteString]] = None

  def dataArray = {
    val nv = nextValue.getOrElse(nextRequest)
    val result = Await.result(nv, timeout.duration)
    nextValue = Some(nextRequest)
    result
  }
}
