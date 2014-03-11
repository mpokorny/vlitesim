package edu.nrao.vlite

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

  override def preStart() {
    require(
      1 <= decimation && decimation <= VLITEConfig.framesPerSec,
      s"Invalid frame rate decimation factor: must be between 1 and ${VLITEConfig.framesPerSec}"
    )
  }

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

  protected def nextFrame: ByteString = {
    val header = VLITEHeader(
      isInvalidData = false,
      secFromRefEpoch = secFromRefEpoch,
      refEpoch = Generator.refEpoch,
      numberWithinSec = numberWithinSec,
      threadID = threadID,
      stationID = stationID)
    incNumberWithinSec()
    val (_, result) = vlitePipeline(header)
    result.head
  }

  protected def incNumberWithinSec() {
    numberWithinSec += 1
    if (numberWithinSec == framesPerSec) {
      numberWithinSec = 0
      secFromRefEpoch += 1
    }
  }

  override def postStop() {
    stream.foreach(_.cancel)
    stream = None
  }

  def receive = starting

  def starting: Receive = {
    numberWithinSec = 0
    secFromRefEpoch = Generator.secondsFromRefEpoch
    stream = Some(system.scheduler.schedule(
      0.seconds,
      pace,
      self,
      Generator.GenFrames))
    val result: Receive = {
      case Generator.GenFrames =>
        val (sec, count) = Generator.timeFromRefEpoch
        val decCount = count / decimation
        secFromRefEpoch = sec
        numberWithinSec = decCount
        become(running)
      case Generator.GetLatency =>
        sender ! Generator.Latency(0)
    }
    result
  }

  def running: Receive = {
    case Generator.GenFrames => {
      val (sec, count) = Generator.timeFromRefEpoch
      val decCount = count / decimation
      val numFrames = ((sec - secFromRefEpoch) * framesPerSec +
        (decCount - numberWithinSec))
      if (numFrames > 0)
        transporter ! Transporter.Transport(Vector.fill(numFrames)(nextFrame))
    }
    case Generator.GetLatency =>
      sender ! Generator.Latency(
        Generator.secondsFromRefEpoch - secFromRefEpoch)
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

  implicit object VLITEConfig extends VLITEConfigZeroData {
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
    val system = gen.context.system
    //implicit lazy val timeout = Timeout(4 * gen.durationPerFrame)
    implicit val timeout = Timeout(1, SECONDS)
    private def ceil(n: Int, d: Int) =
      (n + (d - n % d) % d) / d
    def bufferSize = ceil(gen.framesPerSec, (1.second / pace).toInt) max 2
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
  case object GetLatency
  case class Latency(seconds: Int)
}
