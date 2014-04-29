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

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import org.joda.time.{ DateTime, DateTimeZone, Duration => JodaDuration }
import akka.actor._
import akka.io.{ PipelineFactory, PipelinePorts }
import akka.util.{ ByteString, Timeout }
import java.io.File

abstract class Generator(
  val threadID: Int,
  val stationID: Int,
  val transporter: ActorRef,
  val pace: FiniteDuration,
  decimation: Int)
    extends Actor with ActorLogging {

  import context._

  implicit val VLITEConfig: VLITEConfig

  protected var secFromRefEpoch: Int = 0
  protected var numberWithinSec: Int = 0
  protected var stream: Option[Cancellable] = None

  protected var pendingTimestamps: Vector[(Int, Int)] = Vector.empty

  val framesPerSec = VLITEConfig.framesPerSec / decimation
  // TODO: make durationPerFrame a sequence for dithering error from using
  // integer division? Probably not worth it given the accuracy of the
  // scheduler.
  val durationPerFrame = (1000000000L / framesPerSec).nanos

  val PipelinePorts(vlitePipeline, _, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfig, VLITEStage)

  protected def nextFrame(
    dataArray: ByteString,
    secFromRefEpoch: Int,
    numberWithinSec: Int): ByteString = {
    val header = VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = secFromRefEpoch,
      refEpoch = Generator.refEpoch,
      numberWithinSec = numberWithinSec,
      threadID = threadID,
      stationID = stationID)
    val (_, result) = vlitePipeline(VLITEFrame(header, dataArray))
    result.head
  }

  protected def incNumberWithinSec() {
    numberWithinSec += 1
    if (numberWithinSec == framesPerSec) {
      numberWithinSec = 0
      secFromRefEpoch += 1
    }
  }

  override def preStart() {
    require(
      1 <= decimation && decimation <= VLITEConfig.framesPerSec,
      s"Invalid frame rate decimation factor: must be between 1 and ${VLITEConfig.framesPerSec}"
    )
  }

  override def postStop() {
    stream.foreach(_.cancel)
    stream = None
  }

  def getExpectedFrameRate: Receive = {
    case Generator.GetExpectedFrameRate =>
      sender ! Generator.ExpectedFrameRate(framesPerSec)
  }

  def genFramesOnce() {
    val (sec, count) = Generator.timeFromRefEpoch
    secFromRefEpoch = sec
    numberWithinSec = count / decimation
    stream =
      Some(system.scheduler.scheduleOnce(pace, self, Generator.GenFrames))
  }

  def receive = {
    stream =
      Some(system.scheduler.scheduleOnce(1.second, self, Generator.GenFrames))
    starting
  }

  def starting: Receive = getExpectedFrameRate orElse {
    case Generator.GenFrames =>
      val (sec, count) = Generator.timeFromRefEpoch
      secFromRefEpoch = sec
      numberWithinSec = count / decimation
      stream =
        Some(system.scheduler.schedule(pace, pace, self, Generator.GenFrames))
      become(running)
  }

  def running: Receive = getExpectedFrameRate orElse {
    case Generator.GenFrames =>
      val (sec, count) = Generator.timeFromRefEpoch
      val decCount = count / decimation
      val numFrames = ((sec - secFromRefEpoch) * framesPerSec +
        (decCount - numberWithinSec))
      VLITEConfig.dataArray(numFrames)
      val timestamps = (0 until numFrames) map { _ =>
        val timestamp = (secFromRefEpoch, numberWithinSec)
        incNumberWithinSec()
        timestamp
      }
      pendingTimestamps ++= timestamps
    case ValueSource.Values(vs: Vector[ByteString]) =>
      if (vs.length > 0) {
        pendingTimestamps splitAt vs.length match {
          case (nextTs, remTs) =>
            transporter ! Transporter.Transport(
              vs zip nextTs map {
                case (dat, (sec, num)) => nextFrame(dat, sec, num)
              })
            pendingTimestamps = remTs
        }
      }
  }
}

