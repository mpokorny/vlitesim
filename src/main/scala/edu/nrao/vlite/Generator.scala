package edu.nrao.vlite

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import org.joda.time.{ DateTime, DateTimeZone, Duration => JodaDuration }
import akka.actor._
import akka.io.{ PipelineFactory, PipelinePorts }
import akka.util.{ ByteString, Timeout }

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

  val framesPerSec = VLITEConfig.framesPerSec / decimation
  // TODO: make durationPerFrame a sequence for dithering error from using
  // integer division? Probably not worth it given the accuracy of the
  // scheduler.
  val durationPerFrame = (1000000000L / framesPerSec).nanos

  val PipelinePorts(vlitePipeline, _, _) =
    PipelineFactory.buildFunctionTriple(VLITEConfig, VLITEStage)

  protected def nextFrame: Future[ByteString] = {
    val header = VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = secFromRefEpoch,
      refEpoch = Generator.refEpoch,
      numberWithinSec = numberWithinSec,
      threadID = threadID,
      stationID = stationID)
    incNumberWithinSec()
    VLITEConfig.dataArray map { dataArray =>
      val (_, result) = vlitePipeline(VLITEFrame(header, dataArray))
      result.head
    }
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
    stream =
      Some(system.scheduler.scheduleOnce(1.second, self, Generator.GenFrames))
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

  def receive = starting

  def starting: Receive = getExpectedFrameRate orElse {
    case Generator.GenFrames =>
      genFramesOnce()
      become(priming)
  }

  def priming: Receive = getExpectedFrameRate orElse {
    case Generator.GenFrames => {
      val (sec, count) = Generator.timeFromRefEpoch
      val decCount = count / decimation
      val numFrames = ((sec - secFromRefEpoch) * framesPerSec +
        (decCount - numberWithinSec))
      if (numFrames > 0 &&
        Await.result(
          Future.traverse((0 until numFrames).toVector)(_ => nextFrame).
            map(_ => true).recover { case _ => false },
          Duration.Inf)) {
        stream =
          Some(system.scheduler.schedule(pace, pace, self, Generator.GenFrames))
        become(running)
      } else {
        genFramesOnce()
      }
    }
  }

  def running: Receive = getExpectedFrameRate orElse {
    case Generator.GenFrames => {
      val (sec, count) = Generator.timeFromRefEpoch
      val decCount = count / decimation
      val numFrames = ((sec - secFromRefEpoch) * framesPerSec +
        (decCount - numberWithinSec))
      if (numFrames > 0)
        transporter ! Transporter.Transport(Vector.fill(numFrames)(nextFrame))
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
    implicit val executionContext = gen.context.dispatcher

    val dataArraySize = arraySize
  }
}

final case class SimParams(
  seed: Long,
  filter: Vector[Double],
  scale: Double,
  offset: Long)

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
    lazy val context = gen.context
    implicit lazy val timeout = Timeout(10 * gen.durationPerFrame)
    private def ceil(n: Int, d: Int) =
      (n + (d - n % d) % d) / d
    def bufferSize = 2 * ceil(gen.framesPerSec, (1.second / pace).toInt)
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
    simParams: Option[SimParams] = None): Props =
    if (simParams.isDefined)
      Props(
        classOf[SimdataGenerator],
        threadID,
        stationID,
        transporter,
        pace,
        decimation,
        arraySize,
        simParams.get)
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
