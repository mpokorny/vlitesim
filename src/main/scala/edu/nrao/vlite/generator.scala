package edu.nrao.vlite

import scala.concurrent.duration._
import org.joda.time.{ DateTime, DateTimeZone, Duration => JodaDuration }
import akka.actor._

final class Generator(
  val threadID: Int,
  val stationID: Int,
  val transporter: ActorRef,
  val pace: FiniteDuration,
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
    incNumberWithinSec()
    eth
  }

  protected def incNumberWithinSec() {
    numberWithinSec += 1
    if (numberWithinSec == framesPerSec) {
      numberWithinSec = 0
      secFromRefEpoch += 1
    }
  }

  protected def stop() {
    stream.foreach(_.cancel)
    stream = None
    become(idle)
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    if (isRunning) self ! Generator.WasRunning
    super.preRestart(reason, message)
  }

  protected var isRunning: Boolean = false

  protected var runningStateWasSet: Boolean = false

  def receive = idle

  def idle: Receive = {
    case Generator.Start =>
      runningStateWasSet = true
      become(starting)
    case Generator.GetLatency =>
      sender ! Generator.Latency(0)
    case Generator.WasRunning =>
      if (!runningStateWasSet)
        become(starting)
    case _ =>
  }

  def starting: Receive = {
    isRunning = true
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
      case Generator.Stop =>
        runningStateWasSet = true
        stop()
      case Generator.WasRunning =>
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
        transporter ! Transporter.Transport(nextFrame.frame)
    }
    case Generator.Stop =>
      runningStateWasSet = true
      stop()
    case Generator.GetLatency =>
      sender ! Generator.Latency(
        Generator.secondsFromRefEpoch - secFromRefEpoch)
    case Generator.WasRunning =>
  }
}

object Generator {
  def props(
    threadID: Int,
    stationID: Int,
    transporter: ActorRef,
    pace: FiniteDuration = 1.millis,
    decimation: Int = 1): Props =
    Props(
      classOf[Generator],
      threadID,
      stationID,
      transporter,
      pace,
      decimation)

  val samplesPerSec = 128 * 1000000

  val samplesPerFrame =
    VLITEFrame.dataArraySize / ((VLITEHeader.bitsPerSampleLess1 + 1) / 8)

  val framesPerSec = samplesPerSec / samplesPerFrame

  private val framesPerMs = framesPerSec / 1000.0

  val referenceEpoch = {
    val now = DateTime.now(DateTimeZone.UTC)
    val halfYearMonth = (((now.getMonthOfYear - 1) / 6) * 6) + 1
    now.withDate(now.getYear, halfYearMonth, 1).withTimeAtStartOfDay
  }

  val refEpoch =
    (2 * (referenceEpoch.getYear - 2000) +
      ((referenceEpoch.getMonthOfYear - 1) / 6))

  def timeFromRefEpoch: (Int, Int) = {
    val diff =
      new JodaDuration(referenceEpoch, DateTime.now(DateTimeZone.UTC))
    val sec = diff.toStandardSeconds
    val frac = diff.minus(sec.toStandardDuration)
    (sec.getSeconds, (frac.getMillis * framesPerMs).floor.toInt)
  }

  def secondsFromRefEpoch: Int = timeFromRefEpoch._1

  def framesFromRefEpoch: Int = timeFromRefEpoch._2

  case object Start
  case object Stop
  case object GenFrames
  case object GetLatency
  case class Latency(seconds: Int)
  case object WasRunning
}
