package edu.nrao.vlite

import akka.actor._
import akka.util.Timeout
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import scala.collection.mutable

final class Emulator(
  val device: String,
  val destination: MAC,
  val sourceIDs: Seq[(Int, Int)],
  val pace: FiniteDuration = Emulator.defaultPace,
  val decimation: Int = Emulator.defaultDecimation)
    extends Actor {

  import context._

  override def supervisorStrategy = {
    import akka.actor.SupervisorStrategy._

    OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 1.minute) {
      case _ => Restart
    }
  }

  val transporter =
    actorOf(EthernetTransporter.props(device, destination), "transporter")

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

  def receive: Receive = idle

  def idle: Receive = queries orElse {
    case Emulator.Start =>
      for (g <- generators) g ! Generator.Start
      transporter ! Transporter.Start
      become(running)
    case _ =>
  }

  def running: Receive = queries orElse {
    case Emulator.Stop =>
      transporter ! Transporter.Stop
      for (g <- generators) g ! Generator.Stop
      become(idle)
    case _ =>
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
    device: String,
    destination: MAC,
    sourceIDs: Seq[(Int, Int)],
    pace: FiniteDuration = defaultPace,
    decimation: Int = defaultDecimation): Props =
    Props(classOf[Emulator], device, destination, sourceIDs, pace, decimation)

  val defaultPace = 1.milli

  val defaultDecimation = 1

  case object Start
  case object Stop
  case object GetGeneratorLatencies
  case class Latencies(values: Map[(Int, Int), Int])
  case object LatenciesTimeout
}
