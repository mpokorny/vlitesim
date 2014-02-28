package edu.nrao.vlite

import scala.concurrent.duration._
import org.joda.time.{ DateTime, DateTimeZone, Duration => JodaDuration }
import akka.actor._
import akka.io.{ PipelineFactory, PipelinePorts }
import akka.util.ByteString

final class Generator(
  val threadID: Int,
  val stationID: Int,
  val transporter: ActorRef,
  val pace: FiniteDuration,
  decimation: Int = 1,
  arraySize: Int = 5000) extends Actor with ActorLogging {

  import context._

  implicit object VLITEConfig extends VLITEConfig {
    val dataArraySize = arraySize
    lazy val initDataArray = Seq.fill[Byte](dataArraySize)(0)
  }

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
        incNumberWithinSec()
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
      for (i <- 0 until numFrames)
        transporter ! Transporter.Transport(nextFrame)
    }
    case Generator.GetLatency =>
      sender ! Generator.Latency(
        Generator.secondsFromRefEpoch - secFromRefEpoch)
  }
}

object Generator {
  def props(
    threadID: Int,
    stationID: Int,
    transporter: ActorRef,
    pace: FiniteDuration = 1.millis,
    decimation: Int = 1,
    arraySize: Int = 5000): Props =
    Props(
      classOf[Generator],
      threadID,
      stationID,
      transporter,
      pace,
      decimation,
      arraySize)

  val referenceEpoch = {
    val now = DateTime.now(DateTimeZone.UTC)
    val halfYearMonth = (((now.getMonthOfYear - 1) / 6) * 6) + 1
    now.withDate(now.getYear, halfYearMonth, 1).withTimeAtStartOfDay
  }

  val refEpoch =
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
