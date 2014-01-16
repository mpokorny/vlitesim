package edu.nrao.vlite

import scala.concurrent.duration._
import org.joda.time.{ DateTime, DateTimeZone, Duration => JodaDuration }
import akka.actor._

final class Generator(
  val threadID: Int,
  val stationID: Int,
  val transporter: ActorRef,
  decimation: Int = 1) extends Actor with ActorLogging {

  import context._

  override def preStart() {
    require(
      1 <= decimation && decimation <= Generator.framesPerSec,
      s"Invalid frame rate decimation factor: must be between 1 and ${Generator.framesPerSec}"
    )
  }

  protected var secFromRefEpoch: Int = 0
  protected var numberWithinSec: Int = 0
  protected var stream: Option[Cancellable] = None

  val framesPerSec = Generator.framesPerSec / decimation
  // TODO: make durationPerFrame a sequence for dithering error from using
  // integer division? Probably not worth it given the accuracy of the
  // scheduler.
  val durationPerFrame = (1000000000L / framesPerSec).nanos

  protected def nextFrame: Ethernet[VLITEFrame] = {
    val dummyMAC = MAC(0,0,0,0,0,0)
    val eth = Ethernet(
      dummyMAC,
      dummyMAC,
      VLITEFrame(VLITEHeader(
        isInvalidData = false,
        secFromRefEpoch = secFromRefEpoch,
        refEpoch = Generator.refEpoch,
        numberWithinSec = numberWithinSec,
        threadID = threadID,
        stationID = stationID)))
    numberWithinSec += 1
    if (numberWithinSec == framesPerSec) {
      numberWithinSec = 0
      secFromRefEpoch += 1
    }
    eth
  }

  def receive = idle

  def idle: Receive = {
    case Generator.Start =>
      numberWithinSec = 0
      secFromRefEpoch = Generator.secondsFromRefEpoch
      stream = Some(system.scheduler.schedule(
        0.seconds,
        durationPerFrame,
        self,
        Generator.NextFrame))
      become(running)
    case Generator.GetLatency =>
      sender ! Generator.Latency(0)
    case _ =>
  }

  def running: Receive = {
    case Generator.NextFrame =>
      transporter ! Transporter.Transport(nextFrame.frame)
    case Generator.Stop =>
      stream.foreach(_.cancel)
      stream = None
      become(idle)
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
    decimation: Int = 1): Props =
    Props(classOf[Generator], threadID, stationID, transporter, decimation)

  val samplesPerSec = 128 * 1000000

  val samplesPerFrame =
    VLITEFrame.dataArraySize / ((VLITEHeader.bitsPerSampleLess1 + 1) / 8)

  val framesPerSec = samplesPerSec / samplesPerFrame

  val referenceEpoch = {
    val now = DateTime.now(DateTimeZone.UTC)
    val halfYearMonth = (((now.getMonthOfYear - 1) / 6) * 6) + 1
    now.withDate(now.getYear, halfYearMonth, 1).withTimeAtStartOfDay
  }

  val refEpoch =
    (2 * (referenceEpoch.getYear - 2000) +
      ((referenceEpoch.getMonthOfYear - 1) / 6))

  def secondsFromRefEpoch: Int =
    (new JodaDuration(referenceEpoch, DateTime.now(DateTimeZone.UTC))).
      getStandardSeconds.toInt

  case object Start
  case object Stop
  case object NextFrame
  case object GetLatency
  case class Latency(seconds: Int)
}
