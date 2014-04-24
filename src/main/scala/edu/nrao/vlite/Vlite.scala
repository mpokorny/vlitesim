//
// Copyright © 2014 Associated Universities, Inc. Washington DC, USA.
//
// This file is part of vlitesim.
//
// vlitesim is free software: you can redistribute it and/or modify it under the
// terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later
// version.
//
// vlitesim is distributed in the hope that it will be useful, but WITHOUT ANY
// WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
// A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License along with
// vlitesim.  If not, see <http://www.gnu.org/licenses/>.
//
package edu.nrao.vlite

import scala.concurrent.{ Await, Future, ExecutionContext }
import scala.concurrent.duration._
import akka.actor._
import akka.pattern.ask
import akka.util.{ ByteString, Timeout }
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

case class VLITEFrame(header: VLITEHeader, dataArray: ByteString)

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

  def dataArray: Future[ByteString]

  val samplesPerSec = 128 * 1000000

  lazy val samplesPerFrame =
    dataArraySize / ((VLITEHeader.bitsPerSampleLess1 + 1) / 8)

  lazy val framesPerSec = samplesPerSec / samplesPerFrame

  lazy val framesPerMs = framesPerSec / 1000.0
}

object VLITEStage
    extends SymmetricPipelineStage[VLITEConfig, VLITEFrame, ByteString] {

  override def apply(ctx: VLITEConfig) =
    new SymmetricPipePair[VLITEFrame, ByteString] {

      implicit val byteOrder = LITTLE_ENDIAN

      var buffer: Option[ByteString] = None

      val frameSize = ctx.dataArraySize + 32

      val lengthBy8 = frameSize / 8

      private def shiftMaskOr(acc: Int, v: Int, nbits: Int) =
        (acc << nbits) | (v & ((1 << nbits) - 1))

      def commandPipeline = {
        case VLITEFrame(header, dataArray) =>
          val bb = ByteString.newBuilder.
            putInt(
              shiftMaskOr(
                shiftMaskOr(
                  if (header.isInvalidData) 1 else 0,
                  if (header.isLegacyMode) 1 else 0,
                  1),
                header.secFromRefEpoch,
                30)).
            putInt(
              shiftMaskOr(
                shiftMaskOr(
                  0, // 2bits
                  header.refEpoch,
                  6),
                header.numberWithinSec,
                24)).
            putInt(
              shiftMaskOr(
                shiftMaskOr(
                  header.version, // 3 bits
                  header.log2NumChannels,
                  5),
                lengthBy8,
                24)).
            putInt(
              shiftMaskOr(
                shiftMaskOr(
                  shiftMaskOr(
                    if (header.isComplexData) 1 else 0,
                    header.bitsPerSampleLess1,
                    5),
                  header.threadID,
                  10),
                header.stationID,
                16)).
            putInt(
              shiftMaskOr(
                header.extendedDataVersion,
                header.extendedUserData0,
                24)).
            putInt(header.extendedUserData1).
            putInt(header.extendedUserData2).
            putInt(header.extendedUserData3)
          bb ++= dataArray
          ctx.singleCommand(bb.result)
      }

      protected def extractOne(bs: ByteString): VLITEFrame = {
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
        VLITEFrame(
          VLITEHeader(
            isInvalidData = (word0 & (1 << 31)) != 0,
            secFromRefEpoch = word0 & ((1 << 30) - 1),
            refEpoch = (word1 >> 24) & ((1 << 6) - 1),
            numberWithinSec = word1 & ((1 << 24) - 1),
            lengthBy8 = hdrLengthBy8,
            threadID = (word3 >> 16) & ((1 << 10) - 1),
            stationID = word3 & ((1 << 16) - 1)),
          ByteString(array))
      }

      protected def extractFrames(bs: ByteString, acc: List[VLITEFrame]):
          (Option[ByteString], List[VLITEFrame]) = {
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
  implicit val executionContext: ExecutionContext

  lazy val zeroDataArray = {
    val bldr = ByteString.newBuilder
    bldr.sizeHint(dataArraySize)
    (1 to dataArraySize) foreach (_ => bldr.putByte(0.toByte))
    Future(bldr.result)
  }

  def dataArray = zeroDataArray
}

trait VLITEConfigSimData extends VLITEConfig {
  val seed: Long

  val filter: Vector[Double]

  val scale: Double

  val offset: Long

  val actorRefFactory: ActorRefFactory

  def bufferSize: Int

  lazy val bsActor = actorRefFactory.actorOf(
    ByteStringSource.props(
      SimulatedValueSource.props(
        seed,
        offset,
        scale,
        filter),
      dataArraySize,
      bufferSize),
    "bytestrings")

  implicit val timeout: Timeout

  implicit val executionContext: ExecutionContext

  def nextRequest(implicit timeout: Timeout): Future[ByteString] =
    (bsActor ? ValueSource.Get(1)) map {
      case ValueSource.Values(bss) => bss(0).asInstanceOf[ByteString]
    }

  var nextValue: Option[Future[ByteString]] = None

  def dataArray = {
    val nv = nextValue.getOrElse(nextRequest)
    nextValue = Some(nextRequest)
    nv
  }
}
