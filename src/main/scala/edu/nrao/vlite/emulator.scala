package edu.nrao.vlite

import akka.actor._
import akka.util.Timeout
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import scala.collection.mutable
import java.net.InetSocketAddress

final class Emulator(
  val transport: Emulator.Transport.Transport,
  val device: Option[String],
  val destination: String,
  val sourceIDs: Seq[(Int, Int)],
  val pace: FiniteDuration = Emulator.defaultPace,
  val decimation: Int = Emulator.defaultDecimation)
    extends Actor with ActorLogging {

  import context._

  val transporter = transport match {
    case Emulator.Transport.Ethernet =>
      actorOf(
        EthernetTransporter.props(device.get, MAC(destination)),
        "transporter")
    case Emulator.Transport.UDP => {
      val hostAndPort = destination.split(':')
      val hostname = hostAndPort(0)
      val port = hostAndPort(1).toInt
      actorOf(
        UdpTransporter.props(new InetSocketAddress(hostname, port)),
        "transporter")
    }
  }

  val generators = sourceIDs map {
    case (stationID, threadID) =>
      actorOf(Generator.props(
        stationID = stationID,
        threadID = threadID,
        transporter = transporter,
        pace = pace,
        decimation = decimation))
  }

  protected implicit val queryTimeout = Timeout(1.seconds)

  override def preStart() {
    println(s"Start ${self.path}")
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    if (isRunning) self ! Emulator.WasRunning
    super.preRestart(reason, message)
  }

  protected def startChildren() {
    for (g <- generators) g ! Generator.Start
    transporter ! Transporter.Start
  }

  protected def stopChildren() {
    transporter ! Transporter.Stop
    for (g <- generators) g ! Generator.Stop
  }

  protected var isRunning: Boolean = false

  protected var runningStateWasSet: Boolean = false

  def receive: Receive = idle

  def idle: Receive = {
    log.info("stopped")
    isRunning = false
    stopChildren()
    common orElse {
      case Emulator.Start =>
        runningStateWasSet = true
        become(running)
      case Emulator.WasRunning =>
        if (!runningStateWasSet)
          become(running)
    }
  }

  def running: Receive = {
    log.info("started")
    isRunning = true
    startChildren()
    common orElse {
      case Emulator.Stop =>
        runningStateWasSet = true
        become(idle)
      case Emulator.WasRunning =>
    }
  }

  def common: Receive =
    queries orElse {
      case Transporter.OpenWarning(msg) =>
        log.warning(msg)
    }

  def queries = getGenLatencies orElse getBufferCount

  private val latencies = mutable.Seq.fill(generators.length)(-1)

  private var latencyRequesters = Vector.empty[ActorRef]

  private var latencyTimeout: Option[Cancellable] = None

  private def haveAllLatencies = latencies forall (_ != -1)

  private def resetLatencies() {
    for (i <- 0 until latencies.length) latencies(i) = -1
    latencyRequesters = Vector.empty
    latencyTimeout foreach (_.cancel)
    latencyTimeout = None
  }

  private def sendLatencyResponses() {
    val values = (sourceIDs.zip(latencies).filter {
      case (_, -1) => false
      case _ => true
    }).toMap
    for (r <- latencyRequesters) r ! Emulator.Latencies(values)
    resetLatencies()
  }

  def getGenLatencies: Receive = {
    case Emulator.GetGeneratorLatencies =>
      if (latencyRequesters.isEmpty) {
        for (g <- generators) g ! Generator.GetLatency
        latencyTimeout = Some(system.scheduler.scheduleOnce(
          queryTimeout.duration,
          self,
          Emulator.LatenciesTimeout))
      }
      latencyRequesters = latencyRequesters :+ sender
    case Generator.Latency(l) =>
      latencies(generators.indexOf(sender)) = l
      if (haveAllLatencies)
        sendLatencyResponses()
    case Emulator.LatenciesTimeout =>
      sendLatencyResponses()
  }

  def getBufferCount: Receive = {
    case Transporter.GetBufferCount => {
      (transporter ? Transporter.GetBufferCount) pipeTo sender
    }
  }
}

object Emulator {
  def props(
    transport: Transport.Transport,
    device: Option[String],
    destination: String,
    sourceIDs: Seq[(Int, Int)],
    pace: FiniteDuration = defaultPace,
    decimation: Int = defaultDecimation): Props =
    Props(classOf[Emulator], transport, device, destination, sourceIDs, pace, decimation)

  val defaultPace = 1.milli

  val defaultDecimation = 1

  case object Start
  case object Stop
  case object GetGeneratorLatencies
  case class Latencies(values: Map[(Int, Int), Int])
  case object LatenciesTimeout
  case object WasRunning

  object Transport extends Enumeration {
    type Transport = Value
    val Ethernet, UDP = Value
  }
}