final class ZeroGenerator(
  threadID: Int,
  stationID: Int,
  transporter: ActorRef,
  pace: FiniteDuration,
  decimation: Int,
  arraySize: Int)
    extends Generator(threadID, stationID, transporter, pace, decimation) {
  gen =>

  implicit object VLITEConfig extends VLITEConfigZeroData {
    val dataArraySize = arraySize
  }
}

trait GeneratorParams

final case class SimParams(
  seed: Long,
  filter: Vector[Double],
  scale: Double,
  offset: Long) extends GeneratorParams

final class SimdataGenerator(
  threadID: Int,
  stationID: Int,
  transporter: ActorRef,
  pace: FiniteDuration,
  decimation: Int,
  arraySize: Int,
  simParams: SimParams)
    extends Generator(threadID, stationID, transporter, pace, decimation) {
  gen =>

  implicit object VLITEConfig extends VLITEConfigSimData {
    val SimParams(seed, filter, scale, offset) = simParams
    val dataArraySize = arraySize
    lazy val actorRefFactory = gen.context
    private def ceil(n: Int, d: Int) =
      (n + (d - n % d) % d) / d
    def bufferSize = 2 * ceil(gen.framesPerSec, (1.second / pace).toInt)
  }
}

final case class FileParams(
  val readBufferSize: Int,
  val fileNamePattern: String) extends GeneratorParams {
  def file(threadID: Int, stationID: Int): File = {
    new File(
      fileNamePattern.replaceAll("@THREAD@", threadID.toString).
        replaceAll("@STATION@", "%02d" format stationID))
  }
}

final class FiledataGenerator(
  threadID: Int,
  stationID: Int,
  transporter: ActorRef,
  pace: FiniteDuration,
  decimation: Int,
  arraySize: Int,
  fileParams: FileParams)
    extends Generator(threadID, stationID, transporter, pace, decimation) {
  gen =>

  implicit object VLITEConfig extends VLITEConfigFileData {
    val file = fileParams.file(threadID, stationID)
    val readBufferSize = fileParams.readBufferSize
    val dataArraySize = arraySize
    lazy val actorRefFactory = gen.context
    val bufferSize = 2
  }
}

object Generator {
  def props(
    threadID: Int,
    stationID: Int,
    transporter: ActorRef,
    pace: FiniteDuration = 1.millis,
    decimation: Int = 1,
    arraySize: Int = 5000,
    genParams: Option[GeneratorParams] = None): Props =
    if (genParams.isDefined)
      genParams.get match {
        case s: SimParams =>
          Props(
            classOf[SimdataGenerator],
            threadID,
            stationID,
            transporter,
            pace,
            decimation,
            arraySize,
            s)
        case f: FileParams =>
          Props(
            classOf[FiledataGenerator],
            threadID,
            stationID,
            transporter,
            pace,
            decimation,
            arraySize,
            f)
      }
    else
      Props(
        classOf[ZeroGenerator],
        threadID,
        stationID,
        transporter,
        pace,
        decimation,
        arraySize)

  lazy val referenceEpoch = {
    val now = DateTime.now(DateTimeZone.UTC)
    val halfYearMonth = (((now.getMonthOfYear - 1) / 6) * 6) + 1
    now.withDate(now.getYear, halfYearMonth, 1).withTimeAtStartOfDay
  }

  lazy val refEpoch =
    (2 * (referenceEpoch.getYear - 2000) +
      ((referenceEpoch.getMonthOfYear - 1) / 6))

  def timeFromRefEpoch(implicit config: VLITEConfig): (Int, Int) = {
    val diff =
      new JodaDuration(referenceEpoch, DateTime.now(DateTimeZone.UTC))
    val sec = diff.toStandardSeconds
    val frac = diff.minus(sec.toStandardDuration)
    (sec.getSeconds, (frac.getMillis * config.framesPerMs).floor.toInt)
  }

  def secondsFromRefEpoch(implicit config: VLITEConfig): Int =
    timeFromRefEpoch(config)._1

  def framesFromRefEpoch(implicit config: VLITEConfig): Int =
    timeFromRefEpoch(config)._2

  case object GenFrames
  case object GetExpectedFrameRate
  case class ExpectedFrameRate(framesPerSec: Int)
}
